package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.AnalysisIntakeTests.item;
import static io.github.gb6105.pik.analysis.IntakeFixtures.body;
import static io.github.gb6105.pik.analysis.IntakeFixtures.json;
import static io.github.gb6105.pik.analysis.IntakeFixtures.key;
import static io.github.gb6105.pik.analysis.IntakeFixtures.photo;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.analysis.IntakeFixtures.Installed;
import io.github.gb6105.pik.analysis.IntakeFixtures.Photo;
import io.github.gb6105.pik.consent.AiConsents;
import io.github.gb6105.pik.usage.TrialUsage;
import io.github.gb6105.pik.usage.TrialUsage.Reservation;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * C-1 to C-8 of plan 8.3: intake requests against each other, against consent decisions and against job transitions
 * of stage 5-2, on PostgreSQL with real installs, tokens and consents. The tests order the transactions with latches
 * and by polling {@code pg_stat_activity} for the lock waits of known backends, never by sleeping, and bound every
 * wait. A deadlock fails one transaction with {@code deadlock_detected}, which reaches the client as 503.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "pik.analysis.intake-enabled=true")
@AutoConfigureMockMvc
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AnalysisIntakeConcurrencyTests {

	private static final Duration WAIT = Duration.ofSeconds(15);

	// Texts of the statements the tests wait in, as pg_stat_activity shows them.
	private static final String KEY_LOCK = "pg_advisory_xact_lock(hashtextextended(CAST(";

	private static final String INTAKE_LOCK = "'analysis.intake:'";

	private static final String JOB_PRE_LOCK = "ORDER BY id FOR SHARE";

	private static final String CONSENT_SHARED_LOCK = "pg_advisory_xact_lock_shared(hashtextextended('consent:'";

	private static final String CONSENT_EXCLUSIVE_LOCK = "pg_advisory_xact_lock(hashtextextended('consent:'";

	private static final String IDEMPOTENCY_INSERT = "INSERT INTO idempotency_record";

	private static final String USAGE_LOCK = "usage_quota";

	// The row locks a stage 5-2 transition may take on a job: the rule (ADR-0034) and the one it forbids.
	private static final String FOR_NO_KEY_UPDATE = "FOR NO KEY UPDATE";

	private static final String FOR_UPDATE = "FOR UPDATE";

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transactions;

	@MockitoSpyBean
	TrialUsage trialUsage;

	@MockitoSpyBean
	IntakeRecords records;

	@MockitoSpyBean
	AiConsents consents;

	// Set on the thread of an intake started by startIntake, to learn the backend of its business transaction.
	final ThreadLocal<CompletableFuture<Integer>> backend = new ThreadLocal<>();

	IntakeFixtures fixtures;

	ExecutorService pool;

	@BeforeEach
	void setUp() {
		fixtures = new IntakeFixtures(mvc, jdbc);
		pool = Executors.newCachedThreadPool();
		// 5a is the first step of the business transaction. Stubbed on the spy behind the transaction proxy, unlike
		// the IntakeRecords stubs: through the proxy, the MANDATORY propagation of lockGrantedBasis refuses the call
		// that stubbing makes outside a transaction.
		doAnswer((invocation) -> {
			CompletableFuture<Integer> pid = backend.get();
			if (pid != null) {
				// Last wins: after StartRequired the business transaction runs again, maybe on another connection.
				pid.obtrudeValue(backendPid());
			}
			return invocation.callRealMethod();
		}).when(AopTestUtils.<AiConsents>getUltimateTargetObject(consents)).lockGrantedBasis(any());
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		pool.shutdownNow();
		assertThat(pool.awaitTermination(WAIT.toSeconds(), SECONDS)).as("no task of the test is left running").isTrue();
	}

	// C-1: the later request waits for the intake lock and answers the shared photos with the earlier jobs.
	@Test
	void overlappingRequestOfThePrincipalWaitsAndReferencesTheEarlierJobs() throws Exception {
		Installed device = fixtures.android();
		Photo onlyFirst = photo("v1");
		Photo shared = photo("v1");
		Photo alsoShared = photo("v1");
		Photo onlySecond = photo("v1");
		Hold hold = holdFirstReservation(device);
		Intake first = startIntake(device, key(), body("NEW_PHOTOS", onlyFirst, shared, alsoShared));
		awaitLatch(hold.reached());
		Intake second = startIntake(device, key(), body("NEW_PHOTOS", alsoShared, shared, onlySecond));
		awaitWaitIn(second.pid(), INTAKE_LOCK);
		hold.release().countDown();

		JsonNode earlier = response(first.response(), 202);
		JsonNode later = response(second.response(), 202);
		for (Photo photo : List.of(shared, alsoShared)) {
			assertThat(item(earlier, photo).get("admissionResult").asString()).isEqualTo("ACCEPTED");
			assertThat(item(later, photo).get("admissionResult").asString()).isEqualTo("IN_PROGRESS");
			assertThat(jobOf(later, photo)).isEqualTo(jobOf(earlier, photo));
		}
		assertThat(item(later, onlySecond).get("admissionResult").asString()).isEqualTo("ACCEPTED");
		assertOneLiveJobAndReservationEach(device, onlyFirst, shared, alsoShared, onlySecond);
	}

	// C-1: requests started together, their photos in other orders, whichever gets the intake lock first.
	@Test
	void parallelRequestsOfThePrincipalAcceptEachPhotoOnce() throws Exception {
		Installed device = fixtures.android();
		for (int round = 0; round < 10; round++) {
			Photo a = photo("v1");
			Photo b = photo("v1");
			Photo onlyOne = photo("v1");
			Photo onlyOther = photo("v1");
			List<Callable<MvcTestResult>> requests = List.of(
					() -> fixtures.intake(device, key(), body("NEW_PHOTOS", a, b, onlyOne)),
					() -> fixtures.intake(device, key(), body("NEW_PHOTOS", onlyOther, b, a)));
			List<MvcTestResult> results = parallel(requests);

			JsonNode one = response(results.get(0), 202);
			JsonNode other = response(results.get(1), 202);
			for (Photo photo : List.of(a, b)) {
				assertThat(Stream.of(one, other)
					.map((response) -> item(response, photo).get("admissionResult").asString()))
					.containsExactlyInAnyOrder("ACCEPTED", "IN_PROGRESS");
				assertThat(jobOf(one, photo)).isEqualTo(jobOf(other, photo));
			}
			assertOneLiveJobAndReservationEach(device, a, b, onlyOne, onlyOther);
		}
	}

	// C-2: started together; 6 requests leave the connection pool (10) room for the test thread.
	@Test
	void sameKeyAndBodyInParallelStoreOneRequest() throws Exception {
		Installed device = fixtures.android();
		String key = key();
		String body = body("NEW_PHOTOS", photo("v1"), photo("v1"));

		List<Callable<MvcTestResult>> requests = Collections.nCopies(6, () -> fixtures.intake(device, key, body));
		List<MvcTestResult> results = parallel(requests);

		Set<String> requestIds = new HashSet<>();
		for (MvcTestResult result : results) {
			JsonNode response = json(result);
			if (result.getResponse().getStatus() == 409) {
				assertThat(response.get("code").asString()).isEqualTo("CONFLICT");
				assertThat(response.get("reason").asString()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
				assertThat(response.get("retryable").asBoolean()).isTrue();
			}
			else {
				assertThat(result).hasStatus(202);
				requestIds.add(response.get("jobId").asString());
			}
		}
		assertThat(requestIds).hasSize(1);
		assertStoredOnce(device, 2);
	}

	// C-2: the second request passed the lookup before the first stored its record, waits for the key lock the first
	// holds and replays the stored response without running the business transaction.
	@Test
	void sameKeyWaitingForTheFirstRequestReplaysItsResponse() throws Exception {
		Installed device = fixtures.android();
		String key = key();
		String body = body("NEW_PHOTOS", photo("v1"), photo("v1"));
		Hold hold = holdFirstReservation(device);
		Intake first = startIntake(device, key, body);
		awaitLatch(hold.reached());
		Intake second = startIntake(device, key, body);
		awaitBlockedBy(first.pid(), KEY_LOCK);
		hold.release().countDown();

		JsonNode stored = response(first.response(), 202);
		JsonNode replayed = response(second.response(), 202);
		assertThat(replayed).isEqualTo(stored);
		assertThat(second.backend()).as("the replay reaches no business transaction").isNotDone();
		assertStoredOnce(device, 2);
	}

	// C-3: a decision waits while the intake holds the consent and commits after it.
	@Test
	void decisionWaitsForTheIntakeHoldingTheConsent() throws Exception {
		Installed device = fixtures.android();
		Hold hold = holdFirstReservation(device);
		Intake intake = startIntake(device, key(), body("NEW_PHOTOS", photo("v1")));
		awaitLatch(hold.reached());
		Future<MvcTestResult> decision = pool.submit(() -> fixtures.decide(device, key(), "DENIED", 1));
		awaitBlockedBy(intake.pid(), CONSENT_EXCLUSIVE_LOCK);
		hold.release().countDown();

		JsonNode accepted = response(intake.response(), 202);
		assertThat(decision.get(WAIT.toSeconds(), SECONDS)).hasStatusOk();
		// The request keeps the grant it ran under; the decision replaced that grant afterwards.
		assertThat(jdbc.sql("SELECT consent_version FROM analysis_request WHERE id = :id")
			.param("id", UUID.fromString(accepted.get("jobId").asString()))
			.query(Long.class)
			.single()).isEqualTo(1L);
		assertThat(latestConsent(device)).containsExactly(2L, "WITHDRAWN");
	}

	// C-3: an intake reaching the consent while a decision holds it waits, sees the decision and stores 403.
	@Test
	void intakeWaitsForADecisionInProgressAndStoresTheRefusal() throws Exception {
		Installed device = fixtures.android();
		String decisionKey = key();
		CompletableFuture<Integer> blockerPid = new CompletableFuture<>();
		CountDownLatch unblock = new CountDownLatch(1);
		// An uncommitted record for the decision's key holds the decision at its last write, after the consent lock.
		Future<?> blocker = pool.submit(() -> transactions.executeWithoutResult((status) -> {
			jdbc.sql("""
					INSERT INTO idempotency_record (owner_id, idempotency_key, operation, request_hash, response_code,
					                                response_body, created_at, expires_at)
					VALUES (:owner, :key, 'blocker', 'blocker', 200, '{}', now(), now() + INTERVAL '1 day')
					""").param("owner", device.principalId()).param("key", UUID.fromString(decisionKey)).update();
			blockerPid.complete(backendPid());
			awaitLatch(unblock);
			status.setRollbackOnly();
		}));
		int blocking = blockerPid.get(WAIT.toSeconds(), SECONDS);
		Future<MvcTestResult> decision = pool.submit(() -> fixtures.decide(device, decisionKey, "DENIED", 1));
		awaitBlockedBy(blocking, IDEMPOTENCY_INSERT);
		String key = key();
		// The quick check outside the transaction still sees the grant; 5a waits for the decision.
		Intake intake = startIntake(device, key, body("NEW_PHOTOS", photo("v1")));
		awaitWaitIn(intake.pid(), CONSENT_SHARED_LOCK);
		unblock.countDown();
		blocker.get(WAIT.toSeconds(), SECONDS);

		assertThat(decision.get(WAIT.toSeconds(), SECONDS)).hasStatusOk();
		JsonNode refused = response(intake.response(), 403);
		assertThat(refused.get("code").asString()).isEqualTo("FORBIDDEN");
		assertThat(refused.get("reason").asString()).isEqualTo("AI_CONSENT_REQUIRED");
		assertThat(jdbc.sql("""
				SELECT response_code FROM idempotency_record WHERE owner_id = :owner AND idempotency_key = :key
				""")
			.param("owner", device.principalId())
			.param("key", UUID.fromString(key))
			.query(Integer.class)
			.optional()).contains(403);
		assertThat(count("analysis_request", "owner_id", device)).isZero();
		assertThat(count("photo", "owner_id", device)).isZero();
	}

	// C-4: the photo row of one principal does not hold the other back.
	@Test
	void principalsSendingTheSamePhotoIdGetTheirOwnPhotosAndJobs() throws Exception {
		Installed one = fixtures.android();
		Installed other = fixtures.android();
		UUID photoId = UUID.randomUUID();
		Hold hold = holdFirstReservation(one);
		Intake first = startIntake(one, key(), body("NEW_PHOTOS", photo(photoId, "v1")));
		awaitLatch(hold.reached());

		// Completes while the first request holds its uncommitted photo row.
		Intake second = startIntake(other, key(), body("NEW_PHOTOS", photo(photoId, "v2")));
		JsonNode otherResponse = response(second.response(), 202);
		hold.release().countDown();
		JsonNode oneResponse = response(first.response(), 202);

		assertThat(item(oneResponse, photoId).get("admissionResult").asString()).isEqualTo("ACCEPTED");
		assertThat(item(otherResponse, photoId).get("admissionResult").asString()).isEqualTo("ACCEPTED");
		assertThat(jobOf(oneResponse, photoId)).isNotEqualTo(jobOf(otherResponse, photoId));
		assertThat(jdbc.sql("SELECT owner_id, observed_version FROM photo WHERE client_ref = :ref")
			.param("ref", photoId)
			.query((rs, rowNum) -> Map.entry(rs.getObject("owner_id", UUID.class), rs.getString("observed_version")))
			.list())
			.containsExactlyInAnyOrder(Map.entry(one.principalId(), "v1"), Map.entry(other.principalId(), "v2"));
	}

	// C-5: decisions and intakes of the principal in parallel, in whatever order they take the consent.
	@Test
	void repeatedDecisionsAndIntakesInParallelDoNotDeadlock() throws Exception {
		Installed device = fixtures.android();
		Photo first = photo("v1");
		Photo second = photo("v1");
		int rounds = 10;
		for (int round = 0; round < rounds; round++) {
			long version = 1 + round;
			// A grant again: every intake goes through the whole business transaction.
			List<Callable<MvcTestResult>> requests = List.of(() -> fixtures.decide(device, key(), "GRANTED", version),
					() -> fixtures.intake(device, key(), body("NEW_PHOTOS", first, second, photo("v1"))),
					() -> fixtures.intake(device, key(), body("NEW_PHOTOS", second, first, photo("v1"))));
			List<MvcTestResult> results = parallel(requests);

			assertThat(results.get(0)).hasStatusOk();
			for (MvcTestResult intake : results.subList(1, 3)) {
				JsonNode response = response(intake, 202);
				long consentVersion = jdbc.sql("SELECT consent_version FROM analysis_request WHERE id = :id")
					.param("id", UUID.fromString(response.get("jobId").asString()))
					.query(Long.class)
					.single();
				// The grant in force before or after the decision of the round.
				assertThat(consentVersion).isBetween(version, version + 1);
			}
		}
		assertThat(latestConsent(device)).containsExactly(1L + rounds, "GRANTED");
		assertThat(count("analysis_request", "owner_id", device)).isEqualTo(2L * rounds);
		assertThat(count("analysis_job", "owner_id", device)).isEqualTo(2L + 2L * rounds);
	}

	// C-6: the intake locks the job it points at before the usage allocation (5c'), so a transaction holding the job
	// and then taking the usage allocation goes first. Also with FOR UPDATE, which C-8's late reference cannot take.
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { FOR_UPDATE, FOR_NO_KEY_UPDATE })
	void intakePointingAtALockedJobWaitsBeforeTheUsageLock(String lock) throws Exception {
		Installed device = fixtures.android();
		Photo existing = photo("v1");
		UUID jobId = acceptedJob(device, existing);
		Photo added = photo("v1");
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch proceed = new CountDownLatch(1);
		// A transition of stage 5-2: the job first, then the principal's usage allocation (release).
		Future<Integer> transition = pool.submit(() -> transactions.execute((status) -> {
			lockJob(jobId, lock);
			locked.countDown();
			awaitLatch(proceed);
			int released = trialUsage.release(device.principalId(), jobId);
			status.setRollbackOnly();
			return released;
		}));
		awaitLatch(locked);
		Intake intake = startIntake(device, key(), body("NEW_PHOTOS", existing, added));
		awaitWaitIn(intake.pid(), JOB_PRE_LOCK);
		proceed.countDown();

		assertThat(transition.get(WAIT.toSeconds(), SECONDS)).isEqualTo(1);
		JsonNode response = response(intake.response(), 202);
		assertThat(item(response, existing).get("admissionResult").asString()).isEqualTo("IN_PROGRESS");
		assertThat(jobOf(response, existing)).isEqualTo(jobId);
		assertThat(item(response, added).get("admissionResult").asString()).isEqualTo("ACCEPTED");
	}

	// C-7: the job ends and commits after 5c read it live; the recheck of 5c' gives the photo back as a candidate.
	@Test
	void jobEndedAfterTheReadMakesThePhotoACandidateAgain() throws Exception {
		Installed device = fixtures.android();
		Photo photo = photo("v1");
		UUID jobId = acceptedJob(device, photo);
		AtomicReference<@Nullable Map<UUID, List<UUID>>> readLive = new AtomicReference<>();
		AtomicBoolean first = new AtomicBoolean(true);
		doAnswer((invocation) -> {
			@SuppressWarnings("unchecked")
			Map<UUID, List<UUID>> found = (Map<UUID, List<UUID>>) invocation.callRealMethod();
			// The read of 5c, in the business transaction, which writes; preparation reads outside of it.
			if (TransactionSynchronizationManager.isActualTransactionActive()
					&& !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
					&& first.compareAndSet(true, false)) {
				readLive.set(found);
				pool.submit(() -> transactions.execute((status) -> endJob(device, jobId)))
					.get(WAIT.toSeconds(), SECONDS);
			}
			return found;
		}).when(records).findLiveJobs(eq(device.principalId()), anyCollection());

		JsonNode response = response(fixtures.intake(device, key(), body("NEW_PHOTOS", photo)), 202);

		// 5c read the job as live.
		assertThat(readLive.get()).containsValue(List.of(jobId));
		assertCandidateAgain(device, response, photo, jobId);
	}

	// C-7: the job ends while 5c' waits for its lock; the locking read sees the committed status.
	@Test
	void jobEndingWhileThePreLockWaitsMakesThePhotoACandidateAgain() throws Exception {
		Installed device = fixtures.android();
		Photo photo = photo("v1");
		UUID jobId = acceptedJob(device, photo);
		CountDownLatch ended = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		Future<Integer> transition = pool.submit(() -> transactions.execute((status) -> {
			int released = endJob(device, jobId);
			ended.countDown();
			awaitLatch(commit);
			return released;
		}));
		awaitLatch(ended);
		Intake intake = startIntake(device, key(), body("NEW_PHOTOS", photo));
		// 5c read the job live, as the transition has not committed; 5c' waits for it.
		awaitWaitIn(intake.pid(), JOB_PRE_LOCK);
		commit.countDown();

		assertThat(transition.get(WAIT.toSeconds(), SECONDS)).isEqualTo(1);
		assertCandidateAgain(device, response(intake.response(), 202), photo, jobId);
	}

	// C-8: a photo answered AlreadyReserved points at a job 5c' did not lock, so the item's FK check (KEY SHARE on the
	// job) comes after the usage lock. A transition locking the job FOR NO KEY UPDATE and then the usage allocation
	// does not conflict with that KEY SHARE: both transactions finish.
	@Test
	void lateReferenceToAJobDoesNotDeadlockWithANoKeyUpdateTransition() throws Exception {
		LateReference late = lateReference(FOR_NO_KEY_UPDATE);

		JsonNode response = response(late.intake(), 200);
		assertThat(item(response, late.photo()).get("admissionResult").asString()).isEqualTo("IN_PROGRESS");
		assertThat(jobOf(response, late.photo())).isEqualTo(late.jobId());
		assertThat(late.itemFailure().get()).isNull();
		// The transition got the usage allocation once the intake committed, and released the job's reservation.
		assertThat(late.transition().get(WAIT.toSeconds(), SECONDS)).isEqualTo(1);
		assertThat(jdbc.sql("SELECT status FROM usage_reservation WHERE job_id = :job")
			.param("job", late.jobId())
			.query(String.class)
			.single()).isEqualTo("RELEASED");
	}

	// C-8 control: the same schedule with FOR UPDATE, which conflicts with KEY SHARE, deadlocks. Exactly one of the two
	// transactions fails: the intake, whose wait closes the cycle and whose check after deadlock_timeout finds it.
	// Assumes the Testcontainers database user is a superuser, as set_config('deadlock_timeout', ...) needs one, and
	// that the intake keeps the server default of 1 s, so its check runs long before the transition's 60 s.
	@Test
	void lateReferenceToAJobDeadlocksWithAForUpdateTransition() throws Exception {
		LateReference late = lateReference(FOR_UPDATE);

		JsonNode failed = response(late.intake(), 503);
		assertThat(failed.get("reason").asString()).isEqualTo("INTERNAL_ERROR");
		assertThat(sqlState(late.itemFailure().get())).isEqualTo("40P01");
		// The transition then got the usage allocation and released the job's reservation.
		assertThat(late.transition().get(WAIT.toSeconds(), SECONDS)).isEqualTo(1);
		assertThat(count("analysis_request", "owner_id", late.device())).isOne();
	}

	/**
	 * Runs the C-8 schedule. The job ended in a committed transition whose release has not run, so 5c finds no live
	 * job for the photo and the reservation answers {@code AlreadyReserved}. No designed path of stage 5-2 leaves that
	 * state, since a transition releases the reservation in its own transaction: an {@code AlreadyReserved} holder
	 * that 5c' did not lock is a defensive branch, and C-8 pins the lock rule it relies on. The transition locks the
	 * job, the intake takes the usage allocation, the transition waits for it, and only then the intake inserts the
	 * item. The transition's {@code deadlock_timeout} is long, so that a cycle is always found by the intake, whose
	 * wait starts last, whatever the scheduling.
	 */
	private LateReference lateReference(String lock) throws Exception {
		Installed device = fixtures.android();
		Photo photo = photo("v1");
		UUID jobId = acceptedJob(device, photo);
		jdbc.sql("UPDATE analysis_job SET status = 'FAILED' WHERE id = :id").param("id", jobId).update();
		Hold hold = holdFirstReservation(device);
		AtomicReference<@Nullable Throwable> itemFailure = new AtomicReference<>();
		doAnswer((invocation) -> {
			try {
				return invocation.callRealMethod();
			}
			catch (Throwable ex) {
				itemFailure.set(ex);
				throw ex;
			}
		}).when(records).insertItems(anyList());
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch proceed = new CountDownLatch(1);
		CompletableFuture<Integer> transitionPid = new CompletableFuture<>();
		Future<Integer> transition = pool.submit(() -> transactions.execute((status) -> {
			// Local to this transaction; a superuser setting, which the Testcontainers database user may change.
			jdbc.sql("SELECT set_config('deadlock_timeout', '60s', true)").query(String.class).single();
			transitionPid.complete(backendPid());
			lockJob(jobId, lock);
			locked.countDown();
			awaitLatch(proceed);
			return trialUsage.release(device.principalId(), jobId);
		}));
		awaitLatch(locked);
		Intake intake = startIntake(device, key(), body("NEW_PHOTOS", photo));
		// The intake holds the usage allocation and has not locked the job.
		awaitLatch(hold.reached());
		assertThat(hold.result().get())
			.isEqualTo(new Reservation.AlreadyReserved(Map.of(serverPhotoId(device, photo), jobId)));
		proceed.countDown();
		awaitWaitIn(transitionPid.get(WAIT.toSeconds(), SECONDS), USAGE_LOCK);
		hold.release().countDown();
		return new LateReference(device, photo, jobId, intake.response(), transition, itemFailure);
	}

	/**
	 * Ends the job as a stage 5-2 transition does, in the caller's transaction: the job first, then its release.
	 * @return the photos released
	 */
	private int endJob(Installed device, UUID jobId) {
		lockJob(jobId, FOR_NO_KEY_UPDATE);
		jdbc.sql("""
				UPDATE analysis_job SET status = 'CANCELLED', updated_at = now(), version = version + 1 WHERE id = :id
				""").param("id", jobId).update();
		return trialUsage.release(device.principalId(), jobId);
	}

	private void assertCandidateAgain(Installed device, JsonNode response, Photo photo, UUID endedJobId) {
		assertThat(item(response, photo).get("admissionResult").asString()).isEqualTo("ACCEPTED");
		UUID newJobId = jobOf(response, photo);
		assertThat(newJobId).isNotEqualTo(endedJobId);
		assertThat(jdbc.sql("SELECT status FROM analysis_job WHERE id = :id")
			.param("id", endedJobId)
			.query(String.class)
			.single()).isEqualTo("CANCELLED");
		assertOneLiveJobAndReservationEach(device, photo);
		assertThat(jdbc.sql("SELECT job_id FROM usage_reservation WHERE photo_id = :photo AND status <> 'RELEASED'")
			.param("photo", serverPhotoId(device, photo))
			.query(UUID.class)
			.single()).isEqualTo(newJobId);
	}

	private void assertStoredOnce(Installed device, int photos) {
		assertThat(count("analysis_request", "owner_id", device)).isOne();
		assertThat(count("analysis_job", "owner_id", device)).isEqualTo(photos);
		assertThat(count("usage_reservation", "principal_id", device)).isEqualTo(photos);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM idempotency_record WHERE owner_id = :owner AND operation = :operation
				""")
			.param("owner", device.principalId())
			.param("operation", AnalysisIntake.OPERATION)
			.query(Long.class)
			.single()).isOne();
	}

	private void lockJob(UUID jobId, String lock) {
		jdbc.sql("SELECT id FROM analysis_job WHERE id = :id " + lock).param("id", jobId).query(UUID.class).single();
	}

	private UUID acceptedJob(Installed device, Photo photo) {
		return jobOf(response(fixtures.intake(device, key(), body("NEW_PHOTOS", photo)), 202), photo);
	}

	/**
	 * Sends the intake request on a thread of the pool; its backend is known once the business transaction starts.
	 */
	private Intake startIntake(Installed device, String key, String body) {
		CompletableFuture<Integer> pid = new CompletableFuture<>();
		Future<MvcTestResult> response = pool.submit(() -> {
			backend.set(pid);
			try {
				return fixtures.intake(device, key, body);
			}
			finally {
				backend.remove();
			}
		});
		return new Intake(response, pid);
	}

	/**
	 * Holds the principal's first reservation in its transaction, after {@link TrialUsage#reserve} took the usage
	 * allocation lock, until released.
	 */
	private Hold holdFirstReservation(Installed device) {
		Hold hold = new Hold(new CountDownLatch(1), new CountDownLatch(1), new AtomicReference<>());
		AtomicBoolean first = new AtomicBoolean(true);
		// Stubbed on the spy behind the transaction proxy, whose MANDATORY propagation refuses calls outside one.
		TrialUsage spy = AopTestUtils.getUltimateTargetObject(trialUsage);
		doAnswer((invocation) -> {
			Reservation result = (Reservation) invocation.callRealMethod();
			if (first.compareAndSet(true, false)) {
				hold.result().set(result);
				hold.reached().countDown();
				awaitLatch(hold.release());
			}
			return result;
		}).when(spy).reserve(eq(device.principalId()), any(), any(), anyList());
		return hold;
	}

	/**
	 * Runs the tasks together and waits for each of them.
	 */
	private <T> List<T> parallel(List<Callable<T>> tasks) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<T>> futures = new ArrayList<>();
		for (Callable<T> task : tasks) {
			futures.add(pool.submit(() -> {
				awaitLatch(start);
				return task.call();
			}));
		}
		start.countDown();
		List<T> results = new ArrayList<>();
		for (Future<T> future : futures) {
			results.add(future.get(WAIT.toSeconds(), SECONDS));
		}
		return results;
	}

	private void assertOneLiveJobAndReservationEach(Installed device, Photo... photos) {
		List<UUID> refs = Stream.of(photos).map(Photo::photoId).toList();
		assertThat(jdbc.sql("""
				SELECT p.client_ref,
				       (SELECT count(*) FROM analysis_job j
				        WHERE j.owner_id = p.owner_id AND j.photo_id = p.id
				          AND j.status NOT IN ('FAILED', 'CANCELLED', 'INELIGIBLE')) AS live_jobs,
				       (SELECT count(*) FROM usage_reservation r
				        WHERE r.principal_id = p.owner_id AND r.photo_id = p.id
				          AND r.status <> 'RELEASED') AS reservations
				FROM photo p WHERE p.owner_id = :owner AND p.client_ref IN (:refs)
				""")
			.param("owner", device.principalId())
			.param("refs", refs)
			.query((rs, rowNum) -> List.of(rs.getLong("live_jobs"), rs.getLong("reservations")))
			.list()).hasSize(photos.length).containsOnly(List.of(1L, 1L));
	}

	private UUID serverPhotoId(Installed device, Photo photo) {
		return jdbc.sql("SELECT id FROM photo WHERE owner_id = :owner AND client_ref = :ref")
			.param("owner", device.principalId())
			.param("ref", photo.photoId())
			.query(UUID.class)
			.single();
	}

	private List<Object> latestConsent(Installed device) {
		return jdbc.sql("""
				SELECT version, decision FROM ai_consent WHERE principal_id = :id ORDER BY version DESC LIMIT 1
				""")
			.param("id", device.principalId())
			.query((rs, rowNum) -> List.<Object>of(rs.getLong("version"), rs.getString("decision")))
			.single();
	}

	private long count(String table, String ownerColumn, Installed device) {
		return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + ownerColumn + " = :owner")
			.param("owner", device.principalId())
			.query(Long.class)
			.single();
	}

	// In the caller's transaction, if any: the backend that runs it.
	private int backendPid() {
		return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
	}

	/**
	 * Waits until the backend waits for a lock in a statement that contains the text.
	 */
	private void awaitWaitIn(int pid, String text) {
		await("backend " + pid + " waits for a lock in " + text).atMost(WAIT)
			.pollInterval(Duration.ofMillis(10))
			.until(() -> jdbc.sql("""
					SELECT count(*) > 0 FROM pg_stat_activity
					WHERE pid = :pid AND wait_event_type = 'Lock' AND position(:text IN query) > 0
					""").param("pid", pid).param("text", text).query(Boolean.class).single());
	}

	/**
	 * Waits until a backend waits, for a lock the given backend holds, in a statement that contains the text.
	 */
	private void awaitBlockedBy(int holder, String text) {
		await("a backend waits for backend " + holder + " in " + text).atMost(WAIT)
			.pollInterval(Duration.ofMillis(10))
			.until(() -> jdbc.sql("""
					SELECT count(*) > 0 FROM pg_stat_activity
					WHERE wait_event_type = 'Lock' AND :holder = ANY (pg_blocking_pids(pid))
					  AND position(:text IN query) > 0
					""").param("holder", holder).param("text", text).query(Boolean.class).single());
	}

	private static JsonNode response(Future<MvcTestResult> future, int status) throws Exception {
		return response(future.get(WAIT.toSeconds(), SECONDS), status);
	}

	private static JsonNode response(MvcTestResult result, int status) {
		assertThat(result).hasStatus(status);
		return json(result);
	}

	private static UUID jobOf(JsonNode response, Photo photo) {
		return jobOf(response, photo.photoId());
	}

	private static UUID jobOf(JsonNode response, UUID photoId) {
		return UUID.fromString(item(response, photoId).get("analysisJobId").asString());
	}

	private static @Nullable String sqlState(@Nullable Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql) {
				return sql.getSQLState();
			}
		}
		return null;
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			assertThat(latch.await(WAIT.toSeconds(), SECONDS)).isTrue();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * An intake request sent by {@link #startIntake}.
	 *
	 * @param backend completed with the backend of its business transaction when 5a starts
	 */
	private record Intake(Future<MvcTestResult> response, CompletableFuture<Integer> backend) {

		int pid() throws Exception {
			return backend.get(WAIT.toSeconds(), SECONDS);
		}

	}

	/**
	 * @param reached counted down when the reservation holds the usage allocation lock
	 * @param release lets the reservation return
	 * @param result what the reservation answered
	 */
	private record Hold(CountDownLatch reached, CountDownLatch release, AtomicReference<@Nullable Reservation> result) {

	}

	/**
	 * @param itemFailure what the intake's insert of its items threw, the FK check that waits for the job included
	 */
	private record LateReference(Installed device, Photo photo, UUID jobId, Future<MvcTestResult> intake,
			Future<Integer> transition, AtomicReference<@Nullable Throwable> itemFailure) {

	}

}
