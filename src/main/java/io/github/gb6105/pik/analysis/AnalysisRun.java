package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.InputPreparation.Prepared;
import io.github.gb6105.pik.analysis.JobClaims.ClaimedJob;
import io.github.gb6105.pik.analysis.JobTermination.JobEnding;
import io.github.gb6105.pik.analysis.ResultValidator.Outcome;
import io.github.gb6105.pik.analysis.RunRecords.CallRow;
import io.github.gb6105.pik.analysis.RunRecords.RunBasis;
import io.github.gb6105.pik.analysis.SendPermits.Permit;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One run of a claimed job from its input to its settlement (design 2.4 steps 2 to 6, plan 5.3 to 5.9). The input is
 * read and checked, the send rate waited for and the call validated outside any transaction; each decision is a
 * transaction of its own. A permitted call is sent only when its response can still arrive within the lease and the
 * processing deadline; otherwise it is settled as not sent. A valid output is completed, with a limited retry on a
 * transient database failure within the lease, and every other response is settled by its row. Logs carry counts,
 * statuses, stop reasons, token counts and exception class names only.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("pik.analysis.worker.enabled")
@RequiredArgsConstructor
class AnalysisRun {

	private final RunRecords runs;

	private final CallManifests manifests;

	private final InputPreparation preparation;

	private final SendRateLimiter rateLimiter;

	private final SendPermits permits;

	private final AnalysisModel model;

	private final RunCompletion completion;

	private final ResponseSettlement responses;

	private final JobTermination termination;

	private final RunDeferrals deferrals;

	private final TransactionTemplate transaction;

	private final AnalysisProperties properties;

	/**
	 * Runs the job; called outside any transaction, on a thread of its own.
	 */
	Stop run(ClaimedJob claimed) {
		RunBasis basis = runs.findBasis(claimed.jobId()).orElseThrow();
		Optional<CallManifest> runnable = manifests.runnable(basis.manifestId());
		if (runnable.isEmpty()) {
			return defer(claimed, deferrals.retryAt(null, Instant.now()));
		}
		CallManifest manifest = runnable.get();
		byte[] jpeg;
		switch (preparation.prepare(basis, manifest)) {
			case Prepared.Ready ready -> jpeg = ready.jpeg();
			case Prepared.Failed failed -> {
				Boolean ended = transaction.execute((status) -> termination.end(claimed.jobId(),
						JobEnding.ofRun(failed.reason(), claimed.runToken())));
				return Boolean.TRUE.equals(ended) ? Stop.INPUT_FAILED : Stop.LOST;
			}
			case Prepared.Unavailable unavailable -> {
				return defer(claimed, deferrals.retryAt(null, Instant.now()));
			}
		}
		Instant sendBy = earlier(claimed.lockedUntil().minus(properties.worker().sendMargin()),
				claimed.processingDeadline());
		try {
			if (!rateLimiter.acquire(sendBy)) {
				return defer(claimed, Instant.now());
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return defer(claimed, Instant.now());
		}
		return switch (permits.permit(claimed, basis, manifest)) {
			case Permit.Granted granted -> send(claimed, basis, manifest, granted.call(), jpeg);
			case Permit.Existing existing -> Stop.PERMITTED_BEFORE;
			case Permit.Lost lost -> Stop.LOST;
			case Permit.Deferred deferred -> Stop.DEFERRED;
			case Permit.Ended ended -> Stop.ENDED;
		};
	}

	private Stop send(ClaimedJob claimed, RunBasis basis, CallManifest manifest, CallRow call, byte[] jpeg) {
		Instant sentAt = Instant.now();
		Instant answerBy = sentAt.plus(properties.claude().requestTimeout());
		if (!answerBy.isBefore(earlier(claimed.lockedUntil(), claimed.processingDeadline()))) {
			return settled(responses.settle(claimed, call, CallOutcome.notSent()));
		}
		ModelReply reply = model.call(manifest, jpeg);
		Instant observedAt = Instant.now();
		Outcome validation = null;
		if (reply.row() == ModelReply.Row.OUTPUT && reply instanceof ModelReply.Answered answered
				&& answered.output() != null) {
			validation = ResultValidator.validate(answered.output(), basis.referenceAt());
			if (validation instanceof Outcome.InvalidOutput invalid) {
				log.info("Analysis output failed validation: {}", invalid.reason());
			}
		}
		AnalysisProperties.Manifest settings = manifest.settings();
		CallOutcome outcome = CallOutcome.of(reply, validation instanceof Outcome.Usable, settings.price(),
				settings.maxCostMicros(), sentAt, observedAt);
		ModelReply.Usage usage = outcome.usage();
		log.info("Analysis call answered: row {}, status {}, stop reason {}, input tokens {}, output tokens {}",
				outcome.row(), outcome.httpStatus(), outcome.stopReason(),
				(usage != null) ? usage.inputTokens() : null, (usage != null) ? usage.outputTokens() : null);
		if (validation instanceof Outcome.Usable usable) {
			return complete(claimed, basis, manifest, call, outcome, usable);
		}
		return settled(responses.settle(claimed, call, outcome));
	}

	private Stop complete(ClaimedJob claimed, RunBasis basis, CallManifest manifest, CallRow call,
			CallOutcome outcome, Outcome.Usable usable) {
		int retries = properties.worker().completionRetries();
		for (int attempt = 0;; attempt++) {
			try {
				return switch (completion.complete(claimed, basis, manifest, call, outcome, usable)) {
					case STORED -> Stop.STORED;
					case DISCARDED -> Stop.ENDED;
					case LOST, SETTLED_BEFORE -> Stop.LOST;
				};
			}
			catch (RunCompletion.ResultConflict ex) {
				log.error("Analysis result conflicted with a stored result; the job ends with RESULT_CONFLICT");
				return settled(responses.settle(claimed, call, CallOutcome.resultConflict(outcome)));
			}
			catch (TransientDataAccessException ex) {
				if (attempt >= retries || !Instant.now().isBefore(claimed.lockedUntil())) {
					log.warn("Analysis completion failed {} times ({}); the lease recovery settles the call",
							attempt + 1, ex.getClass().getSimpleName());
					return Stop.LEFT;
				}
			}
		}
	}

	private Stop defer(ClaimedJob claimed, Instant until) {
		Boolean deferred = transaction.execute((status) -> deferrals.defer(claimed.jobId(), claimed.runToken(), until,
				claimed.probe(), Instant.now()));
		return Boolean.TRUE.equals(deferred) ? Stop.DEFERRED : Stop.LOST;
	}

	private static Stop settled(ResponseSettlement.Settled settled) {
		return switch (settled) {
			case ENDED -> Stop.ENDED;
			case DEFERRED -> Stop.DEFERRED;
			case LOST, SETTLED_BEFORE -> Stop.LOST;
		};
	}

	private static Instant earlier(Instant a, Instant b) {
		return a.isBefore(b) ? a : b;
	}

	/**
	 * How a run stopped, for the worker's counts.
	 */
	enum Stop {

		/** The results are stored and the job succeeded. */
		STORED,

		/** The job ended without a result. */
		ENDED,

		/** The input could not be used and the job ended. */
		INPUT_FAILED,

		/** The job went back to {@code READY}. */
		DEFERRED,

		/** The run no longer held the job. */
		LOST,

		/** A call was permitted for the run before and was not sent again. */
		PERMITTED_BEFORE,

		/** The completion kept failing; the job and its call wait for the lease recovery. */
		LEFT

	}

}
