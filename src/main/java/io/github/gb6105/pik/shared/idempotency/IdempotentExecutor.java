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
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
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

	private final TransactionTemplate readTransactions;

	private final IdempotencyProperties properties;

	private final JsonMapper jsonMapper;

	// Built on its own so that spring.jackson.* changes do not alter the hashes of stored records. Record components
	// are sorted too (Jackson 3 keeps creator properties first by default), and absent values are left out, so that
	// reordering or adding command fields does not reject retransmissions sent before the change.
	private final JsonMapper canonicalMapper = JsonMapper.builder()
		.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
		.disable(MapperFeature.SORT_CREATOR_PROPERTIES_FIRST)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
		.changeDefaultPropertyInclusion((inclusion) -> inclusion.withValueInclusion(JsonInclude.Include.NON_ABSENT))
		.build();

	IdempotentExecutor(IdempotencyRecords records, PlatformTransactionManager transactionManager,
			IdempotencyProperties properties, JsonMapper jsonMapper) {
		this.records = records;
		this.transactions = new TransactionTemplate(transactionManager);
		this.readTransactions = new TransactionTemplate(transactionManager);
		this.readTransactions.setReadOnly(true);
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
		return commit(request, () -> new Outcome(HttpStatus.OK, action.get()), ReplayProjection.IDENTITY);
	}

	/**
	 * Finds the stored response before the request does any work, so that a retransmission is answered without
	 * checks or external calls that depend on one-time evidence (ADR-0030 #4). Reads in a transaction of its own and
	 * must be called outside a transaction.
	 * @return the replayed response; empty when the key has no record yet
	 * @throws PikException {@code INVALID_INPUT} / {@code IDEMPOTENCY_KEY_REUSED} when the record is of another
	 * command
	 */
	public Optional<IdempotentResponse> lookup(IdempotentRequest request, ReplayProjection projection) {
		assertOutsideTransaction();
		String requestHash = hash(request);
		return Optional.ofNullable(readTransactions.execute((status) -> {
			StoredResponse stored = records.find(request.ownerId(), request.key().value());
			return (stored != null) ? replay(stored, requestHash, projection) : null;
		}));
	}

	/**
	 * Answers a failure found before {@link #commit}, such as a check outside the business transaction. Under the key
	 * lock a record stored since {@link #lookup}, for example by a retransmission, is replayed; otherwise a final 4xx
	 * is stored and returned, and any other failure is rethrown without a record (B6-2.E6). Must be called outside a
	 * transaction.
	 */
	public IdempotentResponse reject(IdempotentRequest request, PikException failure, ReplayProjection projection) {
		assertOutsideTransaction();
		return replayOrStore(request, hash(request), projection, () -> {
			if (!isStored(failure)) {
				throw failure;
			}
			return problem(request, failure);
		});
	}

	/**
	 * Runs {@code action} as {@link #execute} does, with the success status the action returns. Retransmissions of a
	 * stored success are answered through {@code projection}. Must be called outside a transaction.
	 * @return the response to send, for the first request and for every retransmission
	 */
	public IdempotentResponse commit(IdempotentRequest request, Supplier<Outcome> action,
			ReplayProjection projection) {
		assertOutsideTransaction();
		String requestHash = hash(request);
		try {
			return replayOrStore(request, requestHash, projection, () -> succeeded(action));
		}
		catch (RejectedAction rejected) {
			// A savepoint would keep rollback-only marks and the synchronizations (event listeners) the action
			// registered, so the action's transaction is rolled back as a whole instead.
			IdempotentResponse response = problem(request, rejected.exception);
			return replayOrStore(request, requestHash, projection, () -> response);
		}
	}

	private static void assertOutsideTransaction() {
		Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
				"IdempotentExecutor must start the transactions that read and store the record");
	}

	// Under the key lock: answers with the stored record, or stores the response from produce. The lookup sees
	// records committed while waiting for the lock because connections run READ COMMITTED (application.yml).
	// A retransmission that stores its record between a rejected action and its error response wins.
	private IdempotentResponse replayOrStore(IdempotentRequest request, String requestHash,
			ReplayProjection projection, Supplier<IdempotentResponse> produce) {
		return transactions.execute((status) -> {
			records.lock(request.ownerId(), request.key().value(), properties.lockTimeout());
			StoredResponse stored = records.find(request.ownerId(), request.key().value());
			if (stored != null) {
				return replay(stored, requestHash, projection);
			}
			IdempotentResponse response = produce.get();
			records.insert(request, requestHash, response, properties.retention());
			return response;
		});
	}

	private IdempotentResponse replay(StoredResponse stored, String requestHash, ReplayProjection projection) {
		if (!stored.requestHash().equals(requestHash)) {
			throw new PikException(ErrorCode.INVALID_INPUT, "IDEMPOTENCY_KEY_REUSED", false);
		}
		int status = stored.responseCode();
		JsonNode body = jsonMapper.readTree(stored.responseBody());
		return new IdempotentResponse(status,
				HttpStatusCode.valueOf(status).is2xxSuccessful() ? projection.project(status, body) : body);
	}

	private IdempotentResponse succeeded(Supplier<Outcome> action) {
		Outcome outcome;
		try {
			outcome = action.get();
		}
		catch (PikException ex) {
			if (isStored(ex)) {
				throw new RejectedAction(ex);
			}
			throw ex;
		}
		return new IdempotentResponse(outcome.status().value(), jsonMapper.valueToTree(outcome.body()));
	}

	private IdempotentResponse problem(IdempotentRequest request, PikException ex) {
		ApiError error = new ApiError(ex.getCode(), ex.getReason(), ex.isRetryable());
		ProblemDetail problem = error.toCommandProblemDetail(request.longRunning() ? null : request.key().value());
		ex.getProperties().forEach(problem::setProperty);
		problem.setInstance(currentRequestPath());
		return new IdempotentResponse(error.code().status().value(), jsonMapper.valueToTree(problem));
	}

	// 401, 429 and errors carrying Retry-After depend on state outside the request (token, rate window) and are not
	// stored (ADR-0023, ADR-0024, ADR-0026), nor are failures marked unstorable (ADR-0032 #6-2).
	private static boolean isStored(PikException ex) {
		ErrorCode code = ex.getCode();
		return ex.isStorable() && code.status().is4xxClientError() && code != ErrorCode.UNAUTHENTICATED
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
			super(null, exception, false, false);
			this.exception = exception;
		}

	}

}
