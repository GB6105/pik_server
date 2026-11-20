package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.IntakeFixtures.body;
import static io.github.gb6105.pik.analysis.IntakeFixtures.key;
import static io.github.gb6105.pik.analysis.IntakeFixtures.photo;
import static io.github.gb6105.pik.analysis.RunFixtures.TOKENS;
import static io.github.gb6105.pik.analysis.RunFixtures.output;
import static io.github.gb6105.pik.analysis.RunFixtures.utc;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import io.github.gb6105.pik.analysis.IntakeFixtures.Installed;
import io.github.gb6105.pik.analysis.JobClaims.ClaimedJob;
import io.github.gb6105.pik.analysis.ResultValidator.Outcome;
import io.github.gb6105.pik.analysis.RunCompletion.Completed;
import io.github.gb6105.pik.analysis.RunRecords.CallRow;
import io.github.gb6105.pik.analysis.RunRecords.RunBasis;
import io.github.gb6105.pik.analysis.RunTestConfiguration.CompletedEvents;
import io.github.gb6105.pik.analysis.RunTestConfiguration.ScriptedModel;
import io.github.gb6105.pik.analysis.SendPermits.Permit;
import io.github.gb6105.pik.shared.change.ChangeSequence;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The races of the batches around the run, in orders fixed with latches and with transactions that hold a lock the
 * next step needs (plan 11.3 C-11, C-17): the completion against the lease recovery, the transitions of stage 5-2
 * that take the usage allocation against intake, and the monitor's budget refresh against a permit. Also the call's
 * compare-and-set as the last guard against a double settlement, and a permit denial whose reason went away before
 * the ending.
 */
@AnalysisRunTest
class AnalysisRecoveryConcurrencyTests {

	private static final Duration WAIT = Duration.ofSeconds(30);

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JobClaims claims;

	@Autowired
	RunRecords runs;

	@Autowired
	CallManifests manifests;

	@Autowired
	SendPermits permits;

	@Autowired
	CallSettlements calls;

	@Autowired
	RunCompletion completion;

	@Autowired
	LeaseRecovery recovery;

	@Autowired
	GateMonitor monitor;

	@Autowired
	ScriptedModel model;

	@Autowired
	CompletedEvents events;

	@Autowired
	AnalysisProperties properties;

	@MockitoSpyBean
	InputStore store;

	@MockitoSpyBean
	ChangeSequence changes;

	RunFixtures fixtures;

	ExecutorService pool;

	@BeforeEach
	void setUp() {
		fixtures = new RunFixtures(mvc, jdbc);
		fixtures.isolate();
		model.reset();
		events.clear();
		pool = Executors.newCachedThreadPool();
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		pool.shutdownNow();
		assertThat(pool.awaitTermination(WAIT.toSeconds(), SECONDS)).isTrue();
	}

	// C-11: the completion commits first; the lease recovery then finds the run gone and changes nothing.
	@Test
	void completionThatCommitsFirstWinsOverTheLeaseRecovery() throws Exception {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		Permitted permitted = permitted(claimOne());
		expireLease(jobId);
		CountDownLatch storing = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger completing = new AtomicInteger();
		pauseFirstChangeBlock(completing, storing, release);
		Future<Completed> completed = pool.submit(() -> complete(permitted));
		awaitLatch(storing);
		Future<LeaseRecovery.Outcome> recovered = pool
			.submit(() -> recovery.recover(jobId, permitted.claimed().runToken()));
		awaitBlockedBy(completing.get(), "analysis_job");

		release.countDown();

		assertThat(completed.get(WAIT.toSeconds(), SECONDS)).isEqualTo(Completed.STORED);
		assertThat(recovered.get(WAIT.toSeconds(), SECONDS)).isEqualTo(LeaseRecovery.Outcome.CHANGED);
		long cost = CallOutcome.costMicros(TOKENS, manifests.current().settings().price());
		assertThat(fixtures.job(jobId).get("status")).isEqualTo("SUCCEEDED");
		assertThat(fixtures.call(jobId)).containsEntry("status", "SUCCEEDED")
			.containsEntry("cost_state", "SETTLED")
			.containsEntry("settled_cost_micros", cost);
		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("reserved_micros", 0L)
			.containsEntry("settled_micros", cost);
		assertThat(fixtures.results(jobId)).hasSize(1);
		assertThat(events.events()).hasSize(1);
	}

	// C-11: the lease recovery commits first; the completion then stores nothing and only adjusts the call's cost.
	@Test
	void leaseRecoveryThatCommitsFirstLeavesTheCompletionOnlyTheCallCost() throws Exception {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		Permitted permitted = permitted(claimOne());
		expireLease(jobId);
		Blocker spend = hold("SELECT day FROM ai_spend_day WHERE day = :key FOR UPDATE", BudgetDays.of(Instant.now()));
		Future<LeaseRecovery.Outcome> recovered = pool
			.submit(() -> recovery.recover(jobId, permitted.claimed().runToken()));
		int recovering = awaitWaiter(spend.pid(), "ai_spend_day");
		Future<Completed> completed = pool.submit(() -> complete(permitted));
		awaitBlockedBy(recovering, "analysis_job");

		spend.release();

		assertThat(recovered.get(WAIT.toSeconds(), SECONDS)).isEqualTo(LeaseRecovery.Outcome.RETRIED);
		assertThat(completed.get(WAIT.toSeconds(), SECONDS)).isEqualTo(Completed.SETTLED_BEFORE);
		long cost = CallOutcome.costMicros(TOKENS, manifests.current().settings().price());
		assertThat(fixtures.job(jobId).get("status")).isEqualTo("READY");
		assertThat(fixtures.call(jobId)).containsEntry("status", "UNKNOWN")
			.containsEntry("cost_state", "ADJUSTED")
			.containsEntry("settled_cost_micros", cost);
		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("reserved_micros", 0L)
			.containsEntry("settled_micros", cost);
		assertThat(fixtures.results(jobId)).isEmpty();
		assertThat(events.events()).isEmpty();
		assertThat(fixtures.reservations(jobId)).containsOnly("RESERVED");
	}

	// C-17: the ending of a permit denial holds its job while intake points at it; intake waits, then both commit.
	@Test
	void permitEndingAheadOfIntakeDoesNotDeadlock() throws Exception {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		ClaimedJob claimed = claimOne();
		spendPhoto(device, jobId);
		Blocker attempts = hold("SELECT id FROM analysis_upload_attempt WHERE job_id = :key FOR UPDATE", jobId);
		Future<Permit> permit = pool.submit(() -> permit(claimed));
		int ending = awaitWaiter(attempts.pid(), "analysis_upload_attempt");
		Future<MvcTestResult> intake = intakeAgain(device, jobId);
		awaitBlockedBy(ending, "");

		attempts.release();

		assertThat(permit.get(WAIT.toSeconds(), SECONDS)).isEqualTo(new Permit.Ended(FailureCode.ATTEMPTS_EXHAUSTED));
		assertThat(intake.get(WAIT.toSeconds(), SECONDS)).hasStatus(202);
		assertThat(fixtures.job(jobId).get("failure_code")).isEqualTo("ATTEMPTS_EXHAUSTED");
	}

	// C-17: intake holds the photo and the job and waits for the device mark; the permit and its ending wait for it.
	@Test
	void intakeAheadOfAPermitEndingDoesNotDeadlock() throws Exception {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		ClaimedJob claimed = claimOne();
		spendPhoto(device, jobId);
		Blocker mark = hold("SELECT id FROM device_trial_mark WHERE id = :key FOR UPDATE", device.trialMarkId());
		Future<MvcTestResult> intake = intakeAgain(device, jobId);
		int intaking = awaitWaiter(mark.pid(), "device_trial_mark");
		Future<Permit> permit = pool.submit(() -> permit(claimed));
		awaitBlockedBy(intaking, "");

		mark.release();

		assertThat(intake.get(WAIT.toSeconds(), SECONDS)).hasStatus(202);
		assertThat(permit.get(WAIT.toSeconds(), SECONDS)).isEqualTo(new Permit.Ended(FailureCode.ATTEMPTS_EXHAUSTED));
		assertThat(fixtures.job(jobId).get("failure_code")).isEqualTo("ATTEMPTS_EXHAUSTED");
	}

	// C-17: the unknown confirmation that ends the job and releases its usage, ahead of intake.
	@Test
	void unknownConfirmationAheadOfIntakeDoesNotDeadlock() throws Exception {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		Permitted permitted = unknownRetryUsed(jobId);
		Blocker attempts = hold("SELECT id FROM analysis_upload_attempt WHERE job_id = :key FOR UPDATE", jobId);
		Future<LeaseRecovery.Outcome> recovered = pool
			.submit(() -> recovery.recover(jobId, permitted.claimed().runToken()));
		int recovering = awaitWaiter(attempts.pid(), "analysis_upload_attempt");
		Future<MvcTestResult> intake = intakeAgain(device, jobId);
		awaitBlockedBy(recovering, "");

		attempts.release();

		assertThat(recovered.get(WAIT.toSeconds(), SECONDS)).isEqualTo(LeaseRecovery.Outcome.ENDED);
		assertThat(intake.get(WAIT.toSeconds(), SECONDS)).hasStatus(202);
		assertThat(fixtures.job(jobId).get("failure_code")).isEqualTo("UNKNOWN_OUTCOME");
		assertThat(fixtures.reservations(jobId)).containsOnly("RELEASED");
	}

	// C-17: intake ahead of the unknown confirmation that ends the job.
	@Test
	void intakeAheadOfAnUnknownConfirmationDoesNotDeadlock() throws Exception {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		Permitted permitted = unknownRetryUsed(jobId);
		Blocker mark = hold("SELECT id FROM device_trial_mark WHERE id = :key FOR UPDATE", device.trialMarkId());
		Future<MvcTestResult> intake = intakeAgain(device, jobId);
		int intaking = awaitWaiter(mark.pid(), "device_trial_mark");
		Future<LeaseRecovery.Outcome> recovered = pool
			.submit(() -> recovery.recover(jobId, permitted.claimed().runToken()));
		awaitBlockedBy(intaking, "");

		mark.release();

		assertThat(intake.get(WAIT.toSeconds(), SECONDS)).hasStatus(202);
		assertThat(recovered.get(WAIT.toSeconds(), SECONDS)).isEqualTo(LeaseRecovery.Outcome.ENDED);
		assertThat(fixtures.job(jobId).get("failure_code")).isEqualTo("UNKNOWN_OUTCOME");
	}

	// C-17: the completion holds every lock up to the change counter while intake points at its job.
	@Test
	void completionAheadOfIntakeDoesNotDeadlock() throws Exception {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		Permitted permitted = permitted(claimOne());
		CountDownLatch storing = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger completing = new AtomicInteger();
		pauseFirstChangeBlock(completing, storing, release);
		Future<Completed> completed = pool.submit(() -> complete(permitted));
		awaitLatch(storing);
		Future<MvcTestResult> intake = intakeAgain(device, jobId);
		awaitBlockedBy(completing.get(), "");

		release.countDown();

		assertThat(completed.get(WAIT.toSeconds(), SECONDS)).isEqualTo(Completed.STORED);
		assertThat(intake.get(WAIT.toSeconds(), SECONDS)).hasStatus(202);
	}

	// C-17: intake ahead of the completion.
	@Test
	void intakeAheadOfTheCompletionDoesNotDeadlock() throws Exception {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		Permitted permitted = permitted(claimOne());
		Blocker mark = hold("SELECT id FROM device_trial_mark WHERE id = :key FOR UPDATE", device.trialMarkId());
		Future<MvcTestResult> intake = intakeAgain(device, jobId);
		int intaking = awaitWaiter(mark.pid(), "device_trial_mark");
		Future<Completed> completed = pool.submit(() -> complete(permitted));
		awaitBlockedBy(intaking, "");

		mark.release();

		assertThat(intake.get(WAIT.toSeconds(), SECONDS)).hasStatus(202);
		assertThat(completed.get(WAIT.toSeconds(), SECONDS)).isEqualTo(Completed.STORED);
		assertThat(fixtures.results(jobId)).hasSize(1);
	}

	// C-17: the monitor's budget refresh holds the day's spend while a permit wants it.
	@Test
	void budgetRefreshAheadOfAPermitDoesNotDeadlock() throws Exception {
		fixtures.ready(fixtures.intake().android(), 1);
		ClaimedJob claimed = claimOne();
		changedLimit();
		Blocker budget = hold("SELECT cause FROM ai_gate WHERE cause = :key FOR UPDATE", GateCause.BUDGET.name());
		Future<Boolean> refreshed = pool
			.submit(() -> transaction.execute((status) -> monitor.refreshBudget(Instant.now())));
		int refreshing = awaitWaiter(budget.pid(), "ai_gate");
		Future<Permit> permit = pool.submit(() -> permit(claimed));
		awaitBlockedBy(refreshing, "");

		budget.release();

		assertThat(refreshed.get(WAIT.toSeconds(), SECONDS)).isFalse();
		assertThat(permit.get(WAIT.toSeconds(), SECONDS)).isInstanceOf(Permit.Granted.class);
		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("limit_micros",
				properties.budget().dailyCostLimitMicros());
	}

	// C-17: a permit holds the day's spend and waits for the gates while the monitor refreshes the budget.
	@Test
	void permitAheadOfABudgetRefreshDoesNotDeadlock() throws Exception {
		fixtures.ready(fixtures.intake().android(), 1);
		ClaimedJob claimed = claimOne();
		changedLimit();
		Blocker budget = hold("SELECT cause FROM ai_gate WHERE cause = :key FOR UPDATE", GateCause.BUDGET.name());
		Future<Permit> permit = pool.submit(() -> permit(claimed));
		int permitting = awaitWaiter(budget.pid(), "ai_gate");
		Future<Boolean> refreshed = pool
			.submit(() -> transaction.execute((status) -> monitor.refreshBudget(Instant.now())));
		awaitBlockedBy(permitting, "");

		budget.release();

		assertThat(permit.get(WAIT.toSeconds(), SECONDS)).isInstanceOf(Permit.Granted.class);
		assertThat(refreshed.get(WAIT.toSeconds(), SECONDS)).isFalse();
		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("limit_micros",
				properties.budget().dailyCostLimitMicros());
	}

	// Follow-up of the settlement: past a status check that both passed, the call's compare-and-set settles once.
	@Test
	void callCompareAndSetSettlesOnceWhenTwoSettlementsPassedTheCheck() throws Exception {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		Permitted permitted = permitted(claimOne());
		AnalysisProperties.Manifest settings = permitted.manifest().settings();
		CallOutcome refused = CallOutcome.of(new ModelReply.Answered(200, "req_refusal", TOKENS, "refusal", null, null),
				false, settings.price(), settings.maxCostMicros(), Instant.now(), Instant.now());
		CyclicBarrier checked = new CyclicBarrier(2);
		List<Future<?>> settlements = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			settlements.add(pool.submit(() -> transaction.executeWithoutResult((status) -> {
				assertThat(runs.callStatus(permitted.call().id())).isEqualTo(CallStatus.SENDING);
				try {
					checked.await(WAIT.toSeconds(), SECONDS);
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
				calls.settle(permitted.call(), refused, false, Instant.now());
			})));
		}

		int settled = 0;
		List<Throwable> refusedTwice = new ArrayList<>();
		for (Future<?> settlement : settlements) {
			try {
				settlement.get(WAIT.toSeconds(), SECONDS);
				settled++;
			}
			catch (ExecutionException ex) {
				refusedTwice.add(ex.getCause());
			}
		}

		assertThat(settled).isEqualTo(1);
		assertThat(refusedTwice).singleElement().isInstanceOf(IllegalStateException.class);
		long cost = CallOutcome.costMicros(TOKENS, settings.price());
		assertThat(fixtures.call(jobId)).containsEntry("status", "RESPONDED_FAILURE")
			.containsEntry("settled_cost_micros", cost);
		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("reserved_micros", 0L)
			.containsEntry("settled_micros", cost);
		assertThat(fixtures.dailySends(device.principalId())).isEqualTo(1);
		assertThat(fixtures.photoBudget(fixtures.photoOf(jobId)).get("risk_sends")).isEqualTo(1);
	}

	// Follow-up of the permit: a device send released between the denial and its ending sends the job back.
	@Test
	void permitDenialWhoseReasonWentAwaySendsTheJobBack() throws Exception {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		ClaimedJob claimed = claimOne();
		jdbc.sql("UPDATE device_trial_mark SET send_count = limit_count * :multiplier WHERE id = :id")
			.param("multiplier", properties.budget().deviceSendMultiplier())
			.param("id", device.trialMarkId())
			.update();
		Blocker attempts = hold("SELECT id FROM analysis_upload_attempt WHERE job_id = :key FOR UPDATE", jobId);
		Future<Permit> permit = pool.submit(() -> permit(claimed));
		awaitWaiter(attempts.pid(), "analysis_upload_attempt");
		// The send of another job of the device was released meanwhile.
		jdbc.sql("UPDATE device_trial_mark SET send_count = send_count - 1 WHERE id = :id")
			.param("id", device.trialMarkId())
			.update();

		attempts.release();

		assertThat(permit.get(WAIT.toSeconds(), SECONDS)).isInstanceOf(Permit.Deferred.class);
		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo("READY");
		assertThat(job.get("failure_code")).isNull();
		assertThat(job.get("run_token")).isNull();
		assertThat(fixtures.reservations(jobId)).containsOnly("RESERVED");
		assertThat(fixtures.calls(jobId)).isEmpty();
		assertThat(jdbc.sql("SELECT object_state FROM analysis_upload_attempt WHERE job_id = :job")
			.param("job", jobId)
			.query(String.class)
			.list()).containsOnly("RETAINED");
	}

	private Permitted unknownRetryUsed(UUID jobId) {
		Permitted permitted = permitted(claimOne());
		jdbc.sql("UPDATE photo_ai_budget SET unknown_retry_used = true WHERE photo_id = :photo")
			.param("photo", fixtures.photoOf(jobId))
			.update();
		expireLease(jobId);
		return permitted;
	}

	// The photo has no risky send left, so the permit ends the job with ATTEMPTS_EXHAUSTED.
	private void spendPhoto(Installed device, UUID jobId) {
		jdbc.sql("""
				INSERT INTO photo_ai_budget (photo_id, owner_id, risk_sends, unknown_retry_used, input_invalid_count,
				                             updated_at, version)
				VALUES (:photo, :owner, :sends, false, 0, now(), 0)
				""")
			.param("photo", fixtures.photoOf(jobId))
			.param("owner", device.principalId())
			.param("sends", properties.budget().photoLifetimeSends())
			.update();
	}

	// Today's limit differs from the setting, and a call fits under both.
	private void changedLimit() {
		jdbc.sql("""
				INSERT INTO ai_spend_day (day, limit_micros, reserved_micros, settled_micros, updated_at)
				VALUES (:day, :limit, 0, 0, now())
				""")
			.param("day", BudgetDays.of(Instant.now()))
			.param("limit", 2 * manifests.current().settings().maxCostMicros())
			.update();
	}

	// Intake of the job's photo again, with a new photo: the photo waits for the job, the new one is reserved.
	private Future<MvcTestResult> intakeAgain(Installed device, UUID jobId) {
		UUID clientRef = jdbc.sql("SELECT representative_client_ref FROM analysis_job WHERE id = :id")
			.param("id", jobId)
			.query(UUID.class)
			.single();
		String request = body("NEW_PHOTOS", photo(clientRef, "v1"), photo("v1"));
		return pool.submit(() -> fixtures.intake().intake(device, key(), request));
	}

	private void pauseFirstChangeBlock(AtomicInteger backend, CountDownLatch paused, CountDownLatch release) {
		AtomicBoolean first = new AtomicBoolean(true);
		doAnswer((invocation) -> {
			if (first.compareAndSet(true, false)) {
				backend.set(backendPid());
				paused.countDown();
				awaitLatch(release);
			}
			return invocation.callRealMethod();
		}).when(spiedChanges()).nextBlock(any(), anyInt());
	}

	// The spy behind the transactional proxy: stubbing the proxy would run its transaction check.
	private ChangeSequence spiedChanges() {
		return AopTestUtils.getTargetObject(changes);
	}

	private ClaimedJob claimOne() {
		List<ClaimedJob> claimed = claims.claim(1);
		assertThat(claimed).hasSize(1);
		return claimed.getFirst();
	}

	private Permit permit(ClaimedJob claimed) {
		RunBasis basis = runs.findBasis(claimed.jobId()).orElseThrow();
		return permits.permit(claimed, basis, manifests.runnable(basis.manifestId()).orElseThrow());
	}

	private Permitted permitted(ClaimedJob claimed) {
		RunBasis basis = runs.findBasis(claimed.jobId()).orElseThrow();
		CallManifest manifest = manifests.runnable(basis.manifestId()).orElseThrow();
		Permit permit = permits.permit(claimed, basis, manifest);
		assertThat(permit).isInstanceOf(Permit.Granted.class);
		return new Permitted(claimed, basis, manifest, ((Permit.Granted) permit).call());
	}

	private Completed complete(Permitted permitted) {
		ModelReply.Answered reply = (ModelReply.Answered) output();
		Outcome.Usable usable = (Outcome.Usable) ResultValidator.validate(reply.output(),
				permitted.basis().referenceAt());
		AnalysisProperties.Manifest settings = permitted.manifest().settings();
		CallOutcome outcome = CallOutcome.of(reply, true, settings.price(), settings.maxCostMicros(), Instant.now(),
				Instant.now());
		return completion.complete(permitted.claimed(), permitted.basis(), permitted.manifest(), permitted.call(),
				outcome, usable);
	}

	private void expireLease(UUID jobId) {
		jdbc.sql("UPDATE analysis_job SET locked_until = :past WHERE id = :id")
			.param("past", utc(Instant.now().minusSeconds(1)))
			.param("id", jobId)
			.update();
	}

	/**
	 * Holds the rows the query locks, with {@code :key} bound to {@code key}, in a transaction until released.
	 */
	private Blocker hold(String lockSql, Object key) {
		AtomicInteger pid = new AtomicInteger();
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<?> done = pool.submit(() -> transaction.executeWithoutResult((status) -> {
			pid.set(backendPid());
			jdbc.sql(lockSql).param("key", key).query().listOfRows();
			locked.countDown();
			awaitLatch(release);
		}));
		awaitLatch(locked);
		return new Blocker(pid.get(), release, done);
	}

	// In the caller's transaction, if any: the backend that runs it.
	private int backendPid() {
		return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
	}

	/**
	 * Waits until a backend waits, for a lock the given backend holds, in a statement that contains the text.
	 * @return that backend
	 */
	private int awaitWaiter(int holder, String text) {
		AtomicInteger waiter = new AtomicInteger();
		await("a backend waits for backend " + holder + " in " + text).atMost(WAIT)
			.pollInterval(Duration.ofMillis(10))
			.until(() -> {
				List<Integer> waiting = jdbc.sql("""
						SELECT pid FROM pg_stat_activity
						WHERE wait_event_type = 'Lock' AND :holder = ANY (pg_blocking_pids(pid))
						  AND position(:text IN query) > 0
						""").param("holder", holder).param("text", text).query(Integer.class).list();
				if (waiting.isEmpty()) {
					return false;
				}
				waiter.set(waiting.getFirst());
				return true;
			});
		return waiter.get();
	}

	private void awaitBlockedBy(int holder, String text) {
		awaitWaiter(holder, text);
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

	private record Permitted(ClaimedJob claimed, RunBasis basis, CallManifest manifest, CallRow call) {

	}

	/**
	 * A transaction that holds locks until {@link #release}.
	 */
	private record Blocker(int pid, CountDownLatch latch, Future<?> done) {

		void release() throws Exception {
			latch.countDown();
			done.get(WAIT.toSeconds(), SECONDS);
		}

	}

}
