package io.github.gb6105.pik.usage;

import io.github.gb6105.pik.shared.id.UuidV7;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * Rows that two transactions may create at the same time (a principal's quota, an Android device's mark) are inserted
 * with {@code ON CONFLICT DO NOTHING} and read back, so the later transaction waits for the earlier one and uses its
 * row instead of failing.
 */
@Repository
@RequiredArgsConstructor
class UsageRecords {

	private final EntityManager entityManager;

	void persist(Object entity) {
		entityManager.persist(entity);
	}

	/**
	 * Locks the principal's trial quota, creating it with {@code limit} on first use. Every change of a principal's
	 * reservations takes this lock first and the device mark's second, so they never deadlock.
	 */
	UsageQuota lockOrCreateTrialQuota(UUID principalId, int limit, Instant now) {
		UsageQuota.Key key = UsageQuota.Key.trial(principalId);
		entityManager.createNativeQuery("""
				INSERT INTO usage_quota (principal_id, quota_kind, period_key, limit_count, reserved_count,
				                         consumed_count, created_at, updated_at, version)
				VALUES (:principalId, :quotaKind, :periodKey, :limit, 0, 0, :now, :now, 0)
				ON CONFLICT DO NOTHING
				""")
			.setParameter("principalId", key.principalId())
			.setParameter("quotaKind", key.quotaKind().name())
			.setParameter("periodKey", key.periodKey())
			.setParameter("limit", limit)
			.setParameter("now", now)
			.executeUpdate();
		return entityManager.find(UsageQuota.class, key, LockModeType.PESSIMISTIC_WRITE);
	}

	Optional<UsageQuota> findTrialQuota(UUID principalId) {
		return Optional.ofNullable(entityManager.find(UsageQuota.class, UsageQuota.Key.trial(principalId)));
	}

	Optional<UsageQuota> lockTrialQuota(UUID principalId) {
		return Optional.ofNullable(entityManager.find(UsageQuota.class, UsageQuota.Key.trial(principalId),
				LockModeType.PESSIMISTIC_WRITE));
	}

	Optional<DeviceTrialMark> findMark(UUID id) {
		return Optional.ofNullable(entityManager.find(DeviceTrialMark.class, id));
	}

	Optional<DeviceTrialMark> lockMark(UUID id) {
		return Optional.ofNullable(entityManager.find(DeviceTrialMark.class, id, LockModeType.PESSIMISTIC_WRITE));
	}

	/**
	 * The mark of an Android device, found by its ID hashed with any key version, or created with the current one.
	 */
	UUID androidMark(List<DeviceKeys.KeyedHash> hashes, int limit, Instant now) {
		Optional<UUID> existing = findAndroidMark(hashes);
		if (existing.isPresent()) {
			return existing.get();
		}
		DeviceKeys.KeyedHash current = hashes.get(0);
		entityManager.createNativeQuery("""
				INSERT INTO device_trial_mark (id, platform, device_key_hmac, key_version, limit_count, reserved_count,
				                               consumed_count, created_at, updated_at, version)
				VALUES (:id, 'ANDROID', :hash, :keyVersion, :limit, 0, 0, :now, :now, 0)
				ON CONFLICT (platform, key_version, device_key_hmac) WHERE device_key_hmac IS NOT NULL DO NOTHING
				""")
			.setParameter("id", UuidV7.generate())
			.setParameter("hash", current.hash())
			.setParameter("keyVersion", current.version())
			.setParameter("limit", limit)
			.setParameter("now", now)
			.executeUpdate();
		return findAndroidMark(List.of(current)).orElseThrow();
	}

	private Optional<UUID> findAndroidMark(List<DeviceKeys.KeyedHash> hashes) {
		for (DeviceKeys.KeyedHash hash : hashes) {
			UUID id = entityManager.createQuery("""
					SELECT m.id FROM DeviceTrialMark m
					WHERE m.platform = :platform AND m.keyVersion = :keyVersion AND m.deviceKeyHmac = :hash
					""", UUID.class)
				.setParameter("platform", DevicePlatform.ANDROID)
				.setParameter("keyVersion", hash.version())
				.setParameter("hash", hash.hash())
				.getSingleResultOrNull();
			if (id != null) {
				return Optional.of(id);
			}
		}
		return Optional.empty();
	}

	List<UsageReservation> findUnreleased(UUID principalId, Collection<UUID> photoIds) {
		return entityManager.createQuery("""
				SELECT r FROM UsageReservation r
				WHERE r.principalId = :principalId AND r.photoId IN :photoIds
				  AND r.status <> :released
				""", UsageReservation.class)
			.setParameter("principalId", principalId)
			.setParameter("photoIds", photoIds)
			.setParameter("released", ReservationStatus.RELEASED)
			.getResultList();
	}

	List<UsageReservation> findReserved(UUID principalId, UUID jobId) {
		return entityManager.createQuery("""
				SELECT r FROM UsageReservation r
				WHERE r.principalId = :principalId AND r.jobId = :jobId AND r.status = :reserved
				""", UsageReservation.class)
			.setParameter("principalId", principalId)
			.setParameter("jobId", jobId)
			.setParameter("reserved", ReservationStatus.RESERVED)
			.getResultList();
	}

}
