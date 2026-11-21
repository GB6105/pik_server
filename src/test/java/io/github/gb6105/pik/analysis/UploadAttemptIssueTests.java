package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.IntakeFixtures.json;
import static io.github.gb6105.pik.analysis.IntakeFixtures.key;
import static io.github.gb6105.pik.analysis.UploadFixtures.assertError;
import static io.github.gb6105.pik.analysis.UploadFixtures.assertRejected;
import static io.github.gb6105.pik.analysis.UploadFixtures.items;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import io.github.gb6105.pik.S3MockConfiguration;
import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.analysis.IntakeFixtures.Installed;
import io.github.gb6105.pik.analysis.UploadFixtures.Accepted;
import io.github.gb6105.pik.analysis.UploadFixtures.Image;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /analysis-jobs/{requestId}/upload-attempts} against PostgreSQL and S3Mock (plan 4.1, 11.2 "발급", 11.3
 * C-13), with jobs created by real intake requests.
 * <p>
 * The URL TTL carries a sub-microsecond part so that the issued expiry has nanoseconds on every clock. The macOS JDK
 * clock has microsecond precision, but the Linux clock (CI, production) has nanosecond precision, and PostgreSQL
 * keeps microseconds.
 */
@Import({ TestcontainersConfiguration.class, S3MockConfiguration.class })
@SpringBootTest(properties = { "pik.analysis.intake-enabled=true", "pik.analysis.upload.url-ttl=PT15M0.000000123S" })
@AutoConfigureMockMvc
class UploadAttemptIssueTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@MockitoSpyBean
	InputStore store;

	UploadFixtures fixtures;

	ExecutorService pool;

	@BeforeEach
	void setUp() {
		fixtures = new UploadFixtures(mvc, jdbc);
		pool = Executors.newCachedThreadPool();
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		pool.shutdownNow();
		assertThat(pool.awaitTermination(30, SECONDS)).isTrue();
	}

	@Test
	void issuedAttemptsCarrySignedUploadsAndAreStoredWithoutUrls() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 2);
		Image first = fixtures.image(1000);
		Image second = fixtures.image(2000);
		Map<UUID, Image> images = new LinkedHashMap<>();
		images.put(accepted.jobIds().get(1), second);
		images.put(accepted.jobIds().get(0), first);
		String key = key();
		Instant before = Instant.now();

		MvcTestResult result = fixtures.issue(device, key, accepted.requestId(), items(images));

		assertThat(result).hasStatusOk();
		JsonNode response = json(result);
		assertThat(response.get("jobId").asString()).isEqualTo(key);
		assertThat(response.get("result").asString()).isEqualTo("SUCCEEDED");
		assertThat(response.get("retryable").asBoolean()).isFalse();
		JsonNode items = response.get("items");
		assertThat(items).hasSize(2);
		// In the order of the request.
		assertThat(items.get(0).get("analysisJobId").asString()).isEqualTo(accepted.jobIds().get(1).toString());
		for (JsonNode item : items) {
			UUID jobId = UUID.fromString(item.get("analysisJobId").asString());
			Image image = images.get(jobId);
			UUID attemptId = UUID.fromString(item.get("attemptId").asString());
			assertThat(item.get("result").asString()).isEqualTo("ISSUED");
			assertThat(item.get("code").isNull()).isTrue();
			JsonNode upload = item.get("upload");
			assertThat(upload.get("method").asString()).isEqualTo("PUT");
			assertThat(upload.get("headers").get("content-type").asString()).isEqualTo("image/jpeg");
			assertThat(upload.get("headers").get("content-length").asString())
				.isEqualTo(Integer.toString(image.bytes().length));
			assertThat(upload.get("headers").get("x-amz-checksum-sha256").asString()).isEqualTo(image.sha256Base64());
			assertThat(upload.get("headers").get("if-none-match").asString()).isEqualTo("*");
			assertThat(upload.get("url").asString()).contains("/analysis-input/" + jobId + "/" + attemptId);
			Instant expiresAt = Instant.parse(item.get("expiresAt").asString());
			assertThat(expiresAt).isCloseTo(before.plus(Duration.ofMinutes(15)), within(1, ChronoUnit.MINUTES));
			assertThat(Instant.parse(upload.get("expiresAt").asString())).isBeforeOrEqualTo(expiresAt);

			Map<String, Object> attempt = fixtures.attempt(attemptId);
			assertThat(attempt.get("status")).isEqualTo("ISSUED");
			assertThat(attempt.get("object_state")).isEqualTo("RETAINED");
			assertThat(attempt.get("object_key")).isEqualTo("analysis-input/" + jobId + "/" + attemptId);
			assertThat(attempt.get("content_length")).isEqualTo((long) image.bytes().length);
			assertThat((byte[]) attempt.get("sha256")).isEqualTo(image.sha256());
			assertThat(((Timestamp) attempt.get("url_expires_at")).toInstant()).isEqualTo(expiresAt);
			assertThat(fixtures.job(jobId).get("issued_count")).isEqualTo(1);
			assertThat(fixtures.put(item, image.bytes())).isEqualTo(200);
		}
		String stored = fixtures.storedBody(device, key);
		assertThat(stored).doesNotContain("http").doesNotContain("x-amz");
		assertThat(fixtures.storedStatus(device, key)).contains(200);
	}

	@Test
	void urlExpiryIsCutToTheUploadDeadline() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Instant deadline = Instant.now().plus(Duration.ofMinutes(3)).truncatedTo(ChronoUnit.MILLIS);
		jdbc.sql("UPDATE analysis_job SET upload_deadline = :deadline WHERE id = :id")
			.param("deadline", deadline.atOffset(java.time.ZoneOffset.UTC))
			.param("id", accepted.job())
			.update();

		JsonNode item = fixtures.issued(device, accepted.requestId(), accepted.job(), fixtures.image(10));

		assertThat(Instant.parse(item.get("expiresAt").asString())).isEqualTo(deadline);
	}

	@Test
	void deadlineTooCloseForAUsableUrlIsExpiredWithoutCountingAnything() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		// Ahead, but by less than the validity a URL must have left.
		jdbc.sql("UPDATE analysis_job SET upload_deadline = now() + interval '8 seconds' WHERE id = :id")
			.param("id", accepted.job())
			.update();

		MvcTestResult result = fixtures.issue(device, key(), accepted.requestId(),
				items(Map.of(accepted.job(), fixtures.image(10))));

		assertThat(result).hasStatusOk();
		assertRejected(json(result).get("items").get(0), "CONFLICT", "UPLOAD_EXPIRED", false);
		assertThat(fixtures.attemptStatuses(accepted.job())).isEmpty();
		assertThat(fixtures.job(accepted.job()).get("issued_count")).isEqualTo(0);
		assertThat(rateItems(device.principalId(), device.trialMarkId())).isEmpty();
	}

	@Test
	void newAttemptSupersedesTheCurrentOne() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		JsonNode first = fixtures.issued(device, accepted.requestId(), accepted.job(), fixtures.image(10));

		JsonNode second = fixtures.issued(device, accepted.requestId(), accepted.job(), fixtures.image(20));

		Map<String, Object> superseded = fixtures.attempt(UUID.fromString(first.get("attemptId").asString()));
		assertThat(superseded.get("status")).isEqualTo("SUPERSEDED");
		assertThat(superseded.get("object_state")).isEqualTo("PENDING");
		assertThat(superseded.get("delete_after")).isNotNull();
		assertThat(fixtures.attempt(UUID.fromString(second.get("attemptId").asString())).get("status"))
			.isEqualTo("ISSUED");
		assertThat(fixtures.job(accepted.job()).get("issued_count")).isEqualTo(2);
	}

	@Test
	void sixthAttemptIsRejectedWithoutChange() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		for (int i = 0; i < 5; i++) {
			fixtures.issued(device, accepted.requestId(), accepted.job(), fixtures.image(10));
		}

		MvcTestResult sixth = fixtures.issue(device, key(), accepted.requestId(),
				items(Map.of(accepted.job(), fixtures.image(10))));

		assertThat(sixth).hasStatusOk();
		JsonNode item = json(sixth).get("items").get(0);
		assertRejected(item, "CONFLICT", "UPLOAD_ATTEMPT_LIMIT", false);
		assertThat(item.get("attemptId").isNull()).isTrue();
		assertThat(fixtures.job(accepted.job()).get("issued_count")).isEqualTo(5);
		assertThat(fixtures.attemptStatuses(accepted.job())).containsExactly("SUPERSEDED", "SUPERSEDED", "SUPERSEDED",
				"SUPERSEDED", "ISSUED");
	}

	@Test
	void replayResignsTheCurrentAttemptAndRejectsAReplacedOne() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		String key = key();
		Image image = fixtures.image(100);
		String body = items(Map.of(accepted.job(), image));
		JsonNode first = json(fixtures.issue(device, key, accepted.requestId(), body)).get("items").get(0);

		// The first response was lost: the same key answers with the same attempt and expiry, signed again.
		MvcTestResult replay = fixtures.issue(device, key, accepted.requestId(), body);

		assertThat(replay).hasStatusOk();
		JsonNode replayed = json(replay).get("items").get(0);
		assertThat(replayed.get("result").asString()).isEqualTo("ISSUED");
		assertThat(replayed.get("attemptId")).isEqualTo(first.get("attemptId"));
		assertThat(replayed.get("expiresAt")).isEqualTo(first.get("expiresAt"));
		assertThat(replayed.get("upload").get("url").asString()).isNotBlank();
		assertThat(fixtures.put(replayed, image.bytes())).isEqualTo(200);
		assertThat(fixtures.job(accepted.job()).get("issued_count")).isEqualTo(1);

		fixtures.issued(device, accepted.requestId(), accepted.job(), fixtures.image(100));
		JsonNode notCurrent = json(fixtures.issue(device, key, accepted.requestId(), body)).get("items").get(0);
		assertRejected(notCurrent, "CONFLICT", "ATTEMPT_NOT_CURRENT", true);
		assertThat(notCurrent.get("attemptId")).isEqualTo(first.get("attemptId"));
		assertThat(fixtures.job(accepted.job()).get("issued_count")).isEqualTo(2);
	}

	@Test
	void replayAfterTheConsentIsWithdrawnIsNotCurrent() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		String key = key();
		String body = items(Map.of(accepted.job(), fixtures.image(10)));
		assertThat(fixtures.issue(device, key, accepted.requestId(), body)).hasStatusOk();
		fixtures.withdrawConsent(device);

		JsonNode item = json(fixtures.issue(device, key, accepted.requestId(), body)).get("items").get(0);

		assertRejected(item, "CONFLICT", "ATTEMPT_NOT_CURRENT", true);
	}

	@Test
	void consentWithdrawnRejectsEveryItem() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 2);
		fixtures.withdrawConsent(device);

		MvcTestResult result = fixtures.issue(device, key(), accepted.requestId(),
				items(Map.of(accepted.jobIds().get(0), fixtures.image(10), accepted.jobIds().get(1),
						fixtures.image(10))));

		assertThat(result).hasStatusOk();
		assertThat(json(result).get("items"))
			.allSatisfy((item) -> assertRejected(item, "FORBIDDEN", "CONSENT_WITHDRAWN", false));
		assertThat(fixtures.attemptStatuses(accepted.jobIds().get(0))).isEmpty();
		assertThat(fixtures.job(accepted.jobIds().get(0)).get("issued_count")).isEqualTo(0);
	}

	@Test
	void itemsAreDecidedInThePlannedOrder() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 4);
		Accepted other = fixtures.accepted(device, 1);
		UUID ready = accepted.jobIds().get(0);
		UUID expired = accepted.jobIds().get(1);
		UUID tooLarge = accepted.jobIds().get(2);
		UUID fine = accepted.jobIds().get(3);
		jdbc.sql("""
				UPDATE analysis_job SET status = 'READY', next_attempt_at = now(), input_sha256 = sha256('x'::bytea)
				WHERE id = :id
				""").param("id", ready).update();
		jdbc.sql("UPDATE analysis_job SET upload_deadline = now() - interval '1 second' WHERE id = :id")
			.param("id", expired)
			.update();
		Map<UUID, Image> images = new LinkedHashMap<>();
		images.put(other.job(), fixtures.image(10));
		images.put(ready, fixtures.image(10));
		images.put(expired, fixtures.image(10));
		images.put(tooLarge, fixtures.image(2_097_153));
		images.put(fine, fixtures.image(10));

		JsonNode items = json(fixtures.issue(device, key(), accepted.requestId(), items(images))).get("items");

		assertRejected(items.get(0), "NOT_FOUND", "RESOURCE_NOT_FOUND", false);
		assertRejected(items.get(1), "CONFLICT", "ATTEMPT_NOT_ALLOWED", false);
		assertRejected(items.get(2), "CONFLICT", "UPLOAD_EXPIRED", false);
		assertRejected(items.get(3), "INVALID_INPUT", "VALIDATION_FAILED", false);
		assertThat(items.get(4).get("result").asString()).isEqualTo("ISSUED");
		assertThat(fixtures.attemptStatuses(other.job())).isEmpty();
		assertThat(fixtures.attemptStatuses(tooLarge)).isEmpty();
	}

	@Test
	void inactivePrincipalAndReleasedReservationAreRejected() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 2);
		jdbc.sql("UPDATE usage_reservation SET status = 'RELEASED' WHERE job_id = :job")
			.param("job", accepted.jobIds().get(0))
			.update();

		JsonNode missing = json(fixtures.issue(device, key(), accepted.requestId(),
				items(Map.of(accepted.jobIds().get(0), fixtures.image(10)))))
			.get("items")
			.get(0);
		assertRejected(missing, "CONFLICT", "RESERVATION_MISSING", false);

		jdbc.sql("UPDATE principal SET status = 'DELETING' WHERE id = :id").param("id", device.principalId()).update();
		JsonNode inactive = json(fixtures.issue(device, key(), accepted.requestId(),
				items(Map.of(accepted.jobIds().get(1), fixtures.image(10)))))
			.get("items")
			.get(0);
		assertRejected(inactive, "FORBIDDEN", "PRINCIPAL_INACTIVE", false);
	}

	@Test
	void requestOfAnotherPrincipalIsNotFoundAndNotStored() {
		Installed owner = fixtures.intake().android();
		Installed stranger = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(owner, 1);
		String key = key();

		MvcTestResult result = fixtures.issue(stranger, key, accepted.requestId(),
				items(Map.of(accepted.job(), fixtures.image(10))));

		assertThat(result).hasStatus(404);
		assertError(json(result), key, "NOT_FOUND", "RESOURCE_NOT_FOUND", false);
		assertThat(fixtures.storedStatus(stranger, key)).isEmpty();
		assertThat(fixtures.attemptStatuses(accepted.job())).isEmpty();
		assertThat(fixtures.issue(stranger, key, UUID.randomUUID(), items(Map.of(accepted.job(), fixtures.image(10)))))
			.hasStatus(404);
	}

	@Test
	void invalidValuesAreRefusedWithoutRecord() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(10);
		String duplicate = """
				{"items": [{"analysisJobId": "%1$s", "contentLength": 10, "sha256": "%2$s"},
				           {"analysisJobId": "%1$s", "contentLength": 10, "sha256": "%2$s"}]}
				""".formatted(accepted.job(), image.sha256Base64());
		List<String> invalid = List.of("{}", "{\"items\": []}",
				"{\"items\": [{\"analysisJobId\": \"%s\", \"contentLength\": 0, \"sha256\": \"%s\"}]}"
					.formatted(accepted.job(), image.sha256Base64()),
				"{\"items\": [{\"analysisJobId\": \"%s\", \"contentLength\": 10, \"sha256\": \"%s\"}]}"
					.formatted(accepted.job(), image.sha256Base64().replace("=", "")),
				"{\"items\": [{\"analysisJobId\": \"%s\", \"contentLength\": 10, \"sha256\": \"AAAA\"}]}"
					.formatted(accepted.job()),
				"{\"items\": [{\"contentLength\": 10, \"sha256\": \"%s\"}]}".formatted(image.sha256Base64()));

		for (String body : invalid) {
			String key = key();
			MvcTestResult result = fixtures.issue(device, key, accepted.requestId(), body);
			assertThat(result).as(body).hasStatus(400);
			assertError(json(result), key, "INVALID_INPUT", "VALIDATION_FAILED", false);
			assertThat(fixtures.storedStatus(device, key)).isEmpty();
		}
		String key = key();
		MvcTestResult result = fixtures.issue(device, key, accepted.requestId(), duplicate);
		assertThat(result).hasStatus(400);
		assertError(json(result), key, "INVALID_INPUT", "DUPLICATE_ANALYSIS_JOB", false);
		assertThat(fixtures.storedStatus(device, key)).isEmpty();
		assertThat(fixtures.attemptStatuses(accepted.job())).isEmpty();
	}

	@Test
	void keyOfAnotherRequestOrPathIsReused() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Accepted other = fixtures.accepted(device, 1);
		String key = key();
		String body = items(Map.of(accepted.job(), fixtures.image(10)));
		JsonNode item = json(fixtures.issue(device, key, accepted.requestId(), body)).get("items").get(0);

		MvcTestResult otherRequest = fixtures.issue(device, key, other.requestId(), body);
		MvcTestResult otherPath = fixtures.complete(device, key, accepted.requestId(),
				UUID.fromString(item.get("attemptId").asString()));

		assertThat(otherRequest).hasStatus(400);
		assertError(json(otherRequest), key, "INVALID_INPUT", "IDEMPOTENCY_KEY_REUSED", false);
		assertThat(otherPath).hasStatus(400);
		assertError(json(otherPath), key, "INVALID_INPUT", "IDEMPOTENCY_KEY_REUSED", false);
		assertThat(fixtures.storedStatus(device, key)).contains(200);
	}

	@Test
	void signingFailureAfterTheCommitIsSignedAgainWithTheSameKey() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		String key = key();
		Image image = fixtures.image(10);
		String body = items(Map.of(accepted.job(), image));
		doThrow(new IllegalStateException("no credentials")).doCallRealMethod()
			.when(store)
			.presignPut(any(), anyLong(), any(), any());

		MvcTestResult failed = fixtures.issue(device, key, accepted.requestId(), body);

		assertThat(failed).hasStatus(503);
		assertError(json(failed), key, "TEMPORARY_FAILURE", "STORAGE_UNAVAILABLE", true);
		assertThat(fixtures.storedStatus(device, key)).contains(200);
		MvcTestResult retried = fixtures.issue(device, key, accepted.requestId(), body);
		assertThat(retried).hasStatusOk();
		JsonNode item = json(retried).get("items").get(0);
		assertThat(item.get("result").asString()).isEqualTo("ISSUED");
		assertThat(fixtures.put(item, image.bytes())).isEqualTo(200);
		assertThat(fixtures.job(accepted.job()).get("issued_count")).isEqualTo(1);
	}

	@Test
	void inputRateAboveTheLimitIsRefusedWithoutAnyChange() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		// The current and the next window, so that an hour boundary during the test changes nothing.
		Instant window = Instant.now().truncatedTo(ChronoUnit.HOURS);
		for (Instant start : List.of(window, window.plus(Duration.ofHours(1)))) {
			jdbc.sql("""
					INSERT INTO analysis_input_rate (subject_kind, subject_id, window_start, items, bytes)
					VALUES ('PRINCIPAL', :subject, :window, 300, 0)
					""").param("subject", device.principalId()).param("window", start.atOffset(java.time.ZoneOffset.UTC))
				.update();
		}
		String key = key();

		MvcTestResult result = fixtures.issue(device, key, accepted.requestId(),
				items(Map.of(accepted.job(), fixtures.image(10))));

		assertThat(result).hasStatus(429);
		assertError(json(result), key, "RATE_LIMITED", "UPLOAD_RATE_LIMITED", true);
		long retryAfter = Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
		assertThat(retryAfter).isBetween(1L, 3600L);
		assertThat(fixtures.storedStatus(device, key)).isEmpty();
		assertThat(fixtures.attemptStatuses(accepted.job())).isEmpty();
		assertThat(jdbc.sql("SELECT sum(items) FROM analysis_input_rate WHERE subject_id = :subject")
			.param("subject", device.principalId())
			.query(Long.class)
			.single()).isEqualTo(600L);
	}

	@Test
	void trialMarkRateCountsTrialJobsAfterThePrincipal() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 2);

		fixtures.issue(device, key(), accepted.requestId(),
				items(Map.of(accepted.jobIds().get(0), fixtures.image(10), accepted.jobIds().get(1),
						fixtures.image(10))));

		Map<String, Long> items = new LinkedHashMap<>();
		Map<String, Long> bytes = new LinkedHashMap<>();
		jdbc.sql("""
				SELECT subject_kind, items, bytes FROM analysis_input_rate
				WHERE subject_id IN (:principal, :mark)
				""").param("principal", device.principalId()).param("mark", device.trialMarkId()).query((rs, rowNum) -> {
			items.merge(rs.getString("subject_kind"), rs.getLong("items"), Long::sum);
			bytes.merge(rs.getString("subject_kind"), rs.getLong("bytes"), Long::sum);
			return null;
		}).list();
		assertThat(items).containsEntry("PRINCIPAL", 2L).containsEntry("TRIAL_MARK", 2L);
		// Each attempt counts as the largest upload of its manifest.
		assertThat(bytes).containsEntry("PRINCIPAL", 2 * 2_097_152L).containsEntry("TRIAL_MARK", 2 * 2_097_152L);
	}

	@Test
	void trialMarkRateAboveTheLimitRollsBackThePrincipalCountToo() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		// The current and the next window, so that an hour boundary during the test changes nothing.
		Instant window = Instant.now().truncatedTo(ChronoUnit.HOURS);
		for (Instant start : List.of(window, window.plus(Duration.ofHours(1)))) {
			insertRate("PRINCIPAL", device.principalId(), start, 5);
			insertRate("TRIAL_MARK", device.trialMarkId(), start, 100);
		}
		String key = key();

		MvcTestResult result = fixtures.issue(device, key, accepted.requestId(),
				items(Map.of(accepted.job(), fixtures.image(10))));

		assertThat(result).hasStatus(429);
		assertError(json(result), key, "RATE_LIMITED", "UPLOAD_RATE_LIMITED", true);
		assertThat(fixtures.storedStatus(device, key)).isEmpty();
		assertThat(fixtures.attemptStatuses(accepted.job())).isEmpty();
		assertThat(fixtures.job(accepted.job()).get("issued_count")).isEqualTo(0);
		// The principal's window was counted before the trial mark's refused it, and is rolled back with it.
		assertThat(rateItems(device.principalId(), device.trialMarkId())).containsExactly(Map.entry("PRINCIPAL", 10L),
				Map.entry("TRIAL_MARK", 200L));
	}

	@Test
	void twoPrincipalsOfOneDeviceShareTheLastTrialMarkAllowance() throws Exception {
		String androidId = HexFormat.of().formatHex(fixtures.image(8).bytes());
		Installed first = fixtures.intake().android(androidId);
		Installed second = fixtures.intake().android(androidId);
		UUID markId = first.trialMarkId();
		assertThat(markId).isNotNull().isEqualTo(second.trialMarkId());
		assertThat(second.principalId()).isNotEqualTo(first.principalId());
		Accepted firstJob = fixtures.accepted(first, 1);
		Accepted secondJob = fixtures.accepted(second, 1);
		// Both requests must count in one window, or each would find room in its own: start clear of an hour boundary.
		Instant now = Instant.now();
		Duration toNextHour = Duration.between(now, now.truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1)));
		if (toNextHour.compareTo(Duration.ofSeconds(30)) < 0) {
			Thread.sleep(toNextHour.plusSeconds(1).toMillis());
		}
		Instant window = Instant.now().truncatedTo(ChronoUnit.HOURS);
		for (Instant start : List.of(window, window.plus(Duration.ofHours(1)))) {
			insertRate("TRIAL_MARK", markId, start, 99);
		}
		String firstBody = items(Map.of(firstJob.job(), fixtures.image(10)));
		String secondBody = items(Map.of(secondJob.job(), fixtures.image(10)));
		CountDownLatch start = new CountDownLatch(1);
		Future<MvcTestResult> firstResult = pool.submit(() -> {
			start.await();
			return fixtures.issue(first, key(), firstJob.requestId(), firstBody);
		});
		Future<MvcTestResult> secondResult = pool.submit(() -> {
			start.await();
			return fixtures.issue(second, key(), secondJob.requestId(), secondBody);
		});

		start.countDown();

		int firstStatus = firstResult.get(60, SECONDS).getResponse().getStatus();
		int secondStatus = secondResult.get(60, SECONDS).getResponse().getStatus();
		assertThat(List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(200, 429);
		Installed winner = (firstStatus == 200) ? first : second;
		Installed loser = (firstStatus == 200) ? second : first;
		UUID winnerJob = (firstStatus == 200) ? firstJob.job() : secondJob.job();
		UUID loserJob = (firstStatus == 200) ? secondJob.job() : firstJob.job();
		assertThat(fixtures.attemptStatuses(winnerJob)).containsExactly("ISSUED");
		assertThat(fixtures.attemptStatuses(loserJob)).isEmpty();
		assertThat(rateItems(winner.principalId(), markId)).containsExactly(Map.entry("PRINCIPAL", 1L),
				Map.entry("TRIAL_MARK", 199L));
		assertThat(rateItems(loser.principalId(), null)).isEmpty();
	}

	// C-13: attempts for one job issued at once with different keys leave one current attempt and count each.
	@Test
	void concurrentAttemptsForOneJobLeaveOneCurrentAttempt() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		int requests = 4;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<MvcTestResult>> results = new ArrayList<>();
		for (int i = 0; i < requests; i++) {
			String body = items(Map.of(accepted.job(), fixtures.image(10 + i)));
			results.add(pool.submit(() -> {
				start.await();
				return fixtures.issue(device, key(), accepted.requestId(), body);
			}));
		}

		start.countDown();

		for (Future<MvcTestResult> result : results) {
			MvcTestResult response = result.get(60, SECONDS);
			assertThat(response).hasStatusOk();
			assertThat(json(response).get("items").get(0).get("result").asString()).isEqualTo("ISSUED");
		}
		assertThat(fixtures.attemptStatuses(accepted.job())).hasSize(requests)
			.containsOnlyOnce("ISSUED")
			.filteredOn("SUPERSEDED"::equals)
			.hasSize(requests - 1);
		assertThat(fixtures.job(accepted.job()).get("issued_count")).isEqualTo(requests);
	}

	private void insertRate(String kind, UUID subjectId, Instant window, long items) {
		jdbc.sql("""
				INSERT INTO analysis_input_rate (subject_kind, subject_id, window_start, items, bytes)
				VALUES (:kind, :subject, :window, :items, 0)
				""")
			.param("kind", kind)
			.param("subject", subjectId)
			.param("window", window.atOffset(java.time.ZoneOffset.UTC))
			.param("items", items)
			.update();
	}

	// The items counted in all windows of the principal and of the trial mark, by subject kind.
	private Map<String, Long> rateItems(UUID principalId, @Nullable UUID trialMarkId) {
		Map<String, Long> items = new LinkedHashMap<>();
		jdbc.sql("""
				SELECT subject_kind, sum(items) AS items FROM analysis_input_rate
				WHERE (subject_kind = 'PRINCIPAL' AND subject_id = :principal)
				   OR (subject_kind = 'TRIAL_MARK' AND subject_id = CAST(:mark AS uuid))
				GROUP BY subject_kind ORDER BY subject_kind
				""").param("principal", principalId).param("mark", trialMarkId, java.sql.Types.OTHER)
			.query((rs, rowNum) -> items.put(rs.getString("subject_kind"), rs.getLong("items")))
			.list();
		return items;
	}

}
