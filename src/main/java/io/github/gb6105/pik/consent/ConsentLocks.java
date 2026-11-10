package io.github.gb6105.pik.consent;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Orders consent decisions and the checks that rely on the consent, per principal, until the transaction ends
 * (ADR-0030 #1). A decision holds the exclusive lock and a check the shared one, so a check sees a decision either
 * before or after it, and checks do not wait for each other. The key is prefixed to keep it apart from other advisory
 * locks.
 */
@Repository
@RequiredArgsConstructor
class ConsentLocks {

	private final JdbcClient jdbc;

	void lockShared(UUID principalId) {
		jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock_shared(hashtextextended('consent:' || CAST(:principal AS text), 0))")
			.param("principal", principalId)
			.query(Integer.class)
			.single();
	}

	void lockExclusive(UUID principalId) {
		jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('consent:' || CAST(:principal AS text), 0))")
			.param("principal", principalId)
			.query(Integer.class)
			.single();
	}

}
