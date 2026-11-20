package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.IntakeFixtures.json;
import static io.github.gb6105.pik.analysis.IntakeFixtures.key;
import static io.github.gb6105.pik.analysis.JobRecords.timestamp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.gb6105.pik.S3MockConfiguration;
import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.analysis.IntakeFixtures.Installed;
import io.github.gb6105.pik.analysis.UploadFixtures.Accepted;
import io.github.gb6105.pik.analysis.UploadFixtures.Image;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * The object delete batch, its reconciliation and the input rate window cleanup against PostgreSQL and S3Mock (plan
 * 7.4, 7.5, 11.2 "배치", 11.3 C-19). The batches are called directly; times are moved with the application clock,
 * which the batches compare with. They read every due row of the database, so the assertions are about the rows and
 * object keys of each test only.
 */
@Import({ TestcontainersConfiguration.class, S3MockConfiguration.class })
@SpringBootTest(properties = "pik.analysis.intake-enabled=true")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class UploadCleanupTests {

	static final Duration IN_FLIGHT_MARGIN = Duration.ofMinutes(30);

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UploadCleanup cleanup;

	@MockitoSpyBean
	InputStore store;

	UploadFixtures fixtures;

	@BeforeEach
	void setUp() {
		fixtures = new UploadFixtures(mvc, jdbc);
	}

	@Test
	void deleteBeforeTheUrlExpiresIsRepeatedAfterItAndThenSent() throws Exception {
		Replaced replaced = replaced();
		String key = replaced.oldKey();

		cleanup.deleteObjects();

		verify(store).delete(key);
		assertThat(store.head(key)).isEqualTo(new InputStore.Head.Absent());
		Map<String, Object> attempt = fixtures.attempt(replaced.oldAttemptId());
		assertThat(attempt.get("object_state")).isEqualTo("PENDING");
		assertThat(instant(attempt, "delete_after"))
			.isEqualTo(instant(attempt, "url_expires_at").plus(IN_FLIGHT_MARGIN));

		// Once the URL expired and the margin passed, the next delete is final.
		Instant now = Instant.now();
		setDeleteTimes(replaced.oldAttemptId(), now.minus(IN_FLIGHT_MARGIN).minusSeconds(60), now.minusSeconds(1));
		cleanup.deleteObjects();

		attempt = fixtures.attempt(replaced.oldAttemptId());
		assertThat(attempt.get("object_state")).isEqualTo("DELETE_SENT");
		assertThat(attempt.get("delete_after")).isNull();
		// The current attempt keeps its object.
		verify(store, never()).delete(replaced.currentKey());
		assertThat(fixtures.attempt(replaced.currentAttemptId()).get("object_state")).isEqualTo("RETAINED");
	}

	// C-19: the delete starts before the URL and its margin expired and is acknowledged after; it is not final.
	@Test
	void deleteStartedBeforeTheCutoffButAcknowledgedAfterItIsRepeated() throws Exception {
		Replaced replaced = replaced();
		String key = replaced.oldKey();
		// Nothing else due remains, so the delete of this attempt starts right away.
		cleanup.deleteObjects();
		Instant now = Instant.now();
		Instant urlExpiresAt = now.minus(IN_FLIGHT_MARGIN).plusSeconds(2).truncatedTo(ChronoUnit.MILLIS);
		Instant finalFrom = urlExpiresAt.plus(IN_FLIGHT_MARGIN);
		setDeleteTimes(replaced.oldAttemptId(), urlExpiresAt, now.minusSeconds(1));
		AtomicReference<Instant> started = new AtomicReference<>();
		doAnswer((invocation) -> {
			started.set(Instant.now());
			while (!Instant.now().isAfter(finalFrom)) {
				Thread.sleep(10);
			}
			return invocation.callRealMethod();
		}).when(store).delete(key);

		cleanup.deleteObjects();

		assertThat(started.get()).isBefore(finalFrom);
		Map<String, Object> attempt = fixtures.attempt(replaced.oldAttemptId());
		assertThat(attempt.get("object_state")).isEqualTo("PENDING");
		assertThat(instant(attempt, "delete_after")).isEqualTo(finalFrom);

		cleanup.deleteObjects();

		assertThat(fixtures.attempt(replaced.oldAttemptId()).get("object_state")).isEqualTo("DELETE_SENT");
	}

	@Test
	void failedDeleteStaysPending() throws Exception {
		Replaced replaced = replaced();
		Map<String, Object> before = fixtures.attempt(replaced.oldAttemptId());
		doReturn(false).when(store).delete(replaced.oldKey());

		cleanup.deleteObjects();

		Map<String, Object> after = fixtures.attempt(replaced.oldAttemptId());
		assertThat(after.get("object_state")).isEqualTo("PENDING");
		assertThat(after.get("delete_after")).isEqualTo(before.get("delete_after"));
		assertThat(after.get("version")).isEqualTo(before.get("version"));
		assertThat(store.head(replaced.oldKey())).isInstanceOf(InputStore.Head.Present.class);
	}

	@Test
	void confirmedAttemptOfAWaitingJobIsNeverDeleted() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode item = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		UUID attemptId = attemptId(item);
		assertThat(fixtures.put(item, image.bytes())).isEqualTo(200);
		assertThat(json(fixtures.complete(device, key(), accepted.requestId(), attemptId)).get("status").asString())
			.isEqualTo("READY");
		String key = UploadRecords.objectKey(accepted.job(), attemptId);

		cleanup.deleteObjects();
		cleanup.reconcile();

		verify(store, never()).delete(key);
		Map<String, Object> attempt = fixtures.attempt(attemptId);
		assertThat(attempt.get("status")).isEqualTo("CONFIRMED");
		assertThat(attempt.get("object_state")).isEqualTo("RETAINED");
		assertThat(store.head(key)).isInstanceOf(InputStore.Head.Present.class);
	}

	@Test
	void reconciliationSchedulesTheKeptObjectsOfEndedJobs() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode item = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		UUID attemptId = attemptId(item);
		assertThat(fixtures.put(item, image.bytes())).isEqualTo(200);
		fixtures.complete(device, key(), accepted.requestId(), attemptId);
		// An ended job whose confirmed object was left kept, as no terminal path leaves it.
		jdbc.sql("""
				UPDATE analysis_job SET status = 'FAILED', failure_code = 'INPUT_INVALID', ended_at = now()
				WHERE id = :id
				""").param("id", accepted.job()).update();
		Instant before = Instant.now();

		cleanup.reconcile();

		Map<String, Object> attempt = fixtures.attempt(attemptId);
		assertThat(attempt.get("status")).isEqualTo("CONFIRMED");
		assertThat(attempt.get("object_state")).isEqualTo("PENDING");
		assertThat(instant(attempt, "delete_after")).isBetween(before, Instant.now());

		cleanup.deleteObjects();

		assertThat(store.head(UploadRecords.objectKey(accepted.job(), attemptId)))
			.isEqualTo(new InputStore.Head.Absent());
	}

	@Test
	void deletePendingPastTheAlarmIsReportedWithoutKeysOrIds(CapturedOutput output) throws Exception {
		Replaced replaced = replaced();
		Instant now = Instant.now();
		setDeleteTimes(replaced.oldAttemptId(), now.plus(Duration.ofMinutes(5)), now.minus(Duration.ofHours(7)));
		int start = output.getOut().length();

		cleanup.reconcile();

		assertThat(output.getOut().substring(start))
			.contains("input object deletes are pending longer than PT6H after they were due")
			.doesNotContain(replaced.oldKey())
			.doesNotContain(replaced.oldAttemptId().toString())
			.doesNotContain(replaced.jobId().toString());
	}

	@Test
	void inputRateWindowsOlderThanTwoHoursAreDeleted() {
		UUID subject = UUID.randomUUID();
		Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
		List<Instant> windows = List.of(hour.minus(Duration.ofHours(3)), hour.minus(Duration.ofHours(1)), hour);
		for (Instant window : windows) {
			jdbc.sql("""
					INSERT INTO analysis_input_rate (subject_kind, subject_id, window_start, items, bytes)
					VALUES ('PRINCIPAL', :subject, :window, 1, 100)
					""").param("subject", subject).param("window", timestamp(window)).update();
		}

		cleanup.deleteRateWindows();

		List<Instant> kept = jdbc.sql("""
				SELECT window_start FROM analysis_input_rate WHERE subject_id = :subject ORDER BY window_start
				""").param("subject", subject).query(OffsetDateTime.class).list().stream()
			.map(OffsetDateTime::toInstant)
			.toList();
		assertThat(kept).containsExactly(hour.minus(Duration.ofHours(1)), hour);
	}

	/**
	 * A job whose uploaded attempt was replaced by a new one, so that the old object is to be deleted now.
	 */
	private Replaced replaced() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode old = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		assertThat(fixtures.put(old, image.bytes())).isEqualTo(200);
		JsonNode current = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		Replaced replaced = new Replaced(accepted.job(), attemptId(old), attemptId(current));
		Map<String, Object> attempt = fixtures.attempt(replaced.oldAttemptId());
		assertThat(attempt.get("status")).isEqualTo("SUPERSEDED");
		assertThat(attempt.get("object_state")).isEqualTo("PENDING");
		return replaced;
	}

	// The batches compare with the application clock, so the times come from it too.
	private void setDeleteTimes(UUID attemptId, Instant urlExpiresAt, Instant deleteAfter) {
		jdbc.sql("""
				UPDATE analysis_upload_attempt SET url_expires_at = :expiresAt, delete_after = :deleteAfter
				WHERE id = :id
				""")
			.param("expiresAt", timestamp(urlExpiresAt))
			.param("deleteAfter", timestamp(deleteAfter))
			.param("id", attemptId)
			.update();
	}

	private static Instant instant(Map<String, Object> row, String column) {
		Object value = row.get(column);
		return (value instanceof OffsetDateTime time) ? time.toInstant() : ((Timestamp) value).toInstant();
	}

	private static UUID attemptId(JsonNode item) {
		return UUID.fromString(item.get("attemptId").asString());
	}

	record Replaced(UUID jobId, UUID oldAttemptId, UUID currentAttemptId) {

		String oldKey() {
			return UploadRecords.objectKey(jobId, oldAttemptId);
		}

		String currentKey() {
			return UploadRecords.objectKey(jobId, currentAttemptId);
		}

	}

}
