package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.RunFixtures.TOKENS;
import static io.github.gb6105.pik.analysis.RunFixtures.instant;
import static io.github.gb6105.pik.analysis.RunFixtures.output;
import static io.github.gb6105.pik.analysis.RunFixtures.utc;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

import io.github.gb6105.pik.analysis.AiGates.Admission;
import io.github.gb6105.pik.analysis.AiGates.Gate;
import io.github.gb6105.pik.analysis.AnalysisRun.Stop;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The batches around the run against PostgreSQL and S3Mock with a scripted model (plan 5.8 to 5.10, 7.2, 7.3): the
 * lease recovery, a late answer to a call confirmed unknown, the processing deadline, the circuit and budget monitor
 * (C-18 in part), the probe's generation check, and the worker's poll loop.
 */
@AnalysisRunTest
@ExtendWith(OutputCaptureExtension.class)
class AnalysisRecoveryTests {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static final ModelReply.Usage MORE_TOKENS = new ModelReply.Usage(3000, 600, 0, 0);

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JobClaims claims;

	@Autowired
	AnalysisRun run;

	@Autowired
	AnalysisWorker worker;

	@Autowired
	RunRecords runs;

	@Autowired
	CallManifests manifests;

	@Autowired
	SendPermits permits;

	@Autowired
	ResponseSettlement responses;

	@Autowired
	RunCompletion completion;

	@Autowired
	LeaseRecovery recovery;

	@Autowired
	ProcessingDeadlines deadlines;

	@Autowired
	GateMonitor monitor;

	@Autowired
	AiGates gates;

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

	@BeforeEach
	void setUp() {
		fixtures = new RunFixtures(mvc, jdbc);
		fixtures.isolate();
		model.reset();
		events.clear();
	}

	// Plan 5.8: a run that died before its permit gives the job back.
	@Test
	void leaseRecoverySendsARunWithoutACallBack() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		ClaimedJob claimed = claimOne();
		expireLease(jobId);

		recovery.recover();

		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo("READY");
		assertThat(job.get("run_token")).isNull();
		assertThat(job.get("locked_until")).isNull();
		assertThat(instant(job.get("next_attempt_at"))).isAfter(Instant.now());
		assertThat(job.get("probe_excluded_until")).isNull();
		assertThat(fixtures.calls(jobId)).isEmpty();
		assertThat(fixtures.reservations(jobId)).containsOnly("RESERVED");
		assertThat(recovery.recover(jobId, claimed.runToken())).isEqualTo(LeaseRecovery.Outcome.CHANGED);
	}

	// Plan 5.8, 5.9: a call in flight is confirmed unknown, the photo gets its one call again, then UNKNOWN_OUTCOME.
	@Test
	void leaseRecoveryConfirmsTheCallInFlightUnknownOnceThenEndsTheJob() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		UUID photoId = fixtures.photoOf(jobId);
		Permitted first = permitted(claimOne());
		long maxCost = first.call().reservedCostMicros();
		expireLease(jobId);

		assertThat(recovery.recover(jobId, first.claimed().runToken())).isEqualTo(LeaseRecovery.Outcome.RETRIED);

		Map<String, Object> call = fixtures.call(jobId);
		assertThat(call.get("status")).isEqualTo("UNKNOWN");
		assertThat(call.get("outcome")).isEqualTo("UNKNOWN");
		assertThat(call.get("settled_cost_micros")).isEqualTo(maxCost);
		assertThat(call.get("send_state")).isEqualTo("SPENT");
		assertThat(fixtures.job(jobId).get("status")).isEqualTo("READY");
		assertThat(fixtures.photoBudget(photoId).get("unknown_retry_used")).isEqualTo(true);
		assertThat(fixtures.reservations(jobId)).containsOnly("RESERVED");
		dueNow(jobId);
		Permitted second = permitted(claimOne());
		expireLease(jobId);

		recovery.recover();

		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo("FAILED");
		assertThat(job.get("failure_code")).isEqualTo("UNKNOWN_OUTCOME");
		assertThat(job.get("run_token")).isNull();
		assertThat(fixtures.calls(jobId)).hasSize(2).allSatisfy((row) -> assertThat(row.get("status")).isEqualTo("UNKNOWN"));
		assertThat(fixtures.photoBudget(photoId).get("risk_sends")).isEqualTo(2);
		assertThat(fixtures.dailySends(device.principalId())).isEqualTo(2);
		assertThat(fixtures.reservations(jobId)).containsOnly("RELEASED");
		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("reserved_micros", 0L)
			.containsEntry("settled_micros", maxCost + second.call().reservedCostMicros());
	}

	// Plan 5.8: the batch decides again under the job's lock; a run that is no longer the one it read is left alone.
	@Test
	void leaseRecoverySkipsAJobAnotherRunHoldsOrWhoseLeaseIsNotOver() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		ClaimedJob claimed = claimOne();

		assertThat(recovery.recover(jobId, claimed.runToken())).isEqualTo(LeaseRecovery.Outcome.CHANGED);
		assertThat(fixtures.job(jobId).get("run_token")).isEqualTo(claimed.runToken());

		UUID newer = UUID.randomUUID();
		jdbc.sql("UPDATE analysis_job SET run_token = :token, locked_until = :past WHERE id = :id")
			.param("token", newer)
			.param("past", utc(Instant.now().minusSeconds(1)))
			.param("id", jobId)
			.update();

		assertThat(recovery.recover(jobId, claimed.runToken())).isEqualTo(LeaseRecovery.Outcome.CHANGED);

		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo("RUNNING");
		assertThat(job.get("run_token")).isEqualTo(newer);
	}

	// Plan 5.8, 5.10: a probe that died without a send is cleared, and the job is kept out of probes for a while.
	@Test
	void leaseRecoveryClearsTheProbeOfTheRun() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		openTransient(Instant.now().minusSeconds(1));
		ClaimedJob probe = claimOne();
		assertThat(probe.probe()).isTrue();
		assertThat(fixtures.gate(GateCause.TRANSIENT_CIRCUIT).get("probe_job_id")).isEqualTo(jobId);
		expireLease(jobId);

		assertThat(recovery.recover(jobId, probe.runToken())).isEqualTo(LeaseRecovery.Outcome.DEFERRED);

		Map<String, Object> gate = fixtures.gate(GateCause.TRANSIENT_CIRCUIT);
		assertThat(gate.get("state")).isEqualTo("HALF_OPEN");
		assertThat(gate.get("probe_job_id")).isNull();
		assertThat(gate.get("probe_run_token")).isNull();
		assertThat(fixtures.job(jobId).get("probe_excluded_until")).isNotNull();
	}

	// Follow-up of the worker: a completion that keeps failing leaves the job and its call to the lease recovery.
	@Test
	void completionThatRanOutOfRetriesIsSettledByTheLeaseRecovery() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		doThrow(new CannotAcquireLockException("lock timeout")).when(spiedChanges()).nextBlock(any(), anyInt());
		model.reply(output());
		ClaimedJob claimed = claimOne();

		assertThat(run.run(claimed)).isEqualTo(Stop.LEFT);

		assertThat(fixtures.job(jobId).get("status")).isEqualTo("RUNNING");
		assertThat(fixtures.call(jobId).get("status")).isEqualTo("SENDING");
		expireLease(jobId);

		recovery.recover();

		Map<String, Object> call = fixtures.call(jobId);
		assertThat(call.get("status")).isEqualTo("UNKNOWN");
		assertThat(call.get("settled_cost_micros")).isEqualTo(manifests.current().settings().maxCostMicros());
		assertThat(fixtures.job(jobId).get("status")).isEqualTo("READY");
		assertThat(fixtures.job(jobId).get("run_token")).isNull();
		assertThat(fixtures.results(jobId)).isEmpty();
		assertThat(events.events()).isEmpty();
	}

	// Plan 5.9: the late answer adjusts the cost once and nothing else; a second late answer changes nothing.
	@Test
	void lateAnswerAdjustsTheUnknownCallOnce() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		UUID photoId = fixtures.photoOf(jobId);
		Permitted permitted = permitted(claimOne());
		expireLease(jobId);
		assertThat(recovery.recover(jobId, permitted.claimed().runToken())).isEqualTo(LeaseRecovery.Outcome.RETRIED);
		Map<String, Object> gate = fixtures.gate(GateCause.TRANSIENT_CIRCUIT);
		long cost = CallOutcome.costMicros(TOKENS, manifests.current().settings().price());

		assertThat(responses.settle(permitted.claimed(), permitted.call(), refusal(permitted, TOKENS)))
			.isEqualTo(ResponseSettlement.Settled.SETTLED_BEFORE);

		Map<String, Object> call = fixtures.call(jobId);
		assertThat(call.get("status")).isEqualTo("UNKNOWN");
		assertThat(call.get("cost_state")).isEqualTo("ADJUSTED");
		assertThat(call.get("settled_cost_micros")).isEqualTo(cost);
		assertThat(call.get("input_tokens")).isEqualTo(TOKENS.inputTokens());
		assertThat(call.get("send_state")).isEqualTo("SPENT");
		assertThat(call.get("responded_at")).isNull();
		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("reserved_micros", 0L)
			.containsEntry("settled_micros", cost);
		assertLateAnswerChangedNothingElse(jobId, photoId, gate);

		assertThat(complete(permitted, MORE_TOKENS)).isEqualTo(Completed.SETTLED_BEFORE);

		assertThat(fixtures.call(jobId).get("settled_cost_micros")).isEqualTo(cost);
		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("settled_micros", cost);
		assertLateAnswerChangedNothingElse(jobId, photoId, gate);
	}

	// Plan 5.9: a late 5xx with usage, whose own row settles at the maximum, adjusts the call to the cost of its usage
	// at the call's manifest prices; the day's settled cost moves by the difference, and a second late answer is a no-op.
	@Test
	void lateAnswerAdjustsToTheCostOfItsUsageWhateverItsRow() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		UUID photoId = fixtures.photoOf(jobId);
		Permitted permitted = permitted(claimOne());
		expireLease(jobId);
		assertThat(recovery.recover(jobId, permitted.claimed().runToken())).isEqualTo(LeaseRecovery.Outcome.RETRIED);
		AnalysisProperties.Manifest settings = permitted.manifest().settings();
		long max = settings.maxCostMicros();
		assertThat(fixtures.call(jobId).get("settled_cost_micros")).isEqualTo(max);
		long settledBefore = (Long) fixtures.spendDay().orElseThrow().get("settled_micros");
		Map<String, Object> gate = fixtures.gate(GateCause.TRANSIENT_CIRCUIT);
		ModelReply.Usage usage = new ModelReply.Usage(3001, 601, 1201, 401);
		long actual = CallOutcome.costMicros(usage, settings.price());
		assertThat(actual).isNotEqualTo(max);
		CallOutcome late = CallOutcome.of(new ModelReply.Answered(500, "req_late", usage, null, null, null), false,
				settings.price(), max, Instant.now(), Instant.now());
		assertThat(late.costMicros()).isEqualTo(max);

		assertThat(responses.settle(permitted.claimed(), permitted.call(), late))
			.isEqualTo(ResponseSettlement.Settled.SETTLED_BEFORE);

		Map<String, Object> call = fixtures.call(jobId);
		assertThat(call.get("status")).isEqualTo("UNKNOWN");
		assertThat(call.get("cost_state")).isEqualTo("ADJUSTED");
		assertThat(call.get("settled_cost_micros")).isEqualTo(actual);
		assertThat(call.get("cache_read_tokens")).isEqualTo(usage.cacheReadInputTokens());
		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("reserved_micros", 0L)
			.containsEntry("settled_micros", settledBefore + actual - max);
		assertLateAnswerChangedNothingElse(jobId, photoId, gate);

		assertThat(responses.settle(permitted.claimed(), permitted.call(), refusal(permitted, MORE_TOKENS)))
			.isEqualTo(ResponseSettlement.Settled.SETTLED_BEFORE);

		assertThat(fixtures.call(jobId).get("settled_cost_micros")).isEqualTo(actual);
		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("settled_micros", settledBefore + actual - max);
		assertLateAnswerChangedNothingElse(jobId, photoId, gate);
	}

	// Plan 5.9: a day row that is gone is not created again.
	@Test
	void lateAnswerWithoutItsDayRowAdjustsOnlyTheCall() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		Permitted permitted = permitted(claimOne());
		expireLease(jobId);
		recovery.recover();
		jdbc.sql("DELETE FROM ai_spend_day").update();

		assertThat(complete(permitted, TOKENS)).isEqualTo(Completed.SETTLED_BEFORE);

		Map<String, Object> call = fixtures.call(jobId);
		assertThat(call.get("cost_state")).isEqualTo("ADJUSTED");
		assertThat(call.get("settled_cost_micros"))
			.isEqualTo(CallOutcome.costMicros(TOKENS, manifests.current().settings().price()));
		assertThat(fixtures.spendDay()).isEmpty();
		assertThat(fixtures.results(jobId)).isEmpty();
	}

	// Plan 7.2: jobs that have not ended are cancelled, whether they wait for an upload, a claim or a run.
	@Test
	void processingDeadlineCancelsJobsThatHaveNotEnded() {
		Installed device = fixtures.intake().android();
		UUID ready = fixtures.ready(device, 1).job();
		UUID awaiting = fixtures.uploads().accepted(device, 1).job();
		UUID later = fixtures.ready(device, 1).job();
		pastDeadline(ready);
		pastDeadline(awaiting);

		deadlines.expire();

		for (UUID jobId : List.of(ready, awaiting)) {
			Map<String, Object> job = fixtures.job(jobId);
			assertThat(job.get("status")).isEqualTo("CANCELLED");
			assertThat(job.get("failure_code")).isEqualTo("PROCESSING_EXPIRED");
			assertThat(fixtures.reservations(jobId)).containsOnly("RELEASED");
		}
		assertThat(fixtures.job(later).get("status")).isEqualTo("READY");
		assertThat(deadlines.expire(ready)).isEqualTo(ProcessingDeadlines.Outcome.CHANGED);
	}

	// Plan 7.2: the call in flight of a cancelled job is confirmed unknown and not called again.
	@Test
	void processingDeadlineConfirmsTheCallInFlightUnknown() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		UUID photoId = fixtures.photoOf(jobId);
		Permitted permitted = permitted(claimOne());
		pastDeadline(jobId);

		assertThat(deadlines.expire(jobId)).isEqualTo(ProcessingDeadlines.Outcome.CALL_UNKNOWN);

		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo("CANCELLED");
		assertThat(job.get("failure_code")).isEqualTo("PROCESSING_EXPIRED");
		assertThat(job.get("run_token")).isNull();
		Map<String, Object> call = fixtures.call(jobId);
		assertThat(call.get("status")).isEqualTo("UNKNOWN");
		assertThat(call.get("settled_cost_micros")).isEqualTo(permitted.call().reservedCostMicros());
		assertThat(call.get("send_state")).isEqualTo("SPENT");
		assertThat(fixtures.photoBudget(photoId)).containsEntry("risk_sends", 1)
			.containsEntry("unknown_retry_used", false);
		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("reserved_micros", 0L)
			.containsEntry("settled_micros", permitted.call().reservedCostMicros());
		assertThat(fixtures.reservations(jobId)).containsOnly("RELEASED");
	}

	// Plan 7.2: a run whose answer came after the deadline lost the job and left it running; the batch ends it.
	@Test
	void processingDeadlineEndsAJobItsLateRunLeftRunning() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		model.reply((jpeg) -> {
			pastDeadline(jobId);
			return output();
		});

		assertThat(run.run(claimOne())).isEqualTo(Stop.LOST);
		assertThat(fixtures.job(jobId).get("status")).isEqualTo("RUNNING");
		assertThat(fixtures.call(jobId).get("status")).isEqualTo("SUCCEEDED");

		deadlines.expire();

		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo("CANCELLED");
		assertThat(job.get("failure_code")).isEqualTo("PROCESSING_EXPIRED");
		assertThat(fixtures.call(jobId).get("status")).isEqualTo("SUCCEEDED");
		assertThat(fixtures.results(jobId)).isEmpty();
		assertThat(fixtures.reservations(jobId)).containsOnly("RELEASED");
	}

	// Plan 7.3: the share counts the answers to calls permitted under the current fingerprint only (C-18).
	@Test
	void shareCountsOnlyAnswersUnderTheCurrentFingerprint() {
		UUID jobId = callJob();
		Instant reset = resetConfig();
		answers(jobId, manifests.configFingerprint(), true, 3, reset.plusMillis(1));
		answers(jobId, manifests.configFingerprint(), false, 17, reset.plusMillis(1));
		// Permitted under the configuration before the circuit closed, answered after it.
		answers(jobId, "previous-fingerprint", true, 20, reset.plusMillis(1));

		assertThat(watchConfig()).isFalse();

		assertThat(fixtures.gate(GateCause.CONFIG_CIRCUIT).get("state")).isEqualTo("CLOSED");
	}

	// Plan 7.3: answers before the last close do not count, so a past 400 window does not reopen the circuit (C-18).
	@Test
	void shareCountsOnlyAnswersAfterTheLastClose() {
		UUID jobId = callJob();
		Instant reset = resetConfig();
		answers(jobId, manifests.configFingerprint(), true, 20, reset.minusSeconds(30));
		answers(jobId, manifests.configFingerprint(), false, 20, reset.plusMillis(1));

		assertThat(watchConfig()).isFalse();

		assertThat(fixtures.gate(GateCause.CONFIG_CIRCUIT).get("state")).isEqualTo("CLOSED");
	}

	// Plan 7.3: the share needs the minimum sample, and then opens the closed circuit under the current fingerprint.
	@Test
	void shareOfTheMinimumSampleOpensTheCircuit() {
		UUID jobId = callJob();
		Instant reset = resetConfig();
		int minSamples = properties.circuit().minSamples();
		answers(jobId, manifests.configFingerprint(), true, minSamples - 1, reset.plusMillis(1));
		long generation = (Long) fixtures.gate(GateCause.CONFIG_CIRCUIT).get("generation");

		assertThat(watchConfig()).isFalse();
		assertThat(fixtures.gate(GateCause.CONFIG_CIRCUIT).get("state")).isEqualTo("CLOSED");

		answers(jobId, manifests.configFingerprint(), true, 1, reset.plusMillis(2));

		assertThat(watchConfig()).isTrue();
		Map<String, Object> gate = fixtures.gate(GateCause.CONFIG_CIRCUIT);
		assertThat(gate.get("state")).isEqualTo("OPEN");
		assertThat(gate.get("generation")).isEqualTo(generation + 1);
		assertThat(gate.get("config_version")).isEqualTo(manifests.configFingerprint());
		assertThat(gate.get("opened_at")).isNotNull();
		assertThat(claims.claim(1)).isEmpty();
	}

	// Plan 7.3: the share opens only a closed circuit.
	@Test
	void shareLeavesACircuitThatIsNotClosed() {
		UUID jobId = callJob();
		Instant reset = resetConfig();
		answers(jobId, manifests.configFingerprint(), true, properties.circuit().minSamples(), reset.plusMillis(1));
		jdbc.sql("UPDATE ai_gate SET state = 'HALF_OPEN', generation = generation + 1 WHERE cause = 'CONFIG_CIRCUIT'")
			.update();
		long generation = (Long) fixtures.gate(GateCause.CONFIG_CIRCUIT).get("generation");

		assertThat(watchConfig()).isFalse();

		assertThat(fixtures.gate(GateCause.CONFIG_CIRCUIT)).containsEntry("state", "HALF_OPEN")
			.containsEntry("generation", generation);
	}

	// Plan 7.3, 5.10: a new key version or manifest lets the open circuit try again; the same one does not.
	@Test
	void openCircuitTurnsHalfOpenOnANewFingerprint() {
		jdbc.sql("""
				UPDATE ai_gate SET state = 'OPEN', generation = generation + 1, config_version = :version, opened_at = now()
				WHERE cause = 'CONFIG_CIRCUIT'
				""").param("version", manifests.configFingerprint()).update();

		assertThat(watchConfig()).isFalse();
		assertThat(fixtures.gate(GateCause.CONFIG_CIRCUIT).get("state")).isEqualTo("OPEN");

		jdbc.sql("UPDATE ai_gate SET config_version = 'previous-fingerprint' WHERE cause = 'CONFIG_CIRCUIT'").update();
		long generation = (Long) fixtures.gate(GateCause.CONFIG_CIRCUIT).get("generation");
		Instant before = Instant.now();

		assertThat(watchConfig()).isTrue();

		Map<String, Object> gate = fixtures.gate(GateCause.CONFIG_CIRCUIT);
		assertThat(gate.get("state")).isEqualTo("HALF_OPEN");
		assertThat(gate.get("generation")).isEqualTo(generation + 1);
		assertThat(gate.get("config_version")).isEqualTo(manifests.configFingerprint());
		assertThat(gate.get("probe_job_id")).isNull();
		assertThat(instant(gate.get("blocked_until"))).isAfterOrEqualTo(before.minusMillis(1));
	}

	// C-18: an observation that a newer transition overtook changes nothing.
	@Test
	void staleObservationDoesNotOverwriteANewerState() {
		UUID jobId = callJob();
		Instant reset = resetConfig();
		answers(jobId, manifests.configFingerprint(), true, properties.circuit().minSamples(), reset.plusMillis(1));
		Gate stale = monitor.observeConfig();
		// A 401 opened the circuit meanwhile, and a new key version made it half-open.
		jdbc.sql("""
				UPDATE ai_gate SET state = 'HALF_OPEN', generation = generation + 2, config_version = :version
				WHERE cause = 'CONFIG_CIRCUIT'
				""").param("version", manifests.configFingerprint()).update();

		assertThat(watchConfig(stale)).isFalse();

		assertThat(fixtures.gate(GateCause.CONFIG_CIRCUIT)).containsEntry("state", "HALF_OPEN")
			.containsEntry("generation", stale.generation() + 2);
	}

	// Plan 7.3: a raised limit is written to today's row and closes the budget gate once a call fits.
	@Test
	void raisedLimitClosesTheBudgetGate() {
		long limit = properties.budget().dailyCostLimitMicros();
		long maxCost = manifests.current().settings().maxCostMicros();
		spendDay(limit / 2, 0, limit / 2);
		holdBudget();

		assertThat(refreshBudget()).isTrue();

		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("limit_micros", limit);
		Map<String, Object> gate = fixtures.gate(GateCause.BUDGET);
		assertThat(gate.get("state")).isEqualTo("CLOSED");
		assertThat(gate.get("blocked_until")).isNull();
		assertThat(refreshBudget()).isFalse();

		jdbc.sql("UPDATE ai_spend_day SET limit_micros = :lower, settled_micros = :settled")
			.param("lower", limit - 1)
			.param("settled", limit - maxCost + 1)
			.update();
		holdBudget();

		assertThat(refreshBudget()).isFalse();

		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("limit_micros", limit);
		assertThat(fixtures.gate(GateCause.BUDGET).get("state")).isEqualTo("OPEN");
	}

	// Plan 7.3, 5.10: the monitor never clears a probe, which only the run of its token may clear.
	@Test
	void monitorDoesNotClearAProbe() {
		UUID jobId = callJob();
		Instant reset = resetConfig();
		answers(jobId, manifests.configFingerprint(), true, properties.circuit().minSamples(), reset.plusMillis(1));
		UUID token = UUID.randomUUID();
		jdbc.sql("""
				UPDATE ai_gate SET state = 'HALF_OPEN', generation = generation + 1, probe_job_id = :job,
				                   probe_run_token = :token, config_version = :version, blocked_until = :since
				WHERE cause IN ('CONFIG_CIRCUIT', 'TRANSIENT_CIRCUIT')
				""")
			.param("job", jobId)
			.param("token", token)
			.param("version", manifests.configFingerprint())
			.param("since", utc(Instant.now().minus(properties.circuit().halfOpenAlarm()).minusSeconds(60)))
			.update();
		Map<String, Object> config = fixtures.gate(GateCause.CONFIG_CIRCUIT);
		Map<String, Object> transientCircuit = fixtures.gate(GateCause.TRANSIENT_CIRCUIT);

		monitor.monitor();

		assertThat(fixtures.gate(GateCause.CONFIG_CIRCUIT)).isEqualTo(config);
		assertThat(fixtures.gate(GateCause.TRANSIENT_CIRCUIT)).isEqualTo(transientCircuit);
		assertThat(gates.isProbe(jobId, token)).isTrue();
	}

	// Plan 5.10: a circuit half-open for longer than the alarm setting raises an alarm with its cause only.
	@Test
	void circuitHalfOpenPastTheAlarmIsReported(CapturedOutput output) {
		Duration alarm = properties.circuit().halfOpenAlarm();
		jdbc.sql("""
				UPDATE ai_gate SET state = 'HALF_OPEN', generation = generation + 1,
				                   blocked_until = CASE WHEN cause = 'TRANSIENT_CIRCUIT' THEN :long ELSE :short END
				WHERE cause IN ('CONFIG_CIRCUIT', 'TRANSIENT_CIRCUIT')
				""")
			.param("long", utc(Instant.now().minus(alarm).minusSeconds(60)))
			.param("short", utc(Instant.now().minusSeconds(60)))
			.update();
		int start = output.getOut().length();

		monitor.alarmHalfOpen(Instant.now());

		String logged = output.getOut().substring(start);
		assertThat(logged).contains("TRANSIENT_CIRCUIT has been half-open for longer than")
			.doesNotContain("CONFIG_CIRCUIT has been half-open");
	}

	// Follow-up of the claim: a gate that changed after the unlocked check gets no probe.
	@Test
	void probeIsNotNamedWhenTheGateChangedAfterTheCheck() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		openTransient(Instant.now().minusSeconds(1));
		Admission admission = gates.admission(Instant.now());
		assertThat(admission).isInstanceOf(Admission.Probe.class);
		// The circuit opened again for a later failure before the claim locked its row.
		jdbc.sql("UPDATE ai_gate SET generation = generation + 1 WHERE cause = 'TRANSIENT_CIRCUIT'").update();
		Map<String, Object> changed = fixtures.gate(GateCause.TRANSIENT_CIRCUIT);

		Boolean named = transaction.execute((status) -> gates.assignProbe((Admission.Probe) admission, jobId,
				UUID.randomUUID(), Instant.now()));

		assertThat(named).isFalse();
		assertThat(fixtures.gate(GateCause.TRANSIENT_CIRCUIT)).isEqualTo(changed);
	}

	// Plan 5.1: the poll loop claims as many jobs as it has free slots and runs each to its end.
	@Test
	void pollRunsReadyJobsWithinTheGlobalSlots() {
		int slots = properties.worker().globalSlots();
		int principalSlots = properties.worker().principalSlots();
		List<UUID> jobIds = new ArrayList<>();
		while (jobIds.size() <= slots) {
			jobIds.addAll(fixtures.ready(fixtures.intake().android(), principalSlots).jobIds());
		}
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger mostInFlight = new AtomicInteger();
		for (int i = 0; i < jobIds.size(); i++) {
			model.reply((jpeg) -> {
				mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
				try {
					assertThat(release.await(WAIT.toSeconds(), SECONDS)).isTrue();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
				inFlight.decrementAndGet();
				return output();
			});
		}

		worker.poll();
		await().atMost(WAIT).until(() -> model.calls() == slots);
		worker.poll();

		assertThat(statuses(jobIds, "RUNNING")).isEqualTo(slots);
		assertThat(statuses(jobIds, "READY")).isEqualTo(jobIds.size() - slots);
		release.countDown();
		await().atMost(WAIT).until(() -> statuses(jobIds, "SUCCEEDED") == slots);
		await().atMost(WAIT).until(() -> {
			worker.poll();
			return statuses(jobIds, "SUCCEEDED") == jobIds.size();
		});
		assertThat(mostInFlight.get()).isEqualTo(slots);
		assertThat(model.calls()).isEqualTo(jobIds.size());
		assertThat(events.events()).hasSize(jobIds.size());
	}

	private void assertLateAnswerChangedNothingElse(UUID jobId, UUID photoId, Map<String, Object> gate) {
		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo("READY");
		assertThat(job.get("failure_code")).isNull();
		assertThat(fixtures.results(jobId)).isEmpty();
		assertThat(events.events()).isEmpty();
		assertThat(fixtures.reservations(jobId)).containsOnly("RESERVED");
		assertThat(fixtures.photoBudget(photoId)).containsEntry("risk_sends", 1)
			.containsEntry("unknown_retry_used", true);
		assertThat(fixtures.gate(GateCause.TRANSIENT_CIRCUIT)).isEqualTo(gate);
	}

	private CallOutcome refusal(Permitted permitted, ModelReply.Usage usage) {
		AnalysisProperties.Manifest settings = permitted.manifest().settings();
		return CallOutcome.of(new ModelReply.Answered(200, "req_late", usage, "refusal", null, null), false,
				settings.price(), settings.maxCostMicros(), Instant.now(), Instant.now());
	}

	private Completed complete(Permitted permitted, ModelReply.Usage usage) {
		ModelReply.Answered valid = (ModelReply.Answered) output();
		ModelReply.Answered reply = new ModelReply.Answered(200, "req_late", usage, "end_turn", valid.output(), null);
		Outcome.Usable usable = (Outcome.Usable) ResultValidator.validate(reply.output(),
				permitted.basis().referenceAt());
		AnalysisProperties.Manifest settings = permitted.manifest().settings();
		CallOutcome outcome = CallOutcome.of(reply, true, settings.price(), settings.maxCostMicros(), Instant.now(),
				Instant.now());
		return completion.complete(permitted.claimed(), permitted.basis(), permitted.manifest(), permitted.call(),
				outcome, usable);
	}

	private boolean watchConfig() {
		return watchConfig(monitor.observeConfig());
	}

	private boolean watchConfig(Gate observed) {
		return Boolean.TRUE.equals(transaction.execute((status) -> monitor.watchConfig(observed, Instant.now())));
	}

	private boolean refreshBudget() {
		return Boolean.TRUE.equals(transaction.execute((status) -> monitor.refreshBudget(Instant.now())));
	}

	// A job the answer rows of the share can belong to.
	private UUID callJob() {
		return fixtures.ready(fixtures.intake().android(), 1).job();
	}

	// The configuration circuit closed just now: earlier answers of this and other tests are before the window.
	private Instant resetConfig() {
		Instant reset = Instant.now();
		jdbc.sql("UPDATE ai_gate SET reset_at = :reset WHERE cause = 'CONFIG_CIRCUIT'").param("reset", utc(reset)).update();
		return reset;
	}

	// Settled answers of the job, 400 or 2xx, permitted under the fingerprint.
	private void answers(UUID jobId, String fingerprint, boolean rejected, int count, Instant respondedAt) {
		jdbc.sql("""
				INSERT INTO external_call (id, owner_id, job_id, run_token, manifest_id, funding, photo_id, input_sha256,
				                           budget_day, status, http_status, outcome, config_fingerprint,
				                           reserved_cost_micros, settled_cost_micros, cost_state, send_state, created_at,
				                           sent_at, responded_at, settled_at)
				SELECT gen_random_uuid(), j.owner_id, j.id, gen_random_uuid(), j.manifest_id, j.funding, j.photo_id,
				       sha256(convert_to(CAST(g AS text), 'UTF8')), :day, :status, :http, :outcome, :fingerprint, 1, 1,
				       'SETTLED', 'SPENT', :at, :at, :at, :at
				FROM analysis_job j CROSS JOIN generate_series(1, :count) AS g
				WHERE j.id = :job
				""")
			.param("job", jobId)
			.param("day", BudgetDays.of(respondedAt))
			.param("status", rejected ? "RESPONDED_FAILURE" : "SUCCEEDED")
			.param("http", rejected ? 400 : 200)
			.param("outcome", rejected ? ModelReply.Row.PROVIDER_REJECTED.name() : ModelReply.Row.OUTPUT.name())
			.param("fingerprint", fingerprint)
			.param("at", utc(respondedAt))
			.param("count", count)
			.update();
	}

	private void holdBudget() {
		jdbc.sql("""
				UPDATE ai_gate SET state = 'OPEN', generation = generation + 1, blocked_until = :until, opened_at = now()
				WHERE cause = 'BUDGET'
				""").param("until", utc(BudgetDays.nextStart(Instant.now()))).update();
	}

	private void spendDay(long limit, long reserved, long settled) {
		jdbc.sql("""
				INSERT INTO ai_spend_day (day, limit_micros, reserved_micros, settled_micros, updated_at)
				VALUES (:day, :limit, :reserved, :settled, now())
				""")
			.param("day", BudgetDays.of(Instant.now()))
			.param("limit", limit)
			.param("reserved", reserved)
			.param("settled", settled)
			.update();
	}

	private int statuses(List<UUID> jobIds, String status) {
		return jdbc.sql("SELECT count(*) FROM analysis_job WHERE id IN (:ids) AND status = :status")
			.param("ids", jobIds)
			.param("status", status)
			.query(Integer.class)
			.single();
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
		return new Permitted(claimed, basis, manifest, ((Permit.Granted) permit).call());
	}

	// The spy behind the transactional proxy: stubbing the proxy would run its transaction check.
	private ChangeSequence spiedChanges() {
		return AopTestUtils.getTargetObject(changes);
	}

	private void expireLease(UUID jobId) {
		jdbc.sql("UPDATE analysis_job SET locked_until = :past WHERE id = :id")
			.param("past", utc(Instant.now().minusSeconds(1)))
			.param("id", jobId)
			.update();
	}

	private void pastDeadline(UUID jobId) {
		jdbc.sql("UPDATE analysis_job SET processing_deadline = :past WHERE id = :id")
			.param("past", utc(Instant.now().minusSeconds(1)))
			.param("id", jobId)
			.update();
	}

	private void dueNow(UUID jobId) {
		jdbc.sql("UPDATE analysis_job SET next_attempt_at = :now WHERE id = :id")
			.param("now", utc(Instant.now().minusSeconds(1)))
			.param("id", jobId)
			.update();
	}

	private void openTransient(Instant blockedUntil) {
		jdbc.sql("""
				UPDATE ai_gate SET state = 'OPEN', generation = generation + 1, blocked_until = :until,
				                   failure_streak = :streak, opened_at = :until
				WHERE cause = 'TRANSIENT_CIRCUIT'
				""")
			.param("until", utc(blockedUntil))
			.param("streak", properties.circuit().transientThreshold())
			.update();
	}

	private record Permitted(ClaimedJob claimed, RunBasis basis, CallManifest manifest, CallRow call) {

	}

}
