package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.JobRecords.LockedJob;
import io.github.gb6105.pik.analysis.JobTermination.JobEnding;
import io.github.gb6105.pik.analysis.JobTermination.Settlement;
import io.github.gb6105.pik.analysis.RunRecords.CallRow;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The processing deadline batch (plan 7.2): jobs that have not ended when their processing deadline passed are
 * {@code CANCELLED(PROCESSING_EXPIRED)} through the common ending, a running one too, whether its lease ran out or its
 * last response came too late to win it. Candidates are read without locks; each job then gets a transaction of its
 * own that checks again under its lock. A {@code SENDING} call of the running job is confirmed unknown in the same
 * transaction, at its maximum cost with its sends spent; it is not called again, so the photo keeps its one call
 * after an unknown outcome. Locks in the order job, attempts, call, daily sends, usage allocation and device mark, the
 * device's sends, photo budget, daily cost, gates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ProcessingDeadlines {

	private static final UUID FIRST = new UUID(0, 0);

	private final JobRecords jobs;

	private final RunRecords runs;

	private final CallSettlements calls;

	private final JobTermination termination;

	private final TransactionTemplate transaction;

	private final AnalysisProperties properties;

	@Scheduled(fixedDelayString = "${pik.analysis.worker.deadline-interval}",
			initialDelayString = "${pik.analysis.cleanup.initial-delay}")
	@SchedulerLock(name = "analysis-processing-deadline")
	public void expire() {
		Instant now = Instant.now();
		int batchSize = properties.cleanup().batchSize();
		Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
		UUID after = FIRST;
		List<UUID> page;
		do {
			page = jobs.findPastDeadline(now, after, batchSize);
			for (UUID jobId : page) {
				outcomes.merge(expire(jobId), 1, Integer::sum);
			}
			after = page.isEmpty() ? after : page.getLast();
		}
		while (page.size() == batchSize);
		if (!outcomes.isEmpty()) {
			log.info("Processing deadline outcomes: {}", outcomes);
		}
	}

	/**
	 * Ends the job if its processing deadline passed, in a transaction of its own; called outside any transaction.
	 */
	Outcome expire(UUID jobId) {
		try {
			return Objects.requireNonNull(transaction.execute((status) -> expireLocked(jobId)));
		}
		catch (RuntimeException ex) {
			log.warn("Processing deadline of a job failed: {}", ex.getClass().getSimpleName());
			return Outcome.FAILED;
		}
	}

	private Outcome expireLocked(UUID jobId) {
		LockedJob job = jobs.lock(jobId).orElseThrow();
		Instant now = Instant.now();
		if (job.status().over() || !job.processingDeadline().isBefore(now)) {
			return Outcome.CHANGED;
		}
		UUID runToken = job.runToken();
		Optional<CallRow> sending = (runToken != null) ? runs.findCall(jobId, runToken)
			.filter((call) -> runs.callStatus(call.id()) == CallStatus.SENDING) : Optional.empty();
		Settlement settlement = sending
			.map((call) -> calls.of(call, CallOutcome.unknown(call.reservedCostMicros()), false, now))
			.orElse(Settlement.NONE);
		termination.end(jobId, new JobEnding(FailureCode.PROCESSING_EXPIRED, runToken, false, settlement));
		return sending.isPresent() ? Outcome.CALL_UNKNOWN : Outcome.EXPIRED;
	}

	/**
	 * What the batch did with a job, counted in its log line.
	 */
	enum Outcome {

		/** The job was cancelled. */
		EXPIRED,

		/** The job was cancelled and its call in flight confirmed unknown. */
		CALL_UNKNOWN,

		/** The job ended meanwhile. */
		CHANGED,

		/** The job's transaction failed; the next run tries again. */
		FAILED

	}

}
