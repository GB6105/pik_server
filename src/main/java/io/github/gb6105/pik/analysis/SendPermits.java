package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.account.Principals;
import io.github.gb6105.pik.analysis.AiGates.Gate;
import io.github.gb6105.pik.analysis.AiGates.Passage;
import io.github.gb6105.pik.analysis.JobClaims.ClaimedJob;
import io.github.gb6105.pik.analysis.JobRecords.LockedJob;
import io.github.gb6105.pik.analysis.JobTermination.JobEnding;
import io.github.gb6105.pik.analysis.JobTermination.Recheck;
import io.github.gb6105.pik.analysis.JobTermination.Settlement;
import io.github.gb6105.pik.analysis.RunRecords.CallRow;
import io.github.gb6105.pik.analysis.RunRecords.RunBasis;
import io.github.gb6105.pik.analysis.RunRecords.SpendDay;
import io.github.gb6105.pik.consent.AiConsents;
import io.github.gb6105.pik.photo.PhotoObservations;
import io.github.gb6105.pik.usage.TrialUsage;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The permit transaction right before a send (plan 5.4, design 2.4 step 3). Locks in the order consent, photo, job,
 * the principal's daily sends, device mark, photo budget, daily cost, gates; each budget row is locked right before
 * its check. A call already permitted for the run is returned as it is and never sent again. Every check reads and
 * decides before anything changes: a pass then counts the send on each budget, reserves the maximum cost and inserts
 * the {@code SENDING} call; a denial that ends the job rolls the transaction back and ends the job in a transaction of
 * its own, so the usage allocation is never locked after the device mark, once its reason holds under that
 * transaction's locks; a denial that waits sends the job back to {@code READY} in this transaction, and a daily cost
 * that does not fit opens the budget gate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SendPermits {

	private final AiConsents consents;

	private final PhotoObservations photos;

	private final JobRecords jobs;

	private final RunRecords runs;

	private final Principals principals;

	private final TrialUsage trialUsage;

	private final AiGates gates;

	private final CallManifests manifests;

	private final JobTermination termination;

	private final RunDeferrals deferrals;

	private final TransactionTemplate transaction;

	private final AnalysisProperties properties;

	/**
	 * Decides whether the run may send now; called outside any transaction.
	 */
	Permit permit(ClaimedJob claimed, RunBasis basis, CallManifest manifest) {
		Permit decided = Objects.requireNonNull(transaction.execute((status) -> {
			Permit permit = decide(claimed, basis, manifest);
			if (permit instanceof Permit.Ended) {
				status.setRollbackOnly();
			}
			return permit;
		}));
		if (decided instanceof Permit.Ended ended) {
			return end(claimed, basis, ended);
		}
		return decided;
	}

	/**
	 * Ends the job for a denial that the rolled-back permit decided on its snapshot, in a transaction of its own that
	 * checks the reason again under its own locks: consent and photo before the job, the device's sends and the
	 * photo's sends after the usage release (plan 3.3). A reason that is gone sends the job back to {@code READY}
	 * instead; the next permit decides again.
	 */
	private Permit end(ClaimedJob claimed, RunBasis basis, Permit.Ended ended) {
		try {
			return Objects.requireNonNull(transaction.execute((status) -> endLocked(claimed, basis, ended)));
		}
		catch (JobTermination.ReasonLifted ex) {
			return Objects.requireNonNull(transaction.execute((status) -> {
				Instant now = Instant.now();
				return defer(claimed, deferrals.retryAt(null, now), now);
			}));
		}
	}

	private Permit endLocked(ClaimedJob claimed, RunBasis basis, Permit.Ended ended) {
		UUID ownerId = basis.ownerId();
		boolean consentHolds = consents.basisHolds(ownerId, basis.consentVersion());
		Set<UUID> existing = photos.holdExisting(ownerId, List.of(basis.photoId()));
		FailureCode reason = ended.reason();
		boolean holds = switch (reason) {
			case REPRESENTATIVE_DELETED -> !existing.contains(basis.photoId());
			case PRINCIPAL_INACTIVE -> !principals.isActive(ownerId);
			case CONSENT_WITHDRAWN -> !consentHolds;
			default -> true;
		};
		Instant now = Instant.now();
		if (!holds) {
			return defer(claimed, deferrals.retryAt(null, now), now);
		}
		JobEnding ending = new JobEnding(reason, claimed.runToken(), false, Settlement.NONE,
				recheck(basis, reason, now));
		return termination.end(claimed.jobId(), ending) ? ended : new Permit.Lost();
	}

	// The budget denials, under the locks the ending takes after the usage release.
	private Recheck recheck(RunBasis basis, FailureCode reason, Instant now) {
		AnalysisProperties.Budget budget = properties.budget();
		return switch (reason) {
			case DEVICE_BUDGET_EXHAUSTED -> () -> trialUsage
				.lockDeviceSend(Objects.requireNonNull(basis.trialMarkId(), "A trial job counts on a device mark"),
						budget.deviceSendMultiplier())
				.remaining() <= 0;
			case ATTEMPTS_EXHAUSTED -> () -> runs.lockPhotoBudget(basis.photoId(), basis.ownerId(), now)
				.riskSends() >= budget.photoLifetimeSends();
			default -> Recheck.NONE;
		};
	}

	private Permit decide(ClaimedJob claimed, RunBasis basis, CallManifest manifest) {
		Instant now = Instant.now();
		UUID ownerId = basis.ownerId();
		UUID jobId = claimed.jobId();
		UUID runToken = claimed.runToken();
		boolean consentHolds = consents.basisHolds(ownerId, basis.consentVersion());
		Set<UUID> existing = photos.holdExisting(ownerId, List.of(basis.photoId()));
		LockedJob job = jobs.lock(jobId).orElseThrow();
		if (runs.findCall(jobId, runToken).isPresent()) {
			return new Permit.Existing();
		}
		if (!job.heldBy(runToken)) {
			return new Permit.Lost();
		}
		if (!job.processingDeadline().isAfter(now)) {
			return new Permit.Ended(FailureCode.PROCESSING_EXPIRED);
		}
		AnalysisProperties.Worker worker = properties.worker();
		if (Duration.between(now, Objects.requireNonNull(job.lockedUntil())).compareTo(worker.sendMargin()) < 0) {
			return defer(claimed, now, now);
		}
		if (!existing.contains(basis.photoId())) {
			return new Permit.Ended(FailureCode.REPRESENTATIVE_DELETED);
		}
		if (!principals.isActive(ownerId)) {
			return new Permit.Ended(FailureCode.PRINCIPAL_INACTIVE);
		}
		if (!consentHolds) {
			return new Permit.Ended(FailureCode.CONSENT_WITHDRAWN);
		}
		byte[] inputSha256 = basis.inputSha256();
		if (inputSha256 == null) {
			return new Permit.Ended(FailureCode.INPUT_MISSING);
		}
		if (manifests.runnable(basis.manifestId()).isEmpty()) {
			return defer(claimed, deferrals.retryAt(null, now), now);
		}
		AnalysisProperties.Budget budget = properties.budget();
		LocalDate day = BudgetDays.of(now);
		if (runs.lockDailySends(ownerId, day) >= budget.principalDailySends()) {
			return defer(claimed, BudgetDays.nextStart(now), now);
		}
		UUID markId = null;
		if (basis.funding() == Funding.TRIAL) {
			markId = Objects.requireNonNull(basis.trialMarkId(), "A trial job counts on a device mark");
			if (trialUsage.lockDeviceSend(markId, budget.deviceSendMultiplier()).remaining() <= 0) {
				return new Permit.Ended(FailureCode.DEVICE_BUDGET_EXHAUSTED);
			}
		}
		if (runs.lockPhotoBudget(basis.photoId(), ownerId, now).riskSends() >= budget.photoLifetimeSends()) {
			return new Permit.Ended(FailureCode.ATTEMPTS_EXHAUSTED);
		}
		long maxCostMicros = manifest.settings().maxCostMicros();
		SpendDay spend = runs.lockSpendDay(day, budget.dailyCostLimitMicros(), now);
		Map<GateCause, Gate> locked = gates.lockAll();
		if (!spend.fits(maxCostMicros)) {
			Instant until = spend.spentBySettled(maxCostMicros) ? BudgetDays.nextStart(now)
					: now.plus(budget.reservedRetry());
			gates.holdBudget(locked.get(GateCause.BUDGET), until, now);
			return defer(claimed, until, now);
		}
		switch (AiGates.passage(locked, jobId, runToken, now)) {
			case Passage.Held held -> {
				Instant until = held.until();
				return defer(claimed, (until != null) ? until : deferrals.retryAt(null, now), now);
			}
			case Passage.Pass pass -> {
				if (pass.dueBudget() != null) {
					gates.closeBudget(pass.dueBudget(), now);
				}
			}
		}
		runs.addDailySend(ownerId, day, 1);
		if (markId != null) {
			trialUsage.reserveDeviceSend(markId);
		}
		runs.changePhotoBudget(basis.photoId(), 1, false, now);
		runs.reserveSpend(day, maxCostMicros, now);
		CallRow call = new CallRow(UUID.randomUUID(), ownerId, jobId, runToken, basis.photoId(), basis.funding(), day,
				maxCostMicros, markId, now);
		runs.insertCall(call, basis.manifestId(), inputSha256, manifests.configFingerprint());
		return new Permit.Granted(call);
	}

	private Permit defer(ClaimedJob claimed, Instant until, Instant now) {
		return deferrals.defer(claimed.jobId(), claimed.runToken(), until, claimed.probe(), now)
				? new Permit.Deferred(until) : new Permit.Lost();
	}

	/**
	 * The permit decision.
	 */
	sealed interface Permit {

		/**
		 * Send now.
		 * @param call the {@code SENDING} call inserted for the run
		 */
		record Granted(CallRow call) implements Permit {
		}

		/**
		 * A call was permitted for the run before; it is not sent again, and the lease recovery settles it.
		 */
		record Existing() implements Permit {
		}

		/** The run no longer holds the job. */
		record Lost() implements Permit {
		}

		/**
		 * The job went back to {@code READY}.
		 * @param until when it may be claimed again
		 */
		record Deferred(@Nullable Instant until) implements Permit {
		}

		/**
		 * The job ended without a send.
		 */
		record Ended(FailureCode reason) implements Permit {
		}

	}

}
