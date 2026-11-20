package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.IntakeFixtures.JSON;
import static io.github.gb6105.pik.analysis.IntakeFixtures.T0;
import static io.github.gb6105.pik.analysis.IntakeFixtures.body;
import static io.github.gb6105.pik.analysis.IntakeFixtures.json;
import static io.github.gb6105.pik.analysis.IntakeFixtures.key;
import static io.github.gb6105.pik.analysis.IntakeFixtures.photo;
import static io.github.gb6105.pik.analysis.IntakeFixtures.vector;
import static io.github.gb6105.pik.analysis.RunFixtures.TOKENS;
import static io.github.gb6105.pik.analysis.RunFixtures.answered;
import static io.github.gb6105.pik.analysis.RunFixtures.instant;
import static io.github.gb6105.pik.analysis.RunFixtures.jpeg;
import static io.github.gb6105.pik.analysis.RunFixtures.output;
import static io.github.gb6105.pik.analysis.RunFixtures.utc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;

import io.github.gb6105.pik.analysis.AnalysisRun.Stop;
import io.github.gb6105.pik.analysis.IntakeFixtures.Installed;
import io.github.gb6105.pik.analysis.IntakeFixtures.Photo;
import io.github.gb6105.pik.analysis.JobClaims.ClaimedJob;
import io.github.gb6105.pik.analysis.JobTermination.JobEnding;
import io.github.gb6105.pik.analysis.RunTestConfiguration.CompletedEvents;
import io.github.gb6105.pik.analysis.RunTestConfiguration.ScriptedModel;
import io.github.gb6105.pik.analysis.UploadFixtures.Accepted;
import io.github.gb6105.pik.shared.change.ChangeSequence;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The run of a claimed job against PostgreSQL and S3Mock with a scripted model (plan 5.2 to 5.10, 11.2, design 2.5):
 * the stored results, each permit denial, each settlement table row, the unknown outcome retried once, a run that
 * lost its job, a result conflict, a representative deleted and a consent withdrawn before the completion, the input
 * checks and the gate transitions of claims, permits and responses (C-18 in part).
 */
@AnalysisRunTest
class AnalysisRunTests {

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
	ScriptedModel model;

	@Autowired
	CompletedEvents events;

	@Autowired
	AnalysisResults results;

	@Autowired
	CallManifests manifests;

	@Autowired
	JobTermination termination;

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

	@Test
	void validOutputStoresTheResultOfEveryPhotoOfTheGroup() {
		Installed device = fixtures.intake().android();
		Photo a = photo("v1").groupable(T0, vector(1, 0));
		Photo b = photo("v1").groupable(T0.plusSeconds(30), vector(1, 0.01f));
		Photo c = photo("v1").groupable(T0.plusSeconds(60), vector(1, 0.02f));
		UUID jobId = groupJob(device, a, b, c);
		UUID representative = fixtures.photoOf(jobId);
		model.reply(output());

		assertThat(run.run(claimOne())).isEqualTo(Stop.STORED);

		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo("SUCCEEDED");
		assertThat(job.get("ended_at")).isNotNull();
		assertThat(job.get("run_token")).isNull();
		List<Map<String, Object>> stored = fixtures.results(jobId);
		assertThat(stored).hasSize(3);
		assertThat(stored).allSatisfy((result) -> {
			assertThat(result.get("verification"))
				.isEqualTo(representative.equals(result.get("photo_id")) ? "DIRECT" : "INHERITED");
			assertThat(result.get("epoch")).isEqualTo(1);
			assertThat(result.get("photo_version")).isEqualTo("v1");
			assertThat((Long) result.get("change_seq")).isPositive();
		});
		assertThat(stored.stream().map((result) -> result.get("change_seq")).distinct()).hasSize(3);
		long cost = CallOutcome.costMicros(TOKENS, manifests.current().settings().price());
		Map<String, Object> call = fixtures.call(jobId);
		assertThat(call.get("status")).isEqualTo("SUCCEEDED");
		assertThat(call.get("outcome")).isEqualTo("OUTPUT");
		assertThat(call.get("settled_cost_micros")).isEqualTo(cost);
		assertThat(call.get("send_state")).isEqualTo("SPENT");
		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("reserved_micros", 0L)
			.containsEntry("settled_micros", cost);
		assertThat(fixtures.dailySends(device.principalId())).isEqualTo(1);
		assertThat(fixtures.deviceSends(device.trialMarkId())).isEqualTo(1);
		assertThat(fixtures.photoBudget(representative).get("risk_sends")).isEqualTo(1);
		assertThat(fixtures.reservations(jobId)).hasSize(3).containsOnly("CONSUMED");
		assertThat(jdbc.sql("SELECT first_result_at FROM analysis_request WHERE id = :id")
			.param("id", job.get("request_id"))
			.query(OffsetDateTime.class)
			.single()).isNotNull();
		assertThat(jdbc.sql("SELECT count(*) FROM analysis_upload_attempt WHERE job_id = :job AND delete_after IS NULL")
			.param("job", jobId)
			.query(Long.class)
			.single()).isZero();
		assertThat(events.events()).hasSize(3)
			.allSatisfy((event) -> assertThat(event.epoch()).isEqualTo(1))
			.extracting(AnalysisCompleted::resultId)
			.containsExactlyInAnyOrderElementsOf(stored.stream().map((result) -> (UUID) result.get("id")).toList());
		AnalysisResult found = results.find(device.principalId(), representative).orElseThrow();
		assertThat(found.verification()).isEqualTo(AnalysisResult.Verification.DIRECT);
		assertThat(found.jobId()).isEqualTo(jobId);
		assertThat(found.content().has("truncatedFields")).isTrue();
		assertThat(results.find(UUID.randomUUID(), representative)).isEmpty();
		assertThat(fixtures.gate(GateCause.TRANSIENT_CIRCUIT).get("state")).isEqualTo("CLOSED");
	}

	@Test
	void deletedMemberIsReleasedAndTheOthersStored() {
		Installed device = fixtures.intake().android();
		UUID jobId = groupJob(device, photo("v1").groupable(T0, vector(1, 0)),
				photo("v1").groupable(T0.plusSeconds(30), vector(1, 0.01f)),
				photo("v1").groupable(T0.plusSeconds(60), vector(1, 0.02f)));
		UUID representative = fixtures.photoOf(jobId);
		UUID member = fixtures.members(jobId).stream().filter((id) -> !id.equals(representative)).findFirst().orElseThrow();
		model.reply((jpeg) -> {
			fixtures.deletePhoto(member);
			return output();
		});

		assertThat(run.run(claimOne())).isEqualTo(Stop.STORED);

		assertThat(fixtures.results(jobId)).hasSize(2)
			.noneSatisfy((result) -> assertThat(result.get("photo_id")).isEqualTo(member));
		assertThat(fixtures.reservations(jobId)).containsExactlyInAnyOrder("CONSUMED", "CONSUMED", "RELEASED");
		assertThat(events.events()).hasSize(2);
	}

	static Stream<SettlementRow> settlementRows() {
		return Stream.of(
				new SettlementRow("refusal", answered(200, "refusal"), "FAILED", "REFUSAL", "RESPONDED_FAILURE", Cost.ACTUAL,
						true),
				new SettlementRow("truncated", answered(200, "max_tokens"), "FAILED", "TRUNCATED", "RESPONDED_FAILURE",
						Cost.ACTUAL, true),
				new SettlementRow("schema broken",
						new ModelReply.Answered(200, "req_200", TOKENS, "end_turn", JSON.readTree("[]"), null), "FAILED",
						"INVALID_OUTPUT", "RESPONDED_FAILURE", Cost.ACTUAL, true),
				new SettlementRow("unreadable 2xx", new ModelReply.Unreadable(200, "req_200", null), "FAILED",
						"INVALID_OUTPUT", "RESPONDED_FAILURE", Cost.MAX, true),
				new SettlementRow("400", status(400), "FAILED", "PROVIDER_REJECTED", "RESPONDED_FAILURE", Cost.MAX, true),
				new SettlementRow("413 with usage", answered(413, null), "FAILED", "PROVIDER_REJECTED", "RESPONDED_FAILURE",
						Cost.ACTUAL, true),
				new SettlementRow("402", status(402), "FAILED", "PROVIDER_REJECTED", "RESPONDED_FAILURE", Cost.MAX, true),
				new SettlementRow("401", status(401), "READY", null, "RESPONDED_FAILURE", Cost.MAX, false),
				new SettlementRow("404", status(404), "READY", null, "RESPONDED_FAILURE", Cost.MAX, false),
				new SettlementRow("429", status(429), "READY", null, "RESPONDED_FAILURE", Cost.ZERO, false),
				new SettlementRow("529", status(529), "READY", null, "RESPONDED_FAILURE", Cost.ZERO, false),
				new SettlementRow("500", status(500), "READY", null, "UNKNOWN", Cost.MAX, true),
				new SettlementRow("409", status(409), "READY", null, "UNKNOWN", Cost.MAX, true),
				new SettlementRow("no answer", new ModelReply.NoAnswer(), "READY", null, "UNKNOWN", Cost.MAX, true),
				new SettlementRow("not sent", new ModelReply.NotSent(), "READY", null, "NOT_SENT", Cost.ZERO, false));
	}

	// Design 2.5: every row but a stored result, each settled once on the call, the budgets and the job.
	@ParameterizedTest(name = "{0}")
	@MethodSource("settlementRows")
	void responseIsSettledByItsRow(SettlementRow row) {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		UUID photoId = fixtures.photoOf(jobId);
		model.reply(row.reply());

		run.run(claimOne());

		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo(row.jobStatus());
		assertThat(job.get("failure_code")).isEqualTo(row.failureCode());
		assertThat(job.get("run_token")).isNull();
		Map<String, Object> call = fixtures.call(jobId);
		assertThat(call.get("status")).isEqualTo(row.callStatus());
		assertThat(call.get("cost_state")).isEqualTo("SETTLED");
		AnalysisProperties.Manifest manifest = manifests.current().settings();
		long cost = switch (row.cost()) {
			case ACTUAL -> CallOutcome.costMicros(TOKENS, manifest.price());
			case MAX -> manifest.maxCostMicros();
			case ZERO -> 0;
		};
		assertThat(call.get("settled_cost_micros")).isEqualTo(cost);
		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("reserved_micros", 0L)
			.containsEntry("settled_micros", cost);
		int sends = row.sendSpent() ? 1 : 0;
		assertThat(call.get("send_state")).isEqualTo(row.sendSpent() ? "SPENT" : "RELEASED");
		assertThat(fixtures.dailySends(device.principalId())).isEqualTo(sends);
		assertThat(fixtures.deviceSends(device.trialMarkId())).isEqualTo(sends);
		assertThat(fixtures.photoBudget(photoId).get("risk_sends")).isEqualTo(sends);
		assertThat(fixtures.reservations(jobId)).containsOnly(row.failureCode() != null ? "RELEASED" : "RESERVED");
		assertThat(fixtures.results(jobId)).isEmpty();
		boolean configError = row.reply() instanceof ModelReply.Answered answered
				&& List.of(401, 402, 403, 404).contains(answered.status());
		Map<String, Object> config = fixtures.gate(GateCause.CONFIG_CIRCUIT);
		assertThat(config.get("state")).isEqualTo(configError ? "OPEN" : "CLOSED");
		if (configError) {
			assertThat(config.get("config_version")).isEqualTo(manifests.configFingerprint());
		}
		assertThat(fixtures.gate(GateCause.TRANSIENT_CIRCUIT).get("failure_streak"))
			.isEqualTo(row.callStatus().equals("UNKNOWN") || cost == 0 && row.callStatus().equals("RESPONDED_FAILURE")
					? 1 : 0);
	}

	@Test
	void throttledJobWaitsForRetryAfter() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		model.reply(new ModelReply.Answered(429, "req_429", null, null, null, Duration.ofMinutes(5)));
		Instant before = Instant.now();

		assertThat(run.run(claimOne())).isEqualTo(Stop.DEFERRED);

		Instant next = instant(fixtures.job(jobId).get("next_attempt_at"));
		assertThat(next).isAfterOrEqualTo(before.plus(Duration.ofMinutes(5)));
	}

	// Plan 5.9: the one call again after an unknown outcome, then UNKNOWN_OUTCOME.
	@Test
	void unknownOutcomeIsRetriedOnceThenFails() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		UUID photoId = fixtures.photoOf(jobId);
		model.reply(status(500));
		model.reply(status(503));

		assertThat(run.run(claimOne())).isEqualTo(Stop.DEFERRED);
		assertThat(fixtures.photoBudget(photoId).get("unknown_retry_used")).isEqualTo(true);
		assertThat(fixtures.reservations(jobId)).containsOnly("RESERVED");
		dueNow(jobId);
		assertThat(run.run(claimOne())).isEqualTo(Stop.ENDED);

		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo("FAILED");
		assertThat(job.get("failure_code")).isEqualTo("UNKNOWN_OUTCOME");
		assertThat(fixtures.calls(jobId)).hasSize(2).allSatisfy((call) -> assertThat(call.get("status")).isEqualTo("UNKNOWN"));
		assertThat(fixtures.photoBudget(photoId).get("risk_sends")).isEqualTo(2);
		assertThat(fixtures.reservations(jobId)).containsOnly("RELEASED");
	}

	@Test
	void runThatLostItsJobSettlesOnlyItsCall() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		model.reply((jpeg) -> {
			transaction.executeWithoutResult(
					(status) -> termination.end(jobId, new JobEnding(FailureCode.CONSENT_WITHDRAWN)));
			return output();
		});

		assertThat(run.run(claimOne())).isEqualTo(Stop.LOST);

		assertThat(fixtures.job(jobId).get("failure_code")).isEqualTo("CONSENT_WITHDRAWN");
		Map<String, Object> call = fixtures.call(jobId);
		assertThat(call.get("status")).isEqualTo("SUCCEEDED");
		assertThat(call.get("send_state")).isEqualTo("SPENT");
		assertThat(fixtures.spendDay().orElseThrow()).containsEntry("reserved_micros", 0L);
		assertThat(fixtures.results(jobId)).isEmpty();
		assertThat(events.events()).isEmpty();
		assertThat(fixtures.reservations(jobId)).containsOnly("RELEASED");
	}

	// Plan K-21.
	@Test
	void resultStoredMeanwhileEndsTheJobWithResultConflict() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		UUID photoId = fixtures.photoOf(jobId);
		model.reply((jpeg) -> {
			insertResult(device.principalId(), photoId, jobId);
			return output();
		});

		assertThat(run.run(claimOne())).isEqualTo(Stop.ENDED);

		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo("FAILED");
		assertThat(job.get("failure_code")).isEqualTo("RESULT_CONFLICT");
		Map<String, Object> call = fixtures.call(jobId);
		assertThat(call.get("status")).isEqualTo("SUCCEEDED");
		assertThat(call.get("outcome")).isEqualTo("RESULT_CONFLICT");
		assertThat(call.get("settled_cost_micros"))
			.isEqualTo(CallOutcome.costMicros(TOKENS, manifests.current().settings().price()));
		assertThat(fixtures.results(jobId)).hasSize(1);
		assertThat(events.events()).isEmpty();
		assertThat(fixtures.reservations(jobId)).containsOnly("RELEASED");
	}

	@Test
	void representativeDeletedBeforeTheCompletionDiscardsTheOutput() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		model.reply((jpeg) -> {
			fixtures.deletePhoto(fixtures.photoOf(jobId));
			return output();
		});

		assertThat(run.run(claimOne())).isEqualTo(Stop.ENDED);

		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo("INELIGIBLE");
		assertThat(job.get("failure_code")).isEqualTo("REPRESENTATIVE_DELETED");
		assertThat(fixtures.call(jobId).get("status")).isEqualTo("SUCCEEDED");
		assertThat(fixtures.results(jobId)).isEmpty();
		assertThat(fixtures.reservations(jobId)).containsOnly("RELEASED");
	}

	@Test
	void consentWithdrawnBeforeTheCompletionDiscardsTheOutput() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		model.reply((jpeg) -> {
			fixtures.refuse(device.principalId());
			return output();
		});

		assertThat(run.run(claimOne())).isEqualTo(Stop.ENDED);

		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo("CANCELLED");
		assertThat(job.get("failure_code")).isEqualTo("CONSENT_WITHDRAWN");
		assertThat(fixtures.results(jobId)).isEmpty();
		assertThat(events.events()).isEmpty();
	}

	// Plan 5.4: each denial before any change; no call is made.
	@Test
	void consentWithdrawnBeforeTheSendEndsTheJob() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		ClaimedJob claimed = claimOne();
		fixtures.refuse(device.principalId());

		assertDenied(claimed, Stop.ENDED, "CANCELLED", "CONSENT_WITHDRAWN");
		assertThat(fixtures.reservations(jobId)).containsOnly("RELEASED");
	}

	@Test
	void representativeDeletedBeforeTheSendEndsTheJob() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		ClaimedJob claimed = claimOne();
		fixtures.deletePhoto(fixtures.photoOf(jobId));

		assertDenied(claimed, Stop.ENDED, "INELIGIBLE", "REPRESENTATIVE_DELETED");
	}

	@Test
	void inactivePrincipalEndsTheJob() {
		Installed device = fixtures.intake().android();
		fixtures.ready(device, 1);
		ClaimedJob claimed = claimOne();
		jdbc.sql("UPDATE principal SET status = 'DELETING' WHERE id = :id").param("id", device.principalId()).update();

		assertDenied(claimed, Stop.ENDED, "INELIGIBLE", "PRINCIPAL_INACTIVE");
	}

	@Test
	void passedProcessingDeadlineEndsTheJob() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		ClaimedJob claimed = claimOne();
		jdbc.sql("UPDATE analysis_job SET processing_deadline = :past WHERE id = :id")
			.param("past", utc(Instant.now().minusSeconds(1)))
			.param("id", jobId)
			.update();

		assertDenied(claimed, Stop.ENDED, "CANCELLED", "PROCESSING_EXPIRED");
	}

	@Test
	void deviceWithoutSendsLeftEndsTheJob() {
		Installed device = fixtures.intake().android();
		fixtures.ready(device, 1);
		ClaimedJob claimed = claimOne();
		jdbc.sql("UPDATE device_trial_mark SET send_count = limit_count * :multiplier WHERE id = :id")
			.param("multiplier", properties.budget().deviceSendMultiplier())
			.param("id", device.trialMarkId())
			.update();

		assertDenied(claimed, Stop.ENDED, "FAILED", "DEVICE_BUDGET_EXHAUSTED");
	}

	@Test
	void photoWithoutSendsLeftEndsTheJob() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		ClaimedJob claimed = claimOne();
		jdbc.sql("""
				INSERT INTO photo_ai_budget (photo_id, owner_id, risk_sends, unknown_retry_used, input_invalid_count,
				                             updated_at, version)
				VALUES (:photo, :owner, :sends, false, 0, now(), 0)
				""")
			.param("photo", fixtures.photoOf(jobId))
			.param("owner", device.principalId())
			.param("sends", properties.budget().photoLifetimeSends())
			.update();

		assertDenied(claimed, Stop.ENDED, "FAILED", "ATTEMPTS_EXHAUSTED");
	}

	@Test
	void principalWithoutDailySendsLeftWaitsForTheNextDay() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		ClaimedJob claimed = claimOne();
		jdbc.sql("INSERT INTO ai_send_daily (owner_id, day, send_count) VALUES (:owner, :day, :count)")
			.param("owner", device.principalId())
			.param("day", BudgetDays.of(Instant.now()))
			.param("count", properties.budget().principalDailySends())
			.update();

		assertDenied(claimed, Stop.DEFERRED, "READY", null);
		assertThat(instant(fixtures.job(jobId).get("next_attempt_at")))
			.isEqualTo(BudgetDays.nextStart(Instant.now()));
		assertThat(fixtures.reservations(jobId)).containsOnly("RESERVED");
	}

	@Test
	void dailyCostSpentOpensTheBudgetGateUntilTheNextDay() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		ClaimedJob claimed = claimOne();
		long maxCost = manifests.current().settings().maxCostMicros();
		spendDay(maxCost, 0, 1);

		assertDenied(claimed, Stop.DEFERRED, "READY", null);
		Instant nextDay = BudgetDays.nextStart(Instant.now());
		Map<String, Object> budget = fixtures.gate(GateCause.BUDGET);
		assertThat(budget.get("state")).isEqualTo("OPEN");
		assertThat(instant(budget.get("blocked_until"))).isEqualTo(nextDay);
		assertThat(instant(fixtures.job(jobId).get("next_attempt_at"))).isEqualTo(nextDay);
		assertThat(claims.claim(1)).isEmpty();
	}

	@Test
	void reservedDailyCostOpensTheBudgetGateForTheRetryDelay() {
		Installed device = fixtures.intake().android();
		fixtures.ready(device, 1);
		ClaimedJob claimed = claimOne();
		long maxCost = manifests.current().settings().maxCostMicros();
		spendDay(maxCost, 1, 0);
		Instant before = Instant.now();

		assertDenied(claimed, Stop.DEFERRED, "READY", null);
		Instant until = instant(fixtures.gate(GateCause.BUDGET).get("blocked_until"));
		assertThat(until).isBetween(before.plus(properties.budget().reservedRetry()),
				Instant.now().plus(properties.budget().reservedRetry()));
	}

	@Test
	void budgetGatePastItsDeadlineClosesOnAPermitWithinTheLimit() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		jdbc.sql("""
				UPDATE ai_gate SET state = 'OPEN', generation = generation + 1, blocked_until = :past, opened_at = :past
				WHERE cause = 'BUDGET'
				""").param("past", utc(Instant.now().minusSeconds(1))).update();
		model.reply(output());

		assertThat(run.run(claimOne())).isEqualTo(Stop.STORED);

		assertThat(fixtures.gate(GateCause.BUDGET).get("state")).isEqualTo("CLOSED");
		assertThat(fixtures.job(jobId).get("status")).isEqualTo("SUCCEEDED");
	}

	@Test
	void openGateAtThePermitSendsTheJobBack() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		ClaimedJob claimed = claimOne();
		jdbc.sql("UPDATE ai_gate SET state = 'OPEN', generation = generation + 1 WHERE cause = 'CONFIG_CIRCUIT'").update();

		assertDenied(claimed, Stop.DEFERRED, "READY", null);
		assertThat(fixtures.reservations(jobId)).containsOnly("RESERVED");
		assertThat(fixtures.dailySends(device.principalId())).isZero();
	}

	// Plan 5.3: input checks outside any transaction, before any send.
	@Test
	void imageThatIsNoJpegEndsTheJobAndCountsAgainstThePhoto() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.uploads().accepted(device, 1);
		fixtures.upload(device, accepted.requestId(), accepted.job(), fixtures.uploads().image(500).bytes());
		UUID photoId = fixtures.photoOf(accepted.job());

		assertThat(run.run(claimOne())).isEqualTo(Stop.INPUT_FAILED);

		assertThat(fixtures.job(accepted.job()).get("failure_code")).isEqualTo("INPUT_INVALID");
		assertThat(fixtures.photoBudget(photoId).get("input_invalid_count")).isEqualTo(1);
		assertThat(fixtures.calls(accepted.job())).isEmpty();
		assertThat(model.calls()).isZero();
	}

	@Test
	void imageOverTheManifestLimitsEndsTheJob() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.uploads().accepted(device, 1);
		int edge = manifests.current().settings().maxLongEdgePx() + 1;
		fixtures.upload(device, accepted.requestId(), accepted.job(), jpeg(edge, 8));

		assertThat(run.run(claimOne())).isEqualTo(Stop.INPUT_FAILED);

		assertThat(fixtures.job(accepted.job()).get("failure_code")).isEqualTo("INPUT_INVALID");
	}

	@Test
	void changedAndMissingInputsEndTheJob() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.ready(device, 2);
		doReturn(new InputStore.Read.PreconditionFailed()).doReturn(new InputStore.Read.Absent())
			.when(store)
			.getIfMatch(any(), any(), anyInt());

		assertThat(run.run(claimOne())).isEqualTo(Stop.INPUT_FAILED);
		assertThat(run.run(claimOne())).isEqualTo(Stop.INPUT_FAILED);

		assertThat(accepted.jobIds().stream().map((jobId) -> fixtures.job(jobId).get("failure_code")))
			.containsExactlyInAnyOrder("INPUT_CHANGED", "INPUT_MISSING");
		assertThat(model.calls()).isZero();
	}

	@Test
	void unavailableStorageSendsTheJobBack() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		doReturn(new InputStore.Read.Unavailable()).when(store).getIfMatch(any(), any(), anyInt());

		assertThat(run.run(claimOne())).isEqualTo(Stop.DEFERRED);

		assertThat(fixtures.job(jobId).get("status")).isEqualTo("READY");
		assertThat(fixtures.reservations(jobId)).containsOnly("RESERVED");
	}

	// Plan 5.10.
	@Test
	void transientFailuresOpenTheCircuitAndOneProbeClosesIt() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.ready(device, 3);
		for (int i = 0; i < properties.circuit().transientThreshold(); i++) {
			model.reply(new ModelReply.Answered(429, "req_429", null, null, null, Duration.ofHours(1)));
			assertThat(run.run(claimOne())).isEqualTo(Stop.DEFERRED);
		}
		Map<String, Object> opened = fixtures.gate(GateCause.TRANSIENT_CIRCUIT);
		assertThat(opened.get("state")).isEqualTo("OPEN");
		assertThat(claims.claim(5)).isEmpty();
		expireTransient();
		long generation = (Long) opened.get("generation");

		List<ClaimedJob> probe = claims.claim(5);

		assertThat(probe).hasSize(1).allSatisfy((job) -> assertThat(job.probe()).isTrue());
		Map<String, Object> halfOpen = fixtures.gate(GateCause.TRANSIENT_CIRCUIT);
		assertThat(halfOpen.get("state")).isEqualTo("HALF_OPEN");
		assertThat(halfOpen.get("generation")).isEqualTo(generation + 1);
		assertThat(halfOpen.get("probe_job_id")).isEqualTo(probe.getFirst().jobId());
		assertThat(halfOpen.get("probe_run_token")).isEqualTo(probe.getFirst().runToken());
		assertThat(claims.claim(5)).isEmpty();
		model.reply(output());
		assertThat(run.run(probe.getFirst())).isEqualTo(Stop.STORED);
		Map<String, Object> closed = fixtures.gate(GateCause.TRANSIENT_CIRCUIT);
		assertThat(closed.get("state")).isEqualTo("CLOSED");
		assertThat(closed.get("generation")).isEqualTo(generation + 2);
		assertThat(closed.get("failure_streak")).isEqualTo(0);
		assertThat(closed.get("probe_job_id")).isNull();
		assertThat(accepted.jobIds()).contains(probe.getFirst().jobId());
	}

	// C-18: a probe response of an older generation does not close the newer one, which has a probe of its own.
	@Test
	void staleProbeResponseDoesNotCloseANewerGeneration() {
		Installed device = fixtures.intake().android();
		fixtures.ready(device, 1);
		openTransient(Instant.now().minusSeconds(1));
		ClaimedJob probe = claimOne();
		UUID newerProbe = UUID.randomUUID();
		model.reply((jpeg) -> {
			// The monitor reopened the circuit, and a later claim named another probe.
			jdbc.sql("""
					UPDATE ai_gate SET state = 'HALF_OPEN', generation = generation + 2, probe_job_id = :job,
					                   probe_run_token = :token
					WHERE cause = 'TRANSIENT_CIRCUIT'
					""").param("job", newerProbe).param("token", UUID.randomUUID()).update();
			return output();
		});
		long generation = (Long) fixtures.gate(GateCause.TRANSIENT_CIRCUIT).get("generation") + 2;

		assertThat(run.run(probe)).isEqualTo(Stop.STORED);

		Map<String, Object> gate = fixtures.gate(GateCause.TRANSIENT_CIRCUIT);
		assertThat(gate.get("state")).isEqualTo("HALF_OPEN");
		assertThat(gate.get("generation")).isEqualTo(generation);
		assertThat(gate.get("probe_job_id")).isEqualTo(newerProbe);
	}

	// C-18: a probe 400 under a defect that is still there opens the configuration circuit again.
	@Test
	void configurationProbeRejectedOpensTheCircuitAgain() {
		Installed device = fixtures.intake().android();
		UUID jobId = fixtures.ready(device, 1).job();
		jdbc.sql("UPDATE ai_gate SET state = 'HALF_OPEN', generation = generation + 1 WHERE cause = 'CONFIG_CIRCUIT'")
			.update();
		ClaimedJob probe = claimOne();
		assertThat(fixtures.gate(GateCause.CONFIG_CIRCUIT).get("probe_job_id")).isEqualTo(jobId);
		model.reply(status(400));

		run.run(probe);

		Map<String, Object> gate = fixtures.gate(GateCause.CONFIG_CIRCUIT);
		assertThat(gate.get("state")).isEqualTo("OPEN");
		assertThat(gate.get("config_version")).isEqualTo(manifests.configFingerprint());
		assertThat(gate.get("probe_job_id")).isNull();
	}

	// C-18: a response to a call permitted before the circuit last closed counts for nothing.
	@Test
	void responseOfACallPermittedBeforeTheLastCloseDoesNotCount() {
		Installed device = fixtures.intake().android();
		fixtures.ready(device, 1);
		model.reply((jpeg) -> {
			jdbc.sql("UPDATE ai_gate SET reset_at = :now WHERE cause IN ('CONFIG_CIRCUIT', 'TRANSIENT_CIRCUIT')")
				.param("now", utc(Instant.now()))
				.update();
			return status(401);
		});

		run.run(claimOne());

		assertThat(fixtures.gate(GateCause.CONFIG_CIRCUIT).get("state")).isEqualTo("CLOSED");
		assertThat(fixtures.gate(GateCause.TRANSIENT_CIRCUIT).get("failure_streak")).isEqualTo(0);
	}

	// C-18: a probe whose input keeps failing to load leaves the next probe to another job.
	@Test
	void probeThatCouldNotBeSentLeavesTheNextProbeToAnotherJob() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.ready(device, 2);
		openTransient(Instant.now().minusSeconds(1));
		ClaimedJob first = claimOne();
		doReturn(new InputStore.Read.Unavailable()).when(store).getIfMatch(any(), any(), anyInt());

		assertThat(run.run(first)).isEqualTo(Stop.DEFERRED);

		assertThat(fixtures.job(first.jobId()).get("probe_excluded_until")).isNotNull();
		Map<String, Object> gate = fixtures.gate(GateCause.TRANSIENT_CIRCUIT);
		assertThat(gate.get("state")).isEqualTo("HALF_OPEN");
		assertThat(gate.get("probe_job_id")).isNull();
		dueNow(first.jobId());
		ClaimedJob second = claimOne();
		assertThat(second.jobId()).isNotEqualTo(first.jobId()).isIn(accepted.jobIds());
		assertThat(second.probe()).isTrue();
	}

	private UUID groupJob(Installed device, Photo... photos) {
		MvcTestResult result = fixtures.intake().intake(device, key(), body("NEW_PHOTOS", photos));
		assertThat(result).hasStatus(202);
		JsonNode response = json(result);
		assertThat(response.get("jobs")).hasSize(1);
		UUID requestId = UUID.fromString(response.get("jobId").asString());
		UUID jobId = UUID.fromString(response.get("jobs").get(0).get("analysisJobId").asString());
		fixtures.upload(device, requestId, jobId, jpeg(64, 48));
		return jobId;
	}

	private ClaimedJob claimOne() {
		List<ClaimedJob> claimed = claims.claim(1);
		assertThat(claimed).hasSize(1);
		return claimed.getFirst();
	}

	private void assertDenied(ClaimedJob claimed, Stop stop, String status, String failureCode) {
		assertThat(run.run(claimed)).isEqualTo(stop);
		Map<String, Object> job = fixtures.job(claimed.jobId());
		assertThat(job.get("status")).isEqualTo(status);
		assertThat(job.get("failure_code")).isEqualTo(failureCode);
		assertThat(job.get("run_token")).isNull();
		assertThat(fixtures.calls(claimed.jobId())).isEmpty();
		assertThat(model.calls()).isZero();
	}

	private void dueNow(UUID jobId) {
		jdbc.sql("UPDATE analysis_job SET next_attempt_at = :now WHERE id = :id")
			.param("now", utc(Instant.now().minusSeconds(1)))
			.param("id", jobId)
			.update();
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

	private void expireTransient() {
		jdbc.sql("UPDATE ai_gate SET blocked_until = :past WHERE cause = 'TRANSIENT_CIRCUIT'")
			.param("past", utc(Instant.now().minusSeconds(1)))
			.update();
	}

	private void insertResult(UUID ownerId, UUID photoId, UUID jobId) {
		jdbc.sql("""
				INSERT INTO analysis_result (id, owner_id, photo_id, job_id, verification, photo_version,
				                             basis_change_seq, manifest_id, schema_version, output_locale, search_state,
				                             content, epoch, change_seq, created_at, updated_at, version)
				SELECT :id, :owner, :photo, j.id, 'DIRECT', 'v1', 0, j.manifest_id, 1, 'ko', 'EMPTY', '{}', 1, 0, now(),
				       now(), 0
				FROM analysis_job j WHERE j.id = :job
				""")
			.param("id", UUID.randomUUID())
			.param("owner", ownerId)
			.param("photo", photoId)
			.param("job", jobId)
			.update();
	}

	private static ModelReply status(int status) {
		return new ModelReply.Answered(status, "req_" + status, null, null, null, null);
	}

	enum Cost {

		ACTUAL, MAX, ZERO

	}

	record SettlementRow(String name, ModelReply reply, String jobStatus, String failureCode, String callStatus,
			Cost cost, boolean sendSpent) {

		@Override
		public String toString() {
			return name;
		}

	}

}
