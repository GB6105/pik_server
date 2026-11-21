package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.AiGates.Admission;
import io.github.gb6105.pik.analysis.JobRecords.Candidate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/**
 * The claim transaction (plan 5.2, design 2.4 step 1). The gates are read first without locks; while one holds calls
 * back nothing is claimed. Otherwise the global claim lock is the transaction's first lock, the free global slots are
 * counted under it, and candidates are locked {@code FOR NO KEY UPDATE SKIP LOCKED} in claim order, up to
 * {@code worker.claim-rounds} queries that leave out every candidate already seen and count the jobs claimed in
 * earlier rounds against their owner, while slots remain and the previous query found a candidate. While a circuit is
 * half-open, or due to half-open, one job is claimed as the probe: the gate rows are locked after it, in cause order,
 * and must still be in the generation read before. Each claimed job is {@code RUNNING} with one more attempt, a new
 * run token and a lease.
 */
@Component
@RequiredArgsConstructor
class JobClaims {

	private final JobRecords jobs;

	private final AiGates gates;

	private final TransactionTemplate transaction;

	private final AnalysisProperties properties;

	/**
	 * Claims up to {@code freeSlots} jobs in a transaction of its own; called outside any transaction, since the claim
	 * lock is never taken while other locks are held.
	 * @param freeSlots the runs this instance can start now
	 * @return the claimed jobs, in claim order
	 */
	List<ClaimedJob> claim(int freeSlots) {
		Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
				"A claim runs in a transaction of its own, whose first lock is the claim lock");
		if (freeSlots <= 0) {
			return List.of();
		}
		Admission admission = gates.admission(Instant.now());
		if (admission instanceof Admission.Blocked) {
			return List.of();
		}
		return Objects.requireNonNull(transaction.execute((status) -> claim(freeSlots, admission)));
	}

	private List<ClaimedJob> claim(int freeSlots, Admission admission) {
		jobs.lockClaims();
		Instant now = Instant.now();
		AnalysisProperties.Worker worker = properties.worker();
		Admission.Probe probe = (admission instanceof Admission.Probe found) ? found : null;
		int slots = Math.min(freeSlots, worker.globalSlots() - jobs.countRunning());
		if (probe != null) {
			slots = Math.min(slots, 1);
		}
		List<ClaimedJob> claimed = new ArrayList<>();
		Set<UUID> seen = new LinkedHashSet<>();
		for (int round = 0; round < worker.claimRounds() && claimed.size() < slots; round++) {
			int wanted = slots - claimed.size();
			int window = Math.multiplyExact(wanted, worker.claimOversample());
			List<Candidate> candidates = jobs.claimCandidates(wanted, window, worker.principalSlots(), seen,
					probe != null, now);
			for (Candidate candidate : candidates) {
				seen.add(candidate.id());
				if (!candidate.locked()) {
					continue;
				}
				UUID runToken = UUID.randomUUID();
				if (probe != null && !gates.assignProbe(probe, candidate.id(), runToken, now)) {
					return List.of();
				}
				JobRecords.Claimed job = jobs.markRunning(candidate.id(), runToken, now.plus(worker.lease()), now);
				claimed.add(new ClaimedJob(job.id(), job.ownerId(), runToken, job.lockedUntil(),
						job.processingDeadline(), probe != null));
			}
			// The owner cut ranks candidates before the window, so a short list does not mean every due job was seen;
			// only an empty one does.
			if (candidates.isEmpty()) {
				break;
			}
		}
		return List.copyOf(claimed);
	}

	/**
	 * A job claimed for a run.
	 *
	 * @param runToken the token that names the run on the job and its call
	 * @param lockedUntil the end of the run's lease
	 * @param probe whether the run is the probe of the half-open circuits
	 */
	record ClaimedJob(UUID jobId, UUID ownerId, UUID runToken, Instant lockedUntil, Instant processingDeadline,
			boolean probe) {

	}

}
