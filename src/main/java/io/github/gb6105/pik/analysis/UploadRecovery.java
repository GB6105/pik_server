package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.AttemptConfirmation.Confirmation;
import io.github.gb6105.pik.analysis.JobRecords.LockedJob;
import io.github.gb6105.pik.analysis.JobTermination.JobEnding;
import io.github.gb6105.pik.analysis.UploadRecords.AttemptRow;
import io.github.gb6105.pik.analysis.UploadRecords.OverdueUpload;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The upload recovery batch (plan 7.1): jobs whose upload window is over are confirmed when their current attempt's
 * object is stored, and ended otherwise. Candidates are read without locks; the HEAD runs outside any transaction, and
 * each job then gets its own transaction that decides under the locks again. A stored object goes through the shared
 * confirmation condition, which changes nothing unless the observed attempt is still current. An ending first checks
 * that the job still waits, that its window is over and that its current attempt is still the observed one. A storage
 * that is unavailable leaves the job to the next run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class UploadRecovery {

	private static final UUID FIRST = new UUID(0, 0);

	private final UploadRecords attempts;

	private final JobRecords jobs;

	private final AttemptConfirmation confirmation;

	private final JobTermination termination;

	private final InputStore store;

	private final TransactionTemplate transaction;

	private final AnalysisProperties properties;

	@Scheduled(fixedDelayString = "${pik.analysis.cleanup.recovery-interval}",
			initialDelayString = "${pik.analysis.cleanup.initial-delay}")
	@SchedulerLock(name = "analysis-upload-recovery")
	public void recover() {
		Instant cutoff = Instant.now().minus(properties.upload().confirmGrace());
		int batchSize = properties.cleanup().batchSize();
		Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
		UUID after = FIRST;
		List<OverdueUpload> page;
		do {
			page = attempts.findOverdue(cutoff, properties.upload().maxAttempts(), after, batchSize);
			for (OverdueUpload upload : page) {
				outcomes.merge(recover(upload, cutoff), 1, Integer::sum);
			}
			after = page.isEmpty() ? after : page.getLast().jobId();
		}
		while (page.size() == batchSize);
		if (!outcomes.isEmpty()) {
			log.info("Upload recovery outcomes: {}", outcomes);
		}
	}

	private Outcome recover(OverdueUpload upload, Instant cutoff) {
		try {
			if (upload.attemptId() == null || upload.objectKey() == null) {
				return run(() -> end(upload.jobId(), null, cutoff));
			}
			UUID attemptId = upload.attemptId();
			return switch (store.head(upload.objectKey())) {
				case InputStore.Head.Present present -> run(() -> confirm(upload.jobId(), attemptId, present, cutoff));
				case InputStore.Head.Absent absent -> run(() -> end(upload.jobId(), attemptId, cutoff));
				case InputStore.Head.Unavailable unavailable -> Outcome.UNAVAILABLE;
			};
		}
		catch (RuntimeException ex) {
			log.warn("Upload recovery of a job failed: {}", ex.getClass().getSimpleName());
			return Outcome.FAILED;
		}
	}

	private Outcome run(Supplier<Outcome> step) {
		return Objects.requireNonNull(transaction.execute((status) -> step.get()));
	}

	// The shared condition locks consent, job and attempt; it ignores an attempt that is no longer current.
	private Outcome confirm(UUID jobId, UUID attemptId, InputStore.Head.Present observed, Instant cutoff) {
		return switch (confirmation.confirm(attemptId, observed)) {
			case Confirmation.Confirmed confirmed -> Outcome.CONFIRMED;
			case Confirmation.Ended ended -> Outcome.ENDED;
			case Confirmation.NotCurrent notCurrent -> Outcome.CHANGED;
			// No other attempt can be issued or confirmed in time any more.
			case Confirmation.Rejected rejected -> endOverdue(jobs.lock(jobId).orElseThrow(), cutoff);
			case Confirmation.Unconfirmable unconfirmable -> endOverdue(jobs.lock(jobId).orElseThrow(), cutoff);
		};
	}

	/**
	 * Ends the job if its current attempt is still {@code observedAttemptId}, whose object was not found, or it still
	 * has none when {@code observedAttemptId} is {@code null}. Locks the job, then its attempts.
	 */
	private Outcome end(UUID jobId, @Nullable UUID observedAttemptId, Instant cutoff) {
		LockedJob job = jobs.lock(jobId).orElseThrow();
		AttemptRow current = attempts.lockIssued(List.of(jobId)).get(jobId);
		if (!Objects.equals((current != null) ? current.id() : null, observedAttemptId)) {
			return Outcome.CHANGED;
		}
		return endOverdue(job, cutoff);
	}

	// Under the job's lock: a job confirmed or ended meanwhile is left as it is.
	private Outcome endOverdue(LockedJob job, Instant cutoff) {
		int maxAttempts = properties.upload().maxAttempts();
		if (!attempts.uploadOverdue(job.id(), cutoff, maxAttempts)) {
			return Outcome.CHANGED;
		}
		FailureCode reason = (job.issuedCount() >= maxAttempts) ? FailureCode.UPLOAD_ATTEMPTS_EXHAUSTED
				: FailureCode.UPLOAD_EXPIRED;
		termination.end(job.id(), new JobEnding(reason));
		return Outcome.ENDED;
	}

	/**
	 * What the batch did with a job, counted in its log line.
	 */
	enum Outcome {

		/** The upload was confirmed and the job queued. */
		CONFIRMED,

		/** The job ended. */
		ENDED,

		/** The job or its current attempt changed since it was read; the next run reads it again if needed. */
		CHANGED,

		/** The storage was unavailable; the next run tries again. */
		UNAVAILABLE,

		/** The job's transaction failed; the next run tries again. */
		FAILED

	}

}
