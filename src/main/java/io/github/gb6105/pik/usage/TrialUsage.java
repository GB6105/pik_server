package io.github.gb6105.pik.usage;

import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * The free trial: photos, representatives and members together, counted on both the principal and the requesting
 * device, given once and never reset (FR-B-01, P01). Accepting an analysis job reserves its photos, its result consumes
 * them for good and a failed, cancelled or ineligible job releases them (ADR-0021 #3, ADR-0024 #1). The limit and what
 * is left are never shown to clients.
 * <p>
 * One transaction may change the usage of one principal on one device only: each call locks the principal's quota and
 * then the device's mark, and two transactions spanning several of them could take the locks in opposite orders
 * (ADR-0029 #16). The device's AI sends lock the mark only, so they come after any change of the principal's usage in
 * the transaction (plan 3.3). For the same reason a transaction nested with {@code REQUIRES_NEW} in one that changed
 * usage or sends may not change them again.
 */
@Service
@RequiredArgsConstructor
public class TrialUsage {

	private static final Object SCOPE = new Object();

	private final UsageRecords records;

	private final DeviceCheck deviceCheck;

	private final UsageProperties properties;

	private final TransactionTemplate transaction;

	/**
	 * Records in the DeviceCheck bits that an iOS device starts its trial, before the job that would use it is
	 * accepted (ADR-0029 #5·18). Does nothing when the device is not iOS, is already recorded or cannot cover the
	 * photos for the principal, even while an earlier write is unconfirmed. A device whose bits another install has
	 * set gets no trial. Calls Apple, so it runs outside database transactions; once the bits are set, the device has
	 * started its trial even if the job is then not accepted.
	 * @param photos the photos of the job about to be accepted
	 * @param deviceCheckToken a token the app has just generated; required while the start is not recorded
	 * @throws PikException {@code INVALID_INPUT} / {@code DEVICE_CHECK_TOKEN_REQUIRED} without a token while one is
	 * needed, or as {@link DeviceCheck}
	 */
	public void recordStart(UUID principalId, UUID trialMarkId, int photos, @Nullable String deviceCheckToken) {
		recordStart(principalId, trialMarkId, photos, deviceCheckToken, () -> {
		});
	}

	/**
	 * As {@link #recordStart(UUID, UUID, int, String)}, calling {@code guard} once right before Apple is asked: after
	 * the token check, only when the start is to be written or resumed (B6-2.E1).
	 * @throws PikException also as {@code guard} throws, before anything is written
	 */
	public void recordStart(UUID principalId, UUID trialMarkId, int photos, @Nullable String deviceCheckToken,
			StartGuard guard) {
		Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
				"recordStart calls Apple and must run outside database transactions");
		Assert.isTrue(photos >= 1, "photos must be at least 1");
		Start start = transaction.execute((status) -> {
			DeviceTrialMark mark = records.findMark(trialMarkId).orElseThrow();
			if (!mark.needsDeviceCheck()) {
				return Start.NONE;
			}
			boolean covered = mark.covers(photos) && records.findTrialQuota(principalId)
				.map((quota) -> quota.covers(photos))
				.orElse(photos <= properties.trial().limit());
			if (!covered) {
				return Start.NONE;
			}
			return mark.isStarting() ? Start.RESUME : Start.NEW;
		});
		if (start == Start.NONE) {
			return;
		}
		if (!StringUtils.hasText(deviceCheckToken)) {
			throw new PikException(ErrorCode.INVALID_INPUT, "DEVICE_CHECK_TOKEN_REQUIRED", false);
		}
		guard.beforeStart();
		if (start == Start.NEW) {
			// Another install of the device may have started the trial since this one was registered.
			if (deviceCheck.trialStarted(deviceCheckToken)) {
				updateMark(trialMarkId, DeviceTrialMark::startedElsewhere);
				return;
			}
			updateMark(trialMarkId, DeviceTrialMark::startDeviceCheck);
		}
		try {
			deviceCheck.recordTrialStarted(deviceCheckToken);
		}
		catch (PikException ex) {
			// A refused token wrote nothing; after any other failure the bits may be set, so the mark stays starting.
			// A retry keeps it starting even when refused, since an earlier write may have set the bits.
			if (start == Start.NEW && ex.getCode() == ErrorCode.FORBIDDEN) {
				updateMark(trialMarkId, DeviceTrialMark::cancelDeviceCheck);
			}
			throw ex;
		}
		updateMark(trialMarkId, DeviceTrialMark::recordDeviceCheck);
	}

	/**
	 * Reserves the photos of one job, a representative and its members, in the job's transaction. The trial covers
	 * all of them or none: a bundle larger than what is left is not analyzed (ADR-0029 #6).
	 * @param trialMarkId the mark of the requesting device ({@code device.trial_mark_id})
	 * @param photos distinct photos
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Reservation reserve(UUID principalId, UUID trialMarkId, UUID jobId, List<Photo> photos) {
		Set<UUID> photoIds = photos.stream().map(Photo::id).collect(Collectors.toSet());
		Assert.isTrue(!photos.isEmpty() && photoIds.size() == photos.size(), "photos must be distinct and not empty");
		claimScope(principalId, trialMarkId);
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
			return new Reservation.NotCovered(quota.coversOnceReleased(count) && mark.coversOnceReleased(count));
		}
		if (mark.needsDeviceCheck()) {
			return new Reservation.StartRequired();
		}
		photos.forEach((photo) -> records.persist(UsageReservation.trial(principalId, trialMarkId, jobId, photo, now)));
		quota.reserve(count, now);
		mark.reserve(count, now);
		return new Reservation.Reserved(count);
	}

	/**
	 * Consumes the job's reservations for good, when its result is stored. Repeated calls change nothing.
	 * @return the photos consumed now; 0 when the job holds no reservation, for example because it was released
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int consume(UUID principalId, UUID jobId) {
		return settleAll(principalId, jobId, true);
	}

	/**
	 * Returns the job's reservations, when it fails, is cancelled or is ineligible. Repeated calls change nothing.
	 * @return the photos released now
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int release(UUID principalId, UUID jobId) {
		return settleAll(principalId, jobId, false);
	}

	/**
	 * Settles the job's reservations photo by photo, when its result is stored: the photos given a result are consumed
	 * and the members whose metadata was deleted are released, under one lock of the quota and the device (design 2.4
	 * step 6). The two sets split the photos of the job's reservations. A repeated call that asks for the state each
	 * reservation already has changes nothing.
	 * @param consumePhotoIds the photos to consume
	 * @param releasePhotoIds the photos to release
	 * @throws IllegalArgumentException when the sets overlap or do not cover exactly the photos of the job's
	 * reservations
	 * @throws IllegalStateException when a reservation already has the other final state
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public SettleResult settle(UUID principalId, UUID jobId, Set<UUID> consumePhotoIds, Set<UUID> releasePhotoIds) {
		Assert.isTrue(Collections.disjoint(consumePhotoIds, releasePhotoIds),
				"The photos to consume and to release must not overlap");
		claimScope(principalId, null);
		UsageQuota quota = records.lockTrialQuota(principalId).orElse(null);
		List<UsageReservation> reservations = (quota != null) ? records.findOfJob(principalId, jobId) : List.of();
		Set<UUID> photoIds = reservations.stream().map(UsageReservation::getPhotoId).collect(Collectors.toSet());
		Assert.isTrue(photoIds.size() == consumePhotoIds.size() + releasePhotoIds.size()
				&& photoIds.containsAll(consumePhotoIds) && photoIds.containsAll(releasePhotoIds),
				"The photos to consume and to release must be the photos of the job's reservations");
		List<UsageReservation> toConsume = new ArrayList<>();
		List<UsageReservation> toRelease = new ArrayList<>();
		for (UsageReservation reservation : reservations) {
			boolean consume = consumePhotoIds.contains(reservation.getPhotoId());
			ReservationStatus target = consume ? ReservationStatus.CONSUMED : ReservationStatus.RELEASED;
			if (reservation.getStatus() == ReservationStatus.RESERVED) {
				(consume ? toConsume : toRelease).add(reservation);
			}
			else {
				Assert.state(reservation.getStatus() == target,
						() -> "A " + reservation.getStatus() + " reservation cannot become " + target);
			}
		}
		if (toConsume.isEmpty() && toRelease.isEmpty()) {
			return new SettleResult(0, 0);
		}
		UUID markId = markOf(reservations);
		claimScope(principalId, markId);
		DeviceTrialMark mark = records.lockMark(markId).orElseThrow();
		Instant now = Instant.now();
		if (!toConsume.isEmpty()) {
			toConsume.forEach((reservation) -> reservation.consume(now));
			quota.consume(toConsume.size(), now);
			mark.consume(toConsume.size(), now);
		}
		if (!toRelease.isEmpty()) {
			toRelease.forEach((reservation) -> reservation.release(now));
			quota.release(toRelease.size(), now);
			mark.release(toRelease.size(), now);
		}
		return new SettleResult(toConsume.size(), toRelease.size());
	}

	/**
	 * Locks the device's mark for the AI send budget of a trial job (design 2.7).
	 * @param multiplier sends allowed per photo of the device's trial limit, an analysis setting
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public DeviceSendCheck lockDeviceSend(UUID trialMarkId, int multiplier) {
		claimScope(null, trialMarkId);
		return sendCheck(records.lockMark(trialMarkId).orElseThrow(), multiplier);
	}

	/**
	 * Counts one AI send on the device, after {@link #lockDeviceSend} found one left.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void reserveDeviceSend(UUID trialMarkId) {
		claimScope(null, trialMarkId);
		records.lockMark(trialMarkId).orElseThrow().reserveSend(Instant.now());
	}

	/**
	 * Returns a reserved AI send that was not spent. The count never goes below 0.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void releaseDeviceSend(UUID trialMarkId) {
		claimScope(null, trialMarkId);
		records.lockMark(trialMarkId).orElseThrow().releaseSend(Instant.now());
	}

	/**
	 * Whether the device has no AI send left, read without a lock for a quick check before a job is accepted; the
	 * send itself checks again under {@link #lockDeviceSend}.
	 * @param multiplier as {@link #lockDeviceSend}
	 */
	@Transactional(readOnly = true)
	public boolean deviceSendExhausted(UUID trialMarkId, int multiplier) {
		return sendCheck(records.findMark(trialMarkId).orElseThrow(), multiplier).remaining() == 0;
	}

	private static DeviceSendCheck sendCheck(DeviceTrialMark mark, int multiplier) {
		Assert.isTrue(multiplier >= 1, "multiplier must be at least 1");
		return new DeviceSendCheck(mark.getSendCount(), Math.multiplyExact(mark.getLimitCount(), multiplier));
	}

	private static UUID markOf(List<UsageReservation> reservations) {
		Set<UUID> markIds = reservations.stream().map(UsageReservation::getTrialMarkId).collect(Collectors.toSet());
		Assert.state(markIds.size() == 1, "A job's reservations count on one device mark");
		return markIds.iterator().next();
	}

	// The quota lock comes before reading the reservations, so two settlements of a job cannot both see them reserved.
	private int settleAll(UUID principalId, UUID jobId, boolean consume) {
		claimScope(principalId, null);
		UsageQuota quota = records.lockTrialQuota(principalId).orElse(null);
		if (quota == null) {
			return 0;
		}
		List<UsageReservation> reserved = records.findReserved(principalId, jobId);
		if (reserved.isEmpty()) {
			return 0;
		}
		UUID markId = markOf(reserved);
		claimScope(principalId, markId);
		DeviceTrialMark mark = records.lockMark(markId).orElseThrow();
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
		return count;
	}

	private void updateMark(UUID trialMarkId, BiConsumer<DeviceTrialMark, Instant> change) {
		transaction.executeWithoutResult(
				(status) -> change.accept(records.lockMark(trialMarkId).orElseThrow(), Instant.now()));
	}

	// A scope that is bound but not among the current synchronizations belongs to a suspended outer transaction, whose
	// locks a nested one would wait for. A principal of null stands for the device's sends, which lock the mark only.
	private static void claimScope(@Nullable UUID principalId, @Nullable UUID trialMarkId) {
		Assert.state(TransactionSynchronizationManager.isSynchronizationActive(),
				"Trial usage needs transaction synchronization");
		Scope scope = (Scope) TransactionSynchronizationManager.getResource(SCOPE);
		if (scope == null) {
			scope = new Scope(principalId);
			TransactionSynchronizationManager.registerSynchronization(scope);
			TransactionSynchronizationManager.bindResource(SCOPE, scope);
		}
		else {
			Assert.state(TransactionSynchronizationManager.getSynchronizations().contains(scope),
					"Trial usage must not change in a transaction nested in one that changed it (ADR-0029 #16)");
		}
		scope.claim(principalId, trialMarkId);
	}

	private enum Start {

		NONE, NEW, RESUME

	}

	/**
	 * The principal and device mark a transaction has changed usage for, released before it completes so that
	 * after-commit callbacks may change usage again in a new transaction. A scope started by the device's sends has no
	 * principal, and no principal's usage may change after them: that would lock the quota after the mark.
	 */
	private static final class Scope implements TransactionSynchronization {

		private final @Nullable UUID principalId;

		private @Nullable UUID trialMarkId;

		private Scope(@Nullable UUID principalId) {
			this.principalId = principalId;
		}

		private void claim(@Nullable UUID principalId, @Nullable UUID trialMarkId) {
			Assert.state((principalId == null || principalId.equals(this.principalId))
					&& (trialMarkId == null || this.trialMarkId == null || this.trialMarkId.equals(trialMarkId)),
					"One transaction may change the trial usage of one principal on one device only, before the "
							+ "device's sends (ADR-0029 #16)");
			if (trialMarkId != null) {
				this.trialMarkId = trialMarkId;
			}
		}

		@Override
		public void beforeCompletion() {
			TransactionSynchronizationManager.unbindResourceIfPossible(SCOPE);
		}

		@Override
		public void afterCompletion(int status) {
			TransactionSynchronizationManager.unbindResourceIfPossible(SCOPE);
		}

	}

	/**
	 * @param version the photo version the job analyzes, kept for the record; a photo is counted once whatever its
	 * version
	 */
	public record Photo(UUID id, String version) {
	}

	/**
	 * What {@link #settle(UUID, UUID, Set, Set)} changed now; both 0 when every reservation already had the requested
	 * state.
	 */
	public record SettleResult(int consumed, int released) {

		public boolean unchanged() {
			return consumed == 0 && released == 0;
		}

	}

	/**
	 * The AI sends of a trial device (design 2.7).
	 * @param sent sends reserved or spent so far
	 * @param limit the trial limit of the device times the multiplier
	 */
	public record DeviceSendCheck(int sent, int limit) {

		public int remaining() {
			return Math.max(0, limit - sent);
		}

	}

	/**
	 * Called by {@link #recordStart(UUID, UUID, int, String, StartGuard)} right before Apple records the trial start,
	 * for checks that must hold before the start is written, such as the first trial assertion (B6-2).
	 */
	@FunctionalInterface
	public interface StartGuard {

		void beforeStart();

	}

	/**
	 * What {@link #reserve} did. Only {@link Reserved} starts analysis.
	 */
	public sealed interface Reservation {

		record Reserved(int photos) implements Reservation {
		}

		/**
		 * The trial cannot cover the photos: nothing is reserved and the subscription guidance is due (FR-B-01).
		 * @param retryable whether the photos would fit once the reservations still pending are released, on both the
		 * principal and the device; {@code false} when consumed photos or an exhausted device leave too little
		 */
		record NotCovered(boolean retryable) implements Reservation {
		}

		/**
		 * Some photos already have a reservation that was not released; nothing is reserved.
		 * @param jobsByPhoto the job holding each of them
		 */
		record AlreadyReserved(Map<UUID, UUID> jobsByPhoto) implements Reservation {
		}

		/**
		 * The iOS device has not recorded its trial start, for example because the trial could not cover the photos
		 * when {@link #recordStart} ran and a release freed some since. Nothing is reserved; record the start and
		 * reserve again.
		 */
		record StartRequired() implements Reservation {
		}

	}

}
