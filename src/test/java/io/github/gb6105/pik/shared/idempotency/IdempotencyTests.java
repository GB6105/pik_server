package io.github.gb6105.pik.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariDataSource;
import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.shared.change.ChangeSequence;
import io.github.gb6105.pik.shared.error.CommandResult;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import({ TestcontainersConfiguration.class, IdempotencyTests.CommandProbeController.class,
		IdempotencyTests.TransactionalProbe.class, IdempotencyTests.EventProbe.class })
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
	IdempotencyRecords records;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	JsonMapper jsonMapper;

	@Autowired
	EntityManager entityManager;

	@Autowired
	HikariDataSource dataSource;

	@Autowired
	ApplicationEventPublisher events;

	@Autowired
	ChangeSequence changes;

	@Autowired
	TransactionalProbe transactionalProbe;

	@Autowired
	EventProbe eventProbe;

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
	void reorderedOrExtendedCommandKeepsHashOfEarlierRequests() {
		IdempotencyKey key = new IdempotencyKey(uuidV7());
		AtomicInteger runs = new AtomicInteger();

		executor.execute(new IdempotentRequest(owner, key, "probe.evolve", new CommandBefore("z", "a")),
				runs::incrementAndGet);
		IdempotentResponse replay = executor.execute(
				new IdempotentRequest(owner, key, "probe.evolve", new CommandAfter("a", null, "z")),
				runs::incrementAndGet);

		assertThat(replay.status()).isEqualTo(200);
		assertThat(runs).hasValue(1);
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

	// Spring marks the whole transaction rollback-only when a participating @Transactional method throws.
	@Test
	void clientErrorFromTransactionalParticipantIsStored() {
		UUID key = uuidV7();

		IdempotentResponse response = executor.execute(directRequest(key),
				() -> transactionalProbe.nextThenFail(owner));

		assertThat(response.status()).isEqualTo(409);
		assertThat(storedStatus(key)).hasValue(409);
		assertThat(lastSeq()).isEmpty();
	}

	// Hibernate marks the transaction rollback-only on its own exceptions, even when the action handles them.
	@Test
	void clientErrorAfterPersistenceExceptionIsStored() {
		UUID key = uuidV7();

		IdempotentResponse response = executor.execute(directRequest(key), () -> {
			try {
				return entityManager.createNativeQuery("SELECT 1 / 0").getSingleResult();
			}
			catch (PersistenceException ex) {
				throw new PikException(ErrorCode.INVALID_INPUT, "PROBE_FAILED", false);
			}
		});

		assertThat(response.status()).isEqualTo(400);
		assertThat(storedStatus(key)).hasValue(400);
	}

	@Test
	void clientErrorRollsBackFlushedEntityChanges() {
		UUID key = uuidV7();

		IdempotentResponse response = executor.execute(directRequest(key), () -> {
			entityManager.persist(new ChangeCounterProbe(owner, 42));
			entityManager.flush();
			throw new PikException(ErrorCode.CONFLICT, "PROBE_FAILED", false);
		});

		assertThat(response.status()).isEqualTo(409);
		assertThat(storedStatus(key)).hasValue(409);
		assertThat(lastSeq()).isEmpty();
	}

	@Test
	void rejectedActionDoesNotPublishItsEvents() {
		UUID rejected = uuidV7();
		UUID accepted = uuidV7();

		executor.execute(directRequest(rejected), () -> {
			events.publishEvent(new ProbeEvent(rejected));
			throw new PikException(ErrorCode.CONFLICT, "PROBE_FAILED", false);
		});
		executor.execute(directRequest(accepted), () -> {
			events.publishEvent(new ProbeEvent(accepted));
			return "x";
		});

		await().atMost(Duration.ofSeconds(5))
			.until(() -> eventProbe.delivered(accepted) && publications(accepted) == 0L);
		assertThat(storedStatus(rejected)).hasValue(409);
		assertThat(eventProbe.committed(accepted)).isTrue();
		assertThat(eventProbe.committed(rejected)).isFalse();
		assertThat(eventProbe.delivered(rejected)).isFalse();
		assertThat(publications(rejected)).isZero();
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

	@ParameterizedTest
	@MethodSource("unstoredFailures")
	void unstoredFailureRollsBackAndIsRethrown(RuntimeException failure) {
		UUID key = uuidV7();

		assertThatThrownBy(() -> executor.execute(directRequest(key), () -> {
			changes.next(owner);
			throw failure;
		})).isSameAs(failure);
		assertThat(storedStatus(key)).isEmpty();
		assertThat(lastSeq()).isEmpty();
	}

	static Stream<RuntimeException> unstoredFailures() {
		return Stream.of(new PikException(ErrorCode.RATE_LIMITED, "PROBE_FAILED", true, Duration.ofSeconds(1)),
				new PikException(ErrorCode.CONFLICT, "PROBE_FAILED", true, Duration.ofSeconds(1)),
				new IllegalStateException("probe"));
	}

	@Test
	void missingKeyIsReportedBeforeBodyValidation() {
		String expected = """
				{"code": "INVALID_INPUT", "reason": "IDEMPOTENCY_KEY_REQUIRED", "jobId": null, "result": "FAILED"}
				""";

		assertThat(post(null, "{\"name\": \"\"}")).hasStatus(400).bodyJson().isLenientlyEqualTo(expected);
		assertThat(postWithKeyHeader("", "{\"name\": \"\"}")).hasStatus(400).bodyJson().isLenientlyEqualTo(expected);
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
		assertThat(postWithKeyHeader(uuidV7().toString().toUpperCase(Locale.ROOT))).hasStatus(400)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"reason": "IDEMPOTENCY_KEY_INVALID", "jobId": null}
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
		UUID key = uuidV7();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<IdempotentResponse> first = CompletableFuture
			.supplyAsync(() -> executor.execute(directRequest(key), () -> {
				entered.countDown();
				awaitLatch(release);
				return "first";
			}));
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(post(key, "{\"name\": \"a\"}")).hasStatus(409).bodyJson().isLenientlyEqualTo("""
				{"code": "CONFLICT", "reason": "IDEMPOTENCY_IN_PROGRESS", "retryable": true,
				 "jobId": "%s", "result": "FAILED"}
				""".formatted(key));
		release.countDown();
		assertThat(first.get(5, TimeUnit.SECONDS).body().toString()).isEqualTo("\"first\"");
		assertThat(storedStatus(key)).hasValue(200);
	}

	@Test
	void waitingRequestReceivesResponseOfFirst() throws Exception {
		IdempotentExecutor patient = patientExecutor();
		IdempotentRequest request = directRequest(uuidV7());
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger runs = new AtomicInteger();
		CompletableFuture<IdempotentResponse> first = CompletableFuture
			.supplyAsync(() -> patient.execute(request, () -> {
				runs.incrementAndGet();
				entered.countDown();
				awaitLatch(release);
				return "first";
			}));
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
		CompletableFuture<IdempotentResponse> second = CompletableFuture
			.supplyAsync(() -> patient.execute(request, () -> {
				runs.incrementAndGet();
				return "second";
			}));
		await().atMost(Duration.ofSeconds(5)).until(() -> waitingAdvisoryLocks() > 0);

		release.countDown();
		assertThat(first.get(5, TimeUnit.SECONDS).body().toString()).isEqualTo("\"first\"");
		assertThat(second.get(5, TimeUnit.SECONDS).body().toString()).isEqualTo("\"first\"");
		assertThat(runs).hasValue(1);
	}

	// The retransmission runs the action after the rejected one rolls back; whichever stores first answers both.
	@Test
	void rejectedRequestAndWaitingRetransmissionShareOneResponse() throws Exception {
		IdempotentExecutor patient = patientExecutor();
		IdempotentRequest request = directRequest(uuidV7());
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger attempts = new AtomicInteger();
		Supplier<Object> action = () -> {
			if (attempts.incrementAndGet() == 1) {
				entered.countDown();
				awaitLatch(release);
				throw new PikException(ErrorCode.CONFLICT, "PROBE_FAILED", false);
			}
			return changes.next(owner);
		};
		CompletableFuture<IdempotentResponse> first = CompletableFuture
			.supplyAsync(() -> patient.execute(request, action));
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
		CompletableFuture<IdempotentResponse> second = CompletableFuture
			.supplyAsync(() -> patient.execute(request, action));
		await().atMost(Duration.ofSeconds(5)).until(() -> waitingAdvisoryLocks() > 0);

		release.countDown();
		IdempotentResponse firstResponse = first.get(15, TimeUnit.SECONDS);
		IdempotentResponse secondResponse = second.get(15, TimeUnit.SECONDS);

		assertThat(firstResponse.status()).isEqualTo(secondResponse.status());
		assertThat(normalized(firstResponse)).isEqualTo(normalized(secondResponse));
		assertThat(storedStatus(request.key().value())).hasValue(firstResponse.status());
		assertThat(lastSeq()).isEqualTo(firstResponse.status() == 200 ? Optional.of(1L) : Optional.empty());
	}

	@Test
	void actionRunsUnderReadCommittedWithoutKeyLockTimeout() {
		String outside = setting("lock_timeout");

		IdempotentResponse response = executor.execute(directRequest(uuidV7()),
				() -> List.of(setting("lock_timeout"), setting("transaction_isolation")));

		assertThat(response.body().toString()).isEqualTo("[\"%s\",\"read committed\"]".formatted(outside));
	}

	// Hikari compares the configured isolation with the default it read on its first connection only.
	@Test
	void actionRunsUnderReadCommittedAfterDatabaseDefaultChanges() {
		String database = jdbc.sql("SELECT current_database()").query(String.class).single();
		jdbc.sql("ALTER DATABASE \"%s\" SET default_transaction_isolation = 'repeatable read'".formatted(database))
			.update();
		try {
			dataSource.getHikariPoolMXBean().softEvictConnections();

			IdempotentResponse response = executor.execute(directRequest(uuidV7()),
					() -> List.of(jdbc.sql("SELECT reset_val FROM pg_settings WHERE name = 'default_transaction_isolation'")
						.query(String.class)
						.single(), setting("transaction_isolation")));

			assertThat(response.body().toString()).isEqualTo("[\"repeatable read\",\"read committed\"]");
		}
		finally {
			jdbc.sql("ALTER DATABASE \"%s\" RESET default_transaction_isolation".formatted(database)).update();
			dataSource.getHikariPoolMXBean().softEvictConnections();
		}
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

	@Test
	void propertiesRejectValuesThatRemoveLimits() {
		assertThatThrownBy(() -> properties(Duration.ofNanos(1), 1000)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties(Duration.ofSeconds(1), 0)).isInstanceOf(IllegalArgumentException.class);
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

	// Waits long enough that a slow CI run does not turn the wait into IDEMPOTENCY_IN_PROGRESS.
	private IdempotentExecutor patientExecutor() {
		return new IdempotentExecutor(records, transactionManager, properties(Duration.ofSeconds(10), 1000),
				jsonMapper);
	}

	private static IdempotencyProperties properties(Duration lockTimeout, int cleanupBatchSize) {
		return new IdempotencyProperties(Duration.ofDays(7), lockTimeout, Duration.ofMinutes(1), Duration.ofHours(1),
				cleanupBatchSize);
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

	private long publications(UUID key) {
		return jdbc.sql("SELECT count(*) FROM event_publication WHERE serialized_event LIKE :key")
			.param("key", "%" + key + "%")
			.query(Long.class)
			.single();
	}

	private String setting(String name) {
		return jdbc.sql("SELECT current_setting(:name)").param("name", name).query(String.class).single();
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

	record CommandBefore(String zeta, String alpha) {
	}

	record CommandAfter(String alpha, @Nullable String added, String zeta) {
	}

	record ProbeEvent(UUID key) {
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

	static class TransactionalProbe {

		private final ChangeSequence changes;

		TransactionalProbe(ChangeSequence changes) {
			this.changes = changes;
		}

		@Transactional
		public long nextThenFail(UUID owner) {
			changes.next(owner);
			throw new PikException(ErrorCode.CONFLICT, "PROBE_FAILED", false);
		}

	}

	// Proxied for @Async and @Transactional, so state is reached through methods, not fields.
	static class EventProbe {

		private final Set<UUID> committed = ConcurrentHashMap.newKeySet();

		private final Set<UUID> delivered = ConcurrentHashMap.newKeySet();

		@TransactionalEventListener
		public void onCommit(ProbeEvent event) {
			committed.add(event.key());
		}

		@ApplicationModuleListener
		public void onDelivery(ProbeEvent event) {
			delivered.add(event.key());
		}

		public boolean committed(UUID key) {
			return committed.contains(key);
		}

		public boolean delivered(UUID key) {
			return delivered.contains(key);
		}

	}

}
