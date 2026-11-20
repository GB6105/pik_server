package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.RunFixtures.output;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import io.github.gb6105.pik.analysis.AnalysisRun.Stop;
import io.github.gb6105.pik.analysis.IntakeFixtures.Installed;
import io.github.gb6105.pik.analysis.IntakeRecords.ItemRow;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The run's races with latches and barriers (plan 11.3 C-9, C-10, C-12, C-15, C-16, C-21).
 */
@AnalysisRunTest
class AnalysisRunConcurrencyTests {

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
	JobRecords jobs;

	@Autowired
	RunRecords runs;

	@Autowired
	IntakeRecords intakeRecords;

	@Autowired
	CallManifests manifests;

	@Autowired
	SendPermits permits;

	@Autowired
	ResponseSettlement responses;

	@Autowired
	RunCompletion completion;

	@Autowired
	AnalysisRun run;

	@Autowired
	ScriptedModel model;

	@Autowired
	CompletedEvents events;

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

	// C-9: each job runs once, the principal's slots hold, and a busy principal does not hold another back.
	@Test
	void concurrentClaimsTakeEachJobOnceWithinThePrincipalSlots() throws Exception {
		Installed busy = fixtures.intake().android();
		Installed other = fixtures.intake().android();
		fixtures.ready(busy, 5);
		UUID otherJob = fixtures.ready(other, 1).job();
		CyclicBarrier start = new CyclicBarrier(4);
		List<Future<List<ClaimedJob>>> futures = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			futures.add(pool.submit(() -> {
				start.await(WAIT.toSeconds(), SECONDS);
				return claims.claim(2);
			}));
		}

		List<ClaimedJob> claimed = new ArrayList<>();
		for (Future<List<ClaimedJob>> future : futures) {
			claimed.addAll(future.get(WAIT.toSeconds(), SECONDS));
		}

		assertThat(claimed).extracting(ClaimedJob::jobId).doesNotHaveDuplicates().contains(otherJob);
		assertThat(claimed.stream().filter((job) -> job.ownerId().equals(busy.principalId()))).hasSize(2);
		assertThat(claimed).hasSize(3);
		assertThat(jdbc.sql("SELECT max(attempt_count) FROM analysis_job WHERE owner_id IN (:owners)")
			.param("owners", List.of(busy.principalId(), other.principalId()))
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	// C-9: a candidate another transaction holds is skipped without a wait, and the next one is claimed.
	@Test
	void lockedFrontCandidateIsSkipped() throws Exception {
		UUID front = fixtures.ready(fixtures.intake().android(), 1).job();
		UUID next = fixtures.ready(fixtures.intake().android(), 1).job();
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<?> holder = pool.submit(() -> transaction.executeWithoutResult((status) -> {
			jdbc.sql("SELECT id FROM analysis_job WHERE id = :id FOR NO KEY UPDATE").param("id", front).query().listOfRows();
			locked.countDown();
			awaitLatch(release);
		}));
		awaitLatch(locked);

		List<ClaimedJob> claimed = pool.submit(() -> claims.claim(1)).get(WAIT.toSeconds(), SECONDS);

		release.countDown();
		holder.get(WAIT.toSeconds(), SECONDS);
		assertThat(claimed).extracting(ClaimedJob::jobId).containsExactly(next);
		assertThat(fixtures.job(front).get("status")).isEqualTo("READY");
	}

	// C-10: while a claim holds its candidate, the FK insert of an intake item that waits for the job does not wait.
	@Test
	void claimLockLetsTheItemOfAPhotoInProgressBeInserted() throws Exception {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		UUID later = fixtures.uploads().accepted(device, 1).requestId();
		CountDownLatch claimed = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<?> claim = pool.submit(() -> transaction.executeWithoutResult((status) -> {
			jobs.lockClaims();
			assertThat(jobs.claimCandidates(1, 4, 2, List.of(), false, Instant.now()))
				.anySatisfy((candidate) -> assertThat(candidate.locked()).isTrue());
			claimed.countDown();
			awaitLatch(release);
		}));
		awaitLatch(claimed);
		ItemRow waiting = new ItemRow(later, fixtures.photoOf(jobId), device.principalId(), UUID.randomUUID(), "v1", 0,
				null, TextStatus.UNKNOWN, null, AdmissionResult.IN_PROGRESS, null, null, false, null, null, jobId, null,
				null, null, null, null, Instant.now());

		pool.submit(() -> transaction.executeWithoutResult((status) -> intakeRecords.insertItems(List.of(waiting))))
			.get(10, SECONDS);

		release.countDown();
		claim.get(WAIT.toSeconds(), SECONDS);
		assertThat(jdbc.sql("SELECT count(*) FROM analysis_request_item WHERE analysis_job_id = :job")
			.param("job", jobId)
			.query(Long.class)
			.single()).isEqualTo(2);
	}

	// C-12: a run permitted twice has one call and sends once.
	@Test
	void permitAgainForTheSameRunReturnsTheCallWithoutASend() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		ClaimedJob claimed = claimOne();
		permitted(claimed);

		assertThat(run.run(claimed)).isEqualTo(Stop.PERMITTED_BEFORE);

		assertThat(fixtures.calls(jobId)).hasSize(1);
		assertThat(model.calls()).isZero();
		assertThat(fixtures.dailySends(device.principalId())).isEqualTo(1);
		assertThat(fixtures.deviceSends(device.trialMarkId())).isEqualTo(1);
	}

	// C-15: concurrent permits never reserve past the daily cost limit.
	@Test
	void concurrentPermitsKeepTheDailyCostWithinItsLimit() throws Exception {
		fixtures.ready(fixtures.intake().android(), 2);
		fixtures.ready(fixtures.intake().android(), 2);
		List<ClaimedJob> claimed = claims.claim(4);
		assertThat(claimed).hasSize(4);
		long maxCost = manifests.current().settings().maxCostMicros();
		long limit = 2 * maxCost + maxCost / 2;
		jdbc.sql("""
				INSERT INTO ai_spend_day (day, limit_micros, reserved_micros, settled_micros, updated_at)
				VALUES (:day, :limit, 0, 0, now())
				""").param("day", BudgetDays.of(Instant.now())).param("limit", limit).update();
		CyclicBarrier start = new CyclicBarrier(claimed.size());
		List<Future<Permit>> futures = new ArrayList<>();
		for (ClaimedJob job : claimed) {
			RunBasis basis = runs.findBasis(job.jobId()).orElseThrow();
			CallManifest manifest = manifests.runnable(basis.manifestId()).orElseThrow();
			futures.add(pool.submit(() -> {
				start.await(WAIT.toSeconds(), SECONDS);
				return permits.permit(job, basis, manifest);
			}));
		}

		List<Permit> decided = new ArrayList<>();
		for (Future<Permit> future : futures) {
			decided.add(future.get(WAIT.toSeconds(), SECONDS));
		}

		assertThat(decided.stream().filter(Permit.Granted.class::isInstance)).hasSize(2);
		assertThat(decided.stream().filter(Permit.Deferred.class::isInstance)).hasSize(2);
		Map<String, Object> spend = fixtures.spendDay().orElseThrow();
		assertThat((Long) spend.get("reserved_micros") + (Long) spend.get("settled_micros")).isLessThanOrEqualTo(limit);
		assertThat(spend.get("reserved_micros")).isEqualTo(2 * maxCost);
		assertThat(fixtures.gate(GateCause.BUDGET).get("state")).isEqualTo("OPEN");
	}

	// C-16: a settlement repeated for the same call changes nothing the second time.
	@Test
	void settlementRepeatedForACallChangesNothing() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		Permitted permitted = permitted(claimOne());
		AnalysisProperties.Manifest settings = permitted.manifest().settings();
		CallOutcome unknown = CallOutcome.of(new ModelReply.NoAnswer(), false, settings.price(),
				settings.maxCostMicros(), Instant.now(), Instant.now());

		assertThat(responses.settle(permitted.claimed(), permitted.call(), unknown))
			.isEqualTo(ResponseSettlement.Settled.DEFERRED);
		assertThat(responses.settle(permitted.claimed(), permitted.call(), unknown))
			.isEqualTo(ResponseSettlement.Settled.SETTLED_BEFORE);

		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("settled_micros", settings.maxCostMicros())
			.containsEntry("reserved_micros", 0L);
		assertThat(fixtures.photoBudget(fixtures.photoOf(jobId)).get("risk_sends")).isEqualTo(1);
		assertThat(fixtures.dailySends(device.principalId())).isEqualTo(1);
		assertThat(fixtures.gate(GateCause.TRANSIENT_CIRCUIT).get("failure_streak")).isEqualTo(1);
	}

	// C-16: a completion that failed on a transient database error is retried and stores once.
	@Test
	void completionIsRetriedAfterATransientFailure() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		doThrow(new CannotAcquireLockException("lock timeout")).doCallRealMethod()
			.when(spiedChanges())
			.nextBlock(any(), anyInt());
		model.reply(output());

		assertThat(run.run(claimOne())).isEqualTo(Stop.STORED);

		assertThat(fixtures.results(jobId)).hasSize(1);
		assertThat(fixtures.call(jobId).get("status")).isEqualTo("SUCCEEDED");
		assertThat(fixtures.dailySends(device.principalId())).isEqualTo(1);
		assertThat(fixtures.reservations(jobId)).containsOnly("CONSUMED");
		assertThat(events.events()).hasSize(1);
	}

	// C-16: a completion retried after another one committed settles nothing.
	@Test
	void completionAfterACommittedOneChangesNothing() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		Permitted permitted = permitted(claimOne());
		assertThat(complete(permitted)).isEqualTo(Completed.STORED);
		Map<String, Object> spend = fixtures.spendDay().orElseThrow();

		assertThat(complete(permitted)).isEqualTo(Completed.SETTLED_BEFORE);

		assertThat(fixtures.results(jobId)).hasSize(1);
		assertThat(fixtures.spendDay().orElseThrow().get("settled_micros")).isEqualTo(spend.get("settled_micros"));
		assertThat(events.events()).hasSize(1);
	}

	// C-21: a withdrawal that holds the consent lock first leaves the completion nothing to store.
	@Test
	void withdrawalBeforeTheCompletionLeavesNoResult() throws Exception {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		Permitted permitted = permitted(claimOne());
		CountDownLatch withdrawn = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger withdrawal = new AtomicInteger();
		Future<?> withdraw = pool.submit(() -> transaction.executeWithoutResult((status) -> {
			withdrawal.set(backendPid());
			lockConsent(device.principalId());
			fixtures.refuse(device.principalId());
			withdrawn.countDown();
			awaitLatch(release);
		}));
		awaitLatch(withdrawn);
		Future<Completed> completed = pool.submit(() -> complete(permitted));
		awaitBlockedBy(withdrawal.get(), "consent:");

		release.countDown();
		withdraw.get(WAIT.toSeconds(), SECONDS);

		assertThat(completed.get(WAIT.toSeconds(), SECONDS)).isEqualTo(Completed.DISCARDED);
		assertThat(fixtures.job(jobId).get("failure_code")).isEqualTo("CONSENT_WITHDRAWN");
		assertThat(fixtures.results(jobId)).isEmpty();
		assertThat(events.events()).isEmpty();
	}

	// C-21: a completion that holds the consent lock first makes the withdrawal wait until it committed.
	@Test
	void withdrawalDuringTheCompletionWaitsForIt() throws Exception {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		Permitted permitted = permitted(claimOne());
		CountDownLatch storing = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger completing = new AtomicInteger();
		doAnswer((invocation) -> {
			completing.set(backendPid());
			storing.countDown();
			awaitLatch(release);
			return invocation.callRealMethod();
		}).when(spiedChanges()).nextBlock(any(), anyInt());
		Future<Completed> completed = pool.submit(() -> complete(permitted));
		awaitLatch(storing);
		Future<?> withdraw = pool.submit(() -> transaction.executeWithoutResult((status) -> {
			lockConsent(device.principalId());
			fixtures.refuse(device.principalId());
		}));
		awaitBlockedBy(completing.get(), "consent:");
		assertThat(withdraw.isDone()).isFalse();

		release.countDown();

		assertThat(completed.get(WAIT.toSeconds(), SECONDS)).isEqualTo(Completed.STORED);
		withdraw.get(WAIT.toSeconds(), SECONDS);
		assertThat(fixtures.results(jobId)).hasSize(1);
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

	private Permitted permitted(ClaimedJob claimed) {
		RunBasis basis = runs.findBasis(claimed.jobId()).orElseThrow();
		CallManifest manifest = manifests.runnable(basis.manifestId()).orElseThrow();
		Permit permit = permits.permit(claimed, basis, manifest);
		assertThat(permit).isInstanceOf(Permit.Granted.class);
		assertThat(permits.permit(claimed, basis, manifest)).isInstanceOf(Permit.Existing.class);
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

	private void lockConsent(UUID principalId) {
		jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('consent:' || CAST(:principal AS text), 0))")
			.param("principal", principalId)
			.query(Integer.class)
			.single();
	}

	// In the caller's transaction, if any: the backend that runs it.
	private int backendPid() {
		return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
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

}
