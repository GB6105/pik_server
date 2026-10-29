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

	/** iOS only: whether the DeviceCheck bits record a started trial. */
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

	private DeviceTrialMark(int limit, Instant now) {
		this.id = UuidV7.generate();
		this.platform = DevicePlatform.IOS;
		this.limitCount = limit;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/**
	 * A new iOS install. A device that started a trial before gets none, since two bits cannot tell how much was left
	 * (ADR-0029 #5).
	 */
	static DeviceTrialMark ios(boolean startedBefore, int limit, Instant now) {
		DeviceTrialMark mark = new DeviceTrialMark(limit, now);
		if (startedBefore) {
			mark.startedElsewhere(now);
		}
		return mark;
	}

	boolean covers(int photos) {
		return exhaustedAt == null && limitCount - reservedCount - consumedCount >= photos;
	}

	/**
	 * Whether the DeviceCheck bits must record the start before the device's first trial reservation.
	 */
	boolean needsDeviceCheck() {
		return platform == DevicePlatform.IOS && deviceCheckState != DeviceCheckState.TRIAL_STARTED;
	}

	boolean isStarting() {
		return deviceCheckState == DeviceCheckState.STARTING;
	}

	/**
	 * This install is about to write the start to Apple.
	 */
	void startDeviceCheck(Instant now) {
		if (deviceCheckState == null) {
			deviceCheckState = DeviceCheckState.STARTING;
			updatedAt = now;
		}
	}

	/**
	 * Apple refused the token, so nothing was written.
	 */
	void cancelDeviceCheck(Instant now) {
		if (deviceCheckState == DeviceCheckState.STARTING) {
			deviceCheckState = null;
			updatedAt = now;
		}
	}

	void recordDeviceCheck(Instant now) {
		if (deviceCheckState != DeviceCheckState.TRIAL_STARTED) {
			deviceCheckState = DeviceCheckState.TRIAL_STARTED;
			updatedAt = now;
		}
	}

	/**
	 * Another install of the device started the trial: this one gets none. A mark that is starting wrote the bits
	 * itself and keeps its trial.
	 */
	void startedElsewhere(Instant now) {
		if (deviceCheckState == null) {
			deviceCheckState = DeviceCheckState.TRIAL_STARTED;
			exhaustedAt = now;
			updatedAt = now;
		}
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
