package io.github.gb6105.pik.analysis;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The principal's intake lock, held until the transaction ends (design 0.3 "주체 접수"). Every path that inserts
 * {@code analysis_job} takes it first, so {@code uk_analysis_job_photo_active} only conflicts under it (V5 lock order
 * note). The key is prefixed to keep it apart from other advisory locks.
 */
@Repository
@RequiredArgsConstructor
class IntakeLocks {

	private final JdbcClient jdbc;

	@Transactional(propagation = Propagation.MANDATORY)
	void lock(UUID ownerId) {
		jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('analysis.intake:' || CAST(:owner AS text), 0))")
			.param("owner", ownerId)
			.query(Integer.class)
			.single();
	}

}
