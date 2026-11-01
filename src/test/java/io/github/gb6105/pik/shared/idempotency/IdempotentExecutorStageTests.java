package io.github.gb6105.pik.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gb6105.pik.AuthenticatedMockMvc;
import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.shared.change.ChangeSequence;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.id.UuidV7;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The executor's stages for requests that check before the business transaction ({@code lookup}, {@code reject},
 * {@code commit}), failures that are never stored, problem members carried by {@link PikException} and the
 * {@code jobId} of long-running requests.
 */
@Import({ TestcontainersConfiguration.class, AuthenticatedMockMvc.class,
		IdempotentExecutorStageTests.StageProbeController.class })
@SpringBootTest
@AutoConfigureMockMvc
class IdempotentExecutorStageTests {

	static final Map<String, Object> CHALLENGE = Map.of("challenge", Map.of("challengeId", "probe"));

	@Autowired
	MockMvcTester mvc;

	@Autowired
	IdempotentExecutor executor;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper jsonMapper;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	ChangeSequence changes;

	final UUID owner = UUID.randomUUID();

	@Test
	void unstoredClientErrorRollsBackAndIsRethrown() {
		UUID key = UuidV7.generate();
		PikException failure = PikException.unstored(ErrorCode.FORBIDDEN, "PROBE_FAILED", true);

		assertThatThrownBy(() -> executor.execute(request(key, "x"), () -> {
			changes.next(owner);
			throw failure;
		})).isSameAs(failure);
		assertThat(failure.isStorable()).isFalse();
		assertThat(storedStatus(key)).isEmpty();
		assertThat(lastSeq()).isEmpty();
	}

	@Test
	void storedErrorKeepsItsPropertiesForReplays() {
		UUID key = UuidV7.generate();
		String expected = """
				{"status": 403, "code": "FORBIDDEN", "reason": "PROBE_FAILED", "jobId": "%s", "result": "FAILED",
				 "challenge": {"challengeId": "probe"}}
				""".formatted(key);

		assertThat(post(key, "{\"name\": \"a\", \"fail\": \"stored\"}")).hasStatus(403)
			.bodyJson()
			.isLenientlyEqualTo(expected);
		assertThat(storedStatus(key)).hasValue(403);
		assertThat(post(key, "{\"name\": \"a\", \"fail\": \"stored\"}")).hasStatus(403)
			.bodyJson()
			.isLenientlyEqualTo(expected);
	}

	@Test
	void unstoredErrorIsRenderedWithItsPropertiesAndRetryAfter() {
		UUID key = UuidV7.generate();

		MvcTestResult result = post(key, "{\"name\": \"a\", \"fail\": \"unstored\"}");

		assertThat(result).hasStatus(409)
			.hasHeader(HttpHeaders.RETRY_AFTER, "2")
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "CONFLICT", "reason": "PROBE_FAILED", "retryable": true, "jobId": "%s", "result": "FAILED",
					 "challenge": {"challengeId": "probe"}}
					""".formatted(key));
		assertThat(storedStatus(key)).isEmpty();
	}

	@Test
	void lookupReplaysTheRecordAndRejectsAnotherCommand() {
		UUID key = UuidV7.generate();
		IdempotentRequest request = request(key, "a");
		assertThat(executor.lookup(request, ReplayProjection.IDENTITY)).isEmpty();
		IdempotentResponse accepted = executor.commit(request, accepted("a"), ReplayProjection.IDENTITY);

		assertThat(executor.lookup(request, ReplayProjection.IDENTITY)).hasValueSatisfying((replay) -> {
			assertThat(replay.status()).isEqualTo(202);
			assertThat(normalized(replay)).isEqualTo(normalized(accepted));
		});
		assertKeyReused(() -> executor.lookup(request(key, "b"), ReplayProjection.IDENTITY));
		assertThat(storedStatus(key)).hasValue(202);
	}

	@Test
	void stagesRefuseToJoinExistingTransaction() {
		IdempotentRequest request = request(UuidV7.generate(), "x");
		PikException failure = new PikException(ErrorCode.FORBIDDEN, "PROBE_FAILED", false);

		assertThatIllegalStateException().isThrownBy(() -> transactions
			.executeWithoutResult((status) -> executor.lookup(request, ReplayProjection.IDENTITY)));
		assertThatIllegalStateException().isThrownBy(() -> transactions
			.executeWithoutResult((status) -> executor.reject(request, failure, ReplayProjection.IDENTITY)));
		assertThatIllegalStateException().isThrownBy(() -> transactions
			.executeWithoutResult((status) -> executor.commit(request, accepted("x"), ReplayProjection.IDENTITY)));
	}

	@Test
	void rejectStoresFinalErrorThatLaterStagesReplay() {
		UUID key = UuidV7.generate();
		IdempotentRequest request = request(key, "a");
		AtomicInteger runs = new AtomicInteger();

		IdempotentResponse rejected = executor.reject(request,
				new PikException(ErrorCode.FORBIDDEN, "PROBE_FAILED", false), ReplayProjection.IDENTITY);
		IdempotentResponse replay = executor.commit(request, () -> {
			runs.incrementAndGet();
			return new Outcome(HttpStatus.ACCEPTED, "x");
		}, ReplayProjection.IDENTITY);

		assertThat(rejected.status()).isEqualTo(403);
		assertThat(rejected.body().get("reason").asString()).isEqualTo("PROBE_FAILED");
		assertThat(rejected.body().get("jobId").asString()).isEqualTo(key.toString());
		assertThat(rejected.body().get("result").asString()).isEqualTo("FAILED");
		assertThat(storedBody(key)).isEqualTo(normalized(rejected));
		assertThat(replay.status()).isEqualTo(403);
		assertThat(runs).hasValue(0);
		assertThat(executor.lookup(request, ReplayProjection.IDENTITY)).map(IdempotentResponse::status).hasValue(403);
	}

	@ParameterizedTest
	@MethodSource("unstoredFailures")
	void rejectRethrowsUnstoredFailureWithoutRecord(PikException failure) {
		UUID key = UuidV7.generate();

		assertThatThrownBy(() -> executor.reject(request(key, "a"), failure, ReplayProjection.IDENTITY))
			.isSameAs(failure);
		assertThat(storedStatus(key)).isEmpty();
	}

	static Stream<PikException> unstoredFailures() {
		return Stream.of(PikException.unstored(ErrorCode.FORBIDDEN, "ATTESTATION_REQUIRED", true),
				PikException.unstored(ErrorCode.CONFLICT, "ATTESTATION_CONFLICT", true, Duration.ofSeconds(1),
						Map.of()),
				new PikException(ErrorCode.UNAUTHENTICATED, "PROBE_FAILED", false),
				new PikException(ErrorCode.TEMPORARY_FAILURE, "PROBE_FAILED", true));
	}

	@Test
	void commitStoresTheSuccessStatusAndReplaysItWithoutRunningAgain() {
		UUID key = UuidV7.generate();
		IdempotentRequest request = request(key, "a");
		AtomicInteger runs = new AtomicInteger();
		Supplier<Outcome> action = () -> new Outcome(HttpStatus.ACCEPTED, Map.of("run", runs.incrementAndGet()));

		IdempotentResponse first = executor.commit(request, action, ReplayProjection.IDENTITY);
		IdempotentResponse again = executor.commit(request, action, ReplayProjection.IDENTITY);

		assertThat(first.status()).isEqualTo(202);
		assertThat(first.toResponseEntity().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(again.status()).isEqualTo(202);
		assertThat(normalized(again)).isEqualTo(normalized(first));
		assertThat(runs).hasValue(1);
		assertThat(storedStatus(key)).hasValue(202);
	}

	@Test
	void outcomeIsASuccess() {
		assertThatIllegalArgumentException().isThrownBy(() -> new Outcome(HttpStatus.BAD_REQUEST, "x"));
		assertThatIllegalArgumentException().isThrownBy(() -> new Outcome(HttpStatus.SEE_OTHER, "x"));
	}

	@Test
	void projectionRebuildsSuccessReplaysOnly() {
		AtomicInteger projected = new AtomicInteger();
		ReplayProjection projection = (status, storedBody) -> {
			projected.incrementAndGet();
			ObjectNode body = jsonMapper.createObjectNode();
			body.put("projectedStatus", status);
			body.set("stored", storedBody);
			return body;
		};
		IdempotentRequest success = request(UuidV7.generate(), "a");
		IdempotentRequest failure = request(UuidV7.generate(), "a");

		IdempotentResponse first = executor.commit(success, accepted("a"), projection);
		IdempotentResponse looked = executor.lookup(success, projection).orElseThrow();
		IdempotentResponse committed = executor.commit(success, accepted("other"), projection);
		IdempotentResponse rejected = executor.reject(success,
				PikException.unstored(ErrorCode.FORBIDDEN, "PROBE_FAILED", true), projection);
		executor.reject(failure, new PikException(ErrorCode.CONFLICT, "PROBE_FAILED", false), projection);
		IdempotentResponse storedError = executor.lookup(failure, projection).orElseThrow();

		assertThat(first.body().has("projectedStatus")).isFalse();
		assertThat(Stream.of(looked, committed, rejected)).allSatisfy((replay) -> {
			assertThat(replay.status()).isEqualTo(202);
			assertThat(replay.body().get("projectedStatus").asInt()).isEqualTo(202);
			assertThat(replay.body().get("stored")).isEqualTo(normalized(first));
		});
		assertThat(storedError.status()).isEqualTo(409);
		assertThat(storedError.body().has("projectedStatus")).isFalse();
		assertThat(storedError.body().get("reason").asString()).isEqualTo("PROBE_FAILED");
		assertThat(projected).hasValue(3);
	}

	// A retransmission B commits between request A's lookup and its rejection (R19-04).
	@ParameterizedTest
	@MethodSource("evidenceFailures")
	void rejectAfterLookupReplaysTheCommitOfTheSameKey(PikException failure) {
		UUID key = UuidV7.generate();
		IdempotentRequest requestA = request(key, "same");
		IdempotentRequest requestB = request(key, "same");

		assertThat(executor.lookup(requestA, ReplayProjection.IDENTITY)).isEmpty();
		IdempotentResponse committed = executor.commit(requestB, accepted("b"), ReplayProjection.IDENTITY);
		IdempotentResponse answered = executor.reject(requestA, failure, ReplayProjection.IDENTITY);

		assertThat(committed.status()).isEqualTo(202);
		assertThat(answered.status()).isEqualTo(202);
		assertThat(normalized(answered)).isEqualTo(normalized(committed));
		assertThat(storedStatus(key)).hasValue(202);
	}

	static Stream<PikException> evidenceFailures() {
		return Stream.of(PikException.unstored(ErrorCode.FORBIDDEN, "ATTESTATION_REQUIRED", true),
				PikException.unstored(ErrorCode.CONFLICT, "ATTESTATION_CONFLICT", true, Duration.ofSeconds(1),
						Map.of()));
	}

	@Test
	void rejectAfterLookupWithAnotherBodyIsKeyReusedAndKeepsTheCommit() {
		UUID key = UuidV7.generate();
		IdempotentRequest requestA = request(key, "a");
		IdempotentRequest requestB = request(key, "b");

		assertThat(executor.lookup(requestA, ReplayProjection.IDENTITY)).isEmpty();
		IdempotentResponse committed = executor.commit(requestB, accepted("b"), ReplayProjection.IDENTITY);

		assertKeyReused(() -> executor.reject(requestA,
				PikException.unstored(ErrorCode.FORBIDDEN, "ATTESTATION_REQUIRED", true), ReplayProjection.IDENTITY));
		assertThat(storedStatus(key)).hasValue(202);
		assertThat(storedBody(key)).isEqualTo(normalized(committed));
		assertThat(executor.lookup(requestB, ReplayProjection.IDENTITY)).map(IdempotentResponse::status).hasValue(202);
	}

	// The key is the job ID of an immediate change only; a long-running intake has none on failure (api-contract.md).
	@ParameterizedTest
	@CsvSource({ "immediate, true", "long-running, false" })
	void securityFailureCarriesTheKeyForImmediateChangesOnly(String path, boolean carriesKey) {
		UUID key = UuidV7.generate();

		assertThat(post(path, key, "{\"name\": \"a\"}", "Bearer x.y.z")).hasStatus(401)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "UNAUTHENTICATED", "reason": "ACCESS_TOKEN_INVALID", "jobId": %s, "result": "FAILED"}
					""".formatted(jobId(key, carriesKey)));
	}

	@ParameterizedTest
	@CsvSource({ "immediate, true", "long-running, false" })
	void failureAfterKeyCheckCarriesTheKeyForImmediateChangesOnly(String path, boolean carriesKey) {
		UUID key = UuidV7.generate();

		assertThat(post(path, key, "{\"name\": \"\"}", null)).hasStatus(400).bodyJson().isLenientlyEqualTo("""
				{"code": "INVALID_INPUT", "reason": "VALIDATION_FAILED", "jobId": %s, "result": "FAILED"}
				""".formatted(jobId(key, carriesKey)));
		assertThat(post(path, key, "{\"name\": \"a\", \"fail\": \"unstored\"}", null)).hasStatus(409)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"reason": "PROBE_FAILED", "jobId": %s, "result": "FAILED"}
					""".formatted(jobId(key, carriesKey)));
	}

	@ParameterizedTest
	@CsvSource({ "immediate, true", "long-running, false" })
	void storedFailureCarriesTheKeyForImmediateChangesOnly(String path, boolean carriesKey) {
		UUID key = UuidV7.generate();
		String expected = """
				{"code": "FORBIDDEN", "reason": "PROBE_FAILED", "jobId": %s, "result": "FAILED"}
				""".formatted(jobId(key, carriesKey));

		assertThat(post(path, key, "{\"name\": \"a\", \"fail\": \"stored\"}", null)).hasStatus(403)
			.bodyJson()
			.isLenientlyEqualTo(expected);
		assertThat(storedBody(key).get("jobId").isNull()).isEqualTo(!carriesKey);
		assertThat(post(path, key, "{\"name\": \"a\", \"fail\": \"stored\"}", null)).hasStatus(403)
			.bodyJson()
			.isLenientlyEqualTo(expected);
	}

	@Test
	void longRunningRejectionIsStoredWithoutJobId() {
		UUID key = UuidV7.generate();
		IdempotentRequest request = new IdempotentRequest(owner, new IdempotencyKey(key), "probe.stage", "x", true);

		IdempotentResponse rejected = executor.reject(request,
				new PikException(ErrorCode.FORBIDDEN, "PROBE_FAILED", false), ReplayProjection.IDENTITY);

		assertThat(rejected.body().get("jobId").isNull()).isTrue();
		assertThat(storedBody(key).get("jobId").isNull()).isTrue();
		assertThat(new IdempotentRequest(owner, new IdempotencyKey(key), "probe.stage", "x").longRunning()).isFalse();
	}

	private IdempotentRequest request(UUID key, Object command) {
		return new IdempotentRequest(owner, new IdempotencyKey(key), "probe.stage", command);
	}

	private static Supplier<Outcome> accepted(String by) {
		return () -> new Outcome(HttpStatus.ACCEPTED, Map.of("by", by));
	}

	private static void assertKeyReused(ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(PikException.class, (ex) -> {
			assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_INPUT);
			assertThat(ex.getReason()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
		});
	}

	private MvcTestResult post(UUID key, String body) {
		return post("immediate", key, body, null);
	}

	private MvcTestResult post(String path, UUID key, String body, @Nullable String authorization) {
		var request = mvc.post()
			.uri("/test-stage-commands/{owner}/{path}", owner, path)
			.header("API-Version", "1")
			.header(IdempotencyKey.HEADER, key.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		if (authorization != null) {
			request.header(HttpHeaders.AUTHORIZATION, authorization);
		}
		return request.exchange();
	}

	private static String jobId(UUID key, boolean carriesKey) {
		return carriesKey ? "\"" + key + "\"" : "null";
	}

	// Stored bodies come back from jsonb with reordered keys and narrower number nodes.
	private JsonNode normalized(IdempotentResponse response) {
		return jsonMapper.readTree(response.body().toString());
	}

	private Optional<Long> lastSeq() {
		return jdbc.sql("SELECT last_seq FROM owner_change_counter WHERE owner_id = :owner")
			.param("owner", owner)
			.query(Long.class)
			.optional();
	}

	private Optional<Integer> storedStatus(UUID key) {
		return jdbc.sql("SELECT response_code FROM idempotency_record WHERE owner_id = :owner AND idempotency_key = :key")
			.param("owner", owner)
			.param("key", key)
			.query(Integer.class)
			.optional();
	}

	private JsonNode storedBody(UUID key) {
		return jsonMapper.readTree(jdbc.sql("""
				SELECT response_body::text FROM idempotency_record WHERE owner_id = :owner AND idempotency_key = :key
				""").param("owner", owner).param("key", key).query(String.class).single());
	}

	@RestController
	@RequestMapping("/test-stage-commands/{owner}")
	static class StageProbeController {

		private final IdempotentExecutor executor;

		StageProbeController(IdempotentExecutor executor) {
			this.executor = executor;
		}

		@IdempotentCommand
		@PostMapping("/immediate")
		ResponseEntity<JsonNode> immediate(@PathVariable UUID owner, IdempotencyKey key,
				@Valid @RequestBody ProbeCommand command) {
			return executor.execute(new IdempotentRequest(owner, key, "probe.immediate", command), () -> act(command))
				.toResponseEntity();
		}

		@IdempotentCommand(longRunning = true)
		@PostMapping("/long-running")
		ResponseEntity<JsonNode> longRunning(@PathVariable UUID owner, IdempotencyKey key,
				@Valid @RequestBody ProbeCommand command) {
			return executor
				.commit(new IdempotentRequest(owner, key, "probe.long-running", command, true),
						() -> new Outcome(HttpStatus.ACCEPTED, act(command)), ReplayProjection.IDENTITY)
				.toResponseEntity();
		}

		private static ProbeCommand act(ProbeCommand command) {
			if ("stored".equals(command.fail())) {
				throw new PikException(ErrorCode.FORBIDDEN, "PROBE_FAILED", false, null, CHALLENGE);
			}
			if ("unstored".equals(command.fail())) {
				throw PikException.unstored(ErrorCode.CONFLICT, "PROBE_FAILED", true, Duration.ofSeconds(2), CHALLENGE);
			}
			return command;
		}

		record ProbeCommand(@NotBlank String name, @Nullable String fail) {
		}

	}

}
