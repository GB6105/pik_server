package io.github.gb6105.pik.usage;

import io.github.gb6105.pik.shared.id.UuidV7;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One photo counted against a quota. A photo is counted once: a unique index rejects a second reservation while one
 * is not released, whatever the photo version (ADR-0024 #4-1·6-2). Member photos point at the job of their
 * representative.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class UsageReservation {

	@Id
	private UUID id;

	private UUID principalId;

	@Enumerated(EnumType.STRING)
	private QuotaKind quotaKind;

	private String periodKey;

	private UUID photoId;

	/** Photo version at the time of the reservation, kept for the record only. */
	private String photoVersion;

	private UUID jobId;

	/** The device mark counted together with a trial reservation. */
	private @Nullable UUID trialMarkId;

	@Enumerated(EnumType.STRING)
	private ReservationStatus status;

	private Instant createdAt;

	private Instant updatedAt;

	private UsageReservation(UsageQuota.Key quota, UUID photoId, String photoVersion, UUID jobId,
			@Nullable UUID trialMarkId, Instant now) {
		this.id = UuidV7.generate();
		this.principalId = quota.principalId();
		this.quotaKind = quota.quotaKind();
		this.periodKey = quota.periodKey();
		this.photoId = photoId;
		this.photoVersion = photoVersion;
		this.jobId = jobId;
		this.trialMarkId = trialMarkId;
		this.status = ReservationStatus.RESERVED;
		this.createdAt = now;
		this.updatedAt = now;
	}

	static UsageReservation trial(UUID principalId, UUID trialMarkId, UUID jobId, TrialUsage.Photo photo,
			Instant now) {
		return new UsageReservation(UsageQuota.Key.trial(principalId), photo.id(), photo.version(), jobId,
				trialMarkId, now);
	}

	void consume(Instant now) {
		status = ReservationStatus.CONSUMED;
		updatedAt = now;
	}

	void release(Instant now) {
		status = ReservationStatus.RELEASED;
		updatedAt = now;
	}

}
