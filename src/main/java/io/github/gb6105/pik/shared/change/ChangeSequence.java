package io.github.gb6105.pik.shared.change;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues per-owner change numbers for sync pulls (FR-A-02). The counter row stays locked until the caller's
 * transaction commits, so numbers become visible in commit order and a pull never skips a pending one, which a
 * database sequence cannot guarantee (ERD {@code owner_change_counter}).
 */
@Component
@RequiredArgsConstructor
public class ChangeSequence {

	private final JdbcClient jdbc;

	@Transactional(propagation = Propagation.MANDATORY)
	public long next(UUID ownerId) {
		return jdbc.sql("""
				INSERT INTO owner_change_counter (owner_id, last_seq) VALUES (:owner, 1)
				ON CONFLICT (owner_id) DO UPDATE SET last_seq = owner_change_counter.last_seq + 1
				RETURNING last_seq
				""").param("owner", ownerId).query(Long.class).single();
	}

}
