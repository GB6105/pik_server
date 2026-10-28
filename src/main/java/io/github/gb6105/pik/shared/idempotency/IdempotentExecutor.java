package io.github.gb6105.pik.shared.idempotency;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gb6105.pik.shared.error.ApiError;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.idempotency.IdempotencyRecords.StoredResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a change request at most once per (owner, {@code Idempotency-Key}) and answers retransmissions with the
 * stored response (ADR-0023 #7, ADR-0026). Command services call it explicitly (ADR-0025 #5).
 */
@Service
public class IdempotentExecutor {

	private final IdempotencyRecords records;

	private final TransactionTemplate transactions;

	private final IdempotencyProperties properties;

	private final JsonMapper jsonMapper;

	// Built on its own so that spring.jackson.* changes do not alter the hashes of stored records. Absent values are
	// left out so that a field added to a command does not reject retransmissions sent before the change.
	private final JsonMapper canonicalMapper = JsonMapper.builder()
		.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
		.changeDefaultPropertyInclusion((inclusion) -> inclusion.withValueInclusion(JsonInclude.Include.NON_ABSENT))
		.build();

	IdempotentExecutor(IdempotencyRecords records, PlatformTransactionManager transactionManager,
			IdempotencyProperties properties, JsonMapper jsonMapper) {
		this.records = records;
		this.transactions = new TransactionTemplate(transactionManager);
		this.properties = properties;
		this.jsonMapper = jsonMapper.rebuild().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build();
	}

	/**
	 * Runs {@code action} in the transaction that also stores the record. A final 4xx {@link PikException} rolls
	 * back that transaction as a whole and stores the error response in a new one; any other exception rolls back
	 * everything and is rethrown without a record. Must be called outside a transaction.
	 * @return the response to send, for the first request and for every retransmission
	 */
	public IdempotentResponse execute(IdempotentRequest request, Supplier<?> action) {
		Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
				"IdempotentExecutor must start the transaction that stores the record");
		String requestHash = hash(request);
		try {
			return replayOrStore(request, requestHash, () -> succeeded(action));
		}
		catch (RejectedAction rejected) {
			// A savepoint would keep rollback-only marks and the synchronizations (event listeners) the action
			// registered, so the action's transaction is rolled back as a whole instead.
			IdempotentResponse response = problem(request, rejected.exception);
			return replayOrStore(request, requestHash, () -> response);
		}
	}

	// Under the key lock: answers with the stored record, or stores the response from produce. The lookup sees
	// records committed while waiting for the lock because connections run READ COMMITTED (application.yml).
	// A retransmission that stores its record between a rejected action and its error response wins.
	private IdempotentResponse replayOrStore(IdempotentRequest request, String requestHash,
			Supplier<IdempotentResponse> produce) {
		return transactions.execute((status) -> {
			records.lock(request.ownerId(), request.key().value(), properties.lockTimeout());
			StoredResponse stored = records.find(request.ownerId(), request.key().value());
			if (stored != null) {
				return replay(stored, requestHash);
			}
			IdempotentResponse response = produce.get();
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

	private IdempotentResponse succeeded(Supplier<?> action) {
		Object body;
		try {
			body = action.get();
		}
		catch (PikException ex) {
			if (isStored(ex)) {
				throw new RejectedAction(ex);
			}
			throw ex;
		}
		return new IdempotentResponse(HttpStatus.OK.value(), jsonMapper.valueToTree(body));
	}

	private IdempotentResponse problem(IdempotentRequest request, PikException ex) {
		ApiError error = new ApiError(ex.getCode(), ex.getReason(), ex.isRetryable());
		ProblemDetail problem = error.toCommandProblemDetail(request.key().value());
		problem.setInstance(currentRequestPath());
		return new IdempotentResponse(error.code().status().value(), jsonMapper.valueToTree(problem));
	}

	// 401, 429 and errors carrying Retry-After depend on state outside the request (token, rate window) and are not
	// stored (ADR-0023, ADR-0024, ADR-0026).
	private static boolean isStored(PikException ex) {
		ErrorCode code = ex.getCode();
		return code.status().is4xxClientError() && code != ErrorCode.UNAUTHENTICATED
				&& code != ErrorCode.RATE_LIMITED && ex.getRetryAfter() == null;
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

	/**
	 * Carries a stored 4xx out of the action's transaction so that the transaction rolls back.
	 */
	private static final class RejectedAction extends RuntimeException {

		private final PikException exception;

		RejectedAction(PikException exception) {
			super(null, null, false, false);
			this.exception = exception;
		}

	}

}
