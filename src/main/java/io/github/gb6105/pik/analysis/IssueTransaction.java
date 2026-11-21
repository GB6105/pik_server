package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.account.Principals;
import io.github.gb6105.pik.analysis.JobRecords.LockedJob;
import io.github.gb6105.pik.analysis.UploadAttemptResponse.Item;
import io.github.gb6105.pik.analysis.UploadRecords.AttemptRow;
import io.github.gb6105.pik.analysis.UploadRecords.NewAttempt;
import io.github.gb6105.pik.analysis.UploadRecords.RateSubject;
import io.github.gb6105.pik.analysis.UploadRecords.RateWindow;
import io.github.gb6105.pik.analysis.UploadRecords.RequestBasis;
import io.github.gb6105.pik.consent.AiConsents;
import io.github.gb6105.pik.shared.error.CommandResult;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.id.UuidV7;
import io.github.gb6105.pik.shared.idempotency.Outcome;
import io.github.gb6105.pik.usage.TrialUsage;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The business transaction of an upload attempt request (design 2.3, plan 4.1), run by the idempotency executor. Locks
 * in the order consent (shared), the request's jobs in {@code id} order, their current attempts, then the input rate
 * windows of the principal and of the trial mark (plan 3.3 "시도 발급"), decides each item, and writes in the order the
 * unique index needs: the current attempts become {@code SUPERSEDED}, the new ones are inserted {@code ISSUED}, and the
 * jobs count them. The stored response carries no URL.
 */
@Component
@RequiredArgsConstructor
class IssueTransaction {

	private static final Duration RATE_WINDOW = Duration.ofHours(1);

	private final AiConsents consents;

	private final Principals principals;

	private final TrialUsage trialUsage;

	private final JobRecords jobs;

	private final UploadRecords attempts;

	private final AnalysisProperties properties;

	/**
	 * @param items the checked items of the request, each job once
	 * @return 200 with a decision on every item
	 * @throws PikException {@code RATE_LIMITED} / {@code UPLOAD_RATE_LIMITED} when the attempts to issue exceed an
	 * input rate limit; nothing is stored
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Outcome run(UUID key, RequestBasis request, List<CheckedItem> items) {
		UUID ownerId = request.ownerId();
		if (!consents.basisHolds(ownerId, request.consentVersion())) {
			return outcome(key, items.stream()
				.map((item) -> Item.rejected(item.analysisJobId(), null, UploadRejection.CONSENT_WITHDRAWN))
				.toList());
		}
		Set<UUID> jobIds = new LinkedHashSet<>();
		items.forEach((item) -> jobIds.add(item.analysisJobId()));
		Map<UUID, LockedJob> locked = jobs.lockOfRequest(request.id(), jobIds);
		Map<UUID, AttemptRow> current = attempts.lockIssued(locked.keySet());
		boolean active = principals.isActive(ownerId);
		Instant now = Instant.now();
		AnalysisProperties.Upload upload = properties.upload();

		List<Item> decided = new ArrayList<>();
		List<NewAttempt> issued = new ArrayList<>();
		List<UUID> superseded = new ArrayList<>();
		List<UUID> issuedJobs = new ArrayList<>();
		long bytes = 0;
		int trialItems = 0;
		long trialBytes = 0;
		for (CheckedItem item : items) {
			LockedJob job = locked.get(item.analysisJobId());
			UploadRejection rejection = rejection(job, item, active, now);
			if (rejection != null) {
				decided.add(Item.rejected(item.analysisJobId(), null, rejection));
				continue;
			}
			// Cut to the microseconds that PostgreSQL keeps, so the response states the stored url_expires_at. The
			// Linux clock has nanoseconds. Cutting down keeps the expiry within the TTL and the upload deadline.
			Instant expiresAt = earlier(now.plus(upload.urlTtl()), job.uploadDeadline()).truncatedTo(ChronoUnit.MICROS);
			UUID attemptId = UuidV7.generate();
			issued.add(new NewAttempt(attemptId, ownerId, job.id(), item.contentLength(), item.sha256(), expiresAt));
			issuedJobs.add(job.id());
			AttemptRow previous = current.get(job.id());
			if (previous != null) {
				superseded.add(previous.id());
			}
			decided.add(Item.issued(job.id(), attemptId, expiresAt));
			bytes += job.maxBytes();
			if (job.funding() == Funding.TRIAL) {
				trialItems++;
				trialBytes += job.maxBytes();
			}
		}
		if (!issued.isEmpty()) {
			limitInputRate(request, issued.size(), bytes, trialItems, trialBytes, now);
			attempts.supersede(superseded, now);
			attempts.insertIssued(issued, now);
			jobs.countIssued(issuedJobs, now);
		}
		return outcome(key, decided);
	}

	// Plan 4.1: stops at the first failed check.
	private @Nullable UploadRejection rejection(@Nullable LockedJob job, CheckedItem item, boolean active,
			Instant now) {
		if (job == null) {
			return UploadRejection.NOT_FOUND;
		}
		if (job.status() != JobStatus.AWAITING_UPLOAD) {
			return UploadRejection.ATTEMPT_NOT_ALLOWED;
		}
		if (!job.uploadDeadline().isAfter(now.plus(UploadSigning.MIN_URL_VALIDITY))) {
			return UploadRejection.UPLOAD_EXPIRED;
		}
		if (!active) {
			return UploadRejection.PRINCIPAL_INACTIVE;
		}
		if (job.funding() == Funding.TRIAL && !trialUsage.holdsReservation(job.ownerId(), job.id())) {
			return UploadRejection.RESERVATION_MISSING;
		}
		if (job.issuedCount() >= properties.upload().maxAttempts()) {
			return UploadRejection.UPLOAD_ATTEMPT_LIMIT;
		}
		if (item.contentLength() > job.maxBytes()) {
			return UploadRejection.VALIDATION_FAILED;
		}
		return null;
	}

	/**
	 * Counts the attempts to issue, each as the largest upload of its manifest, in the hour window of the principal
	 * and then of the trial mark for trial jobs (design 2.7). The window rows are updated atomically; an excess rolls
	 * the whole transaction back.
	 */
	private void limitInputRate(RequestBasis request, int items, long bytes, int trialItems, long trialBytes,
			Instant now) {
		AnalysisProperties.InputRate limits = properties.inputRate();
		Instant window = now.truncatedTo(ChronoUnit.HOURS);
		RateWindow principal = attempts.addInputRate(RateSubject.PRINCIPAL, request.ownerId(), window, items, bytes);
		if (principal.items() > limits.principalItemsPerHour()
				|| principal.bytes() > limits.principalBytesPerHour().toBytes()) {
			throw rateLimited(window, now);
		}
		UUID trialMarkId = request.trialMarkId();
		if (trialMarkId != null && trialItems > 0) {
			RateWindow trial = attempts.addInputRate(RateSubject.TRIAL_MARK, trialMarkId, window, trialItems,
					trialBytes);
			if (trial.items() > limits.trialItemsPerHour() || trial.bytes() > limits.trialBytesPerHour().toBytes()) {
				throw rateLimited(window, now);
			}
		}
	}

	private static PikException rateLimited(Instant window, Instant now) {
		return PikException.unstored(ErrorCode.RATE_LIMITED, "UPLOAD_RATE_LIMITED", true,
				Duration.between(now, window.plus(RATE_WINDOW)), Map.of());
	}

	private static Outcome outcome(UUID key, List<Item> items) {
		return new Outcome(HttpStatus.OK, new UploadAttemptResponse(key, CommandResult.SUCCEEDED, false, items));
	}

	private static Instant earlier(Instant a, Instant b) {
		return a.isBefore(b) ? a : b;
	}

	/**
	 * An item whose values passed the request checks.
	 *
	 * @param sha256 the decoded SHA-256, 32 bytes
	 */
	record CheckedItem(UUID analysisJobId, long contentLength, byte[] sha256) {

		@Override
		public String toString() {
			return "CheckedItem[analysisJobId=" + analysisJobId + ", contentLength=" + contentLength + "]";
		}

	}

}
