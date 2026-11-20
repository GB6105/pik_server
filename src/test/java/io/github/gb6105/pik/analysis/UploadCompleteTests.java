package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.IntakeFixtures.json;
import static io.github.gb6105.pik.analysis.IntakeFixtures.key;
import static io.github.gb6105.pik.analysis.UploadFixtures.assertError;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.gb6105.pik.S3MockConfiguration;
import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.analysis.AttemptConfirmation.Confirmation;
import io.github.gb6105.pik.analysis.IntakeFixtures.Installed;
import io.github.gb6105.pik.analysis.UploadFixtures.Accepted;
import io.github.gb6105.pik.analysis.UploadFixtures.Image;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /analysis-jobs/{requestId}/upload-attempts/{attemptId}/complete} against PostgreSQL and S3Mock (plan
 * 4.2, 11.2 "확정", 11.3 C-20 without the recovery batch), with uploads to the presigned URLs.
 */
@Import({ TestcontainersConfiguration.class, S3MockConfiguration.class })
@SpringBootTest(properties = "pik.analysis.intake-enabled=true")
@AutoConfigureMockMvc
class UploadCompleteTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	AttemptConfirmation confirmation;

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
	void uploadedImageIsConfirmedAndTheJobQueued() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(500);
		JsonNode item = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		assertThat(fixtures.put(item, image.bytes())).isEqualTo(200);
		UUID attemptId = attemptId(item);
		String key = key();

		MvcTestResult result = fixtures.complete(device, key, accepted.requestId(), attemptId);

		assertThat(result).hasStatusOk();
		JsonNode response = json(result);
		assertThat(response.get("jobId").asString()).isEqualTo(key);
		assertThat(response.get("result").asString()).isEqualTo("SUCCEEDED");
		assertThat(response.get("retryable").asBoolean()).isFalse();
		assertThat(response.get("analysisJobId").asString()).isEqualTo(accepted.job().toString());
		assertThat(response.get("attemptId").asString()).isEqualTo(attemptId.toString());
		assertThat(response.get("attemptStatus").asString()).isEqualTo("CONFIRMED");
		assertThat(response.get("status").asString()).isEqualTo("READY");
		Map<String, Object> attempt = fixtures.attempt(attemptId);
		assertThat(attempt.get("status")).isEqualTo("CONFIRMED");
		assertThat(attempt.get("object_state")).isEqualTo("RETAINED");
		assertThat(attempt.get("confirmed_at")).isNotNull();
		assertThat(attempt.get("etag")).isNotNull();
		Map<String, Object> job = fixtures.job(accepted.job());
		assertThat(job.get("status")).isEqualTo("READY");
		assertThat(job.get("next_attempt_at")).isNotNull();
		assertThat((byte[]) job.get("input_sha256")).isEqualTo(image.sha256());
		assertThat(job.get("input_etag")).isEqualTo(attempt.get("etag"));
		assertThat(fixtures.reservations(accepted.job())).containsOnly("RESERVED");

		// The same key replays; a new key answers the confirmed attempt without a change or a HEAD.
		assertThat(json(fixtures.complete(device, key, accepted.requestId(), attemptId))).isEqualTo(response);
		clearInvocations(store);
		MvcTestResult again = fixtures.complete(device, key(), accepted.requestId(), attemptId);
		assertThat(again).hasStatusOk();
		assertThat(json(again).get("result").asString()).isEqualTo("SUCCEEDED");
		assertThat(json(again).get("status").asString()).isEqualTo("READY");
		verify(store, never()).head(any());
	}

	@Test
	void attemptReplacedByANewOneIsAStoredConflict() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode old = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		assertThat(fixtures.put(old, image.bytes())).isEqualTo(200);
		fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		String key = key();

		MvcTestResult result = fixtures.complete(device, key, accepted.requestId(), attemptId(old));

		assertThat(result).hasStatus(409);
		assertError(json(result), key, "CONFLICT", "ATTEMPT_NOT_CURRENT", false);
		assertThat(fixtures.storedStatus(device, key)).contains(409);
		assertThat(fixtures.job(accepted.job()).get("status")).isEqualTo("AWAITING_UPLOAD");
	}

	@Test
	void missingObjectIsAnUnstoredConflictWithRetryAfter() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode item = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		String key = key();

		MvcTestResult missing = fixtures.complete(device, key, accepted.requestId(), attemptId(item));

		assertThat(missing).hasStatus(409).hasHeader(HttpHeaders.RETRY_AFTER, "5");
		assertError(json(missing), key, "CONFLICT", "UPLOAD_NOT_FOUND", true);
		assertThat(fixtures.storedStatus(device, key)).isEmpty();
		assertThat(fixtures.attempt(attemptId(item)).get("status")).isEqualTo("ISSUED");

		assertThat(fixtures.put(item, image.bytes())).isEqualTo(200);
		MvcTestResult confirmed = fixtures.complete(device, key, accepted.requestId(), attemptId(item));
		assertThat(confirmed).hasStatusOk();
		assertThat(json(confirmed).get("status").asString()).isEqualTo("READY");
	}

	@Test
	void unavailableStorageIsUnstored() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		JsonNode item = fixtures.issued(device, accepted.requestId(), accepted.job(), fixtures.image(10));
		doReturn(new InputStore.Head.Unavailable()).when(store).head(any());
		String key = key();

		MvcTestResult result = fixtures.complete(device, key, accepted.requestId(), attemptId(item));

		assertThat(result).hasStatus(503);
		assertError(json(result), key, "TEMPORARY_FAILURE", "STORAGE_UNAVAILABLE", true);
		assertThat(fixtures.storedStatus(device, key)).isEmpty();
	}

	@Test
	void objectOtherThanIssuedIsRejectedAndTheJobKeepsWaiting() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 3);
		Image image = fixtures.image(100);

		assertRejectedFor(device, accepted, accepted.jobIds().get(0), image, fixtures.image(99), "SIZE_MISMATCH");
		assertRejectedFor(device, accepted, accepted.jobIds().get(1), image, fixtures.image(100), "CHECKSUM_MISMATCH");

		// Stored after its URL expired, whatever the bytes.
		UUID late = accepted.jobIds().get(2);
		JsonNode item = fixtures.issued(device, accepted.requestId(), late, image);
		assertThat(fixtures.put(item, image.bytes())).isEqualTo(200);
		jdbc.sql("UPDATE analysis_upload_attempt SET url_expires_at = now() - interval '1 hour' WHERE id = :id")
			.param("id", attemptId(item))
			.update();
		JsonNode response = json(fixtures.complete(device, key(), accepted.requestId(), attemptId(item)));
		assertThat(response.get("reason").asString()).isEqualTo("UPLOAD_INVALID");
		assertThat(fixtures.attempt(attemptId(item)).get("reject_reason")).isEqualTo("LATE_UPLOAD");
	}

	@Test
	void withdrawnConsentCancelsTheJobThroughTheCommonTermination() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode superseded = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		JsonNode item = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		assertThat(fixtures.put(item, image.bytes())).isEqualTo(200);
		fixtures.withdrawConsent(device);

		MvcTestResult result = fixtures.complete(device, key(), accepted.requestId(), attemptId(item));

		assertThat(result).hasStatusOk();
		JsonNode response = json(result);
		assertThat(response.get("result").asString()).isEqualTo("CANCELLED");
		assertThat(response.get("code").asString()).isEqualTo("FORBIDDEN");
		assertThat(response.get("reason").asString()).isEqualTo("CONSENT_WITHDRAWN");
		assertThat(response.get("status").asString()).isEqualTo("CANCELLED");
		assertEnded(accepted.job(), "CANCELLED", "CONSENT_WITHDRAWN");
		Map<String, Object> abandoned = fixtures.attempt(attemptId(item));
		assertThat(abandoned.get("status")).isEqualTo("ABANDONED");
		assertThat(abandoned.get("object_state")).isEqualTo("PENDING");
		assertThat(abandoned.get("delete_after")).isNotNull();
		assertThat(fixtures.attempt(attemptId(superseded)).get("status")).isEqualTo("SUPERSEDED");
		assertThat(fixtures.reservations(accepted.job())).containsOnly("RELEASED");
		JsonNode status = json(fixtures.status(device, accepted.requestId(), null));
		assertThat(status.get("jobs").get(0).get("reason").asString()).isEqualTo("CONSENT_WITHDRAWN");
		assertThat(status.get("phase").asString()).isEqualTo("DONE");
	}

	@Test
	void inactivePrincipalOrPassedProcessingDeadlineEndsTheJob() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 2);
		Image image = fixtures.image(100);
		JsonNode expired = fixtures.issued(device, accepted.requestId(), accepted.jobIds().get(0), image);
		JsonNode inactive = fixtures.issued(device, accepted.requestId(), accepted.jobIds().get(1), image);
		assertThat(fixtures.put(expired, image.bytes())).isEqualTo(200);
		assertThat(fixtures.put(inactive, image.bytes())).isEqualTo(200);
		jdbc.sql("UPDATE analysis_job SET processing_deadline = now() - interval '1 second' WHERE id = :id")
			.param("id", accepted.jobIds().get(0))
			.update();

		JsonNode cancelled = json(fixtures.complete(device, key(), accepted.requestId(), attemptId(expired)));
		jdbc.sql("UPDATE principal SET status = 'DELETING' WHERE id = :id").param("id", device.principalId()).update();
		JsonNode ineligible = json(fixtures.complete(device, key(), accepted.requestId(), attemptId(inactive)));

		assertThat(cancelled.get("result").asString()).isEqualTo("CANCELLED");
		assertThat(cancelled.get("reason").asString()).isEqualTo("PROCESSING_EXPIRED");
		assertEnded(accepted.jobIds().get(0), "CANCELLED", "PROCESSING_EXPIRED");
		assertThat(ineligible.get("result").asString()).isEqualTo("FAILED");
		assertThat(ineligible.get("reason").asString()).isEqualTo("PRINCIPAL_INACTIVE");
		assertEnded(accepted.jobIds().get(1), "INELIGIBLE", "PRINCIPAL_INACTIVE");
		assertThat(fixtures.reservations(accepted.jobIds().get(1))).containsOnly("RELEASED");
	}

	@Test
	void confirmationByTheBatchFirstAnswersTheApiWith200() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode item = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		assertThat(fixtures.put(item, image.bytes())).isEqualTo(200);
		UUID attemptId = attemptId(item);
		InputStore.Head head = store.head(UploadRecords.objectKey(accepted.job(), attemptId));

		// As the recovery batch: the HEAD outside the transaction, the shared condition inside it.
		Confirmation first = transaction
			.execute((status) -> confirmation.confirm(attemptId, (InputStore.Head.Present) head));
		Confirmation again = transaction
			.execute((status) -> confirmation.confirm(attemptId, (InputStore.Head.Present) head));

		assertThat(first).isEqualTo(new Confirmation.Confirmed());
		assertThat(again).isEqualTo(new Confirmation.NotCurrent());
		MvcTestResult result = fixtures.complete(device, key(), accepted.requestId(), attemptId);
		assertThat(result).hasStatusOk();
		assertThat(json(result).get("result").asString()).isEqualTo("SUCCEEDED");
		assertThat(json(result).get("attemptStatus").asString()).isEqualTo("CONFIRMED");
	}

	@Test
	void attemptOfAnotherRequestOrPrincipalIsNotFound() throws Exception {
		Installed device = fixtures.intake().android();
		Installed stranger = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Accepted other = fixtures.accepted(device, 1);
		JsonNode item = fixtures.issued(device, accepted.requestId(), accepted.job(), fixtures.image(10));
		String key = key();

		MvcTestResult otherRequest = fixtures.complete(device, key, other.requestId(), attemptId(item));
		MvcTestResult otherPrincipal = fixtures.complete(stranger, key, accepted.requestId(), attemptId(item));

		assertThat(otherRequest).hasStatus(404);
		assertError(json(otherRequest), key, "NOT_FOUND", "RESOURCE_NOT_FOUND", false);
		assertThat(otherPrincipal).hasStatus(404);
		assertThat(fixtures.storedStatus(device, key)).isEmpty();
		assertThat(fixtures.storedStatus(stranger, key)).isEmpty();
	}

	// C-20: the HEAD of the old attempt failed, a new attempt was issued, and the same key then gets the stored 409.
	@Test
	void retryAfterAFailedHeadOfAReplacedAttemptIsAStoredConflict() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode old = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		assertThat(fixtures.put(old, image.bytes())).isEqualTo(200);
		String key = key();
		doReturn(new InputStore.Head.Unavailable()).doCallRealMethod().when(store).head(any());
		assertThat(fixtures.complete(device, key, accepted.requestId(), attemptId(old))).hasStatus(503);
		fixtures.issued(device, accepted.requestId(), accepted.job(), image);

		MvcTestResult retried = fixtures.complete(device, key, accepted.requestId(), attemptId(old));

		assertThat(retried).hasStatus(409);
		assertError(json(retried), key, "CONFLICT", "ATTEMPT_NOT_CURRENT", false);
		assertThat(fixtures.storedStatus(device, key)).contains(409);
		assertThat(fixtures.complete(device, key, accepted.requestId(), attemptId(old))).hasStatus(409);
	}

	// C-20: a new attempt issued while the HEAD of the old one runs makes the old observation stale under the locks.
	@Test
	void newAttemptIssuedDuringTheHeadMakesTheObservationStale() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode old = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		assertThat(fixtures.put(old, image.bytes())).isEqualTo(200);
		doAnswer((invocation) -> {
			Object head = invocation.callRealMethod();
			MvcTestResult issued = pool.submit(() -> fixtures.issue(device, key(), accepted.requestId(),
					UploadFixtures.items(Map.of(accepted.job(), image)))).get(30, SECONDS);
			assertThat(issued).hasStatusOk();
			return head;
		}).doCallRealMethod().when(store).head(any());
		String key = key();

		MvcTestResult result = fixtures.complete(device, key, accepted.requestId(), attemptId(old));

		assertThat(result).hasStatus(409);
		assertError(json(result), key, "CONFLICT", "ATTEMPT_NOT_CURRENT", false);
		assertThat(fixtures.attempt(attemptId(old)).get("status")).isEqualTo("SUPERSEDED");
		assertThat(fixtures.job(accepted.job()).get("status")).isEqualTo("AWAITING_UPLOAD");
		assertThat(fixtures.attemptStatuses(accepted.job())).containsExactly("SUPERSEDED", "ISSUED");
	}

	private void assertRejectedFor(Installed device, Accepted accepted, UUID jobId, Image issued, Image uploaded,
			String rejectReason) {
		JsonNode item = fixtures.issued(device, accepted.requestId(), jobId, issued);
		UUID attemptId = attemptId(item);
		// Another object under the attempt's key, as a device sending other bytes than it asked for.
		InputStore.PresignedPut other = store.presignPut(UploadRecords.objectKey(jobId, attemptId),
				uploaded.bytes().length, uploaded.sha256(), Instant.now().plus(Duration.ofMinutes(5)));
		JsonNode otherItem = IntakeFixtures.JSON.valueToTree(Map.of("upload",
				Map.of("url", other.url().toString(), "method", "PUT", "headers", other.headers())));
		try {
			assertThat(fixtures.put(otherItem, uploaded.bytes())).isEqualTo(200);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}

		MvcTestResult result = fixtures.complete(device, key(), accepted.requestId(), attemptId);

		assertThat(result).hasStatusOk();
		JsonNode response = json(result);
		assertThat(response.get("result").asString()).isEqualTo("FAILED");
		assertThat(response.get("code").asString()).isEqualTo("INVALID_INPUT");
		assertThat(response.get("reason").asString()).isEqualTo("UPLOAD_INVALID");
		assertThat(response.get("retryable").asBoolean()).isTrue();
		assertThat(response.get("attemptStatus").asString()).isEqualTo("REJECTED");
		assertThat(response.get("status").asString()).isEqualTo("AWAITING_UPLOAD");
		Map<String, Object> attempt = fixtures.attempt(attemptId);
		assertThat(attempt.get("status")).isEqualTo("REJECTED");
		assertThat(attempt.get("reject_reason")).isEqualTo(rejectReason);
		assertThat(attempt.get("object_state")).isEqualTo("PENDING");
		assertThat(fixtures.job(jobId).get("status")).isEqualTo("AWAITING_UPLOAD");
		assertThat(fixtures.reservations(jobId)).containsOnly("RESERVED");
	}

	private void assertEnded(UUID jobId, String status, String failureCode) {
		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo(status);
		assertThat(job.get("failure_code")).isEqualTo(failureCode);
		assertThat(job.get("ended_at")).isNotNull();
		assertThat(job.get("run_token")).isNull();
	}

	private static UUID attemptId(JsonNode item) {
		return UUID.fromString(item.get("attemptId").asString());
	}

}
