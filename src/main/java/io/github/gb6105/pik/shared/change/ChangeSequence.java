package io.github.gb6105.pik.shared.change;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * Issues per-owner change numbers for sync pulls (FR-A-02). The counter row stays locked until the caller's
 * transaction commits, so numbers become visible in commit order and a pull never skips a pending one, which a
 * database sequence cannot guarantee (ERD {@code owner_change_counter}). The counter is the last lock of a
 * transaction (stage5-analysis-pipeline.md 0.3), so pending JPA changes are flushed before it is taken: their row
 * locks and constraint checks then come before the counter instead of at commit.
 */
@Component
@RequiredArgsConstructor
public class ChangeSequence {

	private final JdbcClient jdbc;

	private final EntityManager entityManager;

	@Transactional(propagation = Propagation.MANDATORY)
	public long next(UUID ownerId) {
		return nextBlock(ownerId, 1);
	}

	/**
	 * Issues {@code count} consecutive numbers with one counter update, for changes written together. Flushes the
	 * persistence context first, so call it after the transaction's other domain writes.
	 * @return the first number; the others follow it without gaps
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public long nextBlock(UUID ownerId, int count) {
		Assert.isTrue(count >= 1, "count must be at least 1");
		entityManager.flush();
		long last = jdbc.sql("""
				INSERT INTO owner_change_counter (owner_id, last_seq) VALUES (:owner, :count)
				ON CONFLICT (owner_id) DO UPDATE SET last_seq = owner_change_counter.last_seq + :count
				RETURNING last_seq
				""").param("owner", ownerId).param("count", count).query(Long.class).single();
		return last - count + 1;
	}

}
