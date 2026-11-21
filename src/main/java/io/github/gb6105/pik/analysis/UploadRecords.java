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
 * before the attempt rows (plan 3.3), except the delete acknowledgements: one row each, conditional on the version it
 * was read with (plan 7.4, K-13). The rows carry object keys, which must not reach logs.
 */
@Repository
@RequiredArgsConstructor
class UploadRecords {

	private static final String ATTEMPT = """
			SELECT a.id, a.owner_id, a.job_id, a.status, a.object_key, a.content_length, a.sha256, a.url_expires_at
			FROM analysis_upload_attempt a
			""";

	// Plan 7.1: the job waits for an upload and its window closed before :cutoff (now - confirm-grace): the upload
	// deadline passed, or every attempt was issued and the URL of the last one expired. Neither comes back, since no
	// attempt is issued past the deadline or the limit.
	private static final String UPLOAD_OVERDUE = """
			j.status = 'AWAITING_UPLOAD' AND (j.upload_deadline < :cutoff OR (j.issued_count >= :max
			    AND (SELECT max(l.url_expires_at) FROM analysis_upload_attempt l WHERE l.job_id = j.id) < :cutoff))
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
	 * @return the attempts changed
	 */
	int abandonAll(UUID jobId, Instant now) {
		return jdbc.sql("""
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

	/**
	 * The jobs whose upload window is over (plan 7.1) after {@code after} in {@code id} order, read without locks,
	 * each with its current {@code ISSUED} attempt if it has one.
	 * @param cutoff now minus {@code upload.confirm-grace}
	 */
	List<OverdueUpload> findOverdue(Instant cutoff, int maxAttempts, UUID after, int limit) {
		return jdbc.sql("""
				SELECT j.id AS job_id, a.id AS attempt_id, a.object_key
				FROM analysis_job j
				LEFT JOIN analysis_upload_attempt a ON a.job_id = j.id AND a.status = 'ISSUED'
				WHERE j.id > :after AND %s
				ORDER BY j.id
				LIMIT :limit
				""".formatted(UPLOAD_OVERDUE))
			.param("cutoff", timestamp(cutoff))
			.param("max", maxAttempts)
			.param("after", after)
			.param("limit", limit)
			.query((rs, rowNum) -> new OverdueUpload(rs.getObject("job_id", UUID.class),
					rs.getObject("attempt_id", UUID.class), rs.getString("object_key")))
			.list();
	}

	/**
	 * Whether the job's upload window is over (plan 7.1), read under the job's lock.
	 * @param cutoff now minus {@code upload.confirm-grace}
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	boolean uploadOverdue(UUID jobId, Instant cutoff, int maxAttempts) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM analysis_job j WHERE j.id = :id AND %s)
				""".formatted(UPLOAD_OVERDUE))
			.param("id", jobId)
			.param("cutoff", timestamp(cutoff))
			.param("max", maxAttempts)
			.query(Boolean.class)
			.single();
	}

	/**
	 * The attempts whose object is to be deleted by {@code now} after {@code after} in {@code id} order, read without
	 * locks (plan 7.4).
	 */
	List<DueDelete> findDueDeletes(Instant now, UUID after, int limit) {
		return jdbc.sql("""
				SELECT id, object_key, url_expires_at, version FROM analysis_upload_attempt
				WHERE object_state = 'PENDING' AND delete_after <= :now AND id > :after
				ORDER BY id
				LIMIT :limit
				""")
			.param("now", timestamp(now))
			.param("after", after)
			.param("limit", limit)
			.query((rs, rowNum) -> new DueDelete(rs.getObject("id", UUID.class), rs.getString("object_key"),
					rs.getObject("url_expires_at", OffsetDateTime.class).toInstant(), rs.getLong("version")))
			.list();
	}

	/**
	 * Records that a delete started after the URL and its in-flight margin expired succeeded, if the attempt is still
	 * at the version it was read with. One row in its own statement, so it waits for no lock while holding one and
	 * needs no job lock first.
	 * @return whether the attempt changed
	 */
	boolean markDeleteSent(UUID attemptId, long version, Instant now) {
		return jdbc.sql("""
				UPDATE analysis_upload_attempt
				SET object_state = 'DELETE_SENT', delete_after = NULL, updated_at = :now, version = version + 1
				WHERE id = :id AND version = :version AND object_state = 'PENDING'
				""").param("id", attemptId).param("version", version).param("now", timestamp(now)).update() == 1;
	}

	/**
	 * Moves the delete of an object deleted too early to {@code deleteAfter}, if the attempt is still at the version
	 * it was read with; same statement shape as {@link #markDeleteSent}.
	 * @return whether the attempt changed
	 */
	boolean rescheduleDelete(UUID attemptId, long version, Instant deleteAfter, Instant now) {
		return jdbc.sql("""
				UPDATE analysis_upload_attempt
				SET delete_after = :deleteAfter, updated_at = :now, version = version + 1
				WHERE id = :id AND version = :version AND object_state = 'PENDING'
				""")
			.param("id", attemptId)
			.param("version", version)
			.param("deleteAfter", timestamp(deleteAfter))
			.param("now", timestamp(now))
			.update() == 1;
	}

	/**
	 * Ended jobs after {@code after} in {@code id} order with an attempt whose object is neither pending delete nor
	 * deleted, read without locks (plan 7.4 reconciliation).
	 */
	List<UUID> findEndedWithKeptObjects(UUID after, int limit) {
		return jdbc.sql("""
				SELECT DISTINCT a.job_id FROM analysis_upload_attempt a
				JOIN analysis_job j ON j.id = a.job_id
				WHERE a.object_state = 'RETAINED' AND a.job_id > :after
				  AND j.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'INELIGIBLE')
				ORDER BY a.job_id
				LIMIT :limit
				""").param("after", after).param("limit", limit).query(UUID.class).list();
	}

	/**
	 * Counts the deletes still pending although they were due before {@code dueBefore}.
	 */
	long countOverdueDeletes(Instant dueBefore) {
		return jdbc.sql("""
				SELECT count(*) FROM analysis_upload_attempt WHERE object_state = 'PENDING' AND delete_after < :before
				""").param("before", timestamp(dueBefore)).query(Long.class).single();
	}

	/**
	 * Deletes up to {@code limit} input rate windows that started before {@code before} (plan 7.5).
	 * @return the windows deleted
	 */
	int deleteRateWindows(Instant before, int limit) {
		return jdbc.sql("""
				DELETE FROM analysis_input_rate
				WHERE (subject_kind, subject_id, window_start) IN (
				    SELECT subject_kind, subject_id, window_start FROM analysis_input_rate
				    WHERE window_start < :before LIMIT :limit)
				""").param("before", timestamp(before)).param("limit", limit).update();
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

	/**
	 * A job whose upload window is over.
	 *
	 * @param attemptId its current {@code ISSUED} attempt, {@code null} without one
	 * @param objectKey the object key of that attempt, {@code null} without one
	 */
	record OverdueUpload(UUID jobId, @Nullable UUID attemptId, @Nullable String objectKey) {

		@Override
		public String toString() {
			return "OverdueUpload[jobId=" + jobId + ", attemptId=" + attemptId + "]";
		}

	}

	/**
	 * An attempt whose object is to be deleted.
	 *
	 * @param version the attempt's version when it was read, which its acknowledgement requires
	 */
	record DueDelete(UUID id, String objectKey, Instant urlExpiresAt, long version) {

		@Override
		public String toString() {
			return "DueDelete[id=" + id + ", version=" + version + "]";
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
