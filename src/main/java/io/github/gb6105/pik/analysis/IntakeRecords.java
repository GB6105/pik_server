package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.IntakeResponse.GroupView;
import io.github.gb6105.pik.analysis.IntakeResponse.ItemView;
import io.github.gb6105.pik.analysis.IntakeResponse.JobView;
import io.github.gb6105.pik.analysis.IntakeResponse.UploadPolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL for intake: one batch per table in the business transaction, and the reads of the response projection.
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
				INSERT INTO analysis_job (id, owner_id, request_id, group_id, photo_id, photo_version, funding,
				                          manifest_id, status, base_priority, queued_at, upload_deadline,
				                          processing_deadline, created_at, updated_at, version)
				VALUES (:id, :owner, :request, :group, :photo, :photoVersion, :funding, :manifest, :status,
				        :basePriority, :now, :uploadDeadline, :processingDeadline, :now, :now, 0)
				""", rows.stream()
			.map((row) -> new MapSqlParameterSource().addValue("id", row.id())
				.addValue("owner", row.ownerId())
				.addValue("request", row.requestId())
				.addValue("group", row.groupId())
				.addValue("photo", row.photoId())
				.addValue("photoVersion", row.photoVersion())
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
				                                   analysis_job_id, vector_id, vector_ignored_reason, created_at)
				VALUES (:request, :photo, :owner, :clientRef, :photoVersion, :basisChangeSeq, :takenAt, :textStatus,
				        :ocrVersion, :admissionResult, :code, :reason, :retryable, :group, :groupRole, :job, :vector,
				        :vectorIgnoredReason, :now)
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
				.addValue("now", timestamp(row.createdAt())))
			.toArray(MapSqlParameterSource[]::new));
	}

	boolean requestExists(UUID requestId) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM analysis_request WHERE id = :id)")
			.param("id", requestId)
			.query(Boolean.class)
			.single();
	}

	/**
	 * The items of a request in {@code photoId} order with the current status of the job each points at.
	 * {@code analyzedVersion} is the version the job analyzes the photo at: the item's own for an accepted photo, the
	 * accepted item's of the job's request for a photo in progress.
	 */
	List<ItemView> items(UUID requestId) {
		return jdbc.sql("""
				SELECT i.client_ref, i.admission_result, i.code, i.reason, i.retryable, i.analysis_job_id, i.group_id,
				       i.group_role, i.photo_version, i.vector_ignored_reason, j.status AS execution,
				       a.photo_version AS analyzed_version
				FROM analysis_request_item i
				LEFT JOIN analysis_job j ON j.id = i.analysis_job_id
				LEFT JOIN analysis_request_item a ON a.request_id = j.request_id AND a.photo_id = i.photo_id
				                                 AND a.analysis_job_id = j.id AND a.admission_result = 'ACCEPTED'
				WHERE i.request_id = :request
				ORDER BY i.client_ref
				""").param("request", requestId).query(IntakeRecords::item).list();
	}

	/**
	 * The groups of a request in the {@code photoId} order of their representatives.
	 */
	List<GroupView> groups(UUID requestId) {
		return jdbc.sql("""
				SELECT g.id, rep.client_ref AS representative, g.photo_count, j.id AS job_id
				FROM analysis_group g
				JOIN analysis_request_item rep ON rep.request_id = g.request_id
				                              AND rep.photo_id = g.representative_photo_id
				LEFT JOIN analysis_job j ON j.group_id = g.id
				WHERE g.request_id = :request
				ORDER BY rep.client_ref
				""")
			.param("request", requestId)
			.query((rs, rowNum) -> new GroupView(rs.getObject("id", UUID.class),
					rs.getObject("representative", UUID.class), rs.getInt("photo_count"),
					rs.getObject("job_id", UUID.class)))
			.list();
	}

	/**
	 * The jobs the items of a request point at, once each: the jobs it created and the jobs of other requests its
	 * {@code IN_PROGRESS} photos wait for (the scope of {@code counts} and {@code phase}). Ordered by the
	 * {@code photoId} of their representatives (unsigned, as {@code uuid} compares), then by job ID, with the upload
	 * policy of their manifest.
	 */
	List<JobView> jobs(UUID requestId) {
		return jdbc.sql("""
				SELECT j.id, j.request_id, j.group_id, j.status, m.content_type, m.max_bytes, m.max_long_edge_px
				FROM analysis_job j
				JOIN analysis_call_manifest m ON m.id = j.manifest_id
				JOIN analysis_request_item rep ON rep.request_id = j.request_id AND rep.photo_id = j.photo_id
				WHERE j.id IN (SELECT i.analysis_job_id FROM analysis_request_item i
				               WHERE i.request_id = :request AND i.analysis_job_id IS NOT NULL)
				ORDER BY rep.client_ref, j.id
				""").param("request", requestId).query((rs, rowNum) -> {
			JobStatus status = JobStatus.valueOf(rs.getString("status"));
			return new JobView(rs.getObject("id", UUID.class), rs.getObject("request_id", UUID.class),
					rs.getObject("group_id", UUID.class), status,
					status == JobStatus.AWAITING_UPLOAD, new UploadPolicy(rs.getString("content_type"),
							rs.getLong("max_bytes"), rs.getInt("max_long_edge_px")));
		}).list();
	}

	private static ItemView item(ResultSet rs, int rowNum) throws SQLException {
		String role = rs.getString("group_role");
		String execution = rs.getString("execution");
		String ignored = rs.getString("vector_ignored_reason");
		return new ItemView(rs.getObject("client_ref", UUID.class),
				AdmissionResult.valueOf(rs.getString("admission_result")), rs.getString("code"),
				rs.getString("reason"), rs.getBoolean("retryable"), null, rs.getObject("analysis_job_id", UUID.class),
				rs.getObject("group_id", UUID.class), (role != null) ? GroupRole.valueOf(role) : null,
				(execution != null) ? JobStatus.valueOf(execution) : null, rs.getString("photo_version"),
				rs.getString("analyzed_version"), (ignored != null) ? VectorIgnoredReason.valueOf(ignored) : null);
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
	 */
	record JobRow(UUID id, UUID ownerId, UUID requestId, UUID groupId, UUID photoId, String photoVersion,
			Funding funding, String manifestId, int basePriority, Instant queuedAt, Instant uploadDeadline,
			Instant processingDeadline) {

		@Override
		public String toString() {
			return "JobRow[id=" + id + ", requestId=" + requestId + ", groupId=" + groupId + "]";
		}

	}

	record ItemRow(UUID requestId, UUID photoId, UUID ownerId, UUID clientRef, String photoVersion,
			long basisChangeSeq, @Nullable Instant takenAt, TextStatus textStatus, @Nullable String ocrVersion,
			AdmissionResult admissionResult, @Nullable String code, @Nullable String reason, boolean retryable,
			@Nullable UUID groupId, @Nullable GroupRole groupRole, @Nullable UUID analysisJobId,
			@Nullable UUID vectorId, @Nullable VectorIgnoredReason vectorIgnoredReason, Instant createdAt) {

		@Override
		public String toString() {
			return "ItemRow[requestId=" + requestId + ", admissionResult=" + admissionResult + "]";
		}

	}

}
