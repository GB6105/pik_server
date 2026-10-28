package io.github.gb6105.pik.account;

import io.github.gb6105.pik.shared.id.UuidV7;
import io.github.gb6105.pik.shared.security.PrincipalKind;
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

/**
 * Owner of Pik data: an account or an anonymous install. Every {@code owner_id} refers to its ID.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Principal {

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	private PrincipalKind kind;

	@Enumerated(EnumType.STRING)
	private PrincipalStatus status;

	private Instant createdAt;

	private Instant updatedAt;

	@Version
	private long version;

	private Principal(PrincipalKind kind, Instant now) {
		this.id = UuidV7.generate();
		this.kind = kind;
		this.status = PrincipalStatus.ACTIVE;
		this.createdAt = now;
		this.updatedAt = now;
	}

	static Principal anonymous(Instant now) {
		return new Principal(PrincipalKind.ANONYMOUS, now);
	}

	boolean isActive() {
		return status == PrincipalStatus.ACTIVE;
	}

}
