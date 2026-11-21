package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.JobClaims.ClaimedJob;
import io.github.gb6105.pik.analysis.JobRecords.ExpiredLease;
import io.github.gb6105.pik.analysis.JobRecords.LockedJob;
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
 * The lease recovery batch (plan 5.8): running jobs whose lease ran out are taken from their run. Candidates are read
 * without locks with the token of their run; each then gets a transaction of its own that locks the job and goes on
 * only while the same run still holds it, its lease is still over and its processing deadline is ahead (jobs past it
 * are the processing deadline batch's). A run without a {@code SENDING} call goes back to {@code READY} with its token
 * discarded and a backoff; a {@code SENDING} call is confirmed unknown by the response settlement (plan 5.9), which
 * sends the job back once per photo and ends it with {@code UNKNOWN_OUTCOME} the second time. The run's probe is
 * cleared in the same transaction. Locks as the settlement: job, attempts (when it ends), call, daily sends, usage
 * allocation and device mark, the device's sends, photo budget, daily cost, gates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class LeaseRecovery {

	private static final UUID FIRST = new UUID(0, 0);

	private final JobRecords jobs;

	private final RunRecords runs;

	private final ResponseSettlement responses;

	private final RunDeferrals deferrals;

	private final AiGates gates;

	private final TransactionTemplate transaction;

	private final AnalysisProperties properties;

	@Scheduled(fixedDelayString = "${pik.analysis.worker.lease-recovery-interval}",
			initialDelayString = "${pik.analysis.cleanup.initial-delay}")
	@SchedulerLock(name = "analysis-lease-recovery")
	public void recover() {
		Instant now = Instant.now();
		int batchSize = properties.cleanup().batchSize();
		Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
		UUID after = FIRST;
		List<ExpiredLease> page;
		do {
			page = jobs.findExpiredLeases(now, after, batchSize);
			for (ExpiredLease lease : page) {
				outcomes.merge(recover(lease.jobId(), lease.runToken()), 1, Integer::sum);
			}
			after = page.isEmpty() ? after : page.getLast().jobId();
		}
		while (page.size() == batchSize);
		if (!outcomes.isEmpty()) {
			log.info("Lease recovery outcomes: {}", outcomes);
		}
	}

	/**
	 * Takes the job from the run of {@code runToken} in a transaction of its own; called outside any transaction.
	 */
	Outcome recover(UUID jobId, UUID runToken) {
		try {
			return Objects.requireNonNull(transaction.execute((status) -> recoverLocked(jobId, runToken)));
		}
		catch (RuntimeException ex) {
			log.warn("Lease recovery of a job failed: {}", ex.getClass().getSimpleName());
			return Outcome.FAILED;
		}
	}

	private Outcome recoverLocked(UUID jobId, UUID runToken) {
		LockedJob job = jobs.lock(jobId).orElseThrow();
		Instant now = Instant.now();
		Instant lockedUntil = job.lockedUntil();
		if (!job.heldBy(runToken) || lockedUntil == null || !lockedUntil.isBefore(now)) {
			return Outcome.CHANGED;
		}
		if (!job.processingDeadline().isAfter(now)) {
			return Outcome.EXPIRED;
		}
		// Read before the probe is cleared: a probe that dies without a send is not picked as a probe again for a while.
		boolean probe = gates.isProbe(jobId, runToken);
		Optional<CallRow> sending = runs.findCall(jobId, runToken)
			.filter((call) -> runs.callStatus(call.id()) == CallStatus.SENDING);
		Outcome outcome;
		if (sending.isPresent()) {
			CallRow call = sending.get();
			ClaimedJob claimed = new ClaimedJob(jobId, job.ownerId(), runToken, lockedUntil, job.processingDeadline(),
					probe);
			outcome = switch (responses.settleLocked(claimed, call, CallOutcome.unknown(call.reservedCostMicros()))) {
				case DEFERRED -> Outcome.RETRIED;
				case ENDED -> Outcome.ENDED;
				case LOST, SETTLED_BEFORE -> Outcome.SETTLED;
			};
		}
		else {
			deferrals.defer(jobId, runToken, deferrals.retryAt(null, now), probe, now);
			outcome = Outcome.DEFERRED;
		}
		gates.releaseProbe(jobId, runToken, now);
		return outcome;
	}

	/**
	 * What the batch did with a job, counted in its log line.
	 */
	enum Outcome {

		/** The run had no call in flight; the job went back to {@code READY}. */
		DEFERRED,

		/** The call was confirmed unknown and the job goes back to {@code READY} for its one call again. */
		RETRIED,

		/** The call was confirmed unknown a second time for the photo; the job ended with {@code UNKNOWN_OUTCOME}. */
		ENDED,

		/** The call was confirmed unknown and the job was left as it was. */
		SETTLED,

		/** Another run holds the job now, or it is no longer running. */
		CHANGED,

		/** The processing deadline passed; the processing deadline batch ends the job. */
		EXPIRED,

		/** The job's transaction failed; the next run tries again. */
		FAILED

	}

}
