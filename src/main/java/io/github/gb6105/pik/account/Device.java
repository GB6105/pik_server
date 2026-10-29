package io.github.gb6105.pik.account;

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
 * A device of a principal. Logging in on an install adds a row under the account principal with the same
 * {@code installId} (FR-A-04).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Device {

	@Id
	private UUID id;

	private UUID principalId;

	private UUID installId;

	@Enumerated(EnumType.STRING)
	private Platform platform;

	/** Trial mark of the physical device, in the usage module (ADR-0029). */
	private @Nullable UUID trialMarkId;

	private @Nullable String appVersion;

	private @Nullable Instant lastSeenAt;

	private Instant createdAt;

	private Instant updatedAt;

	@Version
	private long version;

	private Device(UUID principalId, UUID installId, Platform platform, @Nullable String appVersion, UUID trialMarkId,
			Instant now) {
		this.id = UuidV7.generate();
		this.principalId = principalId;
		this.installId = installId;
		this.platform = platform;
		this.trialMarkId = trialMarkId;
		this.appVersion = appVersion;
		this.lastSeenAt = now;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/**
	 * A device of a new install, with a server-issued install ID (ADR-0027) and the trial mark of the physical device
	 * (ADR-0029).
	 */
	static Device ofNewInstall(UUID principalId, Platform platform, @Nullable String appVersion, UUID trialMarkId,
			Instant now) {
		return new Device(principalId, UuidV7.generate(), platform, appVersion, trialMarkId, now);
	}

}
