package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.account.Principals;
import io.github.gb6105.pik.analysis.JobRecords.LockedJob;
import io.github.gb6105.pik.analysis.JobTermination.JobEnding;
import io.github.gb6105.pik.analysis.UploadRecords.AttemptBasis;
import io.github.gb6105.pik.analysis.UploadRecords.AttemptRow;
import io.github.gb6105.pik.consent.AiConsents;
import io.github.gb6105.pik.usage.TrialUsage;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * The confirmation condition of an upload, shared by the confirmation API and the upload recovery batch (design 2.3,
 * plan 4.2, 7.1): the attempt is the job's current {@code ISSUED} one, the consent basis holds, the principal is
 * active, the processing deadline has not passed, the trial reservation is held, the object was stored before the
 * URL expired, and its size, SHA-256 and content type are the attempt's. Locks in the order consent (shared), job,
 * attempt (plan 3.3 "확정"); a failure that ends the job goes through {@link JobTermination}, which then locks the
 * usage allocation and the device mark.
 */
@Component
@RequiredArgsConstructor
class AttemptConfirmation {

	private final UploadRecords attempts;

	private final JobRecords jobs;

	private final JobTermination termination;

	private final AiConsents consents;

	private final Principals principals;

	private final TrialUsage trialUsage;

	/**
	 * Takes the locks of a confirmation and reads the job and the attempt under them.
	 * @return empty when the attempt does not exist
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Optional<Locked> lock(UUID attemptId) {
		AttemptBasis basis = attempts.findBasis(attemptId).orElse(null);
		if (basis == null) {
			return Optional.empty();
		}
		boolean basisHolds = consents.basisHolds(basis.ownerId(), basis.consentVersion());
		LockedJob job = jobs.lock(basis.jobId()).orElseThrow();
		AttemptRow attempt = attempts.lock(attemptId).orElseThrow();
		return Optional.of(new Locked(job, attempt, basisHolds));
	}

	/**
	 * Applies the condition to an attempt observed earlier without locks, as the recovery batch does: nothing changes
	 * unless that attempt is still the job's current {@code ISSUED} one.
	 * @param observedAttemptId the attempt the object was read for
	 * @param observed the stored object
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Confirmation confirm(UUID observedAttemptId, InputStore.Head.Present observed) {
		Locked locked = lock(observedAttemptId).orElse(null);
		if (locked == null || !locked.current()) {
			return new Confirmation.NotCurrent();
		}
		return apply(locked, observed);
	}

	/**
	 * Applies the condition under the locks of {@link #lock}, to the job's current {@code ISSUED} attempt.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Confirmation apply(Locked locked, InputStore.Head.Present observed) {
		Assert.state(locked.current(), "Only the job's current ISSUED attempt can be confirmed");
		LockedJob job = locked.job();
		AttemptRow attempt = locked.attempt();
		Instant now = Instant.now();
		FailureCode ending = ending(locked, now);
		if (ending != null) {
			termination.end(job.id(), new JobEnding(ending));
			return new Confirmation.Ended(ending);
		}
		if (job.funding() == Funding.TRIAL && !trialUsage.holdsReservation(job.ownerId(), job.id())) {
			return new Confirmation.Unconfirmable(UploadRejection.RESERVATION_MISSING);
		}
		RejectReason mismatch = mismatch(attempt, observed);
		if (mismatch != null) {
			attempts.reject(attempt.id(), mismatch, now);
			return new Confirmation.Rejected(mismatch);
		}
		attempts.confirm(attempt.id(), observed.etag(), observed.lastModified(), now);
		jobs.markReady(job.id(), attempt.sha256(), observed.etag(), now);
		return new Confirmation.Confirmed();
	}

	// The failures that end the job whatever the object is.
	private @Nullable FailureCode ending(Locked locked, Instant now) {
		if (!locked.basisHolds()) {
			return FailureCode.CONSENT_WITHDRAWN;
		}
		if (!principals.isActive(locked.job().ownerId())) {
			return FailureCode.PRINCIPAL_INACTIVE;
		}
		if (!locked.job().processingDeadline().isAfter(now)) {
			return FailureCode.PROCESSING_EXPIRED;
		}
		return null;
	}

	private static @Nullable RejectReason mismatch(AttemptRow attempt, InputStore.Head.Present observed) {
		if (observed.lastModified().isAfter(attempt.urlExpiresAt())) {
			return RejectReason.LATE_UPLOAD;
		}
		if (observed.size() != attempt.contentLength()) {
			return RejectReason.SIZE_MISMATCH;
		}
		byte[] sha256 = observed.sha256();
		if (sha256 == null || !Arrays.equals(sha256, attempt.sha256())) {
			return RejectReason.CHECKSUM_MISMATCH;
		}
		if (!InputStore.CONTENT_TYPE.equalsIgnoreCase(observed.contentType())) {
			return RejectReason.CONTENT_TYPE_MISMATCH;
		}
		return null;
	}

	/**
	 * A job and one of its attempts read under the confirmation's locks.
	 *
	 * @param basisHolds whether the consent basis of the job's request holds, checked under the consent lock
	 */
	record Locked(LockedJob job, AttemptRow attempt, boolean basisHolds) {

		/**
		 * Whether the attempt is the job's current {@code ISSUED} one; a job that ended has none.
		 */
		boolean current() {
			return attempt.status() == AttemptStatus.ISSUED && job.status() == JobStatus.AWAITING_UPLOAD;
		}

	}

	sealed interface Confirmation {

		/** The attempt is {@code CONFIRMED} and the job {@code READY}. */
		record Confirmed() implements Confirmation {
		}

		/** The attempt is {@code REJECTED}; the job still waits for an upload. */
		record Rejected(RejectReason reason) implements Confirmation {
		}

		/** The job ended through {@link JobTermination}. */
		record Ended(FailureCode reason) implements Confirmation {
		}

		/** Nothing changed: the attempt cannot be confirmed, and no other attempt of the job can either. */
		record Unconfirmable(UploadRejection reason) implements Confirmation {
		}

		/** Nothing changed: the observed attempt is no longer the job's current one. */
		record NotCurrent() implements Confirmation {
		}

	}

}
