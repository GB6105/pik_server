package io.github.gb6105.pik.usage;

import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * The free trial: photos, representatives and members together, counted on both the principal and the requesting
 * device, given once and never reset (FR-B-01, P01). Accepting an analysis job reserves its photos, its result consumes
 * them for good and a failed, cancelled or ineligible job releases them (ADR-0021 #3, ADR-0024 #1). The limit and what
 * is left are never shown to clients.
 */
@Service
@RequiredArgsConstructor
public class TrialUsage {

	private final UsageRecords records;

	private final DeviceCheck deviceCheck;

	private final UsageProperties properties;

	private final TransactionTemplate transaction;

	/**
	 * Records in the DeviceCheck bits that an iOS device starts its trial, before the job that would use it is
	 * accepted (ADR-0029 #5). Does nothing when the device is not iOS, is already recorded or has no trial left for
	 * the principal. Calls Apple, so it runs outside database transactions; once the bits are set, the device has
	 * started its trial even if the job is then not accepted.
	 * @param deviceCheckToken a token the app has just generated; required while the start is not recorded
	 * @throws PikException {@code INVALID_INPUT} / {@code DEVICE_CHECK_TOKEN_REQUIRED} without a token while one is
	 * needed, or as {@link DeviceCheck#recordTrialStarted}
	 */
	public void recordStart(UUID principalId, UUID trialMarkId, @Nullable String deviceCheckToken) {
		Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
				"recordStart calls Apple and must run outside database transactions");
		boolean needed = Boolean.TRUE.equals(transaction.execute((status) -> {
			DeviceTrialMark mark = records.findMark(trialMarkId).orElseThrow();
			return mark.needsDeviceCheck() && mark.covers(1)
					&& records.findTrialQuota(principalId).map((quota) -> quota.covers(1)).orElse(true);
		}));
		if (!needed) {
			return;
		}
		if (!StringUtils.hasText(deviceCheckToken)) {
			throw new PikException(ErrorCode.INVALID_INPUT, "DEVICE_CHECK_TOKEN_REQUIRED", false);
		}
		deviceCheck.recordTrialStarted(deviceCheckToken);
		transaction.executeWithoutResult(
				(status) -> records.lockMark(trialMarkId).orElseThrow().recordDeviceCheck(Instant.now()));
	}

	/**
	 * Reserves the photos of one job, a representative and its members, in the job's transaction. The trial covers
	 * all of them or none: a bundle larger than what is left is not analyzed (ADR-0029 #6). An iOS device needs
	 * {@link #recordStart} first.
	 * @param trialMarkId the mark of the requesting device ({@code device.trial_mark_id})
	 * @param photos distinct photos
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Reservation reserve(UUID principalId, UUID trialMarkId, UUID jobId, List<Photo> photos) {
		Set<UUID> photoIds = photos.stream().map(Photo::id).collect(Collectors.toSet());
		Assert.isTrue(!photos.isEmpty() && photoIds.size() == photos.size(), "photos must be distinct and not empty");
		Instant now = Instant.now();
		UsageQuota quota = records.lockOrCreateTrialQuota(principalId, properties.trial().limit(), now);
		List<UsageReservation> unreleased = records.findUnreleased(principalId, photoIds);
		if (!unreleased.isEmpty()) {
			return new Reservation.AlreadyReserved(unreleased.stream()
				.collect(Collectors.toMap(UsageReservation::getPhotoId, UsageReservation::getJobId)));
		}
		DeviceTrialMark mark = records.lockMark(trialMarkId).orElseThrow();
		int count = photos.size();
		if (!quota.covers(count) || !mark.covers(count)) {
			return new Reservation.NotCovered();
		}
		Assert.state(!mark.needsDeviceCheck(), "recordStart must run before an iOS device's first trial reservation");
		photos.forEach((photo) -> records.persist(UsageReservation.trial(principalId, trialMarkId, jobId, photo, now)));
		quota.reserve(count, now);
		mark.reserve(count, now);
		return new Reservation.Reserved(count);
	}

	/**
	 * Consumes the job's reservations for good, when its result is stored. Repeated calls change nothing.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void consume(UUID principalId, UUID jobId) {
		settle(principalId, jobId, true);
	}

	/**
	 * Returns the job's reservations, when it fails, is cancelled or is ineligible. Repeated calls change nothing.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void release(UUID principalId, UUID jobId) {
		settle(principalId, jobId, false);
	}

	// The quota lock comes before reading the reservations, so two settlements of a job cannot both see them reserved.
	private void settle(UUID principalId, UUID jobId, boolean consume) {
		UsageQuota quota = records.lockTrialQuota(principalId).orElse(null);
		if (quota == null) {
			return;
		}
		List<UsageReservation> reserved = records.findReserved(principalId, jobId);
		if (reserved.isEmpty()) {
			return;
		}
		Set<UUID> markIds = reserved.stream().map(UsageReservation::getTrialMarkId).collect(Collectors.toSet());
		Assert.state(markIds.size() == 1, "A job's reservations count on one device mark");
		DeviceTrialMark mark = records.lockMark(markIds.iterator().next()).orElseThrow();
		Instant now = Instant.now();
		int count = reserved.size();
		if (consume) {
			reserved.forEach((reservation) -> reservation.consume(now));
			quota.consume(count, now);
			mark.consume(count, now);
		}
		else {
			reserved.forEach((reservation) -> reservation.release(now));
			quota.release(count, now);
			mark.release(count, now);
		}
	}

	/**
	 * @param version the photo version the job analyzes, kept for the record; a photo is counted once whatever its
	 * version
	 */
	public record Photo(UUID id, String version) {
	}

	/**
	 * What {@link #reserve} did. Only {@link Reserved} starts analysis.
	 */
	public sealed interface Reservation {

		record Reserved(int photos) implements Reservation {
		}

		/**
		 * The trial cannot cover the photos: nothing is reserved and the subscription guidance is due (FR-B-01).
		 */
		record NotCovered() implements Reservation {
		}

		/**
		 * Some photos already have a reservation that was not released; nothing is reserved.
		 * @param jobsByPhoto the job holding each of them
		 */
		record AlreadyReserved(Map<UUID, UUID> jobsByPhoto) implements Reservation {
		}

	}

}
