package io.github.gb6105.pik.account;

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
 * Install token of an anonymous principal, stored as a hash. It never expires and is exchanged for access tokens
 * until revoked (ADR-0023).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AnonymousInstall {

	@Id
	private UUID principalId;

	private String tokenHash;

	@Enumerated(EnumType.STRING)
	private Platform platform;

	@Enumerated(EnumType.STRING)
	private @Nullable AttestationKind attestationKind;

	private @Nullable Instant attestedAt;

	private @Nullable Instant lastSeenAt;

	private @Nullable Instant revokedAt;

	private Instant createdAt;

	AnonymousInstall(UUID principalId, String tokenHash, Platform platform, AttestationKind attestationKind,
			Instant now) {
		this.principalId = principalId;
		this.tokenHash = tokenHash;
		this.platform = platform;
		this.attestationKind = attestationKind;
		this.attestedAt = now;
		this.lastSeenAt = now;
		this.createdAt = now;
	}

	boolean isRevoked() {
		return revokedAt != null;
	}

}
