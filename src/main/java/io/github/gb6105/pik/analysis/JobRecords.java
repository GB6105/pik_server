package io.github.gb6105.pik.analysis;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
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
			SELECT j.id, j.owner_id, j.request_id, j.photo_id, j.status, j.funding, j.upload_deadline,
			       j.processing_deadline, j.issued_count, m.max_bytes, j.run_token, j.locked_until
			FROM analysis_job j
			JOIN analysis_call_manifest m ON m.id = j.manifest_id
			""";

	// Stands for "no ID" in NOT IN, which an empty list cannot express.
	private static final UUID NO_JOB = new UUID(0, 0);

	// Design 2.4: max(0, 2 x base_priority + 1 - floor(wait / 10 minutes) - (1 while the request has no result)).
	private static final String CLAIM_CANDIDATES = """
			WITH running AS (
			    SELECT owner_id, count(*) AS jobs FROM analysis_job WHERE status = 'RUNNING' GROUP BY owner_id
			), candidate AS MATERIALIZED (
			    SELECT ranked.id, ranked.priority, ranked.queued_at
			    FROM (
			        SELECT j.id, j.owner_id, j.queued_at, p.priority,
			               ROW_NUMBER() OVER (PARTITION BY j.owner_id ORDER BY p.priority, j.queued_at, j.id) AS owner_rank
			        FROM analysis_job j
			        JOIN analysis_request q ON q.id = j.request_id
			        CROSS JOIN LATERAL (
			            SELECT GREATEST(0, 2 * j.base_priority + 1
			                - floor(extract(epoch FROM (CAST(:now AS timestamptz) - j.queued_at)) / 600)
			                - CASE WHEN q.first_result_at IS NULL THEN 1 ELSE 0 END) AS priority
			        ) p
			        WHERE j.status = 'READY' AND j.next_attempt_at <= :now AND j.processing_deadline > :now
			          AND j.id NOT IN (:seen) /*probe*/
			    ) ranked
			    LEFT JOIN running r ON r.owner_id = ranked.owner_id
			    WHERE ranked.owner_rank <= :principalSlots - COALESCE(r.jobs, 0)
			    ORDER BY ranked.priority, ranked.queued_at, ranked.id
			    LIMIT :window
			), locked AS MATERIALIZED (
			    SELECT j.id
			    FROM candidate c
			    JOIN analysis_job j ON j.id = c.id
			    WHERE j.status = 'READY' AND j.next_attempt_at <= :now AND j.processing_deadline > :now
			    ORDER BY c.priority, c.queued_at, c.id
			    LIMIT :slots
			    FOR NO KEY UPDATE OF j SKIP LOCKED
			)
			SELECT c.id, (l.id IS NOT NULL) AS locked
			FROM candidate c
			LEFT JOIN locked l ON l.id = c.id
			ORDER BY c.priority, c.queued_at, c.id
			""";

	// Plan 5.10: a job that lost the probe without a send is not picked as the probe again for a while.
	private static final String PROBE_CANDIDATE = """
			AND (j.probe_excluded_until IS NULL OR j.probe_excluded_until <= :now)""";

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

	/**
	 * Takes the global claim lock until the transaction ends (plan 5.1): the first lock of a claim transaction, never
	 * taken while another lock is held. The key is prefixed to keep it apart from other advisory locks.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	void lockClaims() {
		jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('analysis.claim:global', 0))")
			.query(Integer.class)
			.single();
	}

	/**
	 * Counts the jobs running over all instances, the claims of this transaction included.
	 */
	int countRunning() {
		return jdbc.sql("SELECT count(*) FROM analysis_job WHERE status = 'RUNNING'").query(Integer.class).single();
	}

	/**
	 * Reads up to {@code window} claim candidates and locks the first {@code slots} of them that no other transaction
	 * holds, in one statement (plan 5.2, design 2.4). The inner query numbers the due {@code READY} jobs of each
	 * owner in claim order and keeps those within the owner's free slots, so that one owner's queue cannot fill the
	 * window; the window function has no lock clause. The outer part reads {@code analysis_job} again by those IDs and
	 * locks with {@code FOR NO KEY UPDATE OF j SKIP LOCKED}, never a stronger lock (V5 lock order note), checking the
	 * conditions again on the locked row. Running jobs, those claimed earlier in this transaction included, take the
	 * owner's slots.
	 * @param seen candidates of earlier rounds of this transaction, left out
	 * @param probe whether the claim picks a probe: jobs excluded from probes until a later time are left out
	 * @return the candidates in claim order with whether each was locked; all were examined when fewer than
	 * {@code slots} were locked
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	List<Candidate> claimCandidates(int slots, int window, int principalSlots, Collection<UUID> seen, boolean probe,
			Instant now) {
		return jdbc.sql(CLAIM_CANDIDATES.replace("/*probe*/", probe ? PROBE_CANDIDATE : ""))
			.param("now", timestamp(now))
			.param("seen", seen.isEmpty() ? List.of(NO_JOB) : seen)
			.param("principalSlots", principalSlots)
			.param("window", window)
			.param("slots", slots)
			.query(JobRecords::candidate)
			.list();
	}

	/**
	 * Gives a locked {@code READY} job to a new run: {@code RUNNING}, one more attempt, the run's token and lease.
	 */
	Claimed markRunning(UUID jobId, UUID runToken, Instant lockedUntil, Instant now) {
		return jdbc.sql("""
				UPDATE analysis_job SET status = 'RUNNING', attempt_count = attempt_count + 1, run_token = :token,
				                        locked_until = :until, updated_at = :now, version = version + 1
				WHERE id = :id AND status = 'READY'
				RETURNING owner_id, processing_deadline
				""")
			.param("id", jobId)
			.param("token", runToken)
			.param("until", timestamp(lockedUntil))
			.param("now", timestamp(now))
			.query((rs, rowNum) -> new Claimed(jobId, rs.getObject("owner_id", UUID.class), runToken, lockedUntil,
					rs.getObject("processing_deadline", OffsetDateTime.class).toInstant()))
			.single();
	}

	/**
	 * Sends a running job back to {@code READY}, discarding its run token and lease (design 2.1).
	 * @param probeExcludedUntil when the job may be a probe again, {@code null} to keep the current value
	 */
	void defer(UUID jobId, Instant nextAttemptAt, @Nullable Instant probeExcludedUntil, Instant now) {
		jdbc.sql("""
				UPDATE analysis_job SET status = 'READY', next_attempt_at = :next, run_token = NULL, locked_until = NULL,
				                        probe_excluded_until = COALESCE(:excluded, probe_excluded_until),
				                        updated_at = :now, version = version + 1
				WHERE id = :id
				""")
			.param("id", jobId)
			.param("next", timestamp(nextAttemptAt))
			.param("excluded", (probeExcludedUntil != null) ? timestamp(probeExcludedUntil) : null,
					Types.TIMESTAMP_WITH_TIMEZONE)
			.param("now", timestamp(now))
			.update();
	}

	/**
	 * Ends the job with its result stored, discarding its run token and lease.
	 */
	void succeed(UUID jobId, Instant now) {
		jdbc.sql("""
				UPDATE analysis_job SET status = 'SUCCEEDED', ended_at = :now, run_token = NULL, locked_until = NULL,
				                        updated_at = :now, version = version + 1
				WHERE id = :id
				""").param("id", jobId).param("now", timestamp(now)).update();
	}

	private static LockedJob lockedJob(ResultSet rs, int rowNum) throws SQLException {
		OffsetDateTime lockedUntil = rs.getObject("locked_until", OffsetDateTime.class);
		return new LockedJob(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
				rs.getObject("request_id", UUID.class), rs.getObject("photo_id", UUID.class),
				JobStatus.valueOf(rs.getString("status")), Funding.valueOf(rs.getString("funding")),
				rs.getObject("upload_deadline", OffsetDateTime.class).toInstant(),
				rs.getObject("processing_deadline", OffsetDateTime.class).toInstant(), rs.getInt("issued_count"),
				rs.getLong("max_bytes"), rs.getObject("run_token", UUID.class),
				(lockedUntil != null) ? lockedUntil.toInstant() : null);
	}

	private static Candidate candidate(ResultSet rs, int rowNum) throws SQLException {
		return new Candidate(rs.getObject("id", UUID.class), rs.getBoolean("locked"));
	}

	static OffsetDateTime timestamp(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	/**
	 * A job as read under its lock.
	 *
	 * @param photoId the representative photo
	 * @param issuedCount the upload attempts issued for it so far
	 * @param maxBytes the largest upload of its manifest
	 * @param runToken the token of the run that holds it, {@code null} unless it is {@code RUNNING}
	 * @param lockedUntil the end of that run's lease, {@code null} unless it is {@code RUNNING}
	 */
	record LockedJob(UUID id, UUID ownerId, UUID requestId, UUID photoId, JobStatus status, Funding funding,
			Instant uploadDeadline, Instant processingDeadline, int issuedCount, long maxBytes,
			@Nullable UUID runToken, @Nullable Instant lockedUntil) {

		/**
		 * Whether the run of {@code token} still holds the job.
		 */
		boolean heldBy(UUID token) {
			return status == JobStatus.RUNNING && token.equals(runToken);
		}

		/**
		 * Whether a response of the run of {@code token} may still finish the job (plan 5.6): the run holds it and
		 * its processing deadline is ahead. A lease that ran out still counts until the recovery takes the job.
		 */
		boolean wonBy(UUID token, Instant now) {
			return heldBy(token) && processingDeadline.isAfter(now);
		}

	}

	/**
	 * A claim candidate in claim order.
	 *
	 * @param locked whether the claim locked it; one that another transaction holds, or that left {@code READY},
	 * is skipped
	 */
	record Candidate(UUID id, boolean locked) {

	}

	/**
	 * A job a claim gave to a run.
	 *
	 * @param runToken the token of the run
	 * @param lockedUntil the end of its lease
	 */
	record Claimed(UUID id, UUID ownerId, UUID runToken, Instant lockedUntil, Instant processingDeadline) {

	}

}
