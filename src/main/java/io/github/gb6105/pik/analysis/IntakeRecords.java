package io.github.gb6105.pik.analysis;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

/**
 * Plain SQL for intake: one batch per table in the business transaction, and the one read of the response projection.
 * The rows carry photo IDs and {@code contentVersion} values, so their {@code toString} hides them.
 */
@Repository
@RequiredArgsConstructor
class IntakeRecords {

	// The statuses uk_analysis_job_photo_active counts as live (JobStatus.live).
	private static final String LIVE = "status NOT IN ('FAILED', 'CANCELLED', 'INELIGIBLE')";

	private final JdbcClient jdbc;

	private final NamedParameterJdbcTemplate batch;

	/**
	 * The live jobs that analyze the owner's photos, as representative or as an accepted member. Reads without locks;
	 * the business transaction locks the jobs it relies on with {@link #lockJobs}.
	 * @return server photo ID to the job IDs; photos without a live job are missing
	 */
	Map<UUID, List<UUID>> findLiveJobs(UUID ownerId, Collection<UUID> photoIds) {
		Map<UUID, List<UUID>> jobs = new HashMap<>();
		if (photoIds.isEmpty()) {
			return jobs;
		}
		jdbc.sql("""
				SELECT j.photo_id, j.id AS job_id FROM analysis_job j
				WHERE j.owner_id = :owner AND j.photo_id IN (:photos) AND j.%1$s
				UNION
				SELECT i.photo_id, i.analysis_job_id FROM analysis_request_item i
				JOIN analysis_job j ON j.id = i.analysis_job_id
				WHERE i.owner_id = :owner AND i.photo_id IN (:photos) AND i.admission_result = 'ACCEPTED' AND j.%1$s
				""".formatted(LIVE))
			.param("owner", ownerId)
			.param("photos", photoIds)
			.query((rs, rowNum) -> Map.entry(rs.getObject("photo_id", UUID.class), rs.getObject("job_id", UUID.class)))
			.list()
			.forEach((row) -> jobs.computeIfAbsent(row.getKey(), (photo) -> new ArrayList<>()).add(row.getValue()));
		return jobs;
	}

	/**
	 * Locks the owner's jobs of other requests in {@code id} order with {@code FOR SHARE} before the usage reservation,
	 * so that the FK checks of items pointing at them wait for nothing later and their status holds until commit (V5
	 * lock order note, R19-01).
	 * @return job ID to its current status; jobs of other owners are missing
	 */
	Map<UUID, JobStatus> lockJobs(UUID ownerId, Collection<UUID> jobIds) {
		Map<UUID, JobStatus> statuses = new LinkedHashMap<>();
		jdbc.sql("SELECT id, status FROM analysis_job WHERE id IN (:ids) AND owner_id = :owner ORDER BY id FOR SHARE")
			.param("ids", jobIds)
			.param("owner", ownerId)
			.query((rs, rowNum) -> Map.entry(rs.getObject("id", UUID.class), JobStatus.valueOf(rs.getString("status"))))
			.list()
			.forEach((row) -> statuses.put(row.getKey(), row.getValue()));
		return statuses;
	}

	/**
	 * The owner's stored results of the photos (5c {@code EXISTING_RESULT}), read without locks. The caller holds the
	 * photo rows {@code FOR UPDATE}, and a result is stored only under a share lock on its photo (the completion
	 * transaction), so none appears for them before the caller ends; a regeneration changes only the epoch.
	 * @return server photo ID to its result; photos without one are missing
	 */
	Map<UUID, ExistingResult> findResults(UUID ownerId, Collection<UUID> photoIds) {
		Map<UUID, ExistingResult> results = new HashMap<>();
		if (photoIds.isEmpty()) {
			return results;
		}
		jdbc.sql("""
				SELECT photo_id, id, photo_version FROM analysis_result
				WHERE owner_id = :owner AND photo_id IN (:photos)
				""")
			.param("owner", ownerId)
			.param("photos", photoIds)
			.query((rs, rowNum) -> Map.entry(rs.getObject("photo_id", UUID.class),
					new ExistingResult(rs.getObject("id", UUID.class), rs.getString("photo_version"))))
			.list()
			.forEach((row) -> results.put(row.getKey(), row.getValue()));
		return results;
	}

	/**
	 * The lifetime budgets of the owner's photos (5c {@code INPUT_RETRY_LATER} and {@code ATTEMPTS_EXHAUSTED}), read
	 * without locks for a quick refusal: the permit checks the sends again under the budget lock. Read after the live
	 * jobs are locked (5c'), so a block that the ending of such a job records is seen once that ending commits.
	 * @return server photo ID to its budget; photos without a budget row are missing
	 */
	Map<UUID, PhotoLimits> findBudgets(UUID ownerId, Collection<UUID> photoIds) {
		Map<UUID, PhotoLimits> budgets = new HashMap<>();
		if (photoIds.isEmpty()) {
			return budgets;
		}
		jdbc.sql("""
				SELECT photo_id, risk_sends, input_blocked_until FROM photo_ai_budget
				WHERE owner_id = :owner AND photo_id IN (:photos)
				""")
			.param("owner", ownerId)
			.param("photos", photoIds)
			.query((rs, rowNum) -> {
				OffsetDateTime blockedUntil = rs.getObject("input_blocked_until", OffsetDateTime.class);
				return Map.entry(rs.getObject("photo_id", UUID.class), new PhotoLimits(rs.getInt("risk_sends"),
						(blockedUntil != null) ? blockedUntil.toInstant() : null));
			})
			.list()
			.forEach((row) -> budgets.put(row.getKey(), row.getValue()));
		return budgets;
	}

	void insertRequest(RequestRow row) {
		jdbc.sql("""
				INSERT INTO analysis_request (id, owner_id, origin, trigger, device_id, trial_mark_id, consent_version,
				                              grouping_rule_id, photo_count, representative_count, first_result_at,
				                              created_at, updated_at, version)
				VALUES (:id, :owner, 'CLIENT', :trigger, :device, :mark, :consentVersion, :rule, :photoCount,
				        :representativeCount, NULL, :now, :now, 0)
				""")
			.param("id", row.id())
			.param("owner", row.ownerId())
			.param("trigger", row.trigger().name())
			.param("device", row.deviceId())
			.param("mark", row.trialMarkId(), Types.OTHER)
			.param("consentVersion", row.consentVersion())
			.param("rule", row.groupingRuleId())
			.param("photoCount", row.photoCount())
			.param("representativeCount", row.representativeCount())
			.param("now", timestamp(row.createdAt()))
			.update();
	}

	void insertGroups(List<GroupRow> rows) {
		if (rows.isEmpty()) {
			return;
		}
		batch.batchUpdate("""
				INSERT INTO analysis_group (id, owner_id, request_id, representative_photo_id, photo_count, model_id,
				                            model_version, created_at)
				VALUES (:id, :owner, :request, :representative, :photoCount, :model, :modelVersion, :now)
				""", rows.stream()
			.map((row) -> new MapSqlParameterSource().addValue("id", row.id())
				.addValue("owner", row.ownerId())
				.addValue("request", row.requestId())
				.addValue("representative", row.representativePhotoId())
				.addValue("photoCount", row.photoCount())
				.addValue("model", row.modelId(), Types.VARCHAR)
				.addValue("modelVersion", row.modelVersion(), Types.VARCHAR)
				.addValue("now", timestamp(row.createdAt())))
			.toArray(MapSqlParameterSource[]::new));
	}

	void insertJobs(List<JobRow> rows) {
		if (rows.isEmpty()) {
			return;
		}
		batch.batchUpdate("""
				INSERT INTO analysis_job (id, owner_id, request_id, group_id, photo_id, photo_version,
				                          representative_client_ref, funding, manifest_id, status, base_priority,
				                          queued_at, upload_deadline, processing_deadline, created_at, updated_at,
				                          version)
				VALUES (:id, :owner, :request, :group, :photo, :photoVersion, :clientRef, :funding, :manifest, :status,
				        :basePriority, :now, :uploadDeadline, :processingDeadline, :now, :now, 0)
				""", rows.stream()
			.map((row) -> new MapSqlParameterSource().addValue("id", row.id())
				.addValue("owner", row.ownerId())
				.addValue("request", row.requestId())
				.addValue("group", row.groupId())
				.addValue("photo", row.photoId())
				.addValue("photoVersion", row.photoVersion())
				.addValue("clientRef", row.representativeClientRef())
				.addValue("funding", row.funding().name())
				.addValue("manifest", row.manifestId())
				.addValue("status", JobStatus.AWAITING_UPLOAD.name())
				.addValue("basePriority", row.basePriority())
				.addValue("now", timestamp(row.queuedAt()))
				.addValue("uploadDeadline", timestamp(row.uploadDeadline()))
				.addValue("processingDeadline", timestamp(row.processingDeadline())))
			.toArray(MapSqlParameterSource[]::new));
	}

	void insertItems(List<ItemRow> rows) {
		batch.batchUpdate("""
				INSERT INTO analysis_request_item (request_id, photo_id, owner_id, client_ref, photo_version,
				                                   basis_change_seq, taken_at, text_status, ocr_version,
				                                   admission_result, code, reason, retryable, group_id, group_role,
				                                   analysis_job_id, vector_id, vector_ignored_reason, reused_result_id,
				                                   reused_photo_version, retry_at, created_at)
				VALUES (:request, :photo, :owner, :clientRef, :photoVersion, :basisChangeSeq, :takenAt, :textStatus,
				        :ocrVersion, :admissionResult, :code, :reason, :retryable, :group, :groupRole, :job, :vector,
				        :vectorIgnoredReason, :reusedResult, :reusedPhotoVersion, :retryAt, :now)
				""", rows.stream()
			.map((row) -> new MapSqlParameterSource().addValue("request", row.requestId())
				.addValue("photo", row.photoId())
				.addValue("owner", row.ownerId())
				.addValue("clientRef", row.clientRef())
				.addValue("photoVersion", row.photoVersion())
				.addValue("basisChangeSeq", row.basisChangeSeq())
				.addValue("takenAt", timestampOrNull(row.takenAt()), Types.TIMESTAMP_WITH_TIMEZONE)
				.addValue("textStatus", row.textStatus().name())
				.addValue("ocrVersion", row.ocrVersion(), Types.VARCHAR)
				.addValue("admissionResult", row.admissionResult().name())
				.addValue("code", row.code(), Types.VARCHAR)
				.addValue("reason", row.reason(), Types.VARCHAR)
				.addValue("retryable", row.retryable())
				.addValue("group", row.groupId(), Types.OTHER)
				.addValue("groupRole", (row.groupRole() != null) ? row.groupRole().name() : null, Types.VARCHAR)
				.addValue("job", row.analysisJobId(), Types.OTHER)
				.addValue("vector", row.vectorId(), Types.OTHER)
				.addValue("vectorIgnoredReason",
						(row.vectorIgnoredReason() != null) ? row.vectorIgnoredReason().name() : null, Types.VARCHAR)
				.addValue("reusedResult", row.reusedResultId(), Types.OTHER)
				.addValue("reusedPhotoVersion", row.reusedPhotoVersion(), Types.VARCHAR)
				.addValue("retryAt", timestampOrNull(row.retryAt()), Types.TIMESTAMP_WITH_TIMEZONE)
				.addValue("now", timestamp(row.createdAt())))
			.toArray(MapSqlParameterSource[]::new));
	}

	/**
	 * The rows of a request's response in one statement, so that they come from one snapshot even under READ
	 * COMMITTED (plan 4.3, ADR-0034): JSON arrays of the items, the groups and the jobs, each in the order of the
	 * response (design 1.5). Items are in {@code photoId} order with the current status of the job each points at;
	 * {@code analyzedVersion} is the version that job analyzes the photo at, the item's own for an accepted photo and
	 * the accepted item's of the job's request for a photo in progress, and the reused result's for an existing result.
	 * {@code retryAfter} is the wait a refused item was given, in whole seconds from the intake rounded up, the same in
	 * every replay and status read, so that the status {@code ETag} holds. Groups are in the order of their
	 * representatives' {@code photoId}. Jobs are listed once each: the jobs the request created, also after its items
	 * were deleted (plan 2.9, R2-08), and the jobs of other requests its {@code IN_PROGRESS} photos wait for (the scope
	 * of {@code counts} and {@code phase}), named and ordered by the {@code photoId} of their representative kept on
	 * the job (unsigned, as {@code uuid} compares), then by job ID, with the upload policy of their manifest and their
	 * failure code.
	 * @param ownerId the request's owner; {@code null} reads the request whoever owns it
	 * @return empty when the request does not exist or is of another owner
	 */
	Optional<ProjectionRows> projection(UUID requestId, @Nullable UUID ownerId) {
		return jdbc.sql("""
				SELECT
				  (SELECT COALESCE(json_agg(json_build_object(
				            'photoId', i.client_ref, 'admissionResult', i.admission_result, 'code', i.code,
				            'reason', i.reason, 'retryable', i.retryable, 'analysisJobId', i.analysis_job_id,
				            'groupId', i.group_id, 'role', i.group_role, 'execution', j.status,
				            'retryAfter', CAST(CEIL(EXTRACT(EPOCH FROM i.retry_at - i.created_at)) AS bigint),
				            'observedVersion', i.photo_version,
				            'analyzedVersion', COALESCE(i.reused_photo_version, a.photo_version),
				            'vectorIgnored', i.vector_ignored_reason) ORDER BY i.client_ref), '[]'::json)
				   FROM analysis_request_item i
				   LEFT JOIN analysis_job j ON j.id = i.analysis_job_id
				   LEFT JOIN analysis_request_item a ON a.request_id = j.request_id AND a.photo_id = i.photo_id
				                                    AND a.analysis_job_id = j.id AND a.admission_result = 'ACCEPTED'
				   WHERE i.request_id = r.id) AS items,
				  (SELECT COALESCE(json_agg(json_build_object(
				            'groupId', g.id, 'representativePhotoId', rep.client_ref, 'photoCount', g.photo_count,
				            'analysisJobId', j.id) ORDER BY rep.client_ref), '[]'::json)
				   FROM analysis_group g
				   JOIN analysis_request_item rep ON rep.request_id = g.request_id
				                                 AND rep.photo_id = g.representative_photo_id
				   LEFT JOIN analysis_job j ON j.group_id = g.id
				   WHERE g.request_id = r.id) AS groups,
				  (SELECT COALESCE(json_agg(json_build_object(
				            'analysisJobId', j.id, 'requestId', j.request_id, 'groupId', j.group_id,
				            'representativePhotoId', j.representative_client_ref, 'status', j.status,
				            'reason', j.failure_code, 'uploadRequired', j.status = 'AWAITING_UPLOAD',
				            'uploadPolicy', json_build_object('contentType', m.content_type, 'maxBytes', m.max_bytes,
				                                              'maxLongEdgePx', m.max_long_edge_px))
				            ORDER BY j.representative_client_ref, j.id), '[]'::json)
				   FROM analysis_job j
				   JOIN analysis_call_manifest m ON m.id = j.manifest_id
				   WHERE j.request_id = r.id
				      OR j.id IN (SELECT ri.analysis_job_id FROM analysis_request_item ri
				                  WHERE ri.request_id = r.id AND ri.analysis_job_id IS NOT NULL)) AS jobs
				FROM analysis_request r
				WHERE r.id = :request AND (CAST(:owner AS uuid) IS NULL OR r.owner_id = CAST(:owner AS uuid))
				""")
			.param("request", requestId)
			.param("owner", ownerId, Types.OTHER)
			.query((rs, rowNum) -> new ProjectionRows(rs.getString("items"), rs.getString("groups"),
					rs.getString("jobs")))
			.optional();
	}

	private static OffsetDateTime timestamp(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private static @Nullable OffsetDateTime timestampOrNull(@Nullable Instant instant) {
		return (instant != null) ? timestamp(instant) : null;
	}

	/**
	 * @param consentVersion the consent basis the request runs under (ADR-0030 #1)
	 * @param representativeCount the jobs the request created
	 */
	record RequestRow(UUID id, UUID ownerId, Trigger trigger, UUID deviceId, @Nullable UUID trialMarkId,
			long consentVersion, String groupingRuleId, int photoCount, int representativeCount, Instant createdAt) {

	}

	/**
	 * @param representativePhotoId the server photo ID of the representative
	 */
	record GroupRow(UUID id, UUID ownerId, UUID requestId, UUID representativePhotoId, int photoCount,
			@Nullable String modelId, @Nullable String modelVersion, Instant createdAt) {

		@Override
		public String toString() {
			return "GroupRow[id=" + id + ", requestId=" + requestId + ", photoCount=" + photoCount + "]";
		}

	}

	/**
	 * @param photoId the server photo ID of the representative
	 * @param photoVersion its version observed at intake
	 * @param representativeClientRef its API photoId
	 */
	record JobRow(UUID id, UUID ownerId, UUID requestId, UUID groupId, UUID photoId, String photoVersion,
			UUID representativeClientRef, Funding funding, String manifestId, int basePriority, Instant queuedAt,
			Instant uploadDeadline, Instant processingDeadline) {

		@Override
		public String toString() {
			return "JobRow[id=" + id + ", requestId=" + requestId + ", groupId=" + groupId + "]";
		}

	}

	/**
	 * @param reusedResultId the result an {@code EXISTING_RESULT} photo reuses
	 * @param reusedPhotoVersion the photo version of that result
	 * @param retryAt when a photo refused with {@code INPUT_RETRY_LATER} may be sent again
	 */
	record ItemRow(UUID requestId, UUID photoId, UUID ownerId, UUID clientRef, String photoVersion,
			long basisChangeSeq, @Nullable Instant takenAt, TextStatus textStatus, @Nullable String ocrVersion,
			AdmissionResult admissionResult, @Nullable String code, @Nullable String reason, boolean retryable,
			@Nullable UUID groupId, @Nullable GroupRole groupRole, @Nullable UUID analysisJobId,
			@Nullable UUID vectorId, @Nullable VectorIgnoredReason vectorIgnoredReason, @Nullable UUID reusedResultId,
			@Nullable String reusedPhotoVersion, @Nullable Instant retryAt, Instant createdAt) {

		@Override
		public String toString() {
			return "ItemRow[requestId=" + requestId + ", admissionResult=" + admissionResult + "]";
		}

	}

	/**
	 * A stored result of a photo, reused by intake.
	 *
	 * @param photoVersion the photo version the result is for
	 */
	record ExistingResult(UUID resultId, String photoVersion) {

		@Override
		public String toString() {
			return "ExistingResult[resultId=" + resultId + "]";
		}

	}

	/**
	 * What intake reads of a photo's lifetime budget.
	 *
	 * @param riskSends the risky sends counted for the photo
	 * @param inputBlockedUntil until when the photo is held back after invalid inputs, {@code null} when it is not
	 */
	record PhotoLimits(int riskSends, @Nullable Instant inputBlockedUntil) {

	}

	/**
	 * The JSON arrays of {@link #projection}, of {@code IntakeResponse.ItemView}, {@code GroupView} and
	 * {@code JobView} rows.
	 */
	record ProjectionRows(String items, String groups, String jobs) {

		// They hold photo IDs and contentVersion values.
		@Override
		public String toString() {
			return "ProjectionRows[hidden]";
		}

	}

}
