package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.AnalysisIntakeTests.item;
import static io.github.gb6105.pik.analysis.AnalysisIntakeTests.jobIds;
import static io.github.gb6105.pik.analysis.IntakeFixtures.T0;
import static io.github.gb6105.pik.analysis.IntakeFixtures.body;
import static io.github.gb6105.pik.analysis.IntakeFixtures.evidence;
import static io.github.gb6105.pik.analysis.IntakeFixtures.json;
import static io.github.gb6105.pik.analysis.IntakeFixtures.key;
import static io.github.gb6105.pik.analysis.IntakeFixtures.photo;
import static io.github.gb6105.pik.analysis.IntakeFixtures.vector;
import static io.github.gb6105.pik.analysis.RunFixtures.jpeg;
import static io.github.gb6105.pik.analysis.RunFixtures.output;
import static io.github.gb6105.pik.analysis.RunFixtures.utc;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.gb6105.pik.analysis.AnalysisRun.Stop;
import io.github.gb6105.pik.analysis.IntakeFixtures.Installed;
import io.github.gb6105.pik.analysis.IntakeFixtures.Photo;
import io.github.gb6105.pik.analysis.JobClaims.ClaimedJob;
import io.github.gb6105.pik.analysis.RunTestConfiguration.CompletedEvents;
import io.github.gb6105.pik.analysis.RunTestConfiguration.ScriptedModel;
import io.github.gb6105.pik.shared.change.ChangeSequence;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.usage.TrialUsage;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The intake decisions of stage 5-2 (plan 8, 11.2 "접수 5c") with results stored by real runs, and the manual
 * regeneration of a result (plan 3.4, 11.2 "수동 재처리"). Shares the context of {@link AnalysisRunTests}.
 */
@AnalysisRunTest
class AnalysisIntakeResultsTests {

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
	AnalysisRun run;

	@Autowired
	ScriptedModel model;

	@Autowired
	CompletedEvents events;

	@Autowired
	AnalysisResults results;

	@Autowired
	AnalysisProperties properties;

	@Autowired
	TrialUsage trialUsage;

	// The spies of the run tests, so that their context is shared.
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

	// The representative and the members of a job that succeeded reuse their results, also at another version.
	@Test
	void photosOfASucceededJobReuseTheirResultsWhateverTheVersion() {
		Installed device = fixtures.intake().android();
		Photo a = photo("v1").groupable(T0, vector(1, 0));
		Photo b = photo("v1").groupable(T0.plusSeconds(30), vector(1, 0.01f));
		Photo c = photo("v1").groupable(T0.plusSeconds(60), vector(1, 0.02f));
		UUID jobId = succeeded(device, a, b, c);
		long reservations = reservations(device);
		String key = key();
		String again = body("NEW_PHOTOS", photo(a.photoId(), "v2"), photo(b.photoId(), "v2"),
				photo(c.photoId(), "v1"));

		MvcTestResult result = fixtures.intake().intake(device, key, again);

		assertThat(result).hasStatus(200);
		JsonNode response = json(result);
		assertExistingResults(response, a, b, c);
		assertThat(item(response, a).get("observedVersion").asString()).isEqualTo("v2");
		assertThat(item(response, c).get("observedVersion").asString()).isEqualTo("v1");
		assertThat(response.get("phase").asString()).isEqualTo("DONE");
		assertThat(counts(response)).containsExactly(3, 0, 3, 0, 0, 0, 0, 0);
		assertThat(response.get("groups")).isEmpty();
		assertThat(response.get("jobs")).isEmpty();
		UUID requestId = UUID.fromString(response.get("jobId").asString());
		for (Photo photo : List.of(a, b, c)) {
			Map<String, Object> stored = storedItem(requestId, photo);
			UUID photoId = serverPhotoId(device, photo);
			assertThat(stored).containsEntry("admission_result", "EXISTING_RESULT")
				.containsEntry("reused_result_id", resultOf(photoId))
				.containsEntry("reused_photo_version", "v1")
				.containsEntry("analysis_job_id", null)
				.containsEntry("retry_at", null);
		}
		assertThat(jdbc.sql("SELECT verification FROM analysis_result WHERE job_id = :job")
			.param("job", jobId)
			.query(String.class)
			.list()).containsExactlyInAnyOrder("DIRECT", "INHERITED", "INHERITED");
		assertThat(count("analysis_job", device)).isOne();
		assertThat(reservations(device)).isEqualTo(reservations);

		// A member alone is answered the same way.
		JsonNode member = json(fixtures.intake().intake(device, key(), body("NEW_PHOTOS", photo(b.photoId(), "v3"))));
		assertExistingResults(member, b);

		// The replay reports the stored decisions with the new item fields.
		MvcTestResult replay = fixtures.intake().intake(device, key, again);
		assertThat(replay).hasStatus(200);
		assertThat(json(replay).get("items")).isEqualTo(response.get("items"));
	}

	// The result is decided before the photo's budget would refuse it.
	@Test
	void resultComesBeforeABlockedInputAndUsedUpSends() {
		Installed device = fixtures.intake().android();
		Photo photo = photo("v1");
		succeeded(device, photo);
		UUID photoId = serverPhotoId(device, photo);
		jdbc.sql("""
				UPDATE photo_ai_budget SET risk_sends = :sends, input_invalid_count = 3, input_blocked_until = :until
				WHERE photo_id = :photo
				""")
			.param("sends", properties.budget().photoLifetimeSends())
			.param("until", utc(Instant.now().plus(Duration.ofHours(1))))
			.param("photo", photoId)
			.update();
		exhaustDevice(device);

		JsonNode response = json(fixtures.intake().intake(device, key(), body("NEW_PHOTOS", photo)));

		assertExistingResults(response, photo);
	}

	// A request whose photos all have results starts no trial: an iOS device without a started trial sends no token.
	@Test
	void requestWithOnlyResultsStartsNoTrial() {
		Installed device = fixtures.intake().ios();
		Photo photo = photo("v1");
		MvcTestResult first = fixtures.intake().intake(device, key(), body("NEW_PHOTOS", photo),
				evidence(device, "fake-assertion"));
		assertThat(first).hasStatus(202);
		runOne(device, json(first));
		// As if the trial had not been started on this device.
		jdbc.sql("UPDATE device_trial_mark SET device_check_state = NULL WHERE id = :id")
			.param("id", device.trialMarkId())
			.update();
		long reservations = reservations(device);

		MvcTestResult reused = fixtures.intake().intake(device, key(), body("NEW_PHOTOS", photo(photo.photoId(), "v2")));

		assertThat(reused).hasStatus(200);
		assertExistingResults(json(reused), photo);
		assertThat(markState(device)).isNull();
		assertThat(reservations(device)).isEqualTo(reservations);

		// A photo without a result needs the trial started, so the token.
		MvcTestResult mixed = fixtures.intake().intake(device, key(), body("NEW_PHOTOS", photo, photo("v1")));
		assertThat(mixed).hasStatus(400);
		assertThat(json(mixed).get("reason").asString()).isEqualTo("DEVICE_CHECK_TOKEN_REQUIRED");
		assertThat(markState(device)).isNull();
	}

	// A job that ended without a result leaves its photo to a new job.
	@Test
	void photoOfAnEndedJobGetsANewJob() {
		Installed device = fixtures.intake().android();
		Photo photo = photo("v1");
		UUID ended = jobOf(json(fixtures.intake().intake(device, key(), body("NEW_PHOTOS", photo))), photo);
		endJob(device, ended);

		MvcTestResult result = fixtures.intake().intake(device, key(), body("NEW_PHOTOS", photo));

		assertThat(result).hasStatus(202);
		JsonNode response = json(result);
		assertThat(item(response, photo).get("admissionResult").asString()).isEqualTo("ACCEPTED");
		assertThat(jobOf(response, photo)).isNotEqualTo(ended);
	}

	// Defensive branch: a reservation still held by an ended job answers the photo IN_PROGRESS on it, and DONE.
	@Test
	void reservationHeldByAnEndedJobIsInProgressAndDone() {
		Installed device = fixtures.intake().android();
		Photo photo = photo("v1");
		UUID ended = jobOf(json(fixtures.intake().intake(device, key(), body("NEW_PHOTOS", photo))), photo);
		jdbc.sql("""
				UPDATE analysis_job SET status = 'CANCELLED', failure_code = 'PROCESSING_EXPIRED', ended_at = now(),
				                        updated_at = now(), version = version + 1
				WHERE id = :id
				""").param("id", ended).update();

		MvcTestResult result = fixtures.intake().intake(device, key(), body("NEW_PHOTOS", photo));

		assertThat(result).hasStatus(200);
		JsonNode response = json(result);
		assertThat(item(response, photo).get("admissionResult").asString()).isEqualTo("IN_PROGRESS");
		assertThat(jobOf(response, photo)).isEqualTo(ended);
		assertThat(response.get("phase").asString()).isEqualTo("DONE");
		assertThat(counts(response)).containsExactly(1, 0, 0, 1, 0, 0, 0, 1);
		assertThat(response.get("jobs").get(0).get("status").asString()).isEqualTo("CANCELLED");
		assertThat(response.get("jobs").get(0).get("reason").asString()).isEqualTo("PROCESSING_EXPIRED");
	}

	// Invalid inputs of a photo block it after the threshold; the refusal says when to send it again.
	@Test
	void photoBlockedByInvalidInputsIsRefusedWithItsRetryTime() {
		Installed device = fixtures.intake().android();
		Photo photo = photo("v1");
		for (int i = 0; i < properties.input().invalidThreshold(); i++) {
			MvcTestResult accepted = fixtures.intake().intake(device, key(), body("NEW_PHOTOS", photo));
			assertThat(accepted).hasStatus(202);
			JsonNode response = json(accepted);
			fixtures.upload(device, UUID.fromString(response.get("jobId").asString()), jobOf(response, photo),
					fixtures.uploads().image(500).bytes());
			assertThat(run.run(claimOne())).isEqualTo(Stop.INPUT_FAILED);
		}
		UUID photoId = serverPhotoId(device, photo);
		Instant blockedUntil = RunFixtures.instant(fixtures.photoBudget(photoId).get("input_blocked_until"));
		assertThat(blockedUntil).isAfter(Instant.now().plus(properties.input().blockDuration()).minusSeconds(60));
		String key = key();

		MvcTestResult result = fixtures.intake().intake(device, key, body("NEW_PHOTOS", photo));

		assertThat(result).hasStatus(200);
		JsonNode response = json(result);
		JsonNode item = item(response, photo);
		assertRefused(item, "RATE_LIMITED", "INPUT_RETRY_LATER", true);
		long retryAfter = item.get("retryAfter").asLong();
		long blockSeconds = properties.input().blockDuration().toSeconds();
		assertThat(retryAfter).isBetween(blockSeconds - 60, blockSeconds);
		UUID requestId = UUID.fromString(response.get("jobId").asString());
		assertThat(RunFixtures.instant(storedItem(requestId, photo).get("retry_at"))).isEqualTo(blockedUntil);
		assertThat(response.get("jobs")).isEmpty();

		// The replay and the status give the same wait.
		MvcTestResult replay = fixtures.intake().intake(device, key, body("NEW_PHOTOS", photo));
		assertThat(item(json(replay), photo).get("retryAfter").asLong()).isEqualTo(retryAfter);
		assertThat(item(json(fixtures.uploads().status(device, requestId, null)), photo).get("retryAfter").asLong())
			.isEqualTo(retryAfter);

		// Once the block is over, the photo gets a job again.
		jdbc.sql("UPDATE photo_ai_budget SET input_blocked_until = :until WHERE photo_id = :photo")
			.param("until", utc(Instant.now().minusSeconds(1)))
			.param("photo", photoId)
			.update();
		MvcTestResult later = fixtures.intake().intake(device, key(), body("NEW_PHOTOS", photo));
		assertThat(later).hasStatus(202);
		assertThat(item(json(later), photo).get("admissionResult").asString()).isEqualTo("ACCEPTED");
	}

	// The photo's used-up sends refuse it for good; a blocked input comes first.
	@Test
	void photoWithoutSendsLeftIsRefusedAfterABlockedInput() {
		Installed device = fixtures.intake().android();
		Photo spent = photo("v1");
		Photo blocked = photo("v1");
		for (Photo photo : List.of(spent, blocked)) {
			UUID jobId = jobOf(json(fixtures.intake().intake(device, key(), body("NEW_PHOTOS", photo))), photo);
			endJob(device, jobId);
			budget(device, photo, properties.budget().photoLifetimeSends(), null);
		}
		Instant until = Instant.now().plus(Duration.ofHours(2));
		budget(device, blocked, properties.budget().photoLifetimeSends(), until);

		MvcTestResult result = fixtures.intake().intake(device, key(), body("NEW_PHOTOS", spent, blocked));

		assertThat(result).hasStatus(200);
		JsonNode response = json(result);
		assertRefused(item(response, spent), "FORBIDDEN", "ATTEMPTS_EXHAUSTED", false);
		assertThat(item(response, spent).get("retryAfter").isNull()).isTrue();
		assertRefused(item(response, blocked), "RATE_LIMITED", "INPUT_RETRY_LATER", true);
		assertThat(counts(response)).containsExactly(2, 0, 0, 0, 2, 0, 0, 0);
		assertThat(count("analysis_job", device)).isEqualTo(2);
	}

	// A trial device without sends left gets no new job; the photo's own refusal comes first.
	@Test
	void trialDeviceWithoutSendsLeftGetsNoNewJob() {
		Installed device = fixtures.intake().android();
		Photo spent = photo("v1");
		UUID jobId = jobOf(json(fixtures.intake().intake(device, key(), body("NEW_PHOTOS", spent))), spent);
		endJob(device, jobId);
		budget(device, spent, properties.budget().photoLifetimeSends(), null);
		exhaustDevice(device);
		long reservations = reservations(device);
		Photo added = photo("v1");

		MvcTestResult result = fixtures.intake().intake(device, key(), body("NEW_PHOTOS", spent, added));

		assertThat(result).hasStatus(200);
		JsonNode response = json(result);
		assertRefused(item(response, spent), "FORBIDDEN", "ATTEMPTS_EXHAUSTED", false);
		assertRefused(item(response, added), "FORBIDDEN", "ANALYSIS_UNAVAILABLE", false);
		assertThat(response.get("groups")).isEmpty();
		assertThat(response.get("jobs")).isEmpty();
		assertThat(reservations(device)).isEqualTo(reservations);
	}

	// Each decision of 5c in one request.
	@Test
	void mixedRequestDecidesEachPhoto() {
		Installed device = fixtures.intake().android();
		Photo reused = photo("v1");
		succeeded(device, reused);
		Photo waiting = photo("v1");
		UUID waitingJob = jobOf(json(fixtures.intake().intake(device, key(), body("NEW_PHOTOS", waiting))), waiting);
		Photo spent = photo("v1");
		endJob(device, jobOf(json(fixtures.intake().intake(device, key(), body("NEW_PHOTOS", spent))), spent));
		budget(device, spent, properties.budget().photoLifetimeSends(), null);
		Photo added = photo("v1");

		MvcTestResult result = fixtures.intake().intake(device, key(), body("NEW_PHOTOS", reused, waiting, spent, added));

		assertThat(result).hasStatus(202);
		JsonNode response = json(result);
		assertExistingResults(response, reused);
		assertThat(item(response, waiting).get("admissionResult").asString()).isEqualTo("IN_PROGRESS");
		assertThat(jobOf(response, waiting)).isEqualTo(waitingJob);
		assertRefused(item(response, spent), "FORBIDDEN", "ATTEMPTS_EXHAUSTED", false);
		assertThat(item(response, added).get("admissionResult").asString()).isEqualTo("ACCEPTED");
		assertThat(counts(response)).containsExactly(4, 1, 1, 1, 1, 2, 0, 0);
		assertThat(jobIds(response)).containsExactlyInAnyOrder(waitingJob.toString(),
				jobOf(response, added).toString());
		assertThat(response.get("groups")).hasSize(1);
	}

	@Test
	void regenerationRaisesTheEpochWithANewChangeAndPublishesAfterCommit() {
		Installed device = fixtures.intake().android();
		Photo photo = photo("v1");
		succeeded(device, photo);
		UUID photoId = serverPhotoId(device, photo);
		AnalysisResult before = results.find(device.principalId(), photoId).orElseThrow();
		events.clear();

		int epoch = transaction.execute((status) -> {
			int raised = results.regenerate(device.principalId(), photoId, 1);
			assertThat(events.events()).isEmpty();
			return raised;
		});

		assertThat(epoch).isEqualTo(2);
		AnalysisResult after = results.find(device.principalId(), photoId).orElseThrow();
		assertThat(after.epoch()).isEqualTo(2);
		assertThat(after.resultId()).isEqualTo(before.resultId());
		assertThat(after.content()).isEqualTo(before.content());
		assertThat(after.changeSeq()).isGreaterThan(before.changeSeq());
		assertThat(after.changeSeq()).isEqualTo(lastChangeSeq(device));
		assertThat(events.events())
			.containsExactly(new AnalysisCompleted(device.principalId(), photoId, before.resultId(), 2));
		assertThat(model.calls()).isOne();

		assertThat(results.regenerate(device.principalId(), photoId, 2)).isEqualTo(3);
		assertThat(events.events()).hasSize(2);
	}

	@Test
	void regenerationWithAnOldEpochConflictsAndChangesNothing() {
		Installed device = fixtures.intake().android();
		Photo photo = photo("v1");
		succeeded(device, photo);
		UUID photoId = serverPhotoId(device, photo);
		results.regenerate(device.principalId(), photoId, 1);
		AnalysisResult current = results.find(device.principalId(), photoId).orElseThrow();
		events.clear();

		assertThatThrownBy(() -> results.regenerate(device.principalId(), photoId, 1))
			.isInstanceOfSatisfying(PikException.class, (ex) -> assertError(ex, ErrorCode.CONFLICT, "EPOCH_CONFLICT"));
		assertThatThrownBy(() -> results.regenerate(device.principalId(), photoId, 3))
			.isInstanceOfSatisfying(PikException.class, (ex) -> assertError(ex, ErrorCode.CONFLICT, "EPOCH_CONFLICT"));

		AnalysisResult after = results.find(device.principalId(), photoId).orElseThrow();
		assertThat(after.epoch()).isEqualTo(2);
		assertThat(after.changeSeq()).isEqualTo(current.changeSeq());
		assertThat(events.events()).isEmpty();
	}

	@Test
	void regenerationOfAnotherOwnersResultIsNotFound() {
		Installed device = fixtures.intake().android();
		Photo photo = photo("v1");
		succeeded(device, photo);
		UUID photoId = serverPhotoId(device, photo);
		Installed other = fixtures.intake().android();

		assertThatThrownBy(() -> results.regenerate(other.principalId(), photoId, 1)).isInstanceOfSatisfying(
				PikException.class, (ex) -> assertError(ex, ErrorCode.NOT_FOUND, "RESOURCE_NOT_FOUND"));
		assertThatThrownBy(() -> results.regenerate(device.principalId(), UUID.randomUUID(), 1))
			.isInstanceOfSatisfying(PikException.class,
					(ex) -> assertError(ex, ErrorCode.NOT_FOUND, "RESOURCE_NOT_FOUND"));
		assertThat(results.find(device.principalId(), photoId).orElseThrow().epoch()).isOne();
		assertThat(events.events()).hasSize(1);
	}

	// Two regenerations from the same epoch: the second waits for the first's row lock and then conflicts.
	@Test
	void concurrentRegenerationsFromTheSameEpochSucceedOnce() throws Exception {
		Installed device = fixtures.intake().android();
		Photo photo = photo("v1");
		succeeded(device, photo);
		UUID photoId = serverPhotoId(device, photo);
		events.clear();
		CountDownLatch regenerated = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		AtomicInteger holder = new AtomicInteger();
		Future<Integer> first = pool.submit(() -> transaction.execute((status) -> {
			int epoch = results.regenerate(device.principalId(), photoId, 1);
			holder.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
			regenerated.countDown();
			awaitLatch(commit);
			return epoch;
		}));
		awaitLatch(regenerated);
		Future<Integer> second = pool.submit(() -> results.regenerate(device.principalId(), photoId, 1));
		awaitBlockedBy(holder.get(), "analysis_result");

		commit.countDown();

		assertThat(first.get(WAIT.toSeconds(), SECONDS)).isEqualTo(2);
		Throwable failure = null;
		try {
			second.get(WAIT.toSeconds(), SECONDS);
		}
		catch (ExecutionException ex) {
			failure = ex.getCause();
		}
		assertThat(failure).isInstanceOfSatisfying(PikException.class,
				(ex) -> assertError(ex, ErrorCode.CONFLICT, "EPOCH_CONFLICT"));
		assertThat(results.find(device.principalId(), photoId).orElseThrow().epoch()).isEqualTo(2);
		assertThat(events.events()).hasSize(1);
	}

	/**
	 * Accepts the photos as one job, uploads its image and runs it to its results.
	 */
	private UUID succeeded(Installed device, Photo... photos) {
		MvcTestResult result = fixtures.intake().intake(device, key(), body("NEW_PHOTOS", photos));
		assertThat(result).hasStatus(202);
		return runOne(device, json(result));
	}

	private UUID runOne(Installed device, JsonNode accepted) {
		assertThat(accepted.get("jobs")).hasSize(1);
		UUID requestId = UUID.fromString(accepted.get("jobId").asString());
		UUID jobId = UUID.fromString(accepted.get("jobs").get(0).get("analysisJobId").asString());
		fixtures.upload(device, requestId, jobId, jpeg(64, 48));
		model.reply(output());
		assertThat(run.run(claimOne())).isEqualTo(Stop.STORED);
		assertThat(fixtures.job(jobId).get("status")).isEqualTo("SUCCEEDED");
		return jobId;
	}

	private ClaimedJob claimOne() {
		List<ClaimedJob> claimed = claims.claim(1);
		assertThat(claimed).hasSize(1);
		return claimed.getFirst();
	}

	// Ends the job and returns its reservations, as a transition of stage 5-2 does.
	private void endJob(Installed device, UUID jobId) {
		transaction.executeWithoutResult((status) -> {
			jdbc.sql("""
					UPDATE analysis_job SET status = 'CANCELLED', failure_code = 'PROCESSING_EXPIRED', ended_at = now(),
					                        updated_at = now(), version = version + 1
					WHERE id = :id
					""").param("id", jobId).update();
			trialUsage.release(device.principalId(), jobId);
		});
	}

	private void budget(Installed device, Photo photo, int riskSends, @Nullable Instant blockedUntil) {
		jdbc.sql("""
				INSERT INTO photo_ai_budget (photo_id, owner_id, risk_sends, unknown_retry_used, input_invalid_count,
				                             input_blocked_until, updated_at, version)
				VALUES (:photo, :owner, :sends, false, 0, :until, now(), 0)
				ON CONFLICT (photo_id) DO UPDATE SET risk_sends = :sends, input_blocked_until = :until
				""")
			.param("photo", serverPhotoId(device, photo))
			.param("owner", device.principalId())
			.param("sends", riskSends)
			.param("until", (blockedUntil != null) ? utc(blockedUntil) : null, Types.TIMESTAMP_WITH_TIMEZONE)
			.update();
	}

	private void exhaustDevice(Installed device) {
		jdbc.sql("UPDATE device_trial_mark SET send_count = limit_count * :multiplier WHERE id = :id")
			.param("multiplier", properties.budget().deviceSendMultiplier())
			.param("id", device.trialMarkId())
			.update();
	}

	private static void assertExistingResults(JsonNode response, Photo... photos) {
		for (Photo photo : photos) {
			JsonNode item = item(response, photo);
			assertThat(item.get("admissionResult").asString()).isEqualTo("EXISTING_RESULT");
			assertThat(item.get("code").isNull()).isTrue();
			assertThat(item.get("reason").isNull()).isTrue();
			assertThat(item.get("retryable").asBoolean()).isFalse();
			assertThat(item.get("retryAfter").isNull()).isTrue();
			assertThat(item.get("analysisJobId").isNull()).isTrue();
			assertThat(item.get("groupId").isNull()).isTrue();
			assertThat(item.get("execution").isNull()).isTrue();
			assertThat(item.get("analyzedVersion").asString()).isEqualTo("v1");
		}
	}

	private static void assertRefused(JsonNode item, String code, String reason, boolean retryable) {
		assertThat(item.get("admissionResult").asString()).isEqualTo("NOT_ACCEPTED");
		assertThat(item.get("code").asString()).isEqualTo(code);
		assertThat(item.get("reason").asString()).isEqualTo(reason);
		assertThat(item.get("retryable").asBoolean()).isEqualTo(retryable);
		assertThat(item.get("analysisJobId").isNull()).isTrue();
		assertThat(item.get("groupId").isNull()).isTrue();
	}

	private static void assertError(PikException ex, ErrorCode code, String reason) {
		assertThat(ex.getCode()).isEqualTo(code);
		assertThat(ex.getReason()).isEqualTo(reason);
		assertThat(ex.isRetryable()).isFalse();
	}

	private static UUID jobOf(JsonNode response, Photo photo) {
		return UUID.fromString(item(response, photo).get("analysisJobId").asString());
	}

	private static List<Integer> counts(JsonNode response) {
		JsonNode counts = response.get("counts");
		List<Integer> values = new ArrayList<>();
		Stream.of("requested", "accepted", "existingResult", "inProgress", "notAccepted", "waiting", "running", "done")
			.forEach((name) -> values.add(counts.get(name).asInt()));
		return values;
	}

	private Map<String, Object> storedItem(UUID requestId, Photo photo) {
		return jdbc.sql("SELECT * FROM analysis_request_item WHERE request_id = :request AND client_ref = :ref")
			.param("request", requestId)
			.param("ref", photo.photoId())
			.query()
			.singleRow();
	}

	private UUID serverPhotoId(Installed device, Photo photo) {
		return jdbc.sql("SELECT id FROM photo WHERE owner_id = :owner AND client_ref = :ref")
			.param("owner", device.principalId())
			.param("ref", photo.photoId())
			.query(UUID.class)
			.single();
	}

	private UUID resultOf(UUID photoId) {
		return jdbc.sql("SELECT id FROM analysis_result WHERE photo_id = :photo")
			.param("photo", photoId)
			.query(UUID.class)
			.single();
	}

	private long count(String table, Installed device) {
		return jdbc.sql("SELECT count(*) FROM " + table + " WHERE owner_id = :owner")
			.param("owner", device.principalId())
			.query(Long.class)
			.single();
	}

	private long reservations(Installed device) {
		return jdbc.sql("SELECT count(*) FROM usage_reservation WHERE principal_id = :principal")
			.param("principal", device.principalId())
			.query(Long.class)
			.single();
	}

	private long lastChangeSeq(Installed device) {
		return jdbc.sql("SELECT last_seq FROM owner_change_counter WHERE owner_id = :owner")
			.param("owner", device.principalId())
			.query(Long.class)
			.single();
	}

	private @Nullable String markState(Installed device) {
		return jdbc.sql("SELECT device_check_state FROM device_trial_mark WHERE id = :id")
			.param("id", device.trialMarkId())
			.query(String.class)
			.optional()
			.orElse(null);
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

}
