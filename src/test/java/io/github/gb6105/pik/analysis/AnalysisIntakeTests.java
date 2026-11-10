package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.IntakeFixtures.T0;
import static io.github.gb6105.pik.analysis.IntakeFixtures.body;
import static io.github.gb6105.pik.analysis.IntakeFixtures.evidence;
import static io.github.gb6105.pik.analysis.IntakeFixtures.json;
import static io.github.gb6105.pik.analysis.IntakeFixtures.key;
import static io.github.gb6105.pik.analysis.IntakeFixtures.photo;
import static io.github.gb6105.pik.analysis.IntakeFixtures.vector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.account.AttestationBinding;
import io.github.gb6105.pik.account.AttestationEvidence;
import io.github.gb6105.pik.account.TrialStartAttestations;
import io.github.gb6105.pik.analysis.IntakeFixtures.Installed;
import io.github.gb6105.pik.analysis.IntakeFixtures.Photo;
import io.github.gb6105.pik.photo.PhotoSourceChanged;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.security.AccessTokens;
import io.github.gb6105.pik.shared.security.PrincipalKind;
import io.github.gb6105.pik.usage.TrialUsage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * I-1 to I-20 of plan 8.2 with grouping off: {@code POST /analysis-jobs} against PostgreSQL with real installs,
 * tokens and consents. Grouping on is in {@link AnalysisIntakeGroupingTests}, intake off in
 * {@link AnalysisIntakeDisabledTests}.
 */
@Import({ TestcontainersConfiguration.class, AnalysisIntakeTests.CommittedSourceChanges.class })
@SpringBootTest(properties = { "pik.analysis.intake-enabled=true", "pik.usage.trial.limit=5" })
@AutoConfigureMockMvc
@RecordApplicationEvents
class AnalysisIntakeTests {

	static final Comparator<Photo> PHOTO_ORDER = Comparator.comparing(Photo::photoId, GroupingRuleV1.PHOTO_ID_ORDER);

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	CallManifests callManifests;

	@Autowired
	GroupingRules groupingRules;

	@Autowired
	AccessTokens accessTokens;

	@Autowired
	ApplicationEvents events;

	@Autowired
	CommittedSourceChanges committed;

	@MockitoSpyBean
	TrialUsage trialUsage;

	@MockitoSpyBean
	TrialStartAttestations attestations;

	@MockitoSpyBean
	IntakeProjection projection;

	// The DeviceCheck port is internal to the usage module; its calls are counted by method name.
	@MockitoSpyBean(name = "deviceCheck")
	Object deviceCheck;

	IntakeFixtures fixtures;

	@BeforeEach
	void fixtures() {
		fixtures = new IntakeFixtures(mvc, jdbc);
	}

	// I-1
	@Test
	void androidRequestIsAcceptedWithJobsAwaitingUpload() {
		Installed device = fixtures.android();
		String key = key();
		Photo older = photo("v1").takenAt(T0);
		Photo newer = photo("v1").takenAt(T0.plusSeconds(600));

		MvcTestResult result = fixtures.intake(device, key, body("NEW_PHOTOS", older, newer));

		assertThat(result).hasStatus(202);
		JsonNode response = json(result);
		UUID requestId = UUID.fromString(response.get("jobId").asString());
		assertThat(response.get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(response.get("retryable").asBoolean()).isFalse();
		assertThat(response.get("phase").asString()).isEqualTo("WAITING");
		assertThat(counts(response)).containsExactly(2, 2, 0, 0, 0, 2, 0, 0);
		assertThat(response.get("items")).hasSize(2).allSatisfy((item) -> {
			assertThat(item.get("admissionResult").asString()).isEqualTo("ACCEPTED");
			assertThat(item.get("role").asString()).isEqualTo("REPRESENTATIVE");
			assertThat(item.get("execution").asString()).isEqualTo("AWAITING_UPLOAD");
			assertThat(item.get("observedVersion").asString()).isEqualTo("v1");
			assertThat(item.get("analyzedVersion").asString()).isEqualTo("v1");
			assertThat(item.get("code").isNull()).isTrue();
			assertThat(item.get("retryable").asBoolean()).isFalse();
			assertThat(item.get("vectorIgnored").isNull()).isTrue();
		});
		assertThat(response.get("groups")).hasSize(2)
			.allSatisfy((group) -> assertThat(group.get("photoCount").asInt()).isEqualTo(1));
		assertThat(response.get("jobs")).hasSize(2).allSatisfy((job) -> {
			assertThat(job.get("requestId").asString()).isEqualTo(requestId.toString());
			assertThat(job.get("status").asString()).isEqualTo("AWAITING_UPLOAD");
			assertThat(job.get("uploadRequired").asBoolean()).isTrue();
			assertThat(job.get("uploadPolicy").get("contentType").asString()).isEqualTo("image/jpeg");
			assertThat(job.get("uploadPolicy").get("maxBytes").asLong()).isEqualTo(2_097_152);
			assertThat(job.get("uploadPolicy").get("maxLongEdgePx").asInt()).isEqualTo(1568);
		});
		String text = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
		assertThat(text).doesNotContainIgnoringCase("url").doesNotContain("://");

		assertThat(jdbc.sql("SELECT * FROM analysis_request WHERE id = :id").param("id", requestId).query().singleRow())
			.containsEntry("owner_id", device.principalId())
			.containsEntry("origin", "CLIENT")
			.containsEntry("trigger", "NEW_PHOTOS")
			.containsEntry("device_id", device.deviceId())
			.containsEntry("trial_mark_id", device.trialMarkId())
			.containsEntry("consent_version", 1L)
			.containsEntry("grouping_rule_id", groupingRules.currentId())
			.containsEntry("photo_count", 2)
			.containsEntry("representative_count", 2)
			.containsEntry("first_result_at", null);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM analysis_job
				WHERE request_id = :id AND funding = 'TRIAL' AND status = 'AWAITING_UPLOAD' AND base_priority = 0
				  AND manifest_id = :manifest AND photo_version = 'v1'
				  AND upload_deadline = queued_at + interval '24 hours'
				  AND processing_deadline = queued_at + interval '48 hours'
				""").param("id", requestId).param("manifest", callManifests.currentId()).query(Long.class).single())
			.isEqualTo(2);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM usage_reservation r JOIN analysis_job j ON j.id = r.job_id
				WHERE r.principal_id = :owner AND j.request_id = :id AND r.status = 'RESERVED'
				""").param("owner", device.principalId()).param("id", requestId).query(Long.class).single())
			.isEqualTo(2);
		assertThat(storedStatus(device, key)).contains(202);
	}

	// I-1: the trigger fixes the base priority.
	@Test
	void organizeRequestGetsTheLowerBasePriority() {
		Installed device = fixtures.android();

		MvcTestResult result = fixtures.intake(device, key(), body("ORGANIZE", photo("v1")));

		assertThat(result).hasStatus(202);
		assertThat(jdbc.sql("SELECT base_priority FROM analysis_job WHERE request_id = :id")
			.param("id", requestId(result))
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	// I-2
	@Test
	void photosInProgressAreAnsweredWithTheirJobs() {
		Installed device = fixtures.android();
		Photo first = photo("v1");
		Photo second = photo("v1");
		JsonNode accepted = json(fixtures.intake(device, key(), body("NEW_PHOTOS", first, second)));

		MvcTestResult again = fixtures.intake(device, key(), body("NEW_PHOTOS", first, second));

		assertThat(again).hasStatus(200);
		JsonNode response = json(again);
		assertThat(response.get("phase").asString()).isEqualTo("WAITING");
		assertThat(counts(response)).containsExactly(2, 0, 0, 2, 0, 2, 0, 0);
		assertThat(jobOf(response, first)).isEqualTo(jobOf(accepted, first));
		assertThat(jobOf(response, second)).isEqualTo(jobOf(accepted, second));
		assertThat(response.get("items")).allSatisfy((item) -> {
			assertThat(item.get("admissionResult").asString()).isEqualTo("IN_PROGRESS");
			assertThat(item.get("groupId").isNull()).isTrue();
			assertThat(item.get("role").isNull()).isTrue();
			assertThat(item.get("execution").asString()).isEqualTo("AWAITING_UPLOAD");
		});
		assertThat(response.get("groups")).isEmpty();
		// The jobs the photos wait for, with the request that created them, in the photoId order.
		assertThat(jobIds(response)).containsExactlyElementsOf(
				Stream.of(first, second).sorted(PHOTO_ORDER).map((photo) -> jobOf(accepted, photo)).toList());
		assertThat(response.get("jobs")).allSatisfy((job) -> {
			assertThat(job.get("requestId")).isEqualTo(accepted.get("jobId"));
			assertThat(job.get("status").asString()).isEqualTo("AWAITING_UPLOAD");
			assertThat(job.get("uploadRequired").asBoolean()).isTrue();
			assertThat(job.get("uploadPolicy").get("contentType").asString()).isEqualTo("image/jpeg");
		});
		assertThat(count("analysis_job", device)).isEqualTo(2);
		assertThat(count("usage_reservation", "principal_id", device)).isEqualTo(2);
		assertThat(count("analysis_request", device)).isEqualTo(2);
		assertThat(count("analysis_request_item", device)).isEqualTo(4);
	}

	// I-2: a request mixing a photo in progress and a new one creates a job for the new one only.
	@Test
	void newPhotoNextToOneInProgressGetsAJob() {
		Installed device = fixtures.android();
		Photo first = photo("v1");
		JsonNode earlier = json(fixtures.intake(device, key(), body("NEW_PHOTOS", first)));
		Photo added = photo("v1");

		MvcTestResult result = fixtures.intake(device, key(), body("NEW_PHOTOS", first, added));

		assertThat(result).hasStatus(202);
		JsonNode response = json(result);
		assertThat(item(response, first).get("admissionResult").asString()).isEqualTo("IN_PROGRESS");
		assertThat(item(response, added).get("admissionResult").asString()).isEqualTo("ACCEPTED");
		// Its own job and the one the photo in progress waits for.
		assertThat(jobIds(response)).containsExactlyElementsOf(
				Stream.of(first, added).sorted(PHOTO_ORDER).map((photo) -> jobOf(response, photo)).toList());
		assertThat(job(response, jobOf(response, first)).get("requestId")).isEqualTo(earlier.get("jobId"));
		assertThat(job(response, jobOf(response, added)).get("requestId")).isEqualTo(response.get("jobId"));
		assertThat(counts(response)).containsExactly(2, 1, 0, 1, 0, 2, 0, 0);
	}

	// I-4 with single photos
	@Test
	void photosBeyondTheTrialNeedASubscription() {
		Installed device = fixtures.android();
		List<Photo> photos = IntStream.range(0, 6).mapToObj((i) -> photo("v1").takenAt(T0.plusSeconds(i))).toList();

		MvcTestResult result = fixtures.intake(device, key(), body("NEW_PHOTOS", photos.toArray(Photo[]::new)));

		// The newest five fit; the oldest would fit once pending reservations are released.
		assertThat(result).hasStatus(202);
		JsonNode response = json(result);
		assertThat(counts(response)).containsExactly(6, 5, 0, 0, 1, 5, 0, 0);
		JsonNode refused = item(response, photos.getFirst());
		assertThat(refused.get("admissionResult").asString()).isEqualTo("NOT_ACCEPTED");
		assertThat(refused.get("code").asString()).isEqualTo("FORBIDDEN");
		assertThat(refused.get("reason").asString()).isEqualTo("SUBSCRIPTION_REQUIRED");
		assertThat(refused.get("retryable").asBoolean()).isTrue();
		assertThat(refused.get("analysisJobId").isNull()).isTrue();
		assertThat(refused.get("groupId").isNull()).isFalse();
		// The trial's size and what is left of it are not part of the response.
		assertThat(response.get("counts").propertyNames()).containsExactlyInAnyOrder("requested", "accepted",
				"existingResult", "inProgress", "notAccepted", "waiting", "running", "done");
		assertThat(response.get("items")).allSatisfy((item) -> assertThat(item.propertyNames())
			.containsExactlyInAnyOrder("photoId", "admissionResult", "code", "reason", "retryable", "retryAfter",
					"analysisJobId", "groupId", "role", "execution", "observedVersion", "analyzedVersion",
					"vectorIgnored"));

		// Consumed photos never come back.
		List<UUID> jobs = jdbc.sql("SELECT id FROM analysis_job WHERE request_id = :id")
			.param("id", requestId(result))
			.query(UUID.class)
			.list();
		transaction.executeWithoutResult(
				(status) -> jobs.forEach((job) -> trialUsage.consume(device.principalId(), job)));
		MvcTestResult exhausted = fixtures.intake(device, key(), body("NEW_PHOTOS", photo("v1")));

		assertThat(exhausted).hasStatus(200);
		JsonNode last = json(exhausted).get("items").get(0);
		assertThat(last.get("reason").asString()).isEqualTo("SUBSCRIPTION_REQUIRED");
		assertThat(last.get("retryable").asBoolean()).isFalse();
		assertThat(json(exhausted).get("phase").asString()).isEqualTo("DONE");
	}

	// I-5 and I-6 with grouping off
	@Test
	void groupingOffKeepsPhotosSingleAndStoresNoVector() {
		Installed device = fixtures.android();
		Photo first = photo("v1").groupable(T0, vector(1, 0));
		Photo second = photo("v1").groupable(T0.plusSeconds(1), vector(1, 0));
		Photo threeBytes = photo("v1").groupable(T0.plusSeconds(2), "AAAA");
		Photo notBase64 = photo("v1").groupable(T0.plusSeconds(3), "@@@@");

		MvcTestResult result = fixtures.intake(device, key(),
				body("NEW_PHOTOS", first, second, threeBytes, notBase64));

		assertThat(result).hasStatus(202);
		JsonNode response = json(result);
		assertThat(response.get("groups")).hasSize(4)
			.allSatisfy((group) -> assertThat(group.get("photoCount").asInt()).isEqualTo(1));
		assertThat(item(response, first).get("vectorIgnored").isNull()).isTrue();
		assertThat(item(response, threeBytes).get("vectorIgnored").asString()).isEqualTo("BAD_DIMENSION");
		assertThat(item(response, threeBytes).get("admissionResult").asString()).isEqualTo("ACCEPTED");
		assertThat(item(response, notBase64).get("vectorIgnored").asString()).isEqualTo("MALFORMED_BASE64");
		assertThat(count("photo_vector", device)).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM analysis_request_item WHERE request_id = :id AND vector_id IS NOT NULL")
			.param("id", requestId(result))
			.query(Long.class)
			.single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM analysis_group WHERE request_id = :id AND model_id IS NULL")
			.param("id", requestId(result))
			.query(Long.class)
			.single()).isEqualTo(4);
	}

	// I-7
	@Test
	void observationsRaiseTheSourceChangeOnlyWhenTheVersionDiffers() {
		Installed device = fixtures.android();
		UUID photoId = UUID.randomUUID();

		JsonNode created = json(fixtures.intake(device, key(), body("NEW_PHOTOS", photo(photoId, "A"))));
		Map<String, Object> first = photoRow(device, photoId);
		assertThat(first).containsEntry("source_change_seq", 0L).containsEntry("version", 0L);
		long issued = (Long) first.get("change_seq");
		assertThat(issued).isPositive();
		assertThat(basisChangeSeq(created, photoId)).isZero();

		fixtures.intake(device, key(), body("NEW_PHOTOS", photo(photoId, "A")));
		assertThat(photoRow(device, photoId)).containsEntry("source_change_seq", 0L)
			.containsEntry("change_seq", issued);
		assertThat(changes(device)).isEmpty();

		JsonNode changed = json(fixtures.intake(device, key(), body("NEW_PHOTOS", photo(photoId, "B"))));
		Map<String, Object> second = photoRow(device, photoId);
		assertThat(second).containsEntry("source_change_seq", 1L)
			.containsEntry("observed_version", "B")
			.containsEntry("version", 0L);
		assertThat((Long) second.get("change_seq")).isGreaterThan(issued);
		assertThat(basisChangeSeq(changed, photoId)).isEqualTo(1);
		assertThat(item(changed, photoId).get("observedVersion").asString()).isEqualTo("B");
		// The job still analyzes the version it was accepted for.
		assertThat(item(changed, photoId).get("analyzedVersion").asString()).isEqualTo("A");
		UUID serverPhotoId = (UUID) second.get("id");
		assertThat(changes(device)).containsExactly(new PhotoSourceChanged(device.principalId(), serverPhotoId, 1));

		fixtures.intake(device, key(), body("NEW_PHOTOS", photo(photoId, "A")));
		Map<String, Object> third = photoRow(device, photoId);
		assertThat(third).containsEntry("source_change_seq", 2L);
		assertThat((Long) third.get("change_seq")).isGreaterThan((Long) second.get("change_seq"));
		assertThat(changes(device)).hasSize(2);
		assertThat(jdbc.sql("SELECT count(*) FROM photo WHERE owner_id = :owner AND change_seq = 0")
			.param("owner", device.principalId())
			.query(Long.class)
			.single()).isZero();
	}

	// I-8
	@Test
	void firstIosTrialNeedsAnAssertionAndTheSameKeyGetsPastTheRefusal() {
		Installed device = fixtures.ios();
		String key = key();
		String body = body("NEW_PHOTOS", photo("v1"));

		MvcTestResult required = fixtures.intake(device, key, body,
				Map.of("Device-Check-Token", device.deviceCheckToken()));

		assertThat(required).hasStatus(403);
		JsonNode problem = json(required);
		assertError(problem, "FORBIDDEN", "ATTESTATION_REQUIRED", true);
		JsonNode challenge = problem.get("challenge");
		assertThat(challenge.get("challengeId").asString()).isNotBlank();
		assertThat(challenge.get("nonce").asString()).isNotBlank();
		assertThat(challenge.get("expiresAt").asString()).isNotBlank();
		assertNothingStored(device, key);
		assertThat(markState(device)).isNull();

		MvcTestResult accepted = fixtures.intake(device, key, body, evidence(device, "fake-assertion", challenge));

		assertThat(accepted).hasStatus(202);
		assertThat(markState(device)).isEqualTo("TRIAL_STARTED");

		// A started device sends no evidence and no token.
		MvcTestResult started = fixtures.intake(device, key(), body("NEW_PHOTOS", photo("v1")));
		assertThat(started).hasStatus(202);
	}

	// I-9
	@Test
	void evidenceErrorsAreNotStoredAndBetterEvidenceSucceeds() {
		Installed device = fixtures.ios();
		String key = key();
		String body = body("NEW_PHOTOS", photo("v1"));

		MvcTestResult stale = fixtures.intake(device, key, body, evidence(device, "fake-assertion-stale"));
		assertThat(stale).hasStatus(403);
		assertError(json(stale), "FORBIDDEN", "ATTESTATION_REQUIRED", true);
		assertThat(json(stale).get("challenge").get("nonce").asString()).isNotBlank();

		MvcTestResult race = fixtures.intake(device, key, body, evidence(device, "fake-assertion-race"));
		assertThat(race).hasStatus(409).hasHeader(HttpHeaders.RETRY_AFTER, "1");
		assertError(json(race), "CONFLICT", "ATTESTATION_CONFLICT", true);

		MvcTestResult forged = fixtures.intake(device, key, body, evidence(device, "forged"));
		assertThat(forged).hasStatus(403);
		assertError(json(forged), "FORBIDDEN", "ATTESTATION_FAILED", false);

		MvcTestResult tooLong = fixtures.intake(device, key, body,
				evidence(device, "a".repeat(AnalysisIntake.MAX_EVIDENCE_LENGTH + 1)));
		assertThat(tooLong).hasStatus(403);
		assertError(json(tooLong), "FORBIDDEN", "ATTESTATION_FAILED", false);

		Map<String, String> partial = Map.of("Device-Check-Token", device.deviceCheckToken(), "App-Attest-Assertion",
				"fake-assertion");
		MvcTestResult missingNonce = fixtures.intake(device, key, body, partial);
		assertThat(missingNonce).hasStatus(403);
		assertError(json(missingNonce), "FORBIDDEN", "ATTESTATION_REQUIRED", true);

		assertNothingStored(device, key);
		assertThat(markState(device)).isNull();
		assertThat(fixtures.intake(device, key, body, evidence(device, "fake-assertion"))).hasStatus(202);
	}

	// I-10: a missing token
	@Test
	void missingDeviceCheckTokenIsStored() {
		Installed device = fixtures.ios();
		String key = key();
		String body = body("NEW_PHOTOS", photo("v1"));

		MvcTestResult missing = fixtures.intake(device, key, body);

		assertThat(missing).hasStatus(400);
		assertError(json(missing), "INVALID_INPUT", "DEVICE_CHECK_TOKEN_REQUIRED", false);
		assertThat(storedStatus(device, key)).contains(400);
		MvcTestResult again = fixtures.intake(device, key, body, evidence(device, "fake-assertion"));
		assertThat(again).hasStatus(400);
		assertError(json(again), "INVALID_INPUT", "DEVICE_CHECK_TOKEN_REQUIRED", false);
		assertThat(markState(device)).isNull();
	}

	// I-10: a token DeviceCheck refuses
	@Test
	void refusedDeviceCheckTokenIsStored() {
		Installed device = fixtures.ios();
		String key = key();
		String body = body("NEW_PHOTOS", photo("v1"));
		Map<String, String> refused = new HashMap<>(evidence(device, "fake-assertion"));
		refused.put("Device-Check-Token", "refused-token");

		MvcTestResult failed = fixtures.intake(device, key, body, refused);

		assertThat(failed).hasStatus(403);
		assertError(json(failed), "FORBIDDEN", "DEVICE_CHECK_FAILED", false);
		assertThat(storedStatus(device, key)).contains(403);
		MvcTestResult again = fixtures.intake(device, key, body, evidence(device, "fake-assertion"));
		assertThat(again).hasStatus(403);
		assertError(json(again), "FORBIDDEN", "DEVICE_CHECK_FAILED", false);
		assertThat(count("analysis_request", device)).isZero();
	}

	// I-10: no consent
	@Test
	void missingConsentIsStoredAndAGrantDoesNotChangeTheAnswerForTheKey() {
		Installed device = fixtures.androidWithoutConsent();
		String key = key();
		String body = body("NEW_PHOTOS", photo("v1"));

		MvcTestResult refused = fixtures.intake(device, key, body);

		assertThat(refused).hasStatus(403);
		assertError(json(refused), "FORBIDDEN", "AI_CONSENT_REQUIRED", false);
		assertThat(storedStatus(device, key)).contains(403);
		fixtures.grantConsent(device);
		MvcTestResult again = fixtures.intake(device, key, body);
		assertThat(again).hasStatus(403);
		assertError(json(again), "FORBIDDEN", "AI_CONSENT_REQUIRED", false);
		assertThat(fixtures.intake(device, key(), body)).hasStatus(202);
	}

	// I-11
	@Test
	void unavailableDeviceCheckIsNotStored() {
		Installed device = fixtures.ios();
		String key = key();
		String body = body("NEW_PHOTOS", photo("v1"));
		Map<String, String> unavailable = new HashMap<>(evidence(device, "fake-assertion"));
		unavailable.put("Device-Check-Token", "fake-device-check-unavailable:" + UUID.randomUUID());

		MvcTestResult failed = fixtures.intake(device, key, body, unavailable);

		assertThat(failed).hasStatus(503);
		assertError(json(failed), "TEMPORARY_FAILURE", "DEVICE_CHECK_UNAVAILABLE", true);
		assertNothingStored(device, key);
		assertThat(fixtures.intake(device, key, body, evidence(device, "fake-assertion"))).hasStatus(202);
	}

	// I-12: the trial start is retried once with the assertion of the request
	@Test
	void startRequiredInTheTransactionStartsTheTrialAndRetries() {
		Installed device = fixtures.ios();
		// The first start check finds nothing to do, as when a release happens right after it.
		doNothing().doCallRealMethod()
			.when(trialUsage)
			.recordStart(any(), any(), anyInt(), any(), any(TrialUsage.StartGuard.class));

		MvcTestResult result = fixtures.intake(device, key(), body("NEW_PHOTOS", photo("v1")),
				evidence(device, "fake-assertion"));

		assertThat(result).hasStatus(202);
		verify(trialUsage, times(2)).recordStart(any(), any(), anyInt(), any(), any(TrialUsage.StartGuard.class));
		assertThat(markState(device)).isEqualTo("TRIAL_STARTED");
	}

	// I-12: without evidence the retry asks for it and stores nothing
	@Test
	void startRequiredWithoutEvidenceAsksForIt() {
		Installed device = fixtures.ios();
		String key = key();
		doNothing().doCallRealMethod()
			.when(trialUsage)
			.recordStart(any(), any(), anyInt(), any(), any(TrialUsage.StartGuard.class));

		MvcTestResult result = fixtures.intake(device, key, body("NEW_PHOTOS", photo("v1")),
				Map.of("Device-Check-Token", device.deviceCheckToken()));

		assertThat(result).hasStatus(403);
		assertError(json(result), "FORBIDDEN", "ATTESTATION_REQUIRED", true);
		assertThat(json(result).get("challenge").isObject()).isTrue();
		assertNothingStored(device, key);
	}

	// I-12: StartRequired twice
	@Test
	void startRequiredTwiceIsATemporaryFailureThatChangesNothing() {
		Installed device = fixtures.ios();
		String key = key();
		doNothing().when(trialUsage).recordStart(any(), any(), anyInt(), any(), any(TrialUsage.StartGuard.class));

		MvcTestResult result = fixtures.intake(device, key, body("NEW_PHOTOS", photo("v1")),
				evidence(device, "fake-assertion"));

		assertThat(result).hasStatus(503);
		assertError(json(result), "TEMPORARY_FAILURE", "TRIAL_START_CONFLICT", true);
		assertNothingStored(device, key);
		assertThat(count("photo", device)).isZero();
		assertThat(count("usage_reservation", "principal_id", device)).isZero();
		assertThat(markState(device)).isNull();
	}

	// I-13
	@Test
	void retransmissionReplaysTheFirstStatusWithTheCurrentJobState() {
		Installed device = fixtures.android();
		String key = key();
		String body = body("NEW_PHOTOS", photo("v1"), photo("v1"));
		MvcTestResult first = fixtures.intake(device, key, body);
		assertThat(first).hasStatus(202);
		clearInvocations(trialUsage, attestations);

		MvcTestResult replay = fixtures.intake(device, key, body);

		assertThat(replay).hasStatus(202);
		assertThat(json(replay)).isEqualTo(json(first));
		assertThat(count("analysis_request", device)).isEqualTo(1);
		verify(trialUsage, never()).recordStart(any(), any(), anyInt(), any(), any(TrialUsage.StartGuard.class));

		UUID requestId = requestId(first);
		jdbc.sql("UPDATE analysis_job SET status = 'READY' WHERE request_id = :id").param("id", requestId).update();
		JsonNode ready = json(fixtures.intake(device, key, body));
		assertThat(ready.get("jobs")).allSatisfy((job) -> {
			assertThat(job.get("status").asString()).isEqualTo("READY");
			assertThat(job.get("uploadRequired").asBoolean()).isFalse();
		});
		assertThat(ready.get("items")).allSatisfy(
				(item) -> assertThat(item.get("execution").asString()).isEqualTo("READY"));
		assertThat(ready.get("phase").asString()).isEqualTo("WAITING");
		// Another key for the same photos answers with the jobs of the first request.
		String waitingKey = key();
		MvcTestResult waiting = fixtures.intake(device, waitingKey, body);
		assertThat(waiting).hasStatus(200);
		assertThat(jobIds(json(waiting))).isEqualTo(jobIds(ready));
		assertThat(json(waiting).get("jobs")).allSatisfy((job) -> {
			assertThat(job.get("requestId").asString()).isEqualTo(requestId.toString());
			assertThat(job.get("status").asString()).isEqualTo("READY");
		});

		jdbc.sql("UPDATE analysis_job SET status = 'RUNNING' WHERE request_id = :id").param("id", requestId).update();
		JsonNode running = json(fixtures.intake(device, key, body));
		assertThat(running.get("phase").asString()).isEqualTo("RUNNING");
		assertThat(counts(running)).containsExactly(2, 2, 0, 0, 0, 0, 2, 0);
		MvcTestResult waitingReplay = fixtures.intake(device, waitingKey, body);
		assertThat(waitingReplay).hasStatus(200);
		assertThat(json(waitingReplay).get("phase").asString()).isEqualTo("RUNNING");
		assertThat(counts(json(waitingReplay))).containsExactly(2, 0, 0, 2, 0, 0, 2, 0);
		assertThat(json(waitingReplay).get("jobs")).hasSize(2).allSatisfy((job) -> {
			assertThat(job.get("requestId").asString()).isEqualTo(requestId.toString());
			assertThat(job.get("status").asString()).isEqualTo("RUNNING");
		});

		MvcTestResult reused = fixtures.intake(device, key, body("NEW_PHOTOS", photo("v1")));
		assertThat(reused).hasStatus(400);
		assertError(json(reused), "INVALID_INPUT", "IDEMPOTENCY_KEY_REUSED", false);
		assertThat(storedStatus(device, key)).contains(202);
	}

	// I-13: an iOS replay needs neither token nor evidence
	@Test
	void iosReplayNeedsNoEvidence() {
		Installed device = fixtures.ios();
		String key = key();
		String body = body("NEW_PHOTOS", photo("v1"));
		JsonNode challenge = json(fixtures.intake(device, key, body,
				Map.of("Device-Check-Token", device.deviceCheckToken()))).get("challenge");
		assertThat(fixtures.intake(device, key, body, evidence(device, "fake-assertion", challenge))).hasStatus(202);
		clearInvocations(trialUsage, attestations);

		MvcTestResult replay = fixtures.intake(device, key, body);

		assertThat(replay).hasStatus(202);
		verify(trialUsage, never()).recordStart(any(), any(), anyInt(), any(), any(TrialUsage.StartGuard.class));
		verify(attestations, never()).verify(any(), any(), any(), any());
		verify(attestations, never()).challenge(any(), any(), any());
	}

	// I-15
	@Test
	void bodyErrorsComeBeforeParsingAndCarryNoJobId() {
		Installed device = fixtures.android();
		String key = key();
		String valid = body("NEW_PHOTOS", photo("v1"));
		String tooLarge = valid.substring(0, valid.length() - 1) + " ".repeat(2 * 1024 * 1024) + "}";

		MvcTestResult large = fixtures.intake(device, key, tooLarge);
		assertThat(large).hasStatus(400);
		assertError(json(large), "INVALID_INPUT", "REQUEST_TOO_LARGE", false);

		MvcTestResult compressed = mvc.post()
			.uri("/analysis-jobs")
			.header("API-Version", "1")
			.header("Idempotency-Key", key)
			.header(HttpHeaders.AUTHORIZATION, device.bearer())
			.header(HttpHeaders.CONTENT_ENCODING, "gzip")
			.contentType("application/json")
			.content(valid)
			.exchange();
		assertThat(compressed).hasStatus(400);
		assertError(json(compressed), "INVALID_INPUT", "MEDIA_TYPE_NOT_SUPPORTED", false);

		MvcTestResult malformed = fixtures.intake(device, key, "{\"trigger\": ");
		assertThat(malformed).hasStatus(400);
		assertError(json(malformed), "INVALID_INPUT", "MALFORMED_BODY", false);

		MvcTestResult invalid = fixtures.intake(device, key, body("NEW_PHOTOS"));
		assertThat(invalid).hasStatus(400);
		assertError(json(invalid), "INVALID_INPUT", "VALIDATION_FAILED", false);

		Photo duplicate = photo("v1");
		MvcTestResult twice = fixtures.intake(device, key, body("NEW_PHOTOS", duplicate, duplicate));
		assertThat(twice).hasStatus(400);
		assertError(json(twice), "INVALID_INPUT", "DUPLICATE_PHOTO", false);

		MvcTestResult noOffset = fixtures.intake(device, key, body("NEW_PHOTOS", photo("v1").takenAt("2026-11-10T10:00:00")));
		assertThat(noOffset).hasStatus(400);
		assertError(json(noOffset), "INVALID_INPUT", "TAKEN_AT_INVALID", false);

		// A NUL would fail the database write on every retry; it is refused with the values instead.
		MvcTestResult nul = fixtures.intake(device, key, body("NEW_PHOTOS", photo("v\0")));
		assertThat(nul).hasStatus(400);
		assertError(json(nul), "INVALID_INPUT", "VALIDATION_FAILED", false);

		assertNothingStored(device, key);
		assertThat(fixtures.intake(device, key, valid)).hasStatus(202);
	}

	// I-16
	@Test
	void tokenForAnotherDeviceIsUnauthenticated() {
		Installed device = fixtures.android();
		Installed stranger = fixtures.android();
		String key = key();
		// A token of the principal that names a real device of another principal.
		Installed otherDevice = new Installed(device.principalId(), stranger.deviceId(), device.trialMarkId(),
				"Bearer " + accessTokens.issue(device.principalId(), PrincipalKind.ANONYMOUS, stranger.deviceId())
					.value(),
				null);

		MvcTestResult result = fixtures.intake(otherDevice, key, body("NEW_PHOTOS", photo("v1")));

		assertThat(result).hasStatus(401).hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		assertError(json(result), "UNAUTHENTICATED", "ACCESS_TOKEN_INVALID", false);
		assertNothingStored(device, key);
		assertNothingStored(stranger, key);
	}

	// I-18
	@Test
	void unstoredFailureInTheTransactionRollsBackEveryWrite() {
		Installed device = fixtures.android();
		String key = key();
		String body = body("NEW_PHOTOS", photo("v1"), photo("v1"));
		// The response is built last, after the photo, request and reservation writes and issueChangeSeqs.
		doAnswer((invocation) -> {
			throw PikException.unstored(ErrorCode.TEMPORARY_FAILURE, "INJECTED_FAILURE", true);
		}).doCallRealMethod().when(projection).build(any());

		MvcTestResult failed = fixtures.intake(device, key, body);

		assertThat(failed).hasStatus(503);
		assertError(json(failed), "TEMPORARY_FAILURE", "INJECTED_FAILURE", true);
		assertNothingStored(device, key);
		assertThat(count("photo", device)).isZero();
		assertThat(count("usage_reservation", "principal_id", device)).isZero();
		assertThat(count("owner_change_counter", device)).isZero();

		assertThat(fixtures.intake(device, key, body)).hasStatus(202);
		assertThat(count("photo", device)).isEqualTo(2);
	}

	// I-7 and I-18: the owner's photo and change counter exist before the failure
	@Test
	void rolledBackIntakeLeavesExistingRowsAndDeliversNoSourceChange() {
		Installed device = fixtures.android();
		UUID photoId = UUID.randomUUID();
		assertThat(fixtures.intake(device, key(), body("NEW_PHOTOS", photo(photoId, "A")))).hasStatus(202);
		Map<String, Object> before = photoRow(device, photoId);
		long counter = lastChangeSeq(device);
		long publications = publications();
		String key = key();
		String body = body("NEW_PHOTOS", photo(photoId, "B"), photo("v1"));
		doAnswer((invocation) -> {
			throw PikException.unstored(ErrorCode.TEMPORARY_FAILURE, "INJECTED_FAILURE", true);
		}).doCallRealMethod().when(projection).build(any());

		MvcTestResult failed = fixtures.intake(device, key, body);

		assertThat(failed).hasStatus(503);
		assertError(json(failed), "TEMPORARY_FAILURE", "INJECTED_FAILURE", true);
		assertThat(storedStatus(device, key)).isEmpty();
		assertThat(photoRow(device, photoId)).isEqualTo(before);
		assertThat(count("photo", device)).isEqualTo(1);
		assertThat(count("analysis_request", device)).isEqualTo(1);
		assertThat(count("usage_reservation", "principal_id", device)).isEqualTo(1);
		assertThat(lastChangeSeq(device)).isEqualTo(counter);
		// The source change was published in the transaction, and neither stored nor delivered.
		assertThat(changes(device)).hasSize(1);
		assertThat(publications()).isEqualTo(publications);
		assertThat(delivered(device)).isEmpty();

		assertThat(fixtures.intake(device, key, body)).hasStatus(202);
		assertThat(delivered(device))
			.containsExactly(new PhotoSourceChanged(device.principalId(), (UUID) before.get("id"), 1));
		assertThat(lastChangeSeq(device)).isGreaterThan(counter);
	}

	// I-19: the retry after StartRequired reuses the verified assertion
	@Test
	void assertionIsVerifiedOncePerHttpRequestWithItsBinding() {
		Installed device = fixtures.ios();
		String key = key();
		String body = body("NEW_PHOTOS", photo("v1"));
		Map<String, String> evidence = evidence(device, "fake-assertion");
		// The first start verifies the assertion and then finds the trial cannot cover the photos.
		doAnswer((invocation) -> {
			invocation.<TrialUsage.StartGuard>getArgument(4).beforeStart();
			return null;
		}).doCallRealMethod().when(trialUsage).recordStart(any(), any(), anyInt(), any(), any(TrialUsage.StartGuard.class));

		assertThat(fixtures.intake(device, key, body, evidence)).hasStatus(202);

		ArgumentCaptor<AttestationEvidence> sent = ArgumentCaptor.forClass(AttestationEvidence.class);
		ArgumentCaptor<AttestationBinding> binding = ArgumentCaptor.forClass(AttestationBinding.class);
		verify(attestations, times(1)).verify(any(), any(), sent.capture(), binding.capture());
		assertThat(sent.getValue().nonce()).isEqualTo(evidence.get("App-Attest-Nonce"));
		assertThat(sent.getValue().challengeId()).isEqualTo(evidence.get("App-Attest-Challenge-Id"));
		assertThat(binding.getValue()).isEqualTo(new AttestationBinding(UUID.fromString(key),
				IntakeBodyReader.sha256Hex(body.getBytes(StandardCharsets.UTF_8)),
				IntakeBodyReader.sha256Hex(device.deviceCheckToken().getBytes(StandardCharsets.UTF_8))));
	}

	// I-19: a new HTTP request with the same key verifies again
	@Test
	void assertionIsNotReusedAcrossHttpRequests() {
		Installed device = fixtures.ios();
		String key = key();
		String body = body("NEW_PHOTOS", photo("v1"));
		Map<String, String> unavailable = new HashMap<>(evidence(device, "fake-assertion"));
		unavailable.put("Device-Check-Token", "fake-device-check-unavailable:" + UUID.randomUUID());
		assertThat(fixtures.intake(device, key, body, unavailable)).hasStatus(503);

		assertThat(fixtures.intake(device, key, body, evidence(device, "fake-assertion"))).hasStatus(202);

		verify(attestations, times(2)).verify(any(), any(), any(), any());
	}

	// I-19: a device whose start write was not confirmed writes again without reading the bits
	@Test
	void startingDeviceRecordsAgainWithoutLookup() {
		Installed device = fixtures.ios();
		jdbc.sql("UPDATE device_trial_mark SET device_check_state = 'STARTING' WHERE id = :id")
			.param("id", device.trialMarkId())
			.update();
		clearInvocations(deviceCheck);

		assertThat(fixtures.intake(device, key(), body("NEW_PHOTOS", photo("v1")), evidence(device, "fake-assertion")))
			.hasStatus(202);

		assertThat(deviceCheckCalls("trialStarted")).isZero();
		assertThat(deviceCheckCalls("recordTrialStarted")).isEqualTo(1);
		verify(attestations, times(1)).verify(any(), any(), any(), any());
		assertThat(markState(device)).isEqualTo("TRIAL_STARTED");
	}

	// I-20
	@Test
	void deviceWithoutTrialMarkGetsNoNewJobButKeepsJobsInProgress() {
		Installed device = fixtures.android();
		Photo first = photo("v1");
		String firstKey = key();
		String firstBody = body("NEW_PHOTOS", first);
		JsonNode accepted = json(fixtures.intake(device, firstKey, firstBody));
		jdbc.sql("UPDATE device SET trial_mark_id = NULL WHERE id = :id").param("id", device.deviceId()).update();
		clearInvocations(trialUsage);
		Photo added = photo("v1");

		MvcTestResult result = fixtures.intake(device, key(), body("NEW_PHOTOS", first, added));

		assertThat(result).hasStatus(200);
		JsonNode response = json(result);
		assertThat(item(response, first).get("admissionResult").asString()).isEqualTo("IN_PROGRESS");
		assertThat(jobOf(response, first)).isEqualTo(jobOf(accepted, first));
		JsonNode refused = item(response, added);
		assertThat(refused.get("admissionResult").asString()).isEqualTo("NOT_ACCEPTED");
		assertThat(refused.get("code").asString()).isEqualTo("FORBIDDEN");
		assertThat(refused.get("reason").asString()).isEqualTo("TRIAL_UNAVAILABLE");
		assertThat(refused.get("retryable").asBoolean()).isFalse();
		verify(trialUsage, never()).recordStart(any(), any(), anyInt(), any(), any(TrialUsage.StartGuard.class));
		verify(trialUsage, never()).reserve(any(), any(), any(), any());
		assertThat(jdbc.sql("SELECT trial_mark_id IS NULL FROM analysis_request WHERE id = :id")
			.param("id", requestId(result))
			.query(Boolean.class)
			.single()).isTrue();

		MvcTestResult replay = fixtures.intake(device, firstKey, firstBody);
		assertThat(replay).hasStatus(202);
		assertThat(json(replay).get("jobId")).isEqualTo(accepted.get("jobId"));
	}

	private long deviceCheckCalls(String method) {
		return Mockito.mockingDetails(deviceCheck)
			.getInvocations()
			.stream()
			.filter((invocation) -> invocation.getMethod().getName().equals(method))
			.count();
	}

	private void assertNothingStored(Installed device, String key) {
		assertThat(storedStatus(device, key)).isEmpty();
		assertThat(count("analysis_request", device)).isZero();
	}

	private static void assertError(JsonNode problem, String code, String reason, boolean retryable) {
		assertThat(problem.get("code").asString()).isEqualTo(code);
		assertThat(problem.get("reason").asString()).isEqualTo(reason);
		assertThat(problem.get("retryable").asBoolean()).isEqualTo(retryable);
		assertThat(problem.has("jobId")).isTrue();
		assertThat(problem.get("jobId").isNull()).isTrue();
		assertThat(problem.get("result").asString()).isEqualTo("FAILED");
	}

	private Optional<Integer> storedStatus(Installed device, String key) {
		return jdbc.sql("SELECT response_code FROM idempotency_record WHERE owner_id = :owner AND idempotency_key = :key")
			.param("owner", device.principalId())
			.param("key", UUID.fromString(key))
			.query(Integer.class)
			.optional();
	}

	private long count(String table, Installed device) {
		return count(table, "owner_id", device);
	}

	private long count(String table, String ownerColumn, Installed device) {
		return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + ownerColumn + " = :owner")
			.param("owner", device.principalId())
			.query(Long.class)
			.single();
	}

	private long lastChangeSeq(Installed device) {
		return jdbc.sql("SELECT last_seq FROM owner_change_counter WHERE owner_id = :owner")
			.param("owner", device.principalId())
			.query(Long.class)
			.single();
	}

	private long publications() {
		return jdbc.sql("SELECT count(*) FROM event_publication").query(Long.class).single();
	}

	private List<PhotoSourceChanged> delivered(Installed device) {
		return committed.delivered().stream().filter((event) -> event.ownerId().equals(device.principalId())).toList();
	}

	private Map<String, Object> photoRow(Installed device, UUID clientRef) {
		return jdbc.sql("SELECT * FROM photo WHERE owner_id = :owner AND client_ref = :ref")
			.param("owner", device.principalId())
			.param("ref", clientRef)
			.query()
			.singleRow();
	}

	private long basisChangeSeq(JsonNode response, UUID clientRef) {
		return jdbc.sql("SELECT basis_change_seq FROM analysis_request_item WHERE request_id = :id AND client_ref = :ref")
			.param("id", UUID.fromString(response.get("jobId").asString()))
			.param("ref", clientRef)
			.query(Long.class)
			.single();
	}

	private List<PhotoSourceChanged> changes(Installed device) {
		return events.stream(PhotoSourceChanged.class)
			.filter((event) -> event.ownerId().equals(device.principalId()))
			.toList();
	}

	private String markState(Installed device) {
		return jdbc.sql("SELECT device_check_state FROM device_trial_mark WHERE id = :id")
			.param("id", device.trialMarkId())
			.query(String.class)
			.optional()
			.orElse(null);
	}

	private static UUID requestId(MvcTestResult result) {
		return UUID.fromString(json(result).get("jobId").asString());
	}

	private static List<Integer> counts(JsonNode response) {
		JsonNode counts = response.get("counts");
		List<Integer> values = new ArrayList<>();
		for (String name : List.of("requested", "accepted", "existingResult", "inProgress", "notAccepted", "waiting",
				"running", "done")) {
			values.add(counts.get(name).asInt());
		}
		return values;
	}

	static JsonNode item(JsonNode response, Photo photo) {
		return item(response, photo.photoId());
	}

	static JsonNode item(JsonNode response, UUID photoId) {
		for (JsonNode item : response.get("items")) {
			if (item.get("photoId").asString().equals(photoId.toString())) {
				return item;
			}
		}
		throw new AssertionError("no item for the photo");
	}

	private static String jobOf(JsonNode response, Photo photo) {
		return item(response, photo).get("analysisJobId").asString();
	}

	static List<String> jobIds(JsonNode response) {
		List<String> ids = new ArrayList<>();
		response.get("jobs").forEach((job) -> ids.add(job.get("analysisJobId").asString()));
		return ids;
	}

	static JsonNode job(JsonNode response, String jobId) {
		for (JsonNode job : response.get("jobs")) {
			if (job.get("analysisJobId").asString().equals(jobId)) {
				return job;
			}
		}
		throw new AssertionError("no such job");
	}

	/**
	 * Records the source changes delivered after commit, as a listener of a later stage gets them.
	 */
	static class CommittedSourceChanges {

		private final List<PhotoSourceChanged> delivered = new CopyOnWriteArrayList<>();

		@TransactionalEventListener
		public void on(PhotoSourceChanged event) {
			delivered.add(event);
		}

		// The bean is proxied: read through a method, not the field.
		public List<PhotoSourceChanged> delivered() {
			return List.copyOf(delivered);
		}

	}

}
