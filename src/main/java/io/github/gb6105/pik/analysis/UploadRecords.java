package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.JobRecords.timestamp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plain SQL for upload attempts and the input rate windows. Attempts change only under their job's lock, taken
 * before the attempt rows (plan 3.3). The rows carry object keys, which must not reach logs.
 */
@Repository
@RequiredArgsConstructor
class UploadRecords {

	private static final String ATTEMPT = """
			SELECT a.id, a.owner_id, a.job_id, a.status, a.object_key, a.content_length, a.sha256, a.url_expires_at
			FROM analysis_upload_attempt a
			""";

	private final JdbcClient jdbc;

	private final NamedParameterJdbcTemplate batch;

	/**
	 * The object key of an attempt (plan K-3): job and attempt IDs only, no photo or principal ID.
	 */
	static String objectKey(UUID jobId, UUID attemptId) {
		return "analysis-input/" + jobId + "/" + attemptId;
	}

	/**
	 * The owner's intake request. Its consent basis and trial mark never change.
	 */
	Optional<RequestBasis> findRequest(UUID ownerId, UUID requestId) {
		return jdbc.sql("""
				SELECT id, owner_id, consent_version, trial_mark_id FROM analysis_request
				WHERE id = :id AND owner_id = :owner
				""")
			.param("id", requestId)
			.param("owner", ownerId)
			.query((rs, rowNum) -> new RequestBasis(rs.getObject("id", UUID.class),
					rs.getObject("owner_id", UUID.class), rs.getLong("consent_version"),
					rs.getObject("trial_mark_id", UUID.class)))
			.optional();
	}

	/**
	 * The attempt, if it was issued for a job of the owner's request.
	 */
	Optional<AttemptRow> findOfRequest(UUID ownerId, UUID requestId, UUID attemptId) {
		return jdbc.sql(ATTEMPT + """
				JOIN analysis_job j ON j.id = a.job_id
				WHERE a.id = :id AND a.owner_id = :owner AND j.request_id = :request
				""")
			.param("id", attemptId)
			.param("owner", ownerId)
			.param("request", requestId)
			.query(UploadRecords::attempt)
			.optional();
	}

	/**
	 * Reads the attempt without a lock.
	 */
	Optional<AttemptRow> find(UUID attemptId) {
		return jdbc.sql(ATTEMPT + "WHERE a.id = :id").param("id", attemptId).query(UploadRecords::attempt).optional();
	}

	/**
	 * Reads the attempts without a lock.
	 * @return attempt ID to the attempt; unknown IDs are missing
	 */
	Map<UUID, AttemptRow> find(Collection<UUID> attemptIds) {
		Map<UUID, AttemptRow> attempts = new HashMap<>();
		if (!attemptIds.isEmpty()) {
			jdbc.sql(ATTEMPT + "WHERE a.id IN (:ids)")
				.param("ids", attemptIds)
				.query(UploadRecords::attempt)
				.list()
				.forEach((attempt) -> attempts.put(attempt.id(), attempt));
		}
		return attempts;
	}

	/**
	 * What the locks of a confirmation need before they are taken, read without a lock: the job of the attempt, its
	 * owner and the consent basis of the request that created the job. None of them changes.
	 */
	Optional<AttemptBasis> findBasis(UUID attemptId) {
		return jdbc.sql("""
				SELECT a.job_id, a.owner_id, r.consent_version
				FROM analysis_upload_attempt a
				JOIN analysis_job j ON j.id = a.job_id
				JOIN analysis_request r ON r.id = j.request_id
				WHERE a.id = :id
				""")
			.param("id", attemptId)
			.query((rs, rowNum) -> new AttemptBasis(rs.getObject("job_id", UUID.class),
					rs.getObject("owner_id", UUID.class), rs.getLong("consent_version")))
			.optional();
	}

	/**
	 * Locks the attempt until the transaction ends; its job must be locked first.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Optional<AttemptRow> lock(UUID attemptId) {
		return jdbc.sql(ATTEMPT + "WHERE a.id = :id FOR NO KEY UPDATE")
			.param("id", attemptId)
			.query(UploadRecords::attempt)
			.optional();
	}

	/**
	 * Locks the current {@code ISSUED} attempts of the jobs in {@code id} order until the transaction ends; the jobs
	 * must be locked first.
	 * @return job ID to its current attempt; jobs without one are missing
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Map<UUID, AttemptRow> lockIssued(Collection<UUID> jobIds) {
		Map<UUID, AttemptRow> attempts = new HashMap<>();
		if (!jobIds.isEmpty()) {
			jdbc.sql(ATTEMPT + "WHERE a.job_id IN (:jobs) AND a.status = 'ISSUED' ORDER BY a.id FOR NO KEY UPDATE")
				.param("jobs", jobIds)
				.query(UploadRecords::attempt)
				.list()
				.forEach((attempt) -> attempts.put(attempt.jobId(), attempt));
		}
		return attempts;
	}

	/**
	 * Replaces the current attempts by new ones: they become {@code SUPERSEDED} and their objects are deleted from now
	 * on. Runs before the new attempts are inserted, which {@code uk_upload_attempt_job_issued} requires.
	 */
	void supersede(Collection<UUID> attemptIds, Instant now) {
		if (attemptIds.isEmpty()) {
			return;
		}
		jdbc.sql("""
				UPDATE analysis_upload_attempt
				SET status = 'SUPERSEDED', object_state = 'PENDING', delete_after = :now, updated_at = :now,
				    version = version + 1
				WHERE id IN (:ids) AND status = 'ISSUED'
				""").param("ids", attemptIds).param("now", timestamp(now)).update();
	}

	void insertIssued(List<NewAttempt> attempts, Instant now) {
		if (attempts.isEmpty()) {
			return;
		}
		batch.batchUpdate("""
				INSERT INTO analysis_upload_attempt (id, owner_id, job_id, status, object_key, content_length, sha256,
				                                     url_expires_at, object_state, created_at, updated_at, version)
				VALUES (:id, :owner, :job, 'ISSUED', :key, :length, :sha256, :expiresAt, 'RETAINED', :now, :now, 0)
				""", attempts.stream()
			.map((attempt) -> new MapSqlParameterSource().addValue("id", attempt.id())
				.addValue("owner", attempt.ownerId())
				.addValue("job", attempt.jobId())
				.addValue("key", objectKey(attempt.jobId(), attempt.id()))
				.addValue("length", attempt.contentLength())
				.addValue("sha256", attempt.sha256())
				.addValue("expiresAt", timestamp(attempt.urlExpiresAt()))
				.addValue("now", timestamp(now)))
			.toArray(MapSqlParameterSource[]::new));
	}

	/**
	 * Confirms the attempt with the stored object's ETag and time; the object is kept for the run.
	 */
	void confirm(UUID attemptId, String etag, Instant lastModified, Instant now) {
		jdbc.sql("""
				UPDATE analysis_upload_attempt
				SET status = 'CONFIRMED', confirmed_at = :now, etag = :etag, last_modified = :lastModified,
				    object_state = 'RETAINED', delete_after = NULL, updated_at = :now, version = version + 1
				WHERE id = :id
				""")
			.param("id", attemptId)
			.param("etag", etag)
			.param("lastModified", timestamp(lastModified))
			.param("now", timestamp(now))
			.update();
	}

	/**
	 * Rejects the attempt's object, which is deleted from now on.
	 */
	void reject(UUID attemptId, RejectReason reason, Instant now) {
		jdbc.sql("""
				UPDATE analysis_upload_attempt
				SET status = 'REJECTED', reject_reason = :reason, object_state = 'PENDING', delete_after = :now,
				    updated_at = :now, version = version + 1
				WHERE id = :id
				""").param("id", attemptId).param("reason", reason.name()).param("now", timestamp(now)).update();
	}

	/**
	 * Gives up every attempt of an ended job: the {@code ISSUED} one becomes {@code ABANDONED}, and every object still
	 * kept, the confirmed one included, is deleted from now on. Objects already waiting for their delete keep their
	 * schedule.
	 */
	void abandonAll(UUID jobId, Instant now) {
		jdbc.sql("""
				UPDATE analysis_upload_attempt
				SET status = CASE WHEN status = 'ISSUED' THEN 'ABANDONED' ELSE status END,
				    delete_after = CASE WHEN object_state = 'RETAINED' THEN :now ELSE delete_after END,
				    object_state = CASE WHEN object_state = 'RETAINED' THEN 'PENDING' ELSE object_state END,
				    updated_at = :now, version = version + 1
				WHERE job_id = :job AND (status = 'ISSUED' OR object_state = 'RETAINED')
				""").param("job", jobId).param("now", timestamp(now)).update();
	}

	/**
	 * Adds to the subject's input rate window in one atomic statement.
	 * @return the window's totals with the addition
	 */
	RateWindow addInputRate(RateSubject kind, UUID subjectId, Instant windowStart, int items, long bytes) {
		return jdbc.sql("""
				INSERT INTO analysis_input_rate AS r (subject_kind, subject_id, window_start, items, bytes)
				VALUES (:kind, :subject, :window, :items, :bytes)
				ON CONFLICT (subject_kind, subject_id, window_start)
				DO UPDATE SET items = r.items + EXCLUDED.items, bytes = r.bytes + EXCLUDED.bytes
				RETURNING r.items, r.bytes
				""")
			.param("kind", kind.name())
			.param("subject", subjectId)
			.param("window", timestamp(windowStart))
			.param("items", items)
			.param("bytes", bytes)
			.query((rs, rowNum) -> new RateWindow(rs.getLong("items"), rs.getLong("bytes")))
			.single();
	}

	private static AttemptRow attempt(ResultSet rs, int rowNum) throws SQLException {
		return new AttemptRow(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
				rs.getObject("job_id", UUID.class), AttemptStatus.valueOf(rs.getString("status")),
				rs.getString("object_key"), rs.getLong("content_length"), rs.getBytes("sha256"),
				rs.getObject("url_expires_at", OffsetDateTime.class).toInstant());
	}

	/**
	 * @param consentVersion the consent basis the request runs under (ADR-0030 #1)
	 * @param trialMarkId the trial mark of the requesting device, {@code null} without one
	 */
	record RequestBasis(UUID id, UUID ownerId, long consentVersion, @Nullable UUID trialMarkId) {

	}

	/**
	 * @param consentVersion the consent basis of the request that created the job
	 */
	record AttemptBasis(UUID jobId, UUID ownerId, long consentVersion) {

	}

	/**
	 * @param sha256 the SHA-256 the upload was issued for, 32 bytes
	 * @param urlExpiresAt when its URL stops working, fixed at issue
	 */
	record AttemptRow(UUID id, UUID ownerId, UUID jobId, AttemptStatus status, String objectKey, long contentLength,
			byte[] sha256, Instant urlExpiresAt) {

		@Override
		public String toString() {
			return "AttemptRow[id=" + id + ", jobId=" + jobId + ", status=" + status + "]";
		}

	}

	record NewAttempt(UUID id, UUID ownerId, UUID jobId, long contentLength, byte[] sha256, Instant urlExpiresAt) {

		@Override
		public String toString() {
			return "NewAttempt[id=" + id + ", jobId=" + jobId + "]";
		}

	}

	/**
	 * {@code analysis_input_rate.subject_kind}, in the order the windows are locked.
	 */
	enum RateSubject {

		PRINCIPAL, TRIAL_MARK

	}

	record RateWindow(long items, long bytes) {

	}

}
