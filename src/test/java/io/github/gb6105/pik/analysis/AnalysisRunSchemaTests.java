package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.SchemaRows.assertRejectedBy;
import static io.github.gb6105.pik.SchemaRows.with;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gb6105.pik.SchemaRows;
import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.shared.id.UuidV7;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Constraints of the V6 analysis upload, run, result and budget tables and columns, checked with plain SQL (FR-I-03).
 * Owner, photo and device mark IDs carry no FK, so the rows here use IDs that name nothing in other modules. Rows of
 * the shared tables (ai_gate, ai_spend_day, device_trial_mark) change only in transactions that roll back.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AnalysisRunSchemaTests {

	static final List<String> V6_TABLES = List.of("analysis_upload_attempt", "external_call", "analysis_result",
			"photo_ai_budget", "ai_spend_day", "ai_send_daily", "ai_gate", "analysis_input_rate");

	static final List<String> FAILURE_CODES = List.of("UPLOAD_ATTEMPTS_EXHAUSTED", "UPLOAD_EXPIRED",
			"PROCESSING_EXPIRED", "CONSENT_WITHDRAWN", "PRINCIPAL_INACTIVE", "REPRESENTATIVE_DELETED", "INPUT_CHANGED",
			"INPUT_MISSING", "INPUT_INVALID", "REFUSAL", "TRUNCATED", "INVALID_OUTPUT", "PROVIDER_REJECTED",
			"UNKNOWN_OUTCOME", "ATTEMPTS_EXHAUSTED", "DEVICE_BUDGET_EXHAUSTED", "RESULT_CONFLICT");

	static final String ATTEMPT = "analysis_upload_attempt";

	static final String CALL = "external_call";

	static final String ITEM = "analysis_request_item";

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	PostgreSQLContainer postgres;

	SchemaRows rows;

	UUID owner;

	String ruleId;

	String manifestId;

	UUID requestId;

	@BeforeEach
	void setUp() {
		rows = new SchemaRows(jdbc);
		owner = UUID.randomUUID();
		ruleId = sha256Hex();
		insertRule("", ruleId);
		manifestId = sha256Hex();
		insertManifest(manifestId, null, null, null);
		requestId = UuidV7.generate();
		rows.insert("analysis_request", request(requestId));
	}

	@Test
	void migrationV6CreatesRunTables() {
		boolean applied = jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '6'")
			.query(Boolean.class)
			.single();
		List<String> tables = jdbc
			.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()"
					+ " AND table_name IN (:tables)")
			.param("tables", V6_TABLES)
			.query(String.class)
			.list();

		assertThat(applied).isTrue();
		assertThat(tables).containsExactlyInAnyOrderElementsOf(V6_TABLES);
	}

	@Test
	void onlyPlannedColumnsAreNullable() {
		List<String> nullable = jdbc
			.sql("SELECT table_name || '.' || column_name FROM information_schema.columns"
					+ " WHERE table_schema = current_schema() AND table_name IN (:tables) AND is_nullable = 'YES'")
			.param("tables", V6_TABLES)
			.query(String.class)
			.list();

		assertThat(nullable).containsExactlyInAnyOrder("analysis_upload_attempt.confirmed_at",
				"analysis_upload_attempt.etag", "analysis_upload_attempt.last_modified",
				"analysis_upload_attempt.reject_reason", "analysis_upload_attempt.delete_after",
				"external_call.http_status", "external_call.stop_reason", "external_call.provider_request_id",
				"external_call.outcome", "external_call.input_tokens", "external_call.output_tokens",
				"external_call.cache_creation_tokens", "external_call.cache_read_tokens",
				"external_call.settled_cost_micros", "external_call.device_mark_id", "external_call.sent_at",
				"external_call.responded_at", "external_call.settled_at", "photo_ai_budget.input_blocked_until",
				"ai_gate.blocked_until", "ai_gate.probe_job_id", "ai_gate.probe_run_token", "ai_gate.config_version",
				"ai_gate.opened_at");
	}

	@Test
	void plannedIndexesExist() {
		Map<String, String> indexes = jdbc
			.sql("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = current_schema()"
					+ " AND tablename IN ('analysis_job', 'analysis_upload_attempt', 'external_call',"
					+ " 'analysis_result', 'analysis_input_rate')")
			.query((rs, rowNum) -> Map.entry(rs.getString("indexname"), rs.getString("indexdef")))
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

		assertThat(indexes).containsKeys("ix_analysis_job_ready", "ix_analysis_job_running_owner",
				"ix_analysis_job_running_lease", "ix_analysis_job_awaiting", "ix_analysis_job_open_deadline",
				"uk_upload_attempt_job_issued", "uk_upload_attempt_job_confirmed", "ix_upload_attempt_job",
				"ix_upload_attempt_delete", "uk_external_call_job_run_token", "ix_external_call_responded",
				"ix_external_call_sending", "uk_analysis_result_photo", "ix_analysis_result_job",
				"ix_analysis_input_rate_window");
		// The claim candidate index is a design contract (design 4.3).
		assertThat(indexes.get("ix_analysis_job_ready"))
			.endsWith("(next_attempt_at, base_priority, queued_at) WHERE (status = 'READY'::text)");
		assertThat(indexes.get("ix_external_call_responded"))
			.endsWith("(config_fingerprint, responded_at) WHERE (status <> 'SENDING'::text)");
	}

	@Test
	void runningJobHoldsRunTokenAndLease() {
		insertJob("status", "RUNNING", "run_token", UUID.randomUUID(), "locked_until", later());

		assertJobRejectedBy("ck_analysis_job_running", "status", "RUNNING", "locked_until", later());
		assertJobRejectedBy("ck_analysis_job_running", "status", "RUNNING", "run_token", UUID.randomUUID());
	}

	@Test
	void readyJobHasNextAttemptAndInput() {
		insertJob("status", "READY", "next_attempt_at", now(), "input_sha256", sha256());

		assertJobRejectedBy("ck_analysis_job_ready", "status", "READY", "input_sha256", sha256());
		assertJobRejectedBy("ck_analysis_job_ready", "status", "READY", "next_attempt_at", now());
	}

	@Test
	void awaitingUploadJobHasNoInput() {
		insertJob("status", "AWAITING_UPLOAD");

		assertJobRejectedBy("ck_analysis_job_awaiting_upload", "status", "AWAITING_UPLOAD", "input_sha256", sha256());
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAILED", "CANCELLED", "INELIGIBLE" })
	void failedCancelledOrIneligibleJobNamesReasonAndEnd(String status) {
		insertJob("status", status, "failure_code", "UPLOAD_EXPIRED", "ended_at", now());

		assertJobRejectedBy("ck_analysis_job_failure_code", "status", status, "ended_at", now());
		assertJobRejectedBy("ck_analysis_job_ended_at", "status", status, "failure_code", "UPLOAD_EXPIRED");
	}

	@Test
	void succeededJobHasEndButNoReason() {
		insertJob("status", "SUCCEEDED", "ended_at", now());

		assertJobRejectedBy("ck_analysis_job_failure_code", "status", "SUCCEEDED", "ended_at", now(), "failure_code",
				"REFUSAL");
		assertJobRejectedBy("ck_analysis_job_ended_at", "status", "SUCCEEDED");
	}

	@Test
	void openJobHasNoReasonOrEnd() {
		assertJobRejectedBy("ck_analysis_job_failure_code", "failure_code", "UPLOAD_EXPIRED");
		assertJobRejectedBy("ck_analysis_job_ended_at", "ended_at", now());
	}

	@Test
	void failureCodeIsAPlannedValue() {
		FAILURE_CODES.forEach((code) -> insertJob("status", "FAILED", "failure_code", code, "ended_at", now()));

		assertJobRejectedBy("ck_analysis_job_failure_code_value", "status", "FAILED", "failure_code", "TIMEOUT",
				"ended_at", now());
	}

	@Test
	void jobCountersStartAtZeroAndAreBounded() {
		UUID job = insertJob();
		insertJob("issued_count", 5);

		assertThat(jdbc.sql("SELECT attempt_count, issued_count FROM analysis_job WHERE id = :id")
			.param("id", job)
			.query()
			.singleRow()).containsEntry("attempt_count", 0).containsEntry("issued_count", 0);
		assertJobRejectedBy("ck_analysis_job_attempt_count", "attempt_count", -1);
		assertJobRejectedBy("ck_analysis_job_issued_count", "issued_count", 6);
		assertJobRejectedBy("ck_analysis_job_issued_count", "issued_count", -1);
		assertJobRejectedBy("ck_analysis_job_input_sha256", "status", "READY", "next_attempt_at", now(),
				"input_sha256", new byte[31]);
	}

	@Test
	void pendingAttemptHasDeleteAfter() {
		UUID job = insertJob();
		rows.insert(ATTEMPT, attempt(job, "SUPERSEDED", "object_state", "PENDING", "delete_after", now()));
		rows.insert(ATTEMPT, attempt(job, "SUPERSEDED", "object_state", "DELETE_SENT"));

		assertRejectedBy("ck_upload_attempt_delete_after",
				() -> rows.insert(ATTEMPT, attempt(job, "SUPERSEDED", "object_state", "PENDING")));
		assertRejectedBy("ck_upload_attempt_delete_after",
				() -> rows.insert(ATTEMPT, attempt(job, "SUPERSEDED", "delete_after", now())));
		assertRejectedBy("ck_upload_attempt_delete_after", () -> rows.insert(ATTEMPT,
				attempt(job, "SUPERSEDED", "object_state", "DELETE_SENT", "delete_after", now())));
	}

	@Test
	void jobHasOneIssuedAndOneConfirmedAttempt() {
		UUID job = insertJob();
		rows.insert(ATTEMPT, attempt(job, "ISSUED"));
		rows.insert(ATTEMPT, attempt(job, "CONFIRMED", "confirmed_at", now(), "etag", "\"etag\"", "last_modified",
				now()));
		rows.insert(ATTEMPT, attempt(job, "SUPERSEDED"));
		rows.insert(ATTEMPT, attempt(job, "SUPERSEDED"));
		rows.insert(ATTEMPT, attempt(job, "ABANDONED"));

		assertRejectedBy("uk_upload_attempt_job_issued", () -> rows.insert(ATTEMPT, attempt(job, "ISSUED")));
		assertRejectedBy("uk_upload_attempt_job_confirmed", () -> rows.insert(ATTEMPT, attempt(job, "CONFIRMED")));
		// Another job is not blocked.
		UUID other = insertJob();
		rows.insert(ATTEMPT, attempt(other, "ISSUED"));
		rows.insert(ATTEMPT, attempt(other, "CONFIRMED"));
	}

	@Test
	void attemptRejectsUnknownValuesAndBadInput() {
		UUID job = insertJob();
		List.of("SIZE_MISMATCH", "CHECKSUM_MISMATCH", "CONTENT_TYPE_MISMATCH", "LATE_UPLOAD")
			.forEach((reason) -> rows.insert(ATTEMPT, attempt(job, "REJECTED", "reject_reason", reason)));

		assertRejectedBy("ck_upload_attempt_status", () -> rows.insert(ATTEMPT, attempt(job, "EXPIRED")));
		assertRejectedBy("ck_upload_attempt_object_state",
				() -> rows.insert(ATTEMPT, attempt(job, "SUPERSEDED", "object_state", "DELETED")));
		assertRejectedBy("ck_upload_attempt_reject_reason",
				() -> rows.insert(ATTEMPT, attempt(job, "REJECTED", "reject_reason", "TOO_LARGE")));
		assertRejectedBy("ck_upload_attempt_content_length",
				() -> rows.insert(ATTEMPT, attempt(job, "SUPERSEDED", "content_length", 0L)));
		assertRejectedBy("ck_upload_attempt_sha256",
				() -> rows.insert(ATTEMPT, attempt(job, "SUPERSEDED", "sha256", new byte[31])));
	}

	@Test
	void attemptHasItsOwnObjectKeyAndAStoredJob() {
		UUID job = insertJob();
		Map<String, Object> first = attempt(job, "SUPERSEDED");
		rows.insert(ATTEMPT, first);

		assertRejectedBy("uk_upload_attempt_object_key",
				() -> rows.insert(ATTEMPT, attempt(job, "SUPERSEDED", "object_key", first.get("object_key"))));
		assertRejectedBy("fk_upload_attempt_job", () -> rows.insert(ATTEMPT, attempt(UuidV7.generate(), "ISSUED")));
	}

	@Test
	void sendingCallHoldsCostAndSendReservations() {
		UUID job = insertJob();
		rows.insert(CALL, call(job, "status", "SENDING", "cost_state", "RESERVED", "send_state", "RESERVED",
				"settled_cost_micros", null));
		rows.insert(CALL, call(job, "status", "UNKNOWN", "cost_state", "RESERVED", "send_state", "SPENT"));

		assertCallRejectedBy("ck_external_call_sending", job, "status", "SENDING", "cost_state", "SETTLED",
				"send_state", "RESERVED");
		assertCallRejectedBy("ck_external_call_sending", job, "status", "SENDING", "cost_state", "RESERVED",
				"send_state", "SPENT");
		assertCallRejectedBy("ck_external_call_sending", job, "status", "SUCCEEDED", "cost_state", "RESERVED",
				"send_state", "RESERVED");
	}

	@Test
	void notSentCallCostsNothingAndReleasesItsSend() {
		UUID job = insertJob();
		rows.insert(CALL, call(job, "status", "NOT_SENT", "settled_cost_micros", 0L, "send_state", "RELEASED"));

		assertCallRejectedBy("ck_external_call_not_sent", job, "status", "NOT_SENT", "settled_cost_micros", 1L,
				"send_state", "RELEASED");
		assertCallRejectedBy("ck_external_call_not_sent", job, "status", "NOT_SENT", "settled_cost_micros", null,
				"send_state", "RELEASED");
		assertCallRejectedBy("ck_external_call_not_sent", job, "status", "NOT_SENT", "settled_cost_micros", 0L,
				"send_state", "SPENT");
	}

	@Test
	void onlyAnUnknownCallIsAdjusted() {
		UUID job = insertJob();
		rows.insert(CALL, call(job, "status", "UNKNOWN", "cost_state", "ADJUSTED"));

		assertCallRejectedBy("ck_external_call_adjusted", job, "status", "SUCCEEDED", "cost_state", "ADJUSTED");
		assertCallRejectedBy("ck_external_call_adjusted", job, "status", "RESPONDED_FAILURE", "cost_state",
				"ADJUSTED");
		assertCallRejectedBy("ck_external_call_adjusted", job, "status", "NOT_SENT", "cost_state", "ADJUSTED",
				"settled_cost_micros", 0L, "send_state", "RELEASED");
	}

	@Test
	void callIsOncePerRunOfAJob() {
		UUID job = insertJob();
		UUID token = UUID.randomUUID();
		rows.insert(CALL, call(job, "run_token", token));

		assertCallRejectedBy("uk_external_call_job_run_token", job, "run_token", token);
		rows.insert(CALL, call(job, "run_token", UUID.randomUUID()));
		rows.insert(CALL, call(insertJob(), "run_token", token));
	}

	@Test
	void callRejectsUnknownValuesAndBadAmounts() {
		UUID job = insertJob();
		rows.insert(CALL, call(job, "funding", "PRO", "device_mark_id", null));

		assertCallRejectedBy("ck_external_call_status", job, "status", "DONE");
		assertCallRejectedBy("ck_external_call_cost_state", job, "cost_state", "FREE");
		assertCallRejectedBy("ck_external_call_send_state", job, "send_state", "LOST");
		assertCallRejectedBy("ck_external_call_funding", job, "funding", "FREE", "device_mark_id", null);
		assertCallRejectedBy("ck_external_call_device_mark", job, "funding", "PRO");
		assertCallRejectedBy("ck_external_call_reserved_cost", job, "reserved_cost_micros", 0L);
		assertCallRejectedBy("ck_external_call_settled_cost", job, "settled_cost_micros", -1L);
		assertCallRejectedBy("ck_external_call_input_sha256", job, "input_sha256", new byte[31]);
		assertCallRejectedBy("fk_external_call_manifest", job, "manifest_id", sha256Hex());
		assertCallRejectedBy("fk_external_call_job", UuidV7.generate());
	}

	@Test
	void resultIsOncePerPhoto() {
		UUID job = insertJob();
		UUID photo = UuidV7.generate();
		insertResult(result(job, "photo_id", photo));
		insertResult(result(job, "verification", "INHERITED"));

		assertThat(jdbc.sql("SELECT epoch FROM analysis_result WHERE photo_id = :photo")
			.param("photo", photo)
			.query(Integer.class)
			.single()).isOne();
		assertRejectedBy("uk_analysis_result_photo", () -> insertResult(result(insertJob(), "photo_id", photo)));
		assertRejectedBy("fk_analysis_result_job", () -> insertResult(result(UuidV7.generate())));
		assertRejectedBy("fk_analysis_result_manifest", () -> insertResult(result(job, "manifest_id", sha256Hex())));
	}

	@Test
	void resultRejectsUnknownValuesAndBadNumbers() {
		UUID job = insertJob();
		List.of("AVAILABLE", "EMPTY", "DISCARDED")
			.forEach((state) -> insertResult(result(job, "search_state", state)));

		assertRejectedBy("ck_analysis_result_verification",
				() -> insertResult(result(job, "verification", "COPIED")));
		assertRejectedBy("ck_analysis_result_search_state",
				() -> insertResult(result(job, "search_state", "HIDDEN")));
		assertRejectedBy("ck_analysis_result_schema_version", () -> insertResult(result(job, "schema_version", 0)));
		assertRejectedBy("ck_analysis_result_epoch", () -> insertResult(result(job, "epoch", 0)));
		assertRejectedBy("ck_analysis_result_basis_change_seq",
				() -> insertResult(result(job, "basis_change_seq", -1L)));
		assertRejectedBy("ck_analysis_result_change_seq", () -> insertResult(result(job, "change_seq", -1L)));
	}

	@Test
	void photoBudgetAndDailySendsAreNotNegative() {
		rows.insert("photo_ai_budget", photoBudget());
		rows.insert("ai_send_daily", sendDaily());

		assertRejectedBy("ck_photo_ai_budget_risk_sends",
				() -> rows.insert("photo_ai_budget", with(photoBudget(), "risk_sends", -1)));
		assertRejectedBy("ck_photo_ai_budget_input_invalid_count",
				() -> rows.insert("photo_ai_budget", with(photoBudget(), "input_invalid_count", -1)));
		assertRejectedBy("ck_ai_send_daily_send_count",
				() -> rows.insert("ai_send_daily", with(sendDaily(), "send_count", -1)));
	}

	@Test
	void spendDayAmountsAreNotNegative() {
		inRolledBackTransaction(() -> rows.insert("ai_spend_day", spendDay()));

		for (String column : List.of("limit_micros", "reserved_micros", "settled_micros")) {
			assertRejectedBy("ck_ai_spend_day_micros",
					() -> rows.insert("ai_spend_day", with(spendDay(), column, -1L)));
		}
	}

	@Test
	void inputRateNamesAKnownSubject() {
		rows.insert("analysis_input_rate", inputRate("PRINCIPAL"));
		rows.insert("analysis_input_rate", inputRate("TRIAL_MARK"));

		assertRejectedBy("ck_analysis_input_rate_subject_kind",
				() -> rows.insert("analysis_input_rate", inputRate("DEVICE")));
		assertRejectedBy("ck_analysis_input_rate_counts",
				() -> rows.insert("analysis_input_rate", with(inputRate("PRINCIPAL"), "items", -1)));
		assertRejectedBy("ck_analysis_input_rate_counts",
				() -> rows.insert("analysis_input_rate", with(inputRate("PRINCIPAL"), "bytes", -1L)));
	}

	@Test
	void gateStartsClosedForEachCause() {
		List<Map<String, Object>> gates = jdbc
			.sql("SELECT cause, state, generation, blocked_until, probe_job_id, probe_run_token, config_version,"
					+ " failure_streak, opened_at FROM ai_gate ORDER BY cause")
			.query()
			.listOfRows();

		assertThat(gates).extracting((gate) -> gate.get("cause"))
			.containsExactly("BUDGET", "CONFIG_CIRCUIT", "TRANSIENT_CIRCUIT");
		assertThat(gates).allSatisfy((gate) -> assertThat(gate).containsEntry("state", "CLOSED")
			.containsEntry("generation", 0L)
			.containsEntry("failure_streak", 0)
			.containsEntry("blocked_until", null)
			.containsEntry("probe_job_id", null)
			.containsEntry("probe_run_token", null)
			.containsEntry("config_version", null)
			.containsEntry("opened_at", null));
	}

	@Test
	void gateProbeIsAJobAndRunTokenPair() {
		assertRejectedBy("ck_ai_gate_probe", () -> updateGate("CONFIG_CIRCUIT", "HALF_OPEN", UuidV7.generate(), null));
		assertRejectedBy("ck_ai_gate_probe", () -> updateGate("CONFIG_CIRCUIT", "HALF_OPEN", null, UUID.randomUUID()));

		UUID job = UuidV7.generate();
		UUID token = UUID.randomUUID();
		inRolledBackTransaction(() -> {
			assertThat(updateGate("CONFIG_CIRCUIT", "HALF_OPEN", job, token)).isOne();
			assertThat(updateGate("TRANSIENT_CIRCUIT", "HALF_OPEN", job, token)).isOne();
		});
	}

	@Test
	void gateRejectsUnknownCauseAndStateAndBadCounters() {
		inRolledBackTransaction(() -> assertThat(updateGate("BUDGET", "OPEN", null, null)).isOne());

		assertRejectedBy("ck_ai_gate_budget_state", () -> updateGate("BUDGET", "HALF_OPEN", null, null));
		assertRejectedBy("ck_ai_gate_state", () -> updateGate("CONFIG_CIRCUIT", "BROKEN", null, null));
		assertRejectedBy("ck_ai_gate_cause", () -> jdbc.sql("""
				INSERT INTO ai_gate (cause, state, generation, reset_at, failure_streak, updated_at)
				VALUES ('QUOTA', 'CLOSED', 0, now(), 0, now())
				""").update());
		assertRejectedBy("ck_ai_gate_generation",
				() -> jdbc.sql("UPDATE ai_gate SET generation = -1 WHERE cause = 'BUDGET'").update());
		assertRejectedBy("ck_ai_gate_failure_streak",
				() -> jdbc.sql("UPDATE ai_gate SET failure_streak = -1 WHERE cause = 'TRANSIENT_CIRCUIT'").update());
	}

	@Test
	void existingResultItemNamesTheReusedResult() {
		rows.insert(ITEM, existingResult());

		assertRejectedBy("ck_analysis_request_item_existing_result",
				() -> rows.insert(ITEM, with(existingResult(), "reused_result_id", null)));
		assertRejectedBy("ck_analysis_request_item_existing_result",
				() -> rows.insert(ITEM, with(existingResult(), "reused_photo_version", null)));
		assertRejectedBy("ck_analysis_request_item_existing_result", () -> rows.insert(ITEM,
				with(existingResult(), "reused_result_id", null, "reused_photo_version", null)));
		assertRejectedBy("ck_analysis_request_item_existing_result", () -> rows.insert(ITEM,
				with(notAccepted(), "reused_result_id", UuidV7.generate(), "reused_photo_version", "content-1")));
		assertRejectedBy("ck_analysis_request_item_existing_result",
				() -> rows.insert(ITEM, with(notAccepted(), "reused_photo_version", "content-1")));
	}

	@Test
	void onlyANotAcceptedItemHasRetryAt() {
		rows.insert(ITEM, with(notAccepted(), "retry_at", later()));

		assertRejectedBy("ck_analysis_request_item_retry_at",
				() -> rows.insert(ITEM, with(existingResult(), "retry_at", later())));
	}

	@Test
	void trialMarkSendCountStartsAtZero() {
		UUID mark = UuidV7.generate();
		inRolledBackTransaction(() -> {
			rows.insert("device_trial_mark", trialMark(mark));
			assertThat(jdbc.sql("SELECT send_count FROM device_trial_mark WHERE id = :id")
				.param("id", mark)
				.query(Integer.class)
				.single()).isZero();
		});

		assertRejectedBy("ck_device_trial_mark_send_count",
				() -> rows.insert("device_trial_mark", with(trialMark(UuidV7.generate()), "send_count", -1)));
	}

	@Test
	void manifestCallLimitsArePositiveAndSetTogether() {
		insertManifest(sha256Hex(), 33_000_000L, 4096, 50_000L);

		assertRejectedBy("ck_analysis_call_manifest_max_pixels",
				() -> insertManifest(sha256Hex(), 0L, 4096, 50_000L));
		assertRejectedBy("ck_analysis_call_manifest_max_tokens",
				() -> insertManifest(sha256Hex(), 33_000_000L, 0, 50_000L));
		assertRejectedBy("ck_analysis_call_manifest_max_cost_micros",
				() -> insertManifest(sha256Hex(), 33_000_000L, 4096, 0L));
		assertRejectedBy("ck_analysis_call_manifest_limits",
				() -> insertManifest(sha256Hex(), 33_000_000L, null, null));
		assertRejectedBy("ck_analysis_call_manifest_limits", () -> insertManifest(sha256Hex(), null, 4096, 50_000L));
	}

	/**
	 * Runs V6 on stage 5-1 rows in a schema of its own: V1 to V5, the rows, then V6. A job with a photo row is named
	 * after the photo's client_ref, even when its item says otherwise; a job without one after its representative
	 * ACCEPTED item, not a member item.
	 */
	@Test
	void v6NamesStage51JobsAfterTheirPhotoOrRepresentativeItem() {
		String schema = "v6_backfill_" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
		try {
			migrate(schema, "5");
			insertRule(schema + ".", ruleId);
			jdbc.sql("INSERT INTO " + schema + ".analysis_call_manifest (id, content, content_type, max_bytes,"
					+ " max_long_edge_px, created_at) VALUES (:id, '{}', 'image/jpeg', 2097152, 1568, now())")
				.param("id", manifestId)
				.update();
			UUID request = UuidV7.generate();
			rows.insert(schema + ".analysis_request", request(request));
			UUID photoRef = UUID.randomUUID();
			UUID stored = UuidV7.generate();
			rows.insert(schema + ".photo", photo(stored, photoRef));
			UUID storedJob = stage51Job(schema, request, stored, UUID.randomUUID());
			UUID itemRef = UUID.randomUUID();
			UUID missingJob = stage51Job(schema, request, UuidV7.generate(), itemRef);
			rows.insert(schema + "." + ITEM, with(item(UuidV7.generate()), "request_id", request, "admission_result",
					"ACCEPTED", "group_id", groupOf(schema, missingJob), "group_role", "MEMBER", "analysis_job_id",
					missingJob));

			migrate(schema, "6");

			Map<UUID, UUID> refs = jdbc.sql("SELECT id, representative_client_ref FROM " + schema + ".analysis_job")
				.query((rs, rowNum) -> Map.entry(rs.getObject("id", UUID.class),
						rs.getObject("representative_client_ref", UUID.class)))
				.list()
				.stream()
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
			assertThat(refs).containsOnly(Map.entry(storedJob, photoRef), Map.entry(missingJob, itemRef));
			assertThat(jdbc.sql("SELECT is_nullable FROM information_schema.columns WHERE table_schema = :schema"
					+ " AND table_name = 'analysis_job' AND column_name = 'representative_client_ref'")
				.param("schema", schema)
				.query(String.class)
				.single()).isEqualTo("NO");
		}
		finally {
			jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
		}
	}

	private void migrate(String schema, String target) {
		Flyway.configure()
			.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
			.schemas(schema)
			.target(target)
			.load()
			.migrate();
	}

	/**
	 * Inserts a stage 5-1 job of a single photo with its representative ACCEPTED item.
	 */
	private UUID stage51Job(String schema, UUID request, UUID photo, UUID clientRef) {
		Map<String, Object> group = group(request, photo);
		rows.insert(schema + ".analysis_group", group);
		Map<String, Object> job = job(request, (UUID) group.get("id"), photo);
		job.remove("representative_client_ref");
		rows.insert(schema + ".analysis_job", job);
		rows.insert(schema + "." + ITEM,
				with(item(photo), "request_id", request, "client_ref", clientRef, "admission_result", "ACCEPTED",
						"group_id", group.get("id"), "group_role", "REPRESENTATIVE", "analysis_job_id", job.get("id")));
		return (UUID) job.get("id");
	}

	private UUID groupOf(String schema, UUID job) {
		return jdbc.sql("SELECT group_id FROM " + schema + ".analysis_job WHERE id = :id")
			.param("id", job)
			.query(UUID.class)
			.single();
	}

	private UUID insertJob(Object... changes) {
		UUID photo = UuidV7.generate();
		UUID group = insertGroup(photo);
		Map<String, Object> job = with(job(requestId, group, photo), changes);
		rows.insert("analysis_job", job);
		return (UUID) job.get("id");
	}

	private void assertJobRejectedBy(String constraint, Object... changes) {
		UUID photo = UuidV7.generate();
		Map<String, Object> job = with(job(requestId, insertGroup(photo), photo), changes);
		assertRejectedBy(constraint, () -> rows.insert("analysis_job", job));
	}

	private void assertCallRejectedBy(String constraint, UUID job, Object... changes) {
		Map<String, Object> call = call(job, changes);
		assertRejectedBy(constraint, () -> rows.insert(CALL, call));
	}

	private UUID insertGroup(UUID representative) {
		Map<String, Object> group = group(requestId, representative);
		rows.insert("analysis_group", group);
		return (UUID) group.get("id");
	}

	// JSONB columns take a cast; a bound string parameter would be sent as varchar.
	private void insertResult(Map<String, Object> row) {
		String columns = String.join(", ", row.keySet());
		String values = row.keySet()
			.stream()
			.map((column) -> column.equals("content") ? "CAST(:content AS JSONB)" : ":" + column)
			.collect(Collectors.joining(", "));
		jdbc.sql("INSERT INTO analysis_result (" + columns + ") VALUES (" + values + ")").params(row).update();
	}

	private int updateGate(String cause, String state, @Nullable UUID probeJob, @Nullable UUID probeRunToken) {
		return jdbc.sql("""
				UPDATE ai_gate SET state = :state, generation = generation + 1, probe_job_id = :job,
				                   probe_run_token = :token, updated_at = now()
				WHERE cause = :cause
				""")
			.param("state", state)
			.param("job", probeJob, Types.OTHER)
			.param("token", probeRunToken, Types.OTHER)
			.param("cause", cause)
			.update();
	}

	private void inRolledBackTransaction(Runnable statements) {
		transactions.executeWithoutResult((status) -> {
			statements.run();
			status.setRollbackOnly();
		});
	}

	private Map<String, Object> request(UUID id) {
		OffsetDateTime now = now();
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", id);
		row.put("owner_id", owner);
		row.put("origin", "CLIENT");
		row.put("trigger", "NEW_PHOTOS");
		row.put("device_id", UUID.randomUUID());
		row.put("trial_mark_id", UUID.randomUUID());
		row.put("consent_version", 1L);
		row.put("grouping_rule_id", ruleId);
		row.put("photo_count", 1);
		row.put("representative_count", 1);
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
		row.put("created_at", now());
		return row;
	}

	private Map<String, Object> job(UUID request, UUID group, UUID photo) {
		OffsetDateTime now = now();
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", UuidV7.generate());
		row.put("owner_id", owner);
		row.put("request_id", request);
		row.put("group_id", group);
		row.put("photo_id", photo);
		row.put("photo_version", "content-1");
		row.put("representative_client_ref", UUID.randomUUID());
		row.put("funding", "TRIAL");
		row.put("manifest_id", manifestId);
		row.put("status", "AWAITING_UPLOAD");
		row.put("base_priority", (short) 0);
		row.put("queued_at", now);
		row.put("upload_deadline", now.plusHours(24));
		row.put("processing_deadline", now.plusHours(48));
		row.put("created_at", now);
		row.put("updated_at", now);
		row.put("version", 0L);
		return row;
	}

	private Map<String, Object> attempt(UUID job, String status, Object... changes) {
		OffsetDateTime now = now();
		UUID id = UuidV7.generate();
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", id);
		row.put("owner_id", owner);
		row.put("job_id", job);
		row.put("status", status);
		row.put("object_key", "analysis-input/" + job + "/" + id);
		row.put("content_length", 1_048_576L);
		row.put("sha256", sha256());
		row.put("url_expires_at", now.plusMinutes(15));
		row.put("object_state", "RETAINED");
		row.put("created_at", now);
		row.put("updated_at", now);
		row.put("version", 0L);
		return with(row, changes);
	}

	private Map<String, Object> call(UUID job, Object... changes) {
		OffsetDateTime now = now();
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", UuidV7.generate());
		row.put("owner_id", owner);
		row.put("job_id", job);
		row.put("run_token", UUID.randomUUID());
		row.put("manifest_id", manifestId);
		row.put("funding", "TRIAL");
		row.put("photo_id", UuidV7.generate());
		row.put("input_sha256", sha256());
		row.put("budget_day", LocalDate.now());
		row.put("status", "SUCCEEDED");
		row.put("config_fingerprint", "key-1:" + sha256Hex());
		row.put("reserved_cost_micros", 20_000L);
		row.put("settled_cost_micros", 12_000L);
		row.put("cost_state", "SETTLED");
		row.put("send_state", "SPENT");
		row.put("device_mark_id", UUID.randomUUID());
		row.put("created_at", now);
		return with(row, changes);
	}

	private Map<String, Object> result(UUID job, Object... changes) {
		OffsetDateTime now = now();
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", UuidV7.generate());
		row.put("owner_id", owner);
		row.put("photo_id", UuidV7.generate());
		row.put("job_id", job);
		row.put("verification", "DIRECT");
		row.put("photo_version", "content-1");
		row.put("basis_change_seq", 0L);
		row.put("manifest_id", manifestId);
		row.put("schema_version", 1);
		row.put("output_locale", "ko-KR");
		row.put("search_state", "AVAILABLE");
		row.put("content", "{}");
		row.put("change_seq", 1L);
		row.put("created_at", now);
		row.put("updated_at", now);
		row.put("version", 0L);
		return with(row, changes);
	}

	private Map<String, Object> photoBudget() {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("photo_id", UuidV7.generate());
		row.put("owner_id", owner);
		row.put("risk_sends", 0);
		row.put("unknown_retry_used", false);
		row.put("input_invalid_count", 0);
		row.put("updated_at", now());
		row.put("version", 0L);
		return row;
	}

	private Map<String, Object> sendDaily() {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("owner_id", UUID.randomUUID());
		row.put("day", LocalDate.now());
		row.put("send_count", 0);
		return row;
	}

	// A day long past, so that the row names no day the application uses.
	private Map<String, Object> spendDay() {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("day", LocalDate.of(2000, 1, 1));
		row.put("limit_micros", 100_000_000L);
		row.put("reserved_micros", 0L);
		row.put("settled_micros", 0L);
		row.put("updated_at", now());
		return row;
	}

	private Map<String, Object> inputRate(String subjectKind) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("subject_kind", subjectKind);
		row.put("subject_id", UUID.randomUUID());
		row.put("window_start", now());
		row.put("items", 0);
		row.put("bytes", 0L);
		return row;
	}

	private Map<String, Object> existingResult() {
		return with(item(UuidV7.generate()), "admission_result", "EXISTING_RESULT", "reused_result_id",
				UuidV7.generate(), "reused_photo_version", "content-1");
	}

	private Map<String, Object> notAccepted() {
		return with(item(UuidV7.generate()), "admission_result", "NOT_ACCEPTED", "code", "RATE_LIMITED", "reason",
				"INPUT_RETRY_LATER");
	}

	private Map<String, Object> item(UUID photo) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("request_id", requestId);
		row.put("photo_id", photo);
		row.put("owner_id", owner);
		row.put("client_ref", UUID.randomUUID());
		row.put("photo_version", "content-1");
		row.put("basis_change_seq", 0L);
		row.put("text_status", "UNKNOWN");
		row.put("admission_result", null);
		row.put("retryable", false);
		row.put("created_at", now());
		return row;
	}

	private Map<String, Object> photo(UUID id, UUID clientRef) {
		OffsetDateTime now = now();
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", id);
		row.put("owner_id", owner);
		row.put("client_ref", clientRef);
		row.put("observed_version", "content-1");
		row.put("source_change_seq", 0L);
		row.put("change_seq", 1L);
		row.put("created_at", now);
		row.put("updated_at", now);
		return row;
	}

	private Map<String, Object> trialMark(UUID id) {
		OffsetDateTime now = now();
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", id);
		row.put("platform", "IOS");
		row.put("limit_count", 3);
		row.put("reserved_count", 0);
		row.put("consumed_count", 0);
		row.put("created_at", now);
		row.put("updated_at", now);
		row.put("version", 0L);
		return row;
	}

	// JSONB columns take the untyped literal; a bound string parameter would be sent as varchar.
	private void insertRule(String schemaPrefix, String id) {
		jdbc.sql("INSERT INTO " + schemaPrefix + "analysis_grouping_rule (id, content, created_at)"
				+ " VALUES (:id, '{}', now())")
			.param("id", id)
			.update();
	}

	private void insertManifest(String id, @Nullable Long maxPixels, @Nullable Integer maxTokens,
			@Nullable Long maxCostMicros) {
		jdbc.sql("""
				INSERT INTO analysis_call_manifest (id, content, content_type, max_bytes, max_long_edge_px, max_pixels,
				                                    max_tokens, max_cost_micros, created_at)
				VALUES (:id, '{}', 'image/jpeg', 2097152, 1568, :maxPixels, :maxTokens, :maxCostMicros, now())
				""")
			.param("id", id)
			.param("maxPixels", maxPixels, Types.BIGINT)
			.param("maxTokens", maxTokens, Types.INTEGER)
			.param("maxCostMicros", maxCostMicros, Types.BIGINT)
			.update();
	}

	private static OffsetDateTime now() {
		return OffsetDateTime.now();
	}

	private static OffsetDateTime later() {
		return OffsetDateTime.now().plusMinutes(2);
	}

	private static byte[] sha256() {
		byte[] bytes = new byte[32];
		ThreadLocalRandom.current().nextBytes(bytes);
		return bytes;
	}

	private static String sha256Hex() {
		return HexFormat.of().formatHex(sha256());
	}

}
