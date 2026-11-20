package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.IntakeFixtures.JSON;
import static io.github.gb6105.pik.analysis.IntakeFixtures.key;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gb6105.pik.analysis.IntakeFixtures.Installed;
import io.github.gb6105.pik.analysis.UploadFixtures.Accepted;
import io.github.gb6105.pik.analysis.UploadFixtures.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.imageio.ImageIO;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * Jobs made {@code READY} through intake, upload and confirmation, and the run rows they lead to.
 */
class RunFixtures {

	static final ModelReply.Usage TOKENS = new ModelReply.Usage(1500, 300, 0, 0);

	private final UploadFixtures uploads;

	private final JdbcClient jdbc;

	RunFixtures(MockMvcTester mvc, JdbcClient jdbc) {
		this.uploads = new UploadFixtures(mvc, jdbc);
		this.jdbc = jdbc;
	}

	UploadFixtures uploads() {
		return uploads;
	}

	IntakeFixtures intake() {
		return uploads.intake();
	}

	/**
	 * Leaves this test the only one with open jobs, all gates closed since a minute ago and no daily cost.
	 */
	void isolate() {
		Instant now = Instant.now();
		jdbc.sql("""
				UPDATE analysis_job SET status = 'CANCELLED', failure_code = 'PROCESSING_EXPIRED', ended_at = :now,
				                        run_token = NULL, locked_until = NULL, updated_at = :now
				WHERE status IN ('AWAITING_UPLOAD', 'READY', 'RUNNING')
				""").param("now", utc(now)).update();
		jdbc.sql("""
				UPDATE ai_gate SET state = 'CLOSED', generation = generation + 1, blocked_until = NULL, probe_job_id = NULL,
				                   probe_run_token = NULL, config_version = NULL, reset_at = :resetAt,
				                   failure_streak = 0, opened_at = NULL, updated_at = :now
				""").param("resetAt", utc(now.minusSeconds(60))).param("now", utc(now)).update();
		jdbc.sql("DELETE FROM ai_spend_day").update();
	}

	/**
	 * Single photos accepted with a job each, uploaded as JPEGs and {@code READY}.
	 */
	Accepted ready(Installed device, int photos) {
		Accepted accepted = uploads.accepted(device, photos);
		accepted.jobIds().forEach((jobId) -> upload(device, accepted.requestId(), jobId, jpeg(64, 48)));
		return accepted;
	}

	/**
	 * Uploads the bytes for the job and confirms them.
	 * @return the issued item, with its presigned URL
	 */
	JsonNode upload(Installed device, UUID requestId, UUID jobId, byte[] bytes) {
		JsonNode item = uploads.issued(device, requestId, jobId, new Image(bytes));
		try {
			assertThat(uploads.put(item, bytes)).isEqualTo(200);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		assertThat(uploads.complete(device, key(), requestId, UUID.fromString(item.get("attemptId").asString())))
			.hasStatusOk();
		assertThat(job(jobId).get("status")).isEqualTo("READY");
		return item;
	}

	static byte[] jpeg(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				image.setRGB(x, y, ThreadLocalRandom.current().nextInt(0x1000000));
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			assertThat(ImageIO.write(image, "jpg", out)).isTrue();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out.toByteArray();
	}

	static ModelReply output() {
		return new ModelReply.Answered(200, "req_ok", TOKENS, "end_turn", JSON.readTree(ResultValidatorTests.VALID),
				null);
	}

	static ModelReply answered(int status, String stopReason) {
		return new ModelReply.Answered(status, "req_" + status, TOKENS, stopReason, null, null);
	}

	Map<String, Object> job(UUID jobId) {
		return jdbc.sql("SELECT * FROM analysis_job WHERE id = :id").param("id", jobId).query().singleRow();
	}

	UUID photoOf(UUID jobId) {
		return jdbc.sql("SELECT photo_id FROM analysis_job WHERE id = :id").param("id", jobId).query(UUID.class).single();
	}

	List<Map<String, Object>> calls(UUID jobId) {
		return jdbc.sql("SELECT * FROM external_call WHERE job_id = :job ORDER BY created_at")
			.param("job", jobId)
			.query()
			.listOfRows();
	}

	Map<String, Object> call(UUID jobId) {
		List<Map<String, Object>> calls = calls(jobId);
		assertThat(calls).hasSize(1);
		return calls.getFirst();
	}

	List<Map<String, Object>> results(UUID jobId) {
		return jdbc.sql("SELECT * FROM analysis_result WHERE job_id = :job ORDER BY photo_id")
			.param("job", jobId)
			.query()
			.listOfRows();
	}

	Map<String, Object> gate(GateCause cause) {
		return jdbc.sql("SELECT * FROM ai_gate WHERE cause = :cause").param("cause", cause.name()).query().singleRow();
	}

	Optional<Map<String, Object>> spendDay() {
		return jdbc.sql("SELECT * FROM ai_spend_day WHERE day = :day")
			.param("day", BudgetDays.of(Instant.now()))
			.query()
			.listOfRows()
			.stream()
			.findFirst();
	}

	int dailySends(UUID ownerId) {
		return jdbc.sql("SELECT COALESCE(sum(send_count), 0) FROM ai_send_daily WHERE owner_id = :owner")
			.param("owner", ownerId)
			.query(Integer.class)
			.single();
	}

	int deviceSends(UUID markId) {
		return jdbc.sql("SELECT send_count FROM device_trial_mark WHERE id = :id")
			.param("id", markId)
			.query(Integer.class)
			.single();
	}

	Map<String, Object> photoBudget(UUID photoId) {
		return jdbc.sql("SELECT * FROM photo_ai_budget WHERE photo_id = :photo")
			.param("photo", photoId)
			.query()
			.singleRow();
	}

	List<String> reservations(UUID jobId) {
		return uploads.reservations(jobId);
	}

	/**
	 * Deletes the photo's metadata, as an owner's deletion would.
	 */
	void deletePhoto(UUID photoId) {
		jdbc.sql("DELETE FROM photo_vector WHERE photo_id = :photo").param("photo", photoId).update();
		jdbc.sql("DELETE FROM photo WHERE id = :photo").param("photo", photoId).update();
	}

	/**
	 * Records a refusal after the principal's latest decision, as a withdrawal under the consent lock would.
	 */
	void refuse(UUID principalId) {
		jdbc.sql("""
				INSERT INTO ai_consent (id, principal_id, device_id, purpose, decision, notice_version, version, decided_at)
				SELECT :id, principal_id, device_id, purpose, 'DENIED', notice_version, version + 1, :now
				FROM ai_consent WHERE principal_id = :principal ORDER BY version DESC LIMIT 1
				""")
			.param("id", UUID.randomUUID())
			.param("principal", principalId)
			.param("now", utc(Instant.now()))
			.update();
	}

	/**
	 * The server photo IDs of the job's own {@code ACCEPTED} items.
	 */
	List<UUID> members(UUID jobId) {
		return jdbc.sql("""
				SELECT i.photo_id FROM analysis_request_item i JOIN analysis_job j ON j.request_id = i.request_id
				WHERE j.id = :job AND i.analysis_job_id = :job AND i.admission_result = 'ACCEPTED' ORDER BY i.photo_id
				""").param("job", jobId).query(UUID.class).list();
	}

	/**
	 * A {@code timestamptz} value of a row read as a map.
	 */
	static Instant instant(Object value) {
		return (value instanceof Timestamp timestamp) ? timestamp.toInstant() : ((OffsetDateTime) value).toInstant();
	}

	static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
