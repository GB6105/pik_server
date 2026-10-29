package io.github.gb6105.pik.usage;

import io.github.gb6105.pik.shared.id.UuidV7;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Trial use of one physical device, kept apart from accounts so that it outlives reinstalls and account deletion
 * (ADR-0020, ADR-0021 #8). An Android device is found again through the HMAC of its ID; an iOS install gets a new mark,
 * and the DeviceCheck bits tell whether the device started a trial before. The database keeps
 * {@code reserved + consumed <= limit}.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class DeviceTrialMark {

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	private DevicePlatform platform;

	/** Android only: HMAC of the device ID ({@link DeviceKeys}). */
	private byte @Nullable [] deviceKeyHmac;

	private @Nullable Integer keyVersion;

	/** iOS only: set once the DeviceCheck bits record a started trial. */
	@Enumerated(EnumType.STRING)
	private @Nullable DeviceCheckState deviceCheckState;

	private int limitCount;

	private int reservedCount;

	private int consumedCount;

	/** When the device ran out of trial: its last photo was consumed, or it had started a trial before this install. */
	private @Nullable Instant exhaustedAt;

	private Instant createdAt;

	private Instant updatedAt;

	@Version
	private long version;

	private DeviceTrialMark(boolean startedBefore, int limit, Instant now) {
		this.id = UuidV7.generate();
		this.platform = DevicePlatform.IOS;
		this.deviceCheckState = startedBefore ? DeviceCheckState.TRIAL_STARTED : null;
		this.limitCount = limit;
		this.exhaustedAt = startedBefore ? now : null;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/**
	 * A new iOS install. A device that started a trial before gets none, since two bits cannot tell how much was left
	 * (ADR-0029 #5).
	 */
	static DeviceTrialMark ios(boolean startedBefore, int limit, Instant now) {
		return new DeviceTrialMark(startedBefore, limit, now);
	}

	boolean covers(int photos) {
		return exhaustedAt == null && limitCount - reservedCount - consumedCount >= photos;
	}

	/**
	 * Whether the DeviceCheck bits must record the start before the device's first trial reservation.
	 */
	boolean needsDeviceCheck() {
		return platform == DevicePlatform.IOS && deviceCheckState == null;
	}

	void recordDeviceCheck(Instant now) {
		deviceCheckState = DeviceCheckState.TRIAL_STARTED;
		updatedAt = now;
	}

	void reserve(int photos, Instant now) {
		reservedCount += photos;
		updatedAt = now;
	}

	void consume(int photos, Instant now) {
		reservedCount -= photos;
		consumedCount += photos;
		if (consumedCount >= limitCount && exhaustedAt == null) {
			exhaustedAt = now;
		}
		updatedAt = now;
	}

	void release(int photos, Instant now) {
		reservedCount -= photos;
		updatedAt = now;
	}

}
