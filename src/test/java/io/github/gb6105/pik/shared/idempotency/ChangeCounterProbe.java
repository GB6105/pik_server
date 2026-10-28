package io.github.gb6105.pik.shared.idempotency;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * JPA view of {@code owner_change_counter}, used to check that flushed entity changes roll back with a stored 4xx.
 * Every test context validates it against the schema, so a migration that changes the table must change it too.
 */
@Entity
@Table(name = "owner_change_counter")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ChangeCounterProbe {

	@Id
	private UUID ownerId;

	private long lastSeq;

	ChangeCounterProbe(UUID ownerId, long lastSeq) {
		this.ownerId = ownerId;
		this.lastSeq = lastSeq;
	}

}
