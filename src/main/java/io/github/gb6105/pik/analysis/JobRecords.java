package io.github.gb6105.pik.analysis;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plain SQL for the stage 5-2 transitions of {@code analysis_job}. Every transition locks the job with
 * {@code FOR NO KEY UPDATE} first, never a stronger lock: intake references jobs it did not lock through the FK
 * {@code KEY SHARE} after the usage lock (V5 lock order note, ADR-0034 #37). The updates change neither {@code id}
 * nor {@code group_id}, so they take the same lock level.
 */
@Repository
@RequiredArgsConstructor
class JobRecords {

	private static final String LOCKED_JOB = """
			SELECT j.id, j.owner_id, j.request_id, j.status, j.funding, j.upload_deadline, j.processing_deadline,
			       j.issued_count, m.max_bytes
			FROM analysis_job j
			JOIN analysis_call_manifest m ON m.id = j.manifest_id
			""";

	private final JdbcClient jdbc;

	/**
	 * Locks the job until the transaction ends.
	 * @return the job, empty when it does not exist
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Optional<LockedJob> lock(UUID jobId) {
		return jdbc.sql(LOCKED_JOB + "WHERE j.id = :id FOR NO KEY UPDATE OF j")
			.param("id", jobId)
			.query(JobRecords::lockedJob)
			.optional();
	}

	/**
	 * Locks the jobs of the request among {@code jobIds} in {@code id} order until the transaction ends.
	 * @return job ID to the job; IDs of jobs of other requests and unknown IDs are missing
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Map<UUID, LockedJob> lockOfRequest(UUID requestId, Collection<UUID> jobIds) {
		Map<UUID, LockedJob> jobs = new LinkedHashMap<>();
		jdbc.sql(LOCKED_JOB + "WHERE j.id IN (:ids) AND j.request_id = :request ORDER BY j.id FOR NO KEY UPDATE OF j")
			.param("ids", jobIds)
			.param("request", requestId)
			.query(JobRecords::lockedJob)
			.list()
			.forEach((job) -> jobs.put(job.id(), job));
		return jobs;
	}

	/**
	 * Counts one more upload attempt issued for each job.
	 */
	void countIssued(Collection<UUID> jobIds, Instant now) {
		jdbc.sql("""
				UPDATE analysis_job SET issued_count = issued_count + 1, updated_at = :now, version = version + 1
				WHERE id IN (:ids)
				""").param("ids", jobIds).param("now", timestamp(now)).update();
	}

	/**
	 * Queues the job after its upload was confirmed, with the SHA-256 and ETag of the confirmed object.
	 */
	void markReady(UUID jobId, byte[] inputSha256, String inputEtag, Instant now) {
		jdbc.sql("""
				UPDATE analysis_job SET status = 'READY', next_attempt_at = :now, input_sha256 = :sha256,
				                        input_etag = :etag, updated_at = :now, version = version + 1
				WHERE id = :id
				""")
			.param("id", jobId)
			.param("sha256", inputSha256)
			.param("etag", inputEtag)
			.param("now", timestamp(now))
			.update();
	}

	/**
	 * Ends the job for {@code reason} and discards its run token and lease.
	 */
	void end(UUID jobId, FailureCode reason, Instant now) {
		jdbc.sql("""
				UPDATE analysis_job SET status = :status, failure_code = :reason, ended_at = :now, run_token = NULL,
				                        locked_until = NULL, updated_at = :now, version = version + 1
				WHERE id = :id
				""")
			.param("id", jobId)
			.param("status", reason.status().name())
			.param("reason", reason.name())
			.param("now", timestamp(now))
			.update();
	}

	private static LockedJob lockedJob(ResultSet rs, int rowNum) throws SQLException {
		return new LockedJob(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
				rs.getObject("request_id", UUID.class), JobStatus.valueOf(rs.getString("status")),
				Funding.valueOf(rs.getString("funding")),
				rs.getObject("upload_deadline", OffsetDateTime.class).toInstant(),
				rs.getObject("processing_deadline", OffsetDateTime.class).toInstant(), rs.getInt("issued_count"),
				rs.getLong("max_bytes"));
	}

	static OffsetDateTime timestamp(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	/**
	 * A job as read under its lock.
	 *
	 * @param issuedCount the upload attempts issued for it so far
	 * @param maxBytes the largest upload of its manifest
	 */
	record LockedJob(UUID id, UUID ownerId, UUID requestId, JobStatus status, Funding funding, Instant uploadDeadline,
			Instant processingDeadline, int issuedCount, long maxBytes) {

	}

}
