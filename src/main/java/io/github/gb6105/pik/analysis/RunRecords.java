package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.JobRecords.timestamp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plain SQL of a job's run (plan 2.3 to 2.5, 5): the permitted calls, the send and cost budgets, the request's first
 * result and the stored results. Rows that a budget counts in are created at zero before they are locked, so that a
 * lock is always taken on a row; creating one changes no counter. Records carry photo IDs and must not be logged.
 */
@Repository
@RequiredArgsConstructor
class RunRecords {

	private static final String CALL = """
			SELECT id, owner_id, job_id, run_token, photo_id, funding, budget_day, reserved_cost_micros, device_mark_id,
			       created_at
			FROM external_call
			""";

	private final JdbcClient jdbc;

	/**
	 * What a run needs from its job, read without locks: none of it changes while the job runs.
	 * @return empty when the job does not exist
	 */
	Optional<RunBasis> findBasis(UUID jobId) {
		return jdbc.sql("""
				SELECT j.id, j.owner_id, j.request_id, j.photo_id, j.funding, j.manifest_id, j.input_etag,
				       j.input_sha256, q.consent_version, q.trial_mark_id, a.object_key,
				       COALESCE(i.taken_at, q.created_at) AS reference_at
				FROM analysis_job j
				JOIN analysis_request q ON q.id = j.request_id
				LEFT JOIN analysis_upload_attempt a ON a.job_id = j.id AND a.status = 'CONFIRMED'
				LEFT JOIN analysis_request_item i ON i.request_id = j.request_id AND i.photo_id = j.photo_id
				                                 AND i.analysis_job_id = j.id AND i.admission_result = 'ACCEPTED'
				WHERE j.id = :id
				""").param("id", jobId).query((rs, rowNum) -> new RunBasis(rs.getObject("id", UUID.class),
				rs.getObject("owner_id", UUID.class), rs.getObject("request_id", UUID.class),
				rs.getObject("photo_id", UUID.class), Funding.valueOf(rs.getString("funding")),
				rs.getString("manifest_id"), rs.getLong("consent_version"), rs.getObject("trial_mark_id", UUID.class),
				rs.getObject("reference_at", OffsetDateTime.class).toInstant(), rs.getString("object_key"),
				rs.getString("input_etag"), rs.getBytes("input_sha256")))
			.optional();
	}

	/**
	 * The photos the job analyzes for its own request: the {@code ACCEPTED} items, representative and members, which
	 * never change. Photos of other requests that wait for the job ({@code IN_PROGRESS}) are not among them.
	 */
	List<Member> findMembers(UUID requestId, UUID jobId) {
		return jdbc.sql("""
				SELECT photo_id, photo_version, basis_change_seq
				FROM analysis_request_item
				WHERE request_id = :request AND analysis_job_id = :job AND admission_result = 'ACCEPTED'
				ORDER BY photo_id
				""")
			.param("request", requestId)
			.param("job", jobId)
			.query((rs, rowNum) -> new Member(rs.getObject("photo_id", UUID.class), rs.getString("photo_version"),
					rs.getLong("basis_change_seq")))
			.list();
	}

	/**
	 * The call permitted for the run, read without locks; only its settlement changes it.
	 */
	Optional<CallRow> findCall(UUID jobId, UUID runToken) {
		return jdbc.sql(CALL + "WHERE job_id = :job AND run_token = :token")
			.param("job", jobId)
			.param("token", runToken)
			.query(RunRecords::call)
			.optional();
	}

	/**
	 * The call's status, read without locks. Every settlement locks the call's job first, so a caller holding that
	 * lock reads a status that stays until it commits.
	 */
	CallStatus callStatus(UUID callId) {
		return jdbc.sql("SELECT status FROM external_call WHERE id = :id")
			.param("id", callId)
			.query((rs, rowNum) -> CallStatus.valueOf(rs.getString("status")))
			.single();
	}

	/**
	 * Inserts a permitted call, {@code SENDING} with its maximum cost and risky sends reserved.
	 */
	void insertCall(CallRow call, String manifestId, byte[] inputSha256, String configFingerprint) {
		jdbc.sql("""
				INSERT INTO external_call (id, owner_id, job_id, run_token, manifest_id, funding, photo_id, input_sha256,
				                           budget_day, status, config_fingerprint, reserved_cost_micros, cost_state,
				                           send_state, device_mark_id, created_at)
				VALUES (:id, :owner, :job, :token, :manifest, :funding, :photo, :sha256, :day, 'SENDING', :fingerprint,
				        :reserved, 'RESERVED', 'RESERVED', :mark, :createdAt)
				""")
			.param("id", call.id())
			.param("owner", call.ownerId())
			.param("job", call.jobId())
			.param("token", call.runToken())
			.param("manifest", manifestId)
			.param("funding", call.funding().name())
			.param("photo", call.photoId())
			.param("sha256", inputSha256)
			.param("day", call.budgetDay())
			.param("fingerprint", configFingerprint)
			.param("reserved", call.reservedCostMicros())
			.param("mark", call.deviceMarkId(), Types.OTHER)
			.param("createdAt", timestamp(call.createdAt()))
			.update();
	}

	/**
	 * Settles a {@code SENDING} call once (design 2.1): its final status, what the provider reported, its cost and
	 * whether its risky sends are spent.
	 * @return {@code false}, with nothing changed, when the call was settled before
	 */
	boolean settleCall(UUID callId, CallOutcome outcome, Instant now) {
		ModelReply.Usage usage = outcome.usage();
		return jdbc.sql("""
				UPDATE external_call SET status = :status, http_status = :http, stop_reason = :stop,
				                         provider_request_id = :requestId, outcome = :outcome,
				                         input_tokens = :input, output_tokens = :output,
				                         cache_creation_tokens = :cacheCreation, cache_read_tokens = :cacheRead,
				                         settled_cost_micros = :cost, cost_state = 'SETTLED', send_state = :sendState,
				                         sent_at = :sentAt, responded_at = :respondedAt, settled_at = :now
				WHERE id = :id AND status = 'SENDING'
				""")
			.param("id", callId)
			.param("status", outcome.status().name())
			.param("http", outcome.httpStatus(), Types.INTEGER)
			.param("stop", outcome.stopReason(), Types.VARCHAR)
			.param("requestId", outcome.requestId(), Types.VARCHAR)
			.param("outcome", outcome.row())
			.param("input", (usage != null) ? usage.inputTokens() : null, Types.BIGINT)
			.param("output", (usage != null) ? usage.outputTokens() : null, Types.BIGINT)
			.param("cacheCreation", (usage != null) ? usage.cacheCreationInputTokens() : null, Types.BIGINT)
			.param("cacheRead", (usage != null) ? usage.cacheReadInputTokens() : null, Types.BIGINT)
			.param("cost", outcome.costMicros())
			.param("sendState", outcome.sendSpent() ? "SPENT" : "RELEASED")
			.param("sentAt", (outcome.sentAt() != null) ? timestamp(outcome.sentAt()) : null,
					Types.TIMESTAMP_WITH_TIMEZONE)
			.param("respondedAt", (outcome.respondedAt() != null) ? timestamp(outcome.respondedAt()) : null,
					Types.TIMESTAMP_WITH_TIMEZONE)
			.param("now", timestamp(now))
			.update() == 1;
	}

	/**
	 * Locks the principal's sends of the KST day.
	 * @return the sends reserved or spent so far
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	int lockDailySends(UUID ownerId, LocalDate day) {
		jdbc.sql("""
				INSERT INTO ai_send_daily (owner_id, day, send_count) VALUES (:owner, :day, 0)
				ON CONFLICT (owner_id, day) DO NOTHING
				""").param("owner", ownerId).param("day", day).update();
		return jdbc.sql("SELECT send_count FROM ai_send_daily WHERE owner_id = :owner AND day = :day FOR UPDATE")
			.param("owner", ownerId)
			.param("day", day)
			.query(Integer.class)
			.single();
	}

	/**
	 * Reserves ({@code +1}) or releases ({@code -1}) a send of the principal on the KST day of its permit.
	 */
	void addDailySend(UUID ownerId, LocalDate day, int delta) {
		jdbc.sql("""
				UPDATE ai_send_daily SET send_count = GREATEST(0, send_count + :delta)
				WHERE owner_id = :owner AND day = :day
				""").param("owner", ownerId).param("day", day).param("delta", delta).update();
	}

	/**
	 * Locks the photo's lifetime budget.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	PhotoBudget lockPhotoBudget(UUID photoId, UUID ownerId, Instant now) {
		jdbc.sql("""
				INSERT INTO photo_ai_budget (photo_id, owner_id, risk_sends, unknown_retry_used, input_invalid_count,
				                             updated_at, version)
				VALUES (:photo, :owner, 0, false, 0, :now, 0)
				ON CONFLICT (photo_id) DO NOTHING
				""").param("photo", photoId).param("owner", ownerId).param("now", timestamp(now)).update();
		return jdbc.sql("SELECT risk_sends, unknown_retry_used FROM photo_ai_budget WHERE photo_id = :photo FOR UPDATE")
			.param("photo", photoId)
			.query((rs, rowNum) -> new PhotoBudget(rs.getInt("risk_sends"), rs.getBoolean("unknown_retry_used")))
			.single();
	}

	/**
	 * Whether the photo has used its one automatic call again after an unknown outcome (design 2.6), read without
	 * locks. It changes only under the lock of the photo's live job, which a caller holding that lock keeps.
	 */
	boolean unknownRetryUsed(UUID photoId) {
		return jdbc.sql("SELECT unknown_retry_used FROM photo_ai_budget WHERE photo_id = :photo")
			.param("photo", photoId)
			.query(Boolean.class)
			.optional()
			.orElse(false);
	}

	/**
	 * Reserves ({@code +1}) or releases ({@code -1}) a risky send of the photo; {@code markUnknownRetry} also records
	 * that the photo used its call again after an unknown outcome.
	 */
	void changePhotoBudget(UUID photoId, int riskSendDelta, boolean markUnknownRetry, Instant now) {
		jdbc.sql("""
				UPDATE photo_ai_budget SET risk_sends = GREATEST(0, risk_sends + :delta),
				                           unknown_retry_used = unknown_retry_used OR :unknownRetry,
				                           updated_at = :now, version = version + 1
				WHERE photo_id = :photo
				""")
			.param("photo", photoId)
			.param("delta", riskSendDelta)
			.param("unknownRetry", markUnknownRetry)
			.param("now", timestamp(now))
			.update();
	}

	/**
	 * Counts one more invalid input of the photo; at {@code threshold} the photo is held back from intake until
	 * {@code blockedUntil}, a deadline that is never brought forward (design 2.7).
	 */
	void countInvalidInput(UUID photoId, UUID ownerId, int threshold, Instant blockedUntil, Instant now) {
		jdbc.sql("""
				INSERT INTO photo_ai_budget (photo_id, owner_id, risk_sends, unknown_retry_used, input_invalid_count,
				                             input_blocked_until, updated_at, version)
				VALUES (:photo, :owner, 0, false, 1, CASE WHEN 1 >= :threshold THEN CAST(:blockedUntil AS timestamptz) END,
				        :now, 0)
				ON CONFLICT (photo_id) DO UPDATE
				SET input_invalid_count = photo_ai_budget.input_invalid_count + 1,
				    input_blocked_until = CASE WHEN photo_ai_budget.input_invalid_count + 1 >= :threshold
				                               THEN GREATEST(photo_ai_budget.input_blocked_until, :blockedUntil)
				                               ELSE photo_ai_budget.input_blocked_until END,
				    updated_at = :now, version = photo_ai_budget.version + 1
				""")
			.param("photo", photoId)
			.param("owner", ownerId)
			.param("threshold", threshold)
			.param("blockedUntil", timestamp(blockedUntil))
			.param("now", timestamp(now))
			.update();
	}

	/**
	 * Locks the global spend of the KST day, created with {@code limitMicros} when missing.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	SpendDay lockSpendDay(LocalDate day, long limitMicros, Instant now) {
		jdbc.sql("""
				INSERT INTO ai_spend_day (day, limit_micros, reserved_micros, settled_micros, updated_at)
				VALUES (:day, :limit, 0, 0, :now)
				ON CONFLICT (day) DO NOTHING
				""").param("day", day).param("limit", limitMicros).param("now", timestamp(now)).update();
		return jdbc.sql("""
				SELECT limit_micros, reserved_micros, settled_micros FROM ai_spend_day WHERE day = :day FOR UPDATE
				""")
			.param("day", day)
			.query((rs, rowNum) -> new SpendDay(rs.getLong("limit_micros"), rs.getLong("reserved_micros"),
					rs.getLong("settled_micros")))
			.single();
	}

	void reserveSpend(LocalDate day, long micros, Instant now) {
		jdbc.sql("""
				UPDATE ai_spend_day SET reserved_micros = reserved_micros + :micros, updated_at = :now WHERE day = :day
				""").param("day", day).param("micros", micros).param("now", timestamp(now)).update();
	}

	/**
	 * Replaces a call's reserved maximum cost with its settled cost on the day of its permit. A day row that is gone
	 * is not created again.
	 */
	void settleSpend(LocalDate day, long reservedMicros, long settledMicros, Instant now) {
		jdbc.sql("""
				UPDATE ai_spend_day SET reserved_micros = GREATEST(0, reserved_micros - :reserved),
				                        settled_micros = settled_micros + :settled, updated_at = :now
				WHERE day = :day
				""")
			.param("day", day)
			.param("reserved", reservedMicros)
			.param("settled", settledMicros)
			.param("now", timestamp(now))
			.update();
	}

	/**
	 * Locks the request until the transaction ends ({@code FOR NO KEY UPDATE}: only {@code first_result_at} changes).
	 * @return its first result time, {@code null} before the first
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	@Nullable Instant lockRequest(UUID requestId) {
		OffsetDateTime firstResultAt = jdbc.sql("""
				SELECT first_result_at FROM analysis_request WHERE id = :id FOR NO KEY UPDATE
				""").param("id", requestId).query(OffsetDateTime.class).optional().orElse(null);
		return (firstResultAt != null) ? firstResultAt.toInstant() : null;
	}

	void markFirstResult(UUID requestId, Instant now) {
		jdbc.sql("""
				UPDATE analysis_request SET first_result_at = :now, updated_at = :now, version = version + 1
				WHERE id = :id AND first_result_at IS NULL
				""").param("id", requestId).param("now", timestamp(now)).update();
	}

	/**
	 * Inserts a result unless the photo has one; its change number is a placeholder until {@link #issueChangeSeq}.
	 * @return {@code false} when the photo already had a result ({@code uk_analysis_result_photo})
	 */
	boolean insertResult(NewResult result, Instant now) {
		return jdbc.sql("""
				INSERT INTO analysis_result (id, owner_id, photo_id, job_id, verification, photo_version,
				                             basis_change_seq, manifest_id, schema_version, output_locale, search_state,
				                             content, epoch, change_seq, created_at, updated_at, version)
				VALUES (:id, :owner, :photo, :job, :verification, :photoVersion, :basisChangeSeq, :manifest,
				        :schemaVersion, :locale, :searchState, CAST(:content AS jsonb), 1, 0, :now, :now, 0)
				ON CONFLICT (photo_id) DO NOTHING
				""")
			.param("id", result.id())
			.param("owner", result.ownerId())
			.param("photo", result.photoId())
			.param("job", result.jobId())
			.param("verification", result.verification().name())
			.param("photoVersion", result.photoVersion())
			.param("basisChangeSeq", result.basisChangeSeq())
			.param("manifest", result.manifestId())
			.param("schemaVersion", result.schemaVersion())
			.param("locale", result.outputLocale())
			.param("searchState", result.searchState().name())
			.param("content", result.content())
			.param("now", timestamp(now))
			.update() == 1;
	}

	void issueChangeSeq(UUID resultId, long changeSeq) {
		jdbc.sql("UPDATE analysis_result SET change_seq = :seq WHERE id = :id")
			.param("id", resultId)
			.param("seq", changeSeq)
			.update();
	}

	/**
	 * The owner's result of the photo with its current epoch.
	 */
	Optional<StoredResult> findResult(UUID ownerId, UUID photoId) {
		return jdbc.sql("""
				SELECT id, job_id, verification, photo_version, basis_change_seq, manifest_id, schema_version,
				       output_locale, search_state, content::text AS content, epoch, change_seq
				FROM analysis_result
				WHERE owner_id = :owner AND photo_id = :photo
				""")
			.param("owner", ownerId)
			.param("photo", photoId)
			.query((rs, rowNum) -> new StoredResult(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
					AnalysisResult.Verification.valueOf(rs.getString("verification")), rs.getString("photo_version"),
					rs.getLong("basis_change_seq"), rs.getString("manifest_id"), rs.getInt("schema_version"),
					rs.getString("output_locale"), AnalysisResult.SearchState.valueOf(rs.getString("search_state")),
					rs.getString("content"), rs.getInt("epoch"), rs.getLong("change_seq")))
			.optional();
	}

	private static CallRow call(ResultSet rs, int rowNum) throws SQLException {
		return new CallRow(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
				rs.getObject("job_id", UUID.class), rs.getObject("run_token", UUID.class),
				rs.getObject("photo_id", UUID.class), Funding.valueOf(rs.getString("funding")),
				rs.getObject("budget_day", LocalDate.class), rs.getLong("reserved_cost_micros"),
				rs.getObject("device_mark_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	/**
	 * @param consentVersion the consent basis of the job's request
	 * @param trialMarkId the trial mark of the requesting device, {@code null} without one
	 * @param referenceAt the validation reference of the result: the representative's capture time, else the intake
	 * time (design 2.5)
	 * @param objectKey the confirmed attempt's object, {@code null} without one
	 * @param inputEtag the ETag of the confirmed object
	 * @param inputSha256 the SHA-256 of the confirmed object, 32 bytes
	 */
	record RunBasis(UUID jobId, UUID ownerId, UUID requestId, UUID photoId, Funding funding, String manifestId,
			long consentVersion, @Nullable UUID trialMarkId, Instant referenceAt, @Nullable String objectKey,
			@Nullable String inputEtag, byte @Nullable [] inputSha256) {

		@Override
		public String toString() {
			return "RunBasis[jobId=" + jobId + ", funding=" + funding + "]";
		}

	}

	/**
	 * A photo of a job's own request, with the values its result copies.
	 */
	record Member(UUID photoId, String photoVersion, long basisChangeSeq) {

		@Override
		public String toString() {
			return "Member[]";
		}

	}

	/**
	 * A permitted call.
	 *
	 * @param photoId the job's representative photo
	 * @param budgetDay the KST day of the permit, whose rows count its send and cost
	 * @param reservedCostMicros the maximum cost reserved at permit
	 * @param deviceMarkId the trial mark whose sends count it, {@code null} for {@code PRO}
	 * @param createdAt when it was permitted
	 */
	record CallRow(UUID id, UUID ownerId, UUID jobId, UUID runToken, UUID photoId, Funding funding,
			LocalDate budgetDay, long reservedCostMicros, @Nullable UUID deviceMarkId, Instant createdAt) {

		@Override
		public String toString() {
			return "CallRow[id=" + id + ", jobId=" + jobId + ", funding=" + funding + "]";
		}

	}

	/**
	 * @param riskSends risky sends reserved or spent
	 * @param unknownRetryUsed whether the automatic call again after an unknown outcome is used
	 */
	record PhotoBudget(int riskSends, boolean unknownRetryUsed) {

	}

	record SpendDay(long limitMicros, long reservedMicros, long settledMicros) {

		/**
		 * Whether one more call of {@code maxCostMicros} fits.
		 */
		boolean fits(long maxCostMicros) {
			return settledMicros + reservedMicros + maxCostMicros <= limitMicros;
		}

		/**
		 * Whether the settled costs alone leave no room for the call, so that waiting for reservations to settle
		 * cannot help today.
		 */
		boolean spentBySettled(long maxCostMicros) {
			return settledMicros + maxCostMicros > limitMicros;
		}

	}

	/**
	 * @param content the normalized result as JSON text
	 */
	record NewResult(UUID id, UUID ownerId, UUID photoId, UUID jobId, AnalysisResult.Verification verification,
			String photoVersion, long basisChangeSeq, String manifestId, int schemaVersion, String outputLocale,
			AnalysisResult.SearchState searchState, String content) {

		@Override
		public String toString() {
			return "NewResult[id=" + id + ", verification=" + verification + "]";
		}

	}

	/**
	 * @param content the stored result as JSON text
	 */
	record StoredResult(UUID id, UUID jobId, AnalysisResult.Verification verification, String photoVersion,
			long basisChangeSeq, String manifestId, int schemaVersion, String outputLocale,
			AnalysisResult.SearchState searchState, String content, int epoch, long changeSeq) {

		@Override
		public String toString() {
			return "StoredResult[id=" + id + ", epoch=" + epoch + "]";
		}

	}

}
