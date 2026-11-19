-- Analysis upload, run, result and settlement (ERD group "분석과 검색", analysis module, plus send_count of the usage
-- module, FR-I-03, FR-I-01, FR-I-02, ADR-0022 #3, ADR-0024 #6-3, ADR-0035).
-- FKs stay inside a module (ADR-0022 #3): owner_id (principal), photo_id (photo) and device_mark_id
-- (device_trial_mark) get no FK. Inside the module, reused_result_id gets none because results and items have separate
-- retention (K-10), and ai_gate.probe_job_id none because every path that discards a run token clears its probe.
-- Money is BIGINT microdollars.
-- Send counters (ai_send_daily.send_count, device_trial_mark.send_count, photo_ai_budget.risk_sends) go up by 1 at
-- reservation and down by 1 at release; a spent send stays counted, and send_state of the call row tells the two apart.

-- Lock order (design 0.3 as revised for stage 5-2, ADR-0035): idempotency key -> consent -> principal intake ->
-- principal events -> global claim -> photo -> analysis_request -> analysis_job -> analysis_upload_attempt ->
-- analysis_input_rate -> external_call -> ai_send_daily -> usage allocation -> device_trial_mark -> photo_ai_budget ->
-- ai_spend_day -> ai_gate -> generated rows -> photo_action and event -> owner_change_counter.
-- ai_send_daily comes before the usage allocation, so that the completion transaction locks the allocation and the
-- device mark in one usage.settle call. device_trial_mark holds the trial usage and send_count: locking the row again
-- in the same transaction does not break the order.
-- Rows of one table: analysis_input_rate PRINCIPAL before TRIAL_MARK, ai_gate BUDGET -> CONFIG_CIRCUIT ->
-- TRANSIENT_CIRCUIT (only the causes a transaction needs). Both orders are the text order of the key, so ORDER BY
-- subject_kind or cause gives them.
-- FK parents: every transaction that inserts an attempt, call or result row has locked its job first, so the KEY SHARE
-- of the FK check waits for nothing. analysis_call_manifest rows are immutable.

-- Execution columns of analysis_job. The confirmed attempt is found through uk_upload_attempt_job_confirmed; the job
-- keeps no reference to its attempts, so the retention order below has no cycle. input_sha256 and input_etag are
-- copied from the confirmed attempt and stay after the attempt row is deleted. representative_client_ref is the API
-- photoId of the representative photo, so that the job is still named after its items or photo row are deleted. A job
-- that ends has ended_at; FAILED, CANCELLED and INELIGIBLE also name the reason. probe_excluded_until keeps a job that
-- lost the probe without a send from being picked as the probe again (plan 5.10).
ALTER TABLE analysis_job
    ADD COLUMN attempt_count             INTEGER                  NOT NULL DEFAULT 0,
    ADD COLUMN run_token                 UUID,
    ADD COLUMN locked_until              TIMESTAMP WITH TIME ZONE,
    ADD COLUMN next_attempt_at           TIMESTAMP WITH TIME ZONE,
    ADD COLUMN probe_excluded_until      TIMESTAMP WITH TIME ZONE,
    ADD COLUMN issued_count              INTEGER                  NOT NULL DEFAULT 0,
    ADD COLUMN input_sha256              BYTEA,
    ADD COLUMN input_etag                TEXT,
    ADD COLUMN representative_client_ref UUID,
    ADD COLUMN failure_code              TEXT,
    ADD COLUMN ended_at                  TIMESTAMP WITH TIME ZONE;

-- Stage 5-1 jobs: the client_ref of the photo row, else the one of the representative ACCEPTED item of the request
-- that created the job. Intake inserts that item with the job, so every job finds one of the two.
UPDATE analysis_job j
SET representative_client_ref = p.client_ref
FROM photo p
WHERE p.id = j.photo_id
  AND p.owner_id = j.owner_id;

UPDATE analysis_job j
SET representative_client_ref = i.client_ref
FROM analysis_request_item i
WHERE j.representative_client_ref IS NULL
  AND i.request_id = j.request_id
  AND i.photo_id = j.photo_id
  AND i.analysis_job_id = j.id
  AND i.admission_result = 'ACCEPTED';

ALTER TABLE analysis_job
    ALTER COLUMN representative_client_ref SET NOT NULL,
    ADD CONSTRAINT ck_analysis_job_attempt_count CHECK (attempt_count >= 0),
    ADD CONSTRAINT ck_analysis_job_issued_count CHECK (issued_count BETWEEN 0 AND 5),
    ADD CONSTRAINT ck_analysis_job_input_sha256 CHECK (octet_length(input_sha256) = 32),
    ADD CONSTRAINT ck_analysis_job_running CHECK (status <> 'RUNNING'
        OR (run_token IS NOT NULL AND locked_until IS NOT NULL)),
    ADD CONSTRAINT ck_analysis_job_ready CHECK (status <> 'READY'
        OR (next_attempt_at IS NOT NULL AND input_sha256 IS NOT NULL)),
    ADD CONSTRAINT ck_analysis_job_awaiting_upload CHECK (status <> 'AWAITING_UPLOAD' OR input_sha256 IS NULL),
    ADD CONSTRAINT ck_analysis_job_failure_code CHECK (
        (status IN ('FAILED', 'CANCELLED', 'INELIGIBLE')) = (failure_code IS NOT NULL)),
    ADD CONSTRAINT ck_analysis_job_failure_code_value CHECK (failure_code IN (
        'UPLOAD_ATTEMPTS_EXHAUSTED', 'UPLOAD_EXPIRED', 'PROCESSING_EXPIRED', 'CONSENT_WITHDRAWN', 'PRINCIPAL_INACTIVE',
        'REPRESENTATIVE_DELETED', 'INPUT_CHANGED', 'INPUT_MISSING', 'INPUT_INVALID', 'REFUSAL', 'TRUNCATED',
        'INVALID_OUTPUT', 'PROVIDER_REJECTED', 'UNKNOWN_OUTCOME', 'ATTEMPTS_EXHAUSTED', 'DEVICE_BUDGET_EXHAUSTED',
        'RESULT_CONFLICT')),
    ADD CONSTRAINT ck_analysis_job_ended_at CHECK (
        (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'INELIGIBLE')) = (ended_at IS NOT NULL));

-- Claim candidates (design 4.3), running jobs per owner and expired leases, and the upload and processing deadlines.
CREATE INDEX ix_analysis_job_ready ON analysis_job (next_attempt_at, base_priority, queued_at)
    WHERE status = 'READY';
CREATE INDEX ix_analysis_job_running_owner ON analysis_job (owner_id) WHERE status = 'RUNNING';
CREATE INDEX ix_analysis_job_running_lease ON analysis_job (locked_until) WHERE status = 'RUNNING';
CREATE INDEX ix_analysis_job_awaiting ON analysis_job (upload_deadline) WHERE status = 'AWAITING_UPLOAD';
CREATE INDEX ix_analysis_job_open_deadline ON analysis_job (processing_deadline)
    WHERE status IN ('AWAITING_UPLOAD', 'READY', 'RUNNING');

-- Presigned upload attempts of a job (analysis module). object_key is analysis-input/{jobId}/{attemptId} (K-3).
-- url_expires_at is fixed at issue. A job has at most one ISSUED and one CONFIRMED attempt. object_state: RETAINED
-- (kept), PENDING (delete from delete_after), DELETE_SENT (a delete succeeded after the URL expired; the bucket
-- lifecycle is the final guarantee that the object is gone).
-- Retention (plan 2.9, later stage): attempts are deleted first (DELETE_SENT, every job of the request ended, no
-- unsettled call, + 90 days), then external_call (90 days), then ended jobs, items and requests (90 days; a SUCCEEDED
-- job that a result points at stays with its request).
CREATE TABLE analysis_upload_attempt
(
    id             UUID                     NOT NULL,
    owner_id       UUID                     NOT NULL,
    job_id         UUID                     NOT NULL,
    status         TEXT                     NOT NULL,
    object_key     TEXT                     NOT NULL,
    content_length BIGINT                   NOT NULL,
    sha256         BYTEA                    NOT NULL,
    url_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at   TIMESTAMP WITH TIME ZONE,
    etag           TEXT,
    last_modified  TIMESTAMP WITH TIME ZONE,
    reject_reason  TEXT,
    object_state   TEXT                     NOT NULL,
    delete_after   TIMESTAMP WITH TIME ZONE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    version        BIGINT                   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_upload_attempt_object_key UNIQUE (object_key),
    CONSTRAINT fk_upload_attempt_job FOREIGN KEY (job_id) REFERENCES analysis_job (id),
    CONSTRAINT ck_upload_attempt_status CHECK (status IN ('ISSUED', 'CONFIRMED', 'REJECTED', 'SUPERSEDED',
                                                          'ABANDONED')),
    CONSTRAINT ck_upload_attempt_content_length CHECK (content_length > 0),
    CONSTRAINT ck_upload_attempt_sha256 CHECK (octet_length(sha256) = 32),
    CONSTRAINT ck_upload_attempt_reject_reason CHECK (reject_reason IN ('SIZE_MISMATCH', 'CHECKSUM_MISMATCH',
                                                                        'CONTENT_TYPE_MISMATCH', 'LATE_UPLOAD')),
    CONSTRAINT ck_upload_attempt_object_state CHECK (object_state IN ('RETAINED', 'PENDING', 'DELETE_SENT')),
    CONSTRAINT ck_upload_attempt_delete_after CHECK ((object_state = 'PENDING') = (delete_after IS NOT NULL))
);
CREATE UNIQUE INDEX uk_upload_attempt_job_issued ON analysis_upload_attempt (job_id) WHERE status = 'ISSUED';
CREATE UNIQUE INDEX uk_upload_attempt_job_confirmed ON analysis_upload_attempt (job_id) WHERE status = 'CONFIRMED';
CREATE INDEX ix_upload_attempt_job ON analysis_upload_attempt (job_id);
CREATE INDEX ix_upload_attempt_delete ON analysis_upload_attempt (delete_after) WHERE object_state = 'PENDING';

-- One Claude call permitted for a run of a job (analysis module). The row is inserted SENDING in the permit
-- transaction, whose commit is the send boundary; (job_id, run_token) finds an earlier permit. budget_day is the KST
-- day of the permit, which names the ai_spend_day row of the cost. config_fingerprint is the setting fingerprint at
-- permit (claude.key-version and the hash of the runnable manifests), so that the 400 rate counts only calls of one
-- setting. outcome names the settlement table row. A NOT_SENT call costs nothing and releases its risk send; only an
-- UNKNOWN call is adjusted by a late response. device_mark_id is set for TRIAL calls only.
CREATE TABLE external_call
(
    id                    UUID                     NOT NULL,
    owner_id              UUID                     NOT NULL,
    job_id                UUID                     NOT NULL,
    run_token             UUID                     NOT NULL,
    manifest_id           TEXT                     NOT NULL,
    funding               TEXT                     NOT NULL,
    photo_id              UUID                     NOT NULL,
    input_sha256          BYTEA                    NOT NULL,
    budget_day            DATE                     NOT NULL,
    status                TEXT                     NOT NULL,
    http_status           INTEGER,
    stop_reason           TEXT,
    provider_request_id   TEXT,
    outcome               TEXT,
    config_fingerprint    TEXT                     NOT NULL,
    input_tokens          BIGINT,
    output_tokens         BIGINT,
    cache_creation_tokens BIGINT,
    cache_read_tokens     BIGINT,
    reserved_cost_micros  BIGINT                   NOT NULL,
    settled_cost_micros   BIGINT,
    cost_state            TEXT                     NOT NULL,
    send_state            TEXT                     NOT NULL,
    device_mark_id        UUID,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at               TIMESTAMP WITH TIME ZONE,
    responded_at          TIMESTAMP WITH TIME ZONE,
    settled_at            TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    CONSTRAINT uk_external_call_job_run_token UNIQUE (job_id, run_token),
    CONSTRAINT fk_external_call_job FOREIGN KEY (job_id) REFERENCES analysis_job (id),
    CONSTRAINT fk_external_call_manifest FOREIGN KEY (manifest_id) REFERENCES analysis_call_manifest (id),
    CONSTRAINT ck_external_call_funding CHECK (funding IN ('TRIAL', 'PRO')),
    CONSTRAINT ck_external_call_input_sha256 CHECK (octet_length(input_sha256) = 32),
    CONSTRAINT ck_external_call_status CHECK (status IN ('SENDING', 'SUCCEEDED', 'RESPONDED_FAILURE', 'UNKNOWN',
                                                         'NOT_SENT')),
    CONSTRAINT ck_external_call_cost_state CHECK (cost_state IN ('RESERVED', 'SETTLED', 'ADJUSTED')),
    CONSTRAINT ck_external_call_send_state CHECK (send_state IN ('RESERVED', 'SPENT', 'RELEASED')),
    CONSTRAINT ck_external_call_reserved_cost CHECK (reserved_cost_micros > 0),
    CONSTRAINT ck_external_call_settled_cost CHECK (settled_cost_micros >= 0),
    CONSTRAINT ck_external_call_device_mark CHECK (device_mark_id IS NULL OR funding = 'TRIAL'),
    CONSTRAINT ck_external_call_sending CHECK (
        (status = 'SENDING') = (cost_state = 'RESERVED' AND send_state = 'RESERVED')),
    CONSTRAINT ck_external_call_not_sent CHECK (status <> 'NOT_SENT'
        OR (settled_cost_micros IS NOT DISTINCT FROM 0 AND send_state = 'RELEASED')),
    CONSTRAINT ck_external_call_adjusted CHECK (cost_state <> 'ADJUSTED' OR status = 'UNKNOWN')
);
-- Numerator and denominator of the 400 rate window, and calls still in flight.
CREATE INDEX ix_external_call_responded ON external_call (config_fingerprint, responded_at)
    WHERE status <> 'SENDING';
CREATE INDEX ix_external_call_sending ON external_call (job_id) WHERE status = 'SENDING';

-- Analysis result of a photo (analysis module), one per photo (K-9). The representative gets DIRECT, the other photos
-- of the group INHERITED; both keep the job that ran (ADR-0024 #6-3). photo_version and basis_change_seq are copied
-- from the photo's own item. change_seq is issued by owner_change_counter in the same transaction. A conflict on
-- uk_analysis_result_photo breaks an invariant (EXISTING_RESULT and uk_analysis_job_photo_active keep one live job
-- per photo), and the completion transaction rolls back on it (K-21).
CREATE TABLE analysis_result
(
    id               UUID                     NOT NULL,
    owner_id         UUID                     NOT NULL,
    photo_id         UUID                     NOT NULL,
    job_id           UUID                     NOT NULL,
    verification     TEXT                     NOT NULL,
    photo_version    TEXT                     NOT NULL,
    basis_change_seq BIGINT                   NOT NULL,
    manifest_id      TEXT                     NOT NULL,
    schema_version   INTEGER                  NOT NULL,
    output_locale    TEXT                     NOT NULL,
    search_state     TEXT                     NOT NULL,
    content          JSONB                    NOT NULL,
    epoch            INTEGER                  NOT NULL DEFAULT 1,
    change_seq       BIGINT                   NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    version          BIGINT                   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_analysis_result_photo UNIQUE (photo_id),
    CONSTRAINT fk_analysis_result_job FOREIGN KEY (job_id) REFERENCES analysis_job (id),
    CONSTRAINT fk_analysis_result_manifest FOREIGN KEY (manifest_id) REFERENCES analysis_call_manifest (id),
    CONSTRAINT ck_analysis_result_verification CHECK (verification IN ('DIRECT', 'INHERITED')),
    CONSTRAINT ck_analysis_result_search_state CHECK (search_state IN ('AVAILABLE', 'EMPTY', 'DISCARDED')),
    CONSTRAINT ck_analysis_result_schema_version CHECK (schema_version >= 1),
    CONSTRAINT ck_analysis_result_epoch CHECK (epoch >= 1),
    CONSTRAINT ck_analysis_result_basis_change_seq CHECK (basis_change_seq >= 0),
    CONSTRAINT ck_analysis_result_change_seq CHECK (change_seq >= 0)
);
CREATE INDEX ix_analysis_result_job ON analysis_result (job_id);

-- Lifetime AI budget of a photo (analysis module). risk_sends counts risk sends reserved or spent; unknown_retry_used
-- tells that the one automatic call again after an unknown outcome is used (D-26). input_invalid_count and
-- input_blocked_until hold back a photo whose input keeps failing. The limits are settings.
CREATE TABLE photo_ai_budget
(
    photo_id            UUID                     NOT NULL,
    owner_id            UUID                     NOT NULL,
    risk_sends          INTEGER                  NOT NULL,
    unknown_retry_used  BOOLEAN                  NOT NULL,
    input_invalid_count INTEGER                  NOT NULL,
    input_blocked_until TIMESTAMP WITH TIME ZONE,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT                   NOT NULL,
    PRIMARY KEY (photo_id),
    CONSTRAINT ck_photo_ai_budget_risk_sends CHECK (risk_sends >= 0),
    CONSTRAINT ck_photo_ai_budget_input_invalid_count CHECK (input_invalid_count >= 0)
);

-- Global AI spend of one KST day (analysis module). limit_micros is the setting of the day; a permit reserves the
-- maximum cost of the call and settlement replaces it with the cost of the call.
CREATE TABLE ai_spend_day
(
    day             DATE                     NOT NULL,
    limit_micros    BIGINT                   NOT NULL,
    reserved_micros BIGINT                   NOT NULL,
    settled_micros  BIGINT                   NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (day),
    CONSTRAINT ck_ai_spend_day_micros CHECK (limit_micros >= 0 AND reserved_micros >= 0 AND settled_micros >= 0)
);

-- AI sends of a principal on one KST day (analysis module).
CREATE TABLE ai_send_daily
(
    owner_id   UUID    NOT NULL,
    day        DATE    NOT NULL,
    send_count INTEGER NOT NULL,
    PRIMARY KEY (owner_id, day),
    CONSTRAINT ck_ai_send_daily_send_count CHECK (send_count >= 0)
);

-- Gates in front of every Claude call, one row per cause (analysis module, plan 5.10). Every state transition
-- compares generation with the one observed, raises it by 1 and clears the probe. Setting the probe (the job and run
-- token that may pass a HALF_OPEN circuit) does not raise generation. BUDGET is only CLOSED or OPEN. reset_at is the
-- last transition to CLOSED and the lower bound of the 400 rate sample; opened_at stays NULL until the first opening.
-- Lock order: BUDGET -> CONFIG_CIRCUIT -> TRANSIENT_CIRCUIT (see the top of this file).
CREATE TABLE ai_gate
(
    cause           TEXT                     NOT NULL,
    state           TEXT                     NOT NULL,
    generation      BIGINT                   NOT NULL,
    blocked_until   TIMESTAMP WITH TIME ZONE,
    probe_job_id    UUID,
    probe_run_token UUID,
    config_version  TEXT,
    reset_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    failure_streak  INTEGER                  NOT NULL,
    opened_at       TIMESTAMP WITH TIME ZONE,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (cause),
    CONSTRAINT ck_ai_gate_cause CHECK (cause IN ('BUDGET', 'CONFIG_CIRCUIT', 'TRANSIENT_CIRCUIT')),
    CONSTRAINT ck_ai_gate_state CHECK (state IN ('CLOSED', 'OPEN', 'HALF_OPEN')),
    CONSTRAINT ck_ai_gate_budget_state CHECK (cause <> 'BUDGET' OR state IN ('CLOSED', 'OPEN')),
    CONSTRAINT ck_ai_gate_generation CHECK (generation >= 0),
    CONSTRAINT ck_ai_gate_failure_streak CHECK (failure_streak >= 0),
    CONSTRAINT ck_ai_gate_probe CHECK ((probe_job_id IS NULL) = (probe_run_token IS NULL))
);

INSERT INTO ai_gate (cause, state, generation, reset_at, failure_streak, updated_at)
VALUES ('BUDGET', 'CLOSED', 0, now(), 0, now()),
       ('CONFIG_CIRCUIT', 'CLOSED', 0, now(), 0, now()),
       ('TRANSIENT_CIRCUIT', 'CLOSED', 0, now(), 0, now());

-- Input rate of a principal or a trial mark per window (analysis module). Windows older than the cleanup age are
-- deleted by window_start.
CREATE TABLE analysis_input_rate
(
    subject_kind TEXT                     NOT NULL,
    subject_id   UUID                     NOT NULL,
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    items        INTEGER                  NOT NULL,
    bytes        BIGINT                   NOT NULL,
    PRIMARY KEY (subject_kind, subject_id, window_start),
    CONSTRAINT ck_analysis_input_rate_subject_kind CHECK (subject_kind IN ('PRINCIPAL', 'TRIAL_MARK')),
    CONSTRAINT ck_analysis_input_rate_counts CHECK (items >= 0 AND bytes >= 0)
);
CREATE INDEX ix_analysis_input_rate_window ON analysis_input_rate (window_start);

-- Intake items: an EXISTING_RESULT item names the result it reused and that result's photo version; retry_at is the
-- retryAfter of a NOT_ACCEPTED item (INPUT_RETRY_LATER).
ALTER TABLE analysis_request_item
    ADD COLUMN reused_result_id     UUID,
    ADD COLUMN reused_photo_version TEXT,
    ADD COLUMN retry_at             TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT ck_analysis_request_item_existing_result CHECK (
        (admission_result = 'EXISTING_RESULT') = (reused_result_id IS NOT NULL)
            AND (reused_result_id IS NULL) = (reused_photo_version IS NULL)),
    ADD CONSTRAINT ck_analysis_request_item_retry_at CHECK (retry_at IS NULL OR admission_result = 'NOT_ACCEPTED');

-- AI sends of a trial device (usage module, design 4.3). The limit is the trial limit times a setting.
ALTER TABLE device_trial_mark
    ADD COLUMN send_count INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_device_trial_mark_send_count CHECK (send_count >= 0);

-- Call limits of a manifest. Stage 5-1 rows keep NULL and cannot run (K-12); a manifest that runs sets all three.
ALTER TABLE analysis_call_manifest
    ADD COLUMN max_pixels      BIGINT,
    ADD COLUMN max_tokens      INTEGER,
    ADD COLUMN max_cost_micros BIGINT,
    ADD CONSTRAINT ck_analysis_call_manifest_max_pixels CHECK (max_pixels > 0),
    ADD CONSTRAINT ck_analysis_call_manifest_max_tokens CHECK (max_tokens > 0),
    ADD CONSTRAINT ck_analysis_call_manifest_max_cost_micros CHECK (max_cost_micros > 0),
    ADD CONSTRAINT ck_analysis_call_manifest_limits CHECK (
        (max_pixels IS NULL) = (max_tokens IS NULL) AND (max_tokens IS NULL) = (max_cost_micros IS NULL));
