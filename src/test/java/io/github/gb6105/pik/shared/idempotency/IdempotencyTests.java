package io.github.gb6105.pik.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.shared.change.ChangeSequence;
import io.github.gb6105.pik.shared.error.CommandResult;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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

@Import({ TestcontainersConfiguration.class, IdempotencyTests.CommandProbeController.class })
@SpringBootTest(properties = { "pik.idempotency.lock-timeout=PT1S", "pik.idempotency.cleanup-batch-size=2" })
@AutoConfigureMockMvc
class IdempotencyTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	IdempotentExecutor executor;

	@Autowired
	IdempotencyCleanup cleanup;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transactions;

	final UUID owner = UUID.randomUUID();

	@Test
	void retransmissionReturnsStoredResponseWithoutRunningAgain() {
		UUID key = uuidV7();
		String expected = """
				{"jobId": "%s", "result": "SUCCEEDED", "retryable": false, "seq": 1}
				""".formatted(key);

		assertThat(post(key, "{\"name\": \"a\"}")).hasStatusOk().bodyJson().isLenientlyEqualTo(expected);
		assertThat(post(key, "{ \"fail\": null,  \"name\": \"a\" }")).hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo(expected);
		assertThat(lastSeq()).hasValue(1L);
	}

	@Test
	void sameKeyWithDifferentBodyIsRejectedAndKeepsOriginal() {
		UUID key = uuidV7();
		assertThat(post(key, "{\"name\": \"a\"}")).hasStatusOk();

		assertThat(post(key, "{\"name\": \"b\"}")).hasStatus(400).bodyJson().isLenientlyEqualTo("""
				{"code": "INVALID_INPUT", "reason": "IDEMPOTENCY_KEY_REUSED", "jobId": "%s", "result": "FAILED"}
				""".formatted(key));
		assertThat(post(key, "{\"name\": \"a\"}")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"seq": 1}
				""");
	}

	@Test
	void finalClientErrorRollsBackChangesAndIsReplayed() {
		UUID key = uuidV7();
		String expected = """
				{"status": 409, "code": "CONFLICT", "reason": "PROBE_FAILED", "retryable": false,
				 "jobId": "%s", "result": "FAILED", "instance": "/test-commands/%s"}
				""".formatted(key, owner);

		assertThat(post(key, "{\"name\": \"a\", \"fail\": \"CONFLICT\"}")).hasStatus(409)
			.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
			.bodyJson()
			.isLenientlyEqualTo(expected);
		assertThat(lastSeq()).isEmpty();
		assertThat(storedStatus(key)).hasValue(409);

		assertThat(post(key, "{\"name\": \"a\", \"fail\": \"CONFLICT\"}")).hasStatus(409)
			.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
			.bodyJson()
			.isLenientlyEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource({ "UNAUTHENTICATED, 401", "TEMPORARY_FAILURE, 503" })
	void nonFinalFailureIsNotStored(String code, int status) {
		UUID key = uuidV7();

		assertThat(post(key, "{\"name\": \"a\", \"fail\": \"%s\"}".formatted(code))).hasStatus(status)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "%s", "jobId": "%s", "result": "FAILED"}
					""".formatted(code, key));
		assertThat(storedStatus(key)).isEmpty();
		assertThat(lastSeq()).isEmpty();
	}

	@Test
	void missingKeyIsReportedBeforeBodyValidation() {
		assertThat(post(null, "{\"name\": \"\"}")).hasStatus(400).bodyJson().isLenientlyEqualTo("""
				{"code": "INVALID_INPUT", "reason": "IDEMPOTENCY_KEY_REQUIRED", "jobId": null, "result": "FAILED"}
				""");
	}

	@Test
	void keyMustBeCanonicalUuidV7() {
		assertThat(postWithKeyHeader(UUID.randomUUID().toString())).hasStatus(400)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "INVALID_INPUT", "reason": "IDEMPOTENCY_KEY_INVALID", "result": "FAILED"}
					""");
		assertThat(postWithKeyHeader("1-2-7-8-5")).hasStatus(400).bodyJson().isLenientlyEqualTo("""
				{"reason": "IDEMPOTENCY_KEY_INVALID"}
				""");
	}

	@Test
	void validationFailureOfCommandCarriesJobId() {
		UUID key = uuidV7();

		assertThat(post(key, "{\"name\": \"\"}")).hasStatus(400).bodyJson().isLenientlyEqualTo("""
				{"code": "INVALID_INPUT", "reason": "VALIDATION_FAILED", "jobId": "%s", "result": "FAILED"}
				""".formatted(key));
	}

	@Test
	void concurrentRequestWithSameKeyFailsAfterLockTimeout() throws Exception {
		IdempotentRequest request = directRequest(uuidV7());
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<IdempotentResponse> first = CompletableFuture
			.supplyAsync(() -> executor.execute(request, () -> {
				entered.countDown();
				awaitLatch(release);
				return "first";
			}));
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

		assertThatThrownBy(() -> executor.execute(request, () -> "second"))
			.isInstanceOfSatisfying(PikException.class, (ex) -> {
				assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT);
				assertThat(ex.getReason()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
				assertThat(ex.isRetryable()).isTrue();
			});
		release.countDown();
		assertThat(first.get(5, TimeUnit.SECONDS).body().toString()).isEqualTo("\"first\"");
	}

	@Test
	void waitingRequestReceivesResponseOfFirst() throws Exception {
		IdempotentRequest request = directRequest(uuidV7());
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger runs = new AtomicInteger();
		CompletableFuture<IdempotentResponse> first = CompletableFuture
			.supplyAsync(() -> executor.execute(request, () -> {
				runs.incrementAndGet();
				entered.countDown();
				awaitLatch(release);
				return "first";
			}));
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
		CompletableFuture<IdempotentResponse> second = CompletableFuture
			.supplyAsync(() -> executor.execute(request, () -> {
				runs.incrementAndGet();
				return "second";
			}));
		await().atMost(Duration.ofSeconds(5)).until(() -> waitingAdvisoryLocks() > 0);

		release.countDown();
		assertThat(first.get(5, TimeUnit.SECONDS).body().toString()).isEqualTo("\"first\"");
		assertThat(second.get(5, TimeUnit.SECONDS).body().toString()).isEqualTo("\"first\"");
		assertThat(runs).hasValue(1);
	}

	@Test
	void executorRefusesToJoinExistingTransaction() {
		assertThatThrownBy(() -> transactions
			.executeWithoutResult((status) -> executor.execute(directRequest(uuidV7()), () -> "x")))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void cleanupDeletesExpiredRecordsInBatchesUnderSchedulerLock() {
		for (int i = 0; i < 4; i++) {
			executor.execute(directRequest(uuidV7()), () -> "x");
		}
		jdbc.sql("""
				UPDATE idempotency_record SET expires_at = now() - INTERVAL '1 second'
				WHERE idempotency_key IN (
				    SELECT idempotency_key FROM idempotency_record WHERE owner_id = :owner LIMIT 3)
				""").param("owner", owner).update();

		cleanup.deleteExpired();

		assertThat(jdbc.sql("SELECT count(*) FROM idempotency_record WHERE owner_id = :owner")
			.param("owner", owner)
			.query(Long.class)
			.single()).isEqualTo(1L);
		assertThat(jdbc.sql("""
				SELECT abs(extract(EPOCH FROM locked_at - timezone('utc', now()))) < 60
				FROM shedlock WHERE name = 'idempotency-cleanup'
				""").query(Boolean.class).single()).isTrue();
	}

	private MvcTestResult post(@Nullable UUID key, String body) {
		return postWithKeyHeader(key != null ? key.toString() : null, body);
	}

	private MvcTestResult postWithKeyHeader(String key) {
		return postWithKeyHeader(key, "{\"name\": \"a\"}");
	}

	private MvcTestResult postWithKeyHeader(@Nullable String key, String body) {
		var request = mvc.post()
			.uri("/test-commands/{owner}", owner)
			.header("API-Version", "1")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		if (key != null) {
			request.header(IdempotencyKey.HEADER, key);
		}
		return request.exchange();
	}

	private IdempotentRequest directRequest(UUID key) {
		return new IdempotentRequest(owner, new IdempotencyKey(key), "probe.direct", "x");
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

	private long waitingAdvisoryLocks() {
		return jdbc.sql("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
			.query(Long.class)
			.single();
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	static UUID uuidV7() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		long msb = (System.currentTimeMillis() << 16) | 0x7000L | (random.nextLong() & 0x0FFFL);
		long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
		return new UUID(msb, lsb);
	}

	@RestController
	@RequestMapping("/test-commands")
	static class CommandProbeController {

		private final IdempotentExecutor executor;

		private final ChangeSequence changes;

		CommandProbeController(IdempotentExecutor executor, ChangeSequence changes) {
			this.executor = executor;
			this.changes = changes;
		}

		@IdempotentCommand
		@PostMapping("/{owner}")
		ResponseEntity<JsonNode> apply(@PathVariable UUID owner, IdempotencyKey key,
				@Valid @RequestBody ProbeCommand command) {
			return executor.execute(new IdempotentRequest(owner, key, "probe.apply", command), () -> {
				long seq = changes.next(owner);
				if (command.fail() != null) {
					throw new PikException(ErrorCode.valueOf(command.fail()), "PROBE_FAILED", false);
				}
				return new ProbeResult(key.value(), CommandResult.SUCCEEDED, false, seq);
			}).toResponseEntity();
		}

		record ProbeCommand(@NotBlank String name, @Nullable String fail) {
		}

		record ProbeResult(UUID jobId, CommandResult result, boolean retryable, long seq) {
		}

	}

}
