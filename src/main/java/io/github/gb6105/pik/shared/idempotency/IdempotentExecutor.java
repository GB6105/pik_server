package io.github.gb6105.pik.shared.idempotency;

import io.github.gb6105.pik.shared.error.ApiError;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.idempotency.IdempotencyRecords.StoredResponse;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.HexFormat;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a change request at most once per (owner, {@code Idempotency-Key}) and answers retransmissions with the
 * stored response (ADR-0023 #7, ADR-0026). Command services call it explicitly (ADR-0025 #5).
 */
@Service
public class IdempotentExecutor {

	private final IdempotencyRecords records;

	private final TransactionTemplate transactions;

	private final DataSource dataSource;

	private final EntityManager entityManager;

	private final IdempotencyProperties properties;

	private final JsonMapper jsonMapper;

	private final JsonMapper canonicalMapper;

	IdempotentExecutor(IdempotencyRecords records, PlatformTransactionManager transactionManager,
			DataSource dataSource, EntityManager entityManager, IdempotencyProperties properties,
			JsonMapper jsonMapper) {
		this.records = records;
		this.transactions = new TransactionTemplate(transactionManager);
		this.dataSource = dataSource;
		this.entityManager = entityManager;
		this.properties = properties;
		this.jsonMapper = jsonMapper.rebuild().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build();
		this.canonicalMapper = jsonMapper.rebuild()
			.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
			.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
			.build();
	}

	/**
	 * Runs {@code action} in the transaction that also stores the record. A final 4xx {@link PikException} rolls
	 * back the action's changes and stores the error response; any other exception rolls back everything and is
	 * rethrown without a record. Must be called outside a transaction.
	 * @return the response to send, for the first request and for every retransmission
	 */
	public IdempotentResponse execute(IdempotentRequest request, Supplier<?> action) {
		Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
				"IdempotentExecutor must start the transaction that stores the record");
		String requestHash = hash(request);
		return transactions.execute((status) -> {
			records.lock(request.ownerId(), request.key().value(), properties.lockTimeout());
			StoredResponse stored = records.find(request.ownerId(), request.key().value());
			if (stored != null) {
				return replay(stored, requestHash);
			}
			IdempotentResponse response = run(request, action);
			records.insert(request, requestHash, response, properties.retention());
			return response;
		});
	}

	private IdempotentResponse replay(StoredResponse stored, String requestHash) {
		if (!stored.requestHash().equals(requestHash)) {
			throw new PikException(ErrorCode.INVALID_INPUT, "IDEMPOTENCY_KEY_REUSED", false);
		}
		return new IdempotentResponse(stored.responseCode(), jsonMapper.readTree(stored.responseBody()));
	}

	// HibernateJpaDialect exposes no savepoints to JpaTransactionManager, so the savepoint is taken on the JDBC
	// connection the transaction is bound to (the same one the action's JPA and JDBC work uses).
	private IdempotentResponse run(IdempotentRequest request, Supplier<?> action) {
		ConnectionHolder connection = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
		Assert.state(connection != null, "No JDBC connection bound to the idempotency transaction");
		Savepoint savepoint = createSavepoint(connection);
		try {
			Object body = action.get();
			entityManager.flush();
			return new IdempotentResponse(HttpStatus.OK.value(), jsonMapper.valueToTree(body));
		}
		catch (PikException ex) {
			if (!isFinal(ex.getCode())) {
				throw ex;
			}
			rollbackTo(connection, savepoint);
			// Entities changed before the savepoint rollback would otherwise be flushed again at commit.
			entityManager.clear();
			ApiError error = new ApiError(ex.getCode(), ex.getReason(), ex.isRetryable());
			ProblemDetail problem = error.toCommandProblemDetail(request.key().value());
			problem.setInstance(currentRequestPath());
			return new IdempotentResponse(error.code().status().value(), jsonMapper.valueToTree(problem));
		}
	}

	private static Savepoint createSavepoint(ConnectionHolder connection) {
		try {
			return connection.createSavepoint();
		}
		catch (SQLException ex) {
			throw new CannotCreateTransactionException("Could not create savepoint", ex);
		}
	}

	private static void rollbackTo(ConnectionHolder connection, Savepoint savepoint) {
		try {
			connection.getConnection().rollback(savepoint);
		}
		catch (SQLException ex) {
			throw new TransactionSystemException("Could not roll back to savepoint", ex);
		}
	}

	// 401 and 429 depend on state outside the request (token, rate window) and are not stored (ADR-0023, ADR-0024).
	private static boolean isFinal(ErrorCode code) {
		return code.status().is4xxClientError() && code != ErrorCode.UNAUTHENTICATED
				&& code != ErrorCode.RATE_LIMITED;
	}

	private String hash(IdempotentRequest request) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
		digest.update(request.operation().getBytes(StandardCharsets.UTF_8));
		digest.update((byte) 0);
		digest.update(canonicalMapper.writeValueAsBytes(request.command()));
		return HexFormat.of().formatHex(digest.digest());
	}

	// Spring sets the same value on problem responses rendered by exception handlers.
	private static @Nullable URI currentRequestPath() {
		return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
				? URI.create(attributes.getRequest().getRequestURI()) : null;
	}

}
