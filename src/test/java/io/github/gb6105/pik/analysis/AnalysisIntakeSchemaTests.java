package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.SchemaRows.assertRejectedBy;
import static io.github.gb6105.pik.SchemaRows.with;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gb6105.pik.SchemaRows;
import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.shared.id.UuidV7;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Constraints of the V5 analysis intake tables, checked with plain SQL (FR-I-01, FR-I-02). Owner and photo IDs carry
 * no FK, so the rows here use IDs that name nothing in other modules.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AnalysisIntakeSchemaTests {

	static final List<String> V5_TABLES = List.of("photo", "photo_vector", "analysis_grouping_rule",
			"analysis_call_manifest", "analysis_request", "analysis_group", "analysis_job", "analysis_request_item");

	@Autowired
	JdbcClient jdbc;

	SchemaRows rows;

	UUID owner;

	String ruleId;

	String manifestId;

	@BeforeEach
	void setUp() {
		rows = new SchemaRows(jdbc);
		owner = UUID.randomUUID();
		ruleId = sha256Hex();
		insertRule(ruleId);
		manifestId = sha256Hex();
		insertManifest(manifestId, 2_097_152, 1568);
	}

	@Test
	void migrationV5CreatesIntakeTables() {
		boolean applied = jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '5'")
			.query(Boolean.class)
			.single();
		List<String> tables = jdbc
			.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()"
					+ " AND table_name IN (:tables)")
			.param("tables", V5_TABLES)
			.query(String.class)
			.list();

		assertThat(applied).isTrue();
		assertThat(tables).containsExactlyInAnyOrderElementsOf(V5_TABLES);
	}

	@Test
	void onlyPlannedColumnsAreNullable() {
		List<String> nullable = jdbc
			.sql("SELECT table_name || '.' || column_name FROM information_schema.columns"
					+ " WHERE table_schema = current_schema() AND table_name IN (:tables) AND is_nullable = 'YES'")
			.param("tables", V5_TABLES)
			.query(String.class)
			.list();

		assertThat(nullable).containsExactlyInAnyOrder("analysis_request.device_id", "analysis_request.trial_mark_id",
				"analysis_request.first_result_at", "analysis_group.model_id", "analysis_group.model_version",
				"analysis_request_item.taken_at", "analysis_request_item.ocr_version", "analysis_request_item.code",
				"analysis_request_item.reason", "analysis_request_item.group_id", "analysis_request_item.group_role",
				"analysis_request_item.analysis_job_id", "analysis_request_item.vector_id",
				"analysis_request_item.vector_ignored_reason");
	}

	@Test
	void groupingRuleIdIsLowercaseSha256Hex() {
		assertRejectedBy("ck_analysis_grouping_rule_id", () -> insertRule(sha256Hex().toUpperCase(Locale.ROOT)));
		assertRejectedBy("ck_analysis_grouping_rule_id", () -> insertRule(sha256Hex().substring(1)));
		assertRejectedBy("ck_analysis_grouping_rule_id", () -> insertRule(sha256Hex() + "0"));
		assertRejectedBy("ck_analysis_grouping_rule_id", () -> insertRule("g" + sha256Hex().substring(1)));
	}

	@Test
	void manifestUploadLimitsArePositive() {
		assertRejectedBy("ck_analysis_call_manifest_max_bytes", () -> insertManifest(sha256Hex(), 0, 1568));
		assertRejectedBy("ck_analysis_call_manifest_max_long_edge_px",
				() -> insertManifest(sha256Hex(), 2_097_152, 0));
	}

	@Test
	void clientRequestNamesItsDevice() {
		rows.insert("analysis_request", request());
		rows.insert("analysis_request", with(request(), "origin", "SERVER_BATCH", "trigger", "PRO_BACKLOG",
				"device_id", null));

		assertRejectedBy("ck_analysis_request_device",
				() -> rows.insert("analysis_request", with(request(), "device_id", null)));
	}

	@Test
	void requestRejectsUnknownOriginAndTrigger() {
		assertRejectedBy("ck_analysis_request_origin",
				() -> rows.insert("analysis_request", with(request(), "origin", "DEVICE")));
		assertRejectedBy("ck_analysis_request_trigger",
				() -> rows.insert("analysis_request", with(request(), "trigger", "MANUAL")));
	}

	@Test
	void requestCountsAndConsentVersionAreBounded() {
		rows.insert("analysis_request", with(request(), "representative_count", 0));

		assertRejectedBy("ck_analysis_request_consent_version",
				() -> rows.insert("analysis_request", with(request(), "consent_version", 0L)));
		assertRejectedBy("ck_analysis_request_photo_count",
				() -> rows.insert("analysis_request", with(request(), "photo_count", 0)));
		assertRejectedBy("ck_analysis_request_representative_count",
				() -> rows.insert("analysis_request", with(request(), "representative_count", -1)));
	}

	@Test
	void requestNeedsAStoredGroupingRule() {
		assertRejectedBy("fk_analysis_request_grouping_rule",
				() -> rows.insert("analysis_request", with(request(), "grouping_rule_id", sha256Hex())));
	}

	@Test
	void groupHasAtLeastOnePhoto() {
		UUID request = insertRequest();

		assertRejectedBy("ck_analysis_group_photo_count",
				() -> rows.insert("analysis_group", with(group(request, UuidV7.generate()), "photo_count", 0)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "AWAITING_UPLOAD", "READY", "RUNNING", "SUCCEEDED" })
	void liveJobBlocksAnotherJobForThePhoto(String status) {
		UUID photo = UuidV7.generate();
		insertJob(insertRequest(), photo, status);
		UUID request = insertRequest();

		assertRejectedBy("uk_analysis_job_photo_active", () -> insertJob(request, photo, "AWAITING_UPLOAD"));
		// Another photo of the owner and the same photo ID under another owner are not blocked.
		insertJob(request, UuidV7.generate(), "AWAITING_UPLOAD");
		UUID otherOwner = UUID.randomUUID();
		UUID otherGroup = insertGroup(request, photo);
		rows.insert("analysis_job", with(job(request, otherGroup, photo, "AWAITING_UPLOAD"), "owner_id", otherOwner));
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAILED", "CANCELLED", "INELIGIBLE" })
	void failedCancelledOrIneligibleJobFreesThePhoto(String status) {
		UUID photo = UuidV7.generate();
		UUID first = insertJob(insertRequest(), photo, "AWAITING_UPLOAD");
		UUID request = insertRequest();
		assertRejectedBy("uk_analysis_job_photo_active", () -> insertJob(request, photo, "AWAITING_UPLOAD"));

		jdbc.sql("UPDATE analysis_job SET status = :status WHERE id = :id")
			.param("status", status)
			.param("id", first)
			.update();
		insertJob(request, photo, "AWAITING_UPLOAD");

		assertThat(jdbc.sql("SELECT count(*) FROM analysis_job WHERE owner_id = :owner AND photo_id = :photo")
			.param("owner", owner)
			.param("photo", photo)
			.query(Long.class)
			.single()).isEqualTo(2L);
	}

	@Test
	void jobRejectsUnknownStatusFundingAndPriority() {
		UUID request = insertRequest();

		assertRejectedBy("ck_analysis_job_status", () -> insertJob(request, UuidV7.generate(), "PENDING"));
		assertRejectedBy("ck_analysis_job_status", () -> insertJob(request, UuidV7.generate(), "NEEDS_CHECK"));
		assertRejectedBy("ck_analysis_job_funding", () -> insertJobWith(request, "funding", "FREE"));
		assertRejectedBy("ck_analysis_job_base_priority", () -> insertJobWith(request, "base_priority", (short) -1));
		assertRejectedBy("ck_analysis_job_base_priority", () -> insertJobWith(request, "base_priority", (short) 3));
		insertJobWith(request, "funding", "PRO", "base_priority", (short) 2);
	}

	@Test
	void groupHasOneJob() {
		UUID request = insertRequest();
		UUID group = insertGroup(request, UuidV7.generate());
		rows.insert("analysis_job", job(request, group, UuidV7.generate(), "AWAITING_UPLOAD"));

		assertRejectedBy("uk_analysis_job_group",
				() -> rows.insert("analysis_job", job(request, group, UuidV7.generate(), "AWAITING_UPLOAD")));
	}

	@Test
	void groupNeedsAStoredRequest() {
		assertRejectedBy("fk_analysis_group_request",
				() -> rows.insert("analysis_group", group(UuidV7.generate(), UuidV7.generate())));
	}

	@Test
	void jobNeedsStoredRequestAndGroup() {
		UUID request = insertRequest();
		UUID photo = UuidV7.generate();
		UUID group = insertGroup(request, photo);

		assertRejectedBy("fk_analysis_job_request",
				() -> rows.insert("analysis_job", job(UuidV7.generate(), group, photo, "AWAITING_UPLOAD")));
		assertRejectedBy("fk_analysis_job_group",
				() -> rows.insert("analysis_job", job(request, UuidV7.generate(), photo, "AWAITING_UPLOAD")));
	}

	@Test
	void jobNeedsAStoredManifest() {
		UUID request = insertRequest();

		assertRejectedBy("fk_analysis_job_manifest", () -> insertJobWith(request, "manifest_id", sha256Hex()));
	}

	@Test
	void acceptedItemNamesJobGroupAndRole() {
		UUID request = insertRequest();
		UUID photo = UuidV7.generate();
		UUID group = insertGroup(request, photo);
		UUID job = insertJobOfGroup(request, group, photo);
		rows.insert("analysis_request_item", accepted(request, photo, group, job));

		assertRejectedBy("ck_analysis_request_item_accepted", () -> rows.insert("analysis_request_item",
				with(accepted(request, UuidV7.generate(), group, job), "analysis_job_id", null)));
		assertRejectedBy("ck_analysis_request_item_accepted", () -> rows.insert("analysis_request_item",
				with(accepted(request, UuidV7.generate(), group, job), "group_id", null, "group_role", null)));
	}

	@Test
	void inProgressItemNamesJobButNoGroup() {
		UUID earlier = insertRequest();
		UUID photo = UuidV7.generate();
		UUID group = insertGroup(earlier, photo);
		UUID job = insertJobOfGroup(earlier, group, photo);
		UUID request = insertRequest();
		rows.insert("analysis_request_item", inProgress(request, photo, job));

		assertRejectedBy("ck_analysis_request_item_in_progress", () -> rows.insert("analysis_request_item",
				with(inProgress(request, UuidV7.generate(), job), "analysis_job_id", null)));
		assertRejectedBy("ck_analysis_request_item_in_progress", () -> rows.insert("analysis_request_item",
				with(inProgress(request, UuidV7.generate(), job), "group_id", group, "group_role", "MEMBER")));
	}

	@Test
	void notAcceptedItemHasCodeAndReason() {
		UUID request = insertRequest();
		UUID photo = UuidV7.generate();
		UUID group = insertGroup(request, photo);
		rows.insert("analysis_request_item", notAccepted(request, photo));
		// Groups that were not accepted are recorded too (FR-I-02).
		rows.insert("analysis_request_item", with(notAccepted(request, UuidV7.generate()), "group_id", group,
				"group_role", "MEMBER"));

		assertRejectedBy("ck_analysis_request_item_not_accepted",
				() -> rows.insert("analysis_request_item", with(notAccepted(request, UuidV7.generate()), "code", null)));
		assertRejectedBy("ck_analysis_request_item_not_accepted", () -> rows.insert("analysis_request_item",
				with(notAccepted(request, UuidV7.generate()), "reason", null)));
	}

	@Test
	void existingResultValueIsAllowedWithoutJobOrGroup() {
		UUID request = insertRequest();
		rows.insert("analysis_request_item",
				with(notAccepted(request, UuidV7.generate()), "admission_result", "EXISTING_RESULT", "code", null,
						"reason", null));

		assertThat(jdbc.sql("SELECT admission_result FROM analysis_request_item WHERE request_id = :request")
			.param("request", request)
			.query(String.class)
			.single()).isEqualTo("EXISTING_RESULT");
	}

	@Test
	void groupRoleGoesWithGroup() {
		UUID request = insertRequest();
		UUID group = insertGroup(request, UuidV7.generate());

		assertRejectedBy("ck_analysis_request_item_group", () -> rows.insert("analysis_request_item",
				with(notAccepted(request, UuidV7.generate()), "group_id", group)));
		assertRejectedBy("ck_analysis_request_item_group", () -> rows.insert("analysis_request_item",
				with(notAccepted(request, UuidV7.generate()), "group_role", "REPRESENTATIVE")));
	}

	@Test
	void usedVectorHasNoIgnoredReason() {
		UUID request = insertRequest();
		rows.insert("analysis_request_item",
				with(notAccepted(request, UuidV7.generate()), "vector_id", UuidV7.generate()));
		rows.insert("analysis_request_item",
				with(notAccepted(request, UuidV7.generate()), "vector_ignored_reason", "ZERO_NORM"));

		assertRejectedBy("ck_analysis_request_item_vector", () -> rows.insert("analysis_request_item",
				with(notAccepted(request, UuidV7.generate()), "vector_id", UuidV7.generate(), "vector_ignored_reason",
						"BAD_DIMENSION")));
	}

	@Test
	void itemRejectsUnknownValues() {
		UUID request = insertRequest();
		UUID group = insertGroup(request, UuidV7.generate());

		assertRejectedBy("ck_analysis_request_item_admission_result", () -> rows.insert("analysis_request_item",
				with(notAccepted(request, UuidV7.generate()), "admission_result", "REJECTED")));
		assertRejectedBy("ck_analysis_request_item_text_status", () -> rows.insert("analysis_request_item",
				with(notAccepted(request, UuidV7.generate()), "text_status", "NONE")));
		assertRejectedBy("ck_analysis_request_item_group_role", () -> rows.insert("analysis_request_item",
				with(notAccepted(request, UuidV7.generate()), "group_id", group, "group_role", "LEADER")));
		assertRejectedBy("ck_analysis_request_item_vector_ignored_reason", () -> rows.insert("analysis_request_item",
				with(notAccepted(request, UuidV7.generate()), "vector_ignored_reason", "TOO_LONG")));
		assertRejectedBy("ck_analysis_request_item_basis_change_seq", () -> rows.insert("analysis_request_item",
				with(notAccepted(request, UuidV7.generate()), "basis_change_seq", -1L)));
	}

	@Test
	void itemIsOncePerPhotoOfARequest() {
		UUID request = insertRequest();
		UUID photo = UuidV7.generate();
		rows.insert("analysis_request_item", notAccepted(request, photo));

		assertRejectedBy("analysis_request_item_pkey",
				() -> rows.insert("analysis_request_item", notAccepted(request, photo)));
		rows.insert("analysis_request_item", notAccepted(insertRequest(), photo));
	}

	@Test
	void itemNeedsStoredRequestJobAndGroup() {
		UUID request = insertRequest();

		assertRejectedBy("fk_analysis_request_item_request",
				() -> rows.insert("analysis_request_item", notAccepted(UuidV7.generate(), UuidV7.generate())));
		assertRejectedBy("fk_analysis_request_item_job", () -> rows.insert("analysis_request_item",
				inProgress(request, UuidV7.generate(), UuidV7.generate())));
		assertRejectedBy("fk_analysis_request_item_group", () -> rows.insert("analysis_request_item",
				with(notAccepted(request, UuidV7.generate()), "group_id", UuidV7.generate(), "group_role", "MEMBER")));
	}

	private UUID insertRequest() {
		Map<String, Object> request = request();
		rows.insert("analysis_request", request);
		return (UUID) request.get("id");
	}

	private UUID insertGroup(UUID request, UUID representative) {
		Map<String, Object> group = group(request, representative);
		rows.insert("analysis_group", group);
		return (UUID) group.get("id");
	}

	private UUID insertJob(UUID request, UUID photo, String status) {
		return insertJobOfGroup(request, insertGroup(request, photo), photo, "status", status);
	}

	private UUID insertJobWith(UUID request, Object... changes) {
		UUID photo = UuidV7.generate();
		return insertJobOfGroup(request, insertGroup(request, photo), photo, changes);
	}

	private UUID insertJobOfGroup(UUID request, UUID group, UUID photo, Object... changes) {
		Map<String, Object> job = with(job(request, group, photo, "AWAITING_UPLOAD"), changes);
		rows.insert("analysis_job", job);
		return (UUID) job.get("id");
	}

	private Map<String, Object> request() {
		OffsetDateTime now = OffsetDateTime.now();
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", UuidV7.generate());
		row.put("owner_id", owner);
		row.put("origin", "CLIENT");
		row.put("trigger", "NEW_PHOTOS");
		row.put("device_id", UUID.randomUUID());
		row.put("trial_mark_id", UUID.randomUUID());
		row.put("consent_version", 1L);
		row.put("grouping_rule_id", ruleId);
		row.put("photo_count", 1);
		row.put("representative_count", 1);
		row.put("first_result_at", null);
		row.put("created_at", now);
		row.put("updated_at", now);
		row.put("version", 0L);
		return row;
	}

	private Map<String, Object> group(UUID request, UUID representative) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", UuidV7.generate());
		row.put("owner_id", owner);
		row.put("request_id", request);
		row.put("representative_photo_id", representative);
		row.put("photo_count", 1);
		row.put("model_id", null);
		row.put("model_version", null);
		row.put("created_at", OffsetDateTime.now());
		return row;
	}

	private Map<String, Object> job(UUID request, UUID group, UUID photo, String status) {
		OffsetDateTime now = OffsetDateTime.now();
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", UuidV7.generate());
		row.put("owner_id", owner);
		row.put("request_id", request);
		row.put("group_id", group);
		row.put("photo_id", photo);
		row.put("photo_version", "content-1");
		row.put("funding", "TRIAL");
		row.put("manifest_id", manifestId);
		row.put("status", status);
		row.put("base_priority", (short) 0);
		row.put("queued_at", now);
		row.put("upload_deadline", now.plusHours(24));
		row.put("processing_deadline", now.plusHours(48));
		row.put("created_at", now);
		row.put("updated_at", now);
		row.put("version", 0L);
		return row;
	}

	private Map<String, Object> accepted(UUID request, UUID photo, UUID group, UUID job) {
		return with(item(request, photo), "admission_result", "ACCEPTED", "group_id", group, "group_role",
				"REPRESENTATIVE", "analysis_job_id", job);
	}

	private Map<String, Object> inProgress(UUID request, UUID photo, UUID job) {
		return with(item(request, photo), "admission_result", "IN_PROGRESS", "analysis_job_id", job);
	}

	private Map<String, Object> notAccepted(UUID request, UUID photo) {
		return with(item(request, photo), "admission_result", "NOT_ACCEPTED", "code", "FORBIDDEN", "reason",
				"SUBSCRIPTION_REQUIRED");
	}

	private Map<String, Object> item(UUID request, UUID photo) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("request_id", request);
		row.put("photo_id", photo);
		row.put("owner_id", owner);
		row.put("client_ref", UUID.randomUUID());
		row.put("photo_version", "content-1");
		row.put("basis_change_seq", 0L);
		row.put("taken_at", OffsetDateTime.now());
		row.put("text_status", "UNKNOWN");
		row.put("ocr_version", null);
		row.put("admission_result", null);
		row.put("code", null);
		row.put("reason", null);
		row.put("retryable", false);
		row.put("group_id", null);
		row.put("group_role", null);
		row.put("analysis_job_id", null);
		row.put("vector_id", null);
		row.put("vector_ignored_reason", null);
		row.put("created_at", OffsetDateTime.now());
		return row;
	}

	// JSONB columns take the untyped literal; a bound string parameter would be sent as varchar.
	private void insertRule(String id) {
		jdbc.sql("INSERT INTO analysis_grouping_rule (id, content, created_at) VALUES (:id, '{}', now())")
			.param("id", id)
			.update();
	}

	private void insertManifest(String id, int maxBytes, int maxLongEdgePx) {
		jdbc.sql("INSERT INTO analysis_call_manifest (id, content, content_type, max_bytes, max_long_edge_px, created_at)"
				+ " VALUES (:id, '{}', 'image/jpeg', :maxBytes, :maxLongEdgePx, now())")
			.param("id", id)
			.param("maxBytes", maxBytes)
			.param("maxLongEdgePx", maxLongEdgePx)
			.update();
	}

	private static String sha256Hex() {
		byte[] bytes = new byte[32];
		ThreadLocalRandom.current().nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

}
