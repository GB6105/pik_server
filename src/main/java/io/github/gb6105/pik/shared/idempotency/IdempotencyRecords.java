package io.github.gb6105.pik.shared.idempotency;

import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class IdempotencyRecords {

	private final JdbcClient jdbc;

	/**
	 * Serializes requests with the same key until the current transaction ends. The record row does not exist
	 * before the response is final, so a transaction-scoped advisory lock on the key stands in for a row lock.
	 */
	void lock(UUID ownerId, UUID key, Duration timeout) {
		String previous = jdbc.sql("SELECT current_setting('lock_timeout')").query(String.class).single();
		setLockTimeout(timeout.toMillis() + "ms");
		try {
			jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CAST(:owner AS text) || '/' || CAST(:key AS text), 0))")
				.param("owner", ownerId)
				.param("key", key)
				.query(Integer.class)
				.single();
		}
		catch (DataAccessException ex) {
			if (isLockTimeout(ex)) {
				throw new PikException(ErrorCode.CONFLICT, "IDEMPOTENCY_IN_PROGRESS", true);
			}
			throw ex;
		}
		setLockTimeout(previous);
	}

	@Nullable StoredResponse find(UUID ownerId, UUID key) {
		return jdbc.sql("""
				SELECT request_hash, response_code, response_body::text AS response_body
				FROM idempotency_record
				WHERE owner_id = :owner AND idempotency_key = :key
				""")
			.param("owner", ownerId)
			.param("key", key)
			.query(StoredResponse.class)
			.optional()
			.orElse(null);
	}

	void insert(IdempotentRequest request, String requestHash, IdempotentResponse response, Duration retention) {
		jdbc.sql("""
				INSERT INTO idempotency_record (owner_id, idempotency_key, operation, request_hash, response_code,
				                                response_body, created_at, expires_at)
				VALUES (:owner, :key, :operation, :hash, :code, CAST(:body AS jsonb), now(),
				        now() + :retentionSeconds * INTERVAL '1 second')
				""")
			.param("owner", request.ownerId())
			.param("key", request.key().value())
			.param("operation", request.operation())
			.param("hash", requestHash)
			.param("code", response.status())
			.param("body", response.body().toString())
			.param("retentionSeconds", retention.toSeconds())
			.update();
	}

	int deleteExpired(int limit) {
		return jdbc.sql("""
				DELETE FROM idempotency_record
				WHERE (owner_id, idempotency_key) IN (
				    SELECT owner_id, idempotency_key FROM idempotency_record WHERE expires_at < now() LIMIT :limit)
				""")
			.param("limit", limit)
			.update();
	}

	// lock_not_available; Spring leaves this SQLSTATE uncategorized for PostgreSQL.
	private static boolean isLockTimeout(DataAccessException ex) {
		return ex.getMostSpecificCause() instanceof SQLException sql && "55P03".equals(sql.getSQLState());
	}

	private void setLockTimeout(String value) {
		jdbc.sql("SELECT set_config('lock_timeout', :value, true)").param("value", value).query(String.class).single();
	}

	record StoredResponse(String requestHash, int responseCode, String responseBody) {

	}

}
