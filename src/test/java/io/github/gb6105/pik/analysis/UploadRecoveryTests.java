package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.IntakeFixtures.json;
import static io.github.gb6105.pik.analysis.IntakeFixtures.key;
import static io.github.gb6105.pik.analysis.JobRecords.timestamp;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.gb6105.pik.S3MockConfiguration;
import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.analysis.IntakeFixtures.Installed;
import io.github.gb6105.pik.analysis.UploadFixtures.Accepted;
import io.github.gb6105.pik.analysis.UploadFixtures.Image;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/**
 * The upload recovery batch against PostgreSQL and S3Mock (plan 7.1, 11.2 "배치", 11.3 C-14 and the batch half of
 * C-20). The batch is called directly; jobs are made overdue by moving their deadlines into the past. The batch reads
 * every overdue job of the database, so the assertions are about the jobs and object keys of each test only.
 */
@Import({ TestcontainersConfiguration.class, S3MockConfiguration.class })
@SpringBootTest(properties = "pik.analysis.intake-enabled=true")
@AutoConfigureMockMvc
class UploadRecoveryTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UploadRecovery recovery;

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
	void storedObjectOfTheCurrentAttemptIsConfirmed() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 2);
		Image image = fixtures.image(200);
		UUID overdue = accepted.jobIds().get(0);
		UUID waiting = accepted.jobIds().get(1);
		JsonNode overdueItem = fixtures.issued(device, accepted.requestId(), overdue, image);
		JsonNode waitingItem = fixtures.issued(device, accepted.requestId(), waiting, image);
		assertThat(fixtures.put(overdueItem, image.bytes())).isEqualTo(200);
		assertThat(fixtures.put(waitingItem, image.bytes())).isEqualTo(200);
		passUploadDeadline(overdue);

		recovery.recover();

		Map<String, Object> job = fixtures.job(overdue);
		assertThat(job.get("status")).isEqualTo("READY");
		assertThat((byte[]) job.get("input_sha256")).isEqualTo(image.sha256());
		Map<String, Object> attempt = fixtures.attempt(attemptId(overdueItem));
		assertThat(attempt.get("status")).isEqualTo("CONFIRMED");
		assertThat(attempt.get("object_state")).isEqualTo("RETAINED");
		assertThat(fixtures.reservations(overdue)).containsOnly("RESERVED");
		// A job whose upload window is still open is not read.
		assertThat(fixtures.job(waiting).get("status")).isEqualTo("AWAITING_UPLOAD");
		assertThat(fixtures.attempt(attemptId(waitingItem)).get("status")).isEqualTo("ISSUED");
		verify(store, never()).head(UploadRecords.objectKey(waiting, attemptId(waitingItem)));
	}

	@Test
	void missingObjectEndsTheJobAsExpiredOrWithAllAttemptsUsed() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 2);
		UUID expired = accepted.jobIds().get(0);
		UUID exhausted = accepted.jobIds().get(1);
		JsonNode expiredItem = fixtures.issued(device, accepted.requestId(), expired, fixtures.image(10));
		JsonNode exhaustedItem = fixtures.issued(device, accepted.requestId(), exhausted, fixtures.image(10));
		passUploadDeadline(expired);
		// Every attempt issued and the last URL expired, before the upload deadline.
		useAllAttempts(exhausted, attemptId(exhaustedItem));

		recovery.recover();

		assertEnded(expired, "CANCELLED", "UPLOAD_EXPIRED");
		assertEnded(exhausted, "FAILED", "UPLOAD_ATTEMPTS_EXHAUSTED");
		for (JsonNode item : new JsonNode[] { expiredItem, exhaustedItem }) {
			Map<String, Object> attempt = fixtures.attempt(attemptId(item));
			assertThat(attempt.get("status")).isEqualTo("ABANDONED");
			assertThat(attempt.get("object_state")).isEqualTo("PENDING");
			assertThat(attempt.get("delete_after")).isNotNull();
		}
		verify(store).head(UploadRecords.objectKey(expired, attemptId(expiredItem)));
		verify(store).head(UploadRecords.objectKey(exhausted, attemptId(exhaustedItem)));
	}

	@Test
	void objectStoredAfterTheUrlExpiredIsRejectedAndEndsTheJob() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode item = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		assertThat(fixtures.put(item, image.bytes())).isEqualTo(200);
		useAllAttempts(accepted.job(), attemptId(item));

		recovery.recover();

		Map<String, Object> attempt = fixtures.attempt(attemptId(item));
		assertThat(attempt.get("status")).isEqualTo("REJECTED");
		assertThat(attempt.get("reject_reason")).isEqualTo("LATE_UPLOAD");
		assertThat(attempt.get("object_state")).isEqualTo("PENDING");
		assertEnded(accepted.job(), "FAILED", "UPLOAD_ATTEMPTS_EXHAUSTED");
	}

	@Test
	void jobWhoseLastAttemptWasRejectedEndsWithoutAHead() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode item = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		assertThat(fixtures.put(item, image.bytes())).isEqualTo(200);
		jdbc.sql("UPDATE analysis_upload_attempt SET url_expires_at = :expiresAt WHERE id = :id")
			.param("expiresAt", timestamp(Instant.now().minus(Duration.ofHours(1))))
			.param("id", attemptId(item))
			.update();
		JsonNode rejected = json(fixtures.complete(device, key(), accepted.requestId(), attemptId(item)));
		assertThat(rejected.get("attemptStatus").asString()).isEqualTo("REJECTED");
		passUploadDeadline(accepted.job());
		clearInvocations(store);

		recovery.recover();

		assertEnded(accepted.job(), "CANCELLED", "UPLOAD_EXPIRED");
		assertThat(fixtures.attemptStatuses(accepted.job())).containsExactly("REJECTED");
		verify(store, never()).head(startsWith("analysis-input/" + accepted.job() + "/"));
	}

	@Test
	void jobWithoutAnAttemptExpires() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		passUploadDeadline(accepted.job());

		recovery.recover();

		assertEnded(accepted.job(), "CANCELLED", "UPLOAD_EXPIRED");
		assertThat(fixtures.attemptStatuses(accepted.job())).isEmpty();
	}

	@Test
	void unavailableStorageLeavesTheJobToTheNextRun() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode item = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		assertThat(fixtures.put(item, image.bytes())).isEqualTo(200);
		passUploadDeadline(accepted.job());
		long jobVersion = version(fixtures.job(accepted.job()));
		doReturn(new InputStore.Head.Unavailable()).when(store).head(any());

		recovery.recover();

		Map<String, Object> job = fixtures.job(accepted.job());
		assertThat(job.get("status")).isEqualTo("AWAITING_UPLOAD");
		assertThat(version(job)).isEqualTo(jobVersion);
		assertThat(fixtures.attempt(attemptId(item)).get("status")).isEqualTo("ISSUED");
		assertThat(fixtures.reservations(accepted.job())).containsOnly("RESERVED");

		doCallRealHead();
		recovery.recover();
		assertThat(fixtures.job(accepted.job()).get("status")).isEqualTo("READY");
	}

	// C-14: the API confirms while the batch's HEAD runs; the batch then finds the attempt no longer current.
	@Test
	void confirmationByTheApiDuringTheBatchHeadLeavesTheBatchNothingToDo() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode item = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		UUID attemptId = attemptId(item);
		assertThat(fixtures.put(item, image.bytes())).isEqualTo(200);
		passUploadDeadline(accepted.job());
		long jobVersion = version(fixtures.job(accepted.job()));
		onFirstHead(UploadRecords.objectKey(accepted.job(), attemptId), () -> {
			MvcTestResult result = pool.submit(() -> fixtures.complete(device, key(), accepted.requestId(), attemptId))
				.get(30, SECONDS);
			assertThat(result).hasStatusOk();
			assertThat(json(result).get("status").asString()).isEqualTo("READY");
		}, null);

		recovery.recover();

		assertConfirmedOnce(accepted.job(), attemptId, jobVersion);
	}

	// C-14 and C-20: the batch confirms while the API's HEAD runs; the API then answers with the confirmed attempt,
	// also when its HEAD found no object (deleted meanwhile).
	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void confirmationByTheBatchDuringTheApiHeadAnswersTheApiWith200(boolean objectGoneForTheApi) throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode item = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		UUID attemptId = attemptId(item);
		assertThat(fixtures.put(item, image.bytes())).isEqualTo(200);
		passUploadDeadline(accepted.job());
		long jobVersion = version(fixtures.job(accepted.job()));
		onFirstHead(UploadRecords.objectKey(accepted.job(), attemptId),
				() -> pool.submit(() -> recovery.recover()).get(30, SECONDS),
				objectGoneForTheApi ? new InputStore.Head.Absent() : null);
		String key = key();

		MvcTestResult result = fixtures.complete(device, key, accepted.requestId(), attemptId);

		assertThat(result).hasStatusOk();
		JsonNode response = json(result);
		assertThat(response.get("result").asString()).isEqualTo("SUCCEEDED");
		assertThat(response.get("attemptStatus").asString()).isEqualTo("CONFIRMED");
		assertThat(response.get("status").asString()).isEqualTo("READY");
		assertThat(fixtures.storedStatus(device, key)).contains(200);
		assertConfirmedOnce(accepted.job(), attemptId, jobVersion);
	}

	// C-14: a new attempt issued while the batch's HEAD of the old one runs makes the old observation stale.
	@Test
	void headOfAnAttemptReplacedMeanwhileIsIgnored() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode old = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		passUploadDeadline(accepted.job());
		onFirstHead(UploadRecords.objectKey(accepted.job(), attemptId(old)), () -> {
			// Issuing needs an open upload window, so it is reopened for the new attempt only.
			setUploadDeadline(accepted.job(), Instant.now().plus(Duration.ofHours(1)));
			MvcTestResult issued = pool.submit(() -> fixtures.issue(device, key(), accepted.requestId(),
					UploadFixtures.items(Map.of(accepted.job(), image)))).get(30, SECONDS);
			assertThat(issued).hasStatusOk();
			passUploadDeadline(accepted.job());
		}, null);

		recovery.recover();

		assertThat(fixtures.job(accepted.job()).get("status")).isEqualTo("AWAITING_UPLOAD");
		assertThat(fixtures.attemptStatuses(accepted.job())).containsExactlyInAnyOrder("SUPERSEDED", "ISSUED");
		assertThat(fixtures.reservations(accepted.job())).containsOnly("RESERVED");
	}

	/**
	 * Runs {@code during} inside the first HEAD of {@code key}, after the real HEAD; other HEADs are real.
	 * @param answer what that HEAD returns instead of the real result, {@code null} for the real one
	 */
	private void onFirstHead(String key, ThrowingRunnable during, InputStore.@Nullable Head answer) {
		AtomicBoolean done = new AtomicBoolean();
		doAnswer((invocation) -> {
			Object head = invocation.callRealMethod();
			if (key.equals(invocation.getArgument(0)) && done.compareAndSet(false, true)) {
				during.run();
				return (answer != null) ? answer : head;
			}
			return head;
		}).when(store).head(any());
	}

	private void doCallRealHead() {
		doAnswer((invocation) -> invocation.callRealMethod()).when(store).head(any());
	}

	private void assertConfirmedOnce(UUID jobId, UUID attemptId, long jobVersionBefore) {
		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo("READY");
		assertThat(version(job)).isEqualTo(jobVersionBefore + 1);
		Map<String, Object> attempt = fixtures.attempt(attemptId);
		assertThat(attempt.get("status")).isEqualTo("CONFIRMED");
		assertThat(version(attempt)).isEqualTo(1);
	}

	private void assertEnded(UUID jobId, String status, String failureCode) {
		Map<String, Object> job = fixtures.job(jobId);
		assertThat(job.get("status")).isEqualTo(status);
		assertThat(job.get("failure_code")).isEqualTo(failureCode);
		assertThat(job.get("ended_at")).isNotNull();
		assertThat(fixtures.reservations(jobId)).containsOnly("RELEASED");
	}

	private void passUploadDeadline(UUID jobId) {
		setUploadDeadline(jobId, Instant.now().minus(Duration.ofHours(1)));
	}

	// The batch compares with the application clock, so the times come from it too.
	private void setUploadDeadline(UUID jobId, Instant deadline) {
		jdbc.sql("UPDATE analysis_job SET upload_deadline = :deadline WHERE id = :id")
			.param("deadline", timestamp(deadline))
			.param("id", jobId)
			.update();
	}

	private void useAllAttempts(UUID jobId, UUID lastAttemptId) {
		jdbc.sql("UPDATE analysis_job SET issued_count = 5 WHERE id = :id").param("id", jobId).update();
		jdbc.sql("UPDATE analysis_upload_attempt SET url_expires_at = :expiresAt WHERE id = :id")
			.param("expiresAt", timestamp(Instant.now().minus(Duration.ofHours(1))))
			.param("id", lastAttemptId)
			.update();
	}

	private static long version(Map<String, Object> row) {
		return ((Number) row.get("version")).longValue();
	}

	private static UUID attemptId(JsonNode item) {
		return UUID.fromString(item.get("attemptId").asString());
	}

	@FunctionalInterface
	interface ThrowingRunnable {

		void run() throws Exception;

	}

}
