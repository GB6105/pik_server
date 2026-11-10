-- Photo observations and analysis intake (ERD groups "사진 연결" (photo) and "분석과 검색" (photo_vector, analysis),
-- photo and analysis modules, FR-I-01, FR-I-02, ADR-0022 #2·3·4, ADR-0024 #4-1·4-2, ADR-0030 #1,
-- ADR-0031 #1·2·4·10·14·18).
-- Stage 5-1 columns only: attempt, call, result and budget tables and the execution columns of analysis_job follow in
-- stage 5-2. FKs stay inside a module (ADR-0022 #3): owner_id (principal), device_id (device), trial_mark_id
-- (device_trial_mark) and the photo IDs and vector_id that analysis tables keep (photo, photo_vector) get no FK because
-- those tables belong to other modules.

-- Server row of a photo the client sent (photo module). client_ref is the API photoId (ADR-0022 #2).
-- observed_version is the last contentVersion seen; source_change_seq starts at 0 and grows by 1 only when an
-- observation differs from the one before (ADR-0031 #4). change_seq is the sync change number issued last in the
-- business transaction that created the row or raised source_change_seq; 0 is a placeholder that never commits.
-- version is the Pik data version and observations do not raise it.
CREATE TABLE photo
(
    id                UUID                     NOT NULL,
    owner_id          UUID                     NOT NULL,
    client_ref        UUID                     NOT NULL,
    observed_version  TEXT                     NOT NULL,
    source_change_seq BIGINT                   NOT NULL,
    change_seq        BIGINT                   NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT                   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_photo_owner_client_ref UNIQUE (owner_id, client_ref),
    CONSTRAINT ck_photo_observed_version CHECK (char_length(observed_version) BETWEEN 1 AND 128),
    CONSTRAINT ck_photo_source_change_seq CHECK (source_change_seq >= 0),
    CONSTRAINT ck_photo_change_seq CHECK (change_seq >= 0)
);

-- Immutable versions of the client vector of a photo (photo module). vector holds the received F32LE bytes before
-- normalization and content_sha256 their SHA-256. The same content is found again through the unique constraint, other
-- content becomes a new row, and rows never change. Intake items point at the version they grouped with (vector_id).
-- Every insert runs under the lock of its photo row, so the unique constraint only conflicts under that lock.
CREATE TABLE photo_vector
(
    id             UUID                     NOT NULL,
    photo_id       UUID                     NOT NULL,
    model_id       TEXT                     NOT NULL,
    model_version  TEXT                     NOT NULL,
    owner_id       UUID                     NOT NULL,
    encoding       TEXT                     NOT NULL,
    dims           INTEGER                  NOT NULL,
    vector         BYTEA                    NOT NULL,
    content_sha256 BYTEA                    NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_photo_vector_content UNIQUE (photo_id, model_id, model_version, content_sha256),
    CONSTRAINT fk_photo_vector_photo FOREIGN KEY (photo_id) REFERENCES photo (id),
    CONSTRAINT ck_photo_vector_model_id CHECK (char_length(model_id) BETWEEN 1 AND 64),
    CONSTRAINT ck_photo_vector_model_version CHECK (char_length(model_version) BETWEEN 1 AND 64),
    CONSTRAINT ck_photo_vector_encoding CHECK (encoding = 'F32LE'),
    CONSTRAINT ck_photo_vector_dims CHECK (dims > 0),
    CONSTRAINT ck_photo_vector_length CHECK (octet_length(vector) = dims * 4),
    CONSTRAINT ck_photo_vector_content_sha256 CHECK (octet_length(content_sha256) = 32)
);
CREATE INDEX ix_photo_vector_owner ON photo_vector (owner_id);

-- Grouping rule versions (analysis module). id is the SHA-256 (lowercase hex) of the canonical JSON of the rule
-- manifest; rows are inserted at startup when missing and never change.
CREATE TABLE analysis_grouping_rule
(
    id         TEXT                     NOT NULL,
    content    JSONB                    NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_analysis_grouping_rule_id CHECK (id ~ '^[0-9a-f]{64}$')
);

-- Call manifests fixed on jobs at intake (analysis module, ADR-0031 #14). id is the SHA-256 hex of the content; rows
-- never change. Only the upload policy has typed columns; stage 5-2 adds the columns it needs.
CREATE TABLE analysis_call_manifest
(
    id               TEXT                     NOT NULL,
    content          JSONB                    NOT NULL,
    content_type     TEXT                     NOT NULL,
    max_bytes        INTEGER                  NOT NULL,
    max_long_edge_px INTEGER                  NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_analysis_call_manifest_max_bytes CHECK (max_bytes > 0),
    CONSTRAINT ck_analysis_call_manifest_max_long_edge_px CHECK (max_long_edge_px > 0)
);

-- One intake request (analysis module). id is the jobId of the response. Server batches add rows of their own
-- (origin SERVER_BATCH, ADR-0024 #4-2); client requests name the requesting device. consent_version is the consent
-- basis the request ran under (ADR-0030 #1). The request status is computed from its jobs, not stored.
CREATE TABLE analysis_request
(
    id                   UUID                     NOT NULL,
    owner_id             UUID                     NOT NULL,
    origin               TEXT                     NOT NULL,
    trigger              TEXT                     NOT NULL,
    device_id            UUID,
    trial_mark_id        UUID,
    consent_version      BIGINT                   NOT NULL,
    grouping_rule_id     TEXT                     NOT NULL,
    photo_count          INTEGER                  NOT NULL,
    representative_count INTEGER                  NOT NULL,
    first_result_at      TIMESTAMP WITH TIME ZONE,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    version              BIGINT                   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_analysis_request_grouping_rule FOREIGN KEY (grouping_rule_id) REFERENCES analysis_grouping_rule (id),
    CONSTRAINT ck_analysis_request_origin CHECK (origin IN ('CLIENT', 'SERVER_BATCH')),
    CONSTRAINT ck_analysis_request_trigger CHECK (trigger IN ('NEW_PHOTOS', 'ORGANIZE', 'PRO_BACKLOG')),
    CONSTRAINT ck_analysis_request_device CHECK (origin <> 'CLIENT' OR device_id IS NOT NULL),
    CONSTRAINT ck_analysis_request_consent_version CHECK (consent_version >= 1),
    CONSTRAINT ck_analysis_request_photo_count CHECK (photo_count >= 1),
    CONSTRAINT ck_analysis_request_representative_count CHECK (representative_count >= 0)
);
CREATE INDEX ix_analysis_request_owner_created ON analysis_request (owner_id, created_at);

-- Groups formed within one intake request (analysis module, FR-I-02). Every candidate photo that went through grouping
-- gets one group (a single photo = size 1), whether its job was accepted or not. model_id and model_version name the
-- vector model the group was formed with and stay empty for a single photo. The persistent current group of a photo
-- (photo_group) follows in stage 9.
CREATE TABLE analysis_group
(
    id                      UUID                     NOT NULL,
    owner_id                UUID                     NOT NULL,
    request_id              UUID                     NOT NULL,
    representative_photo_id UUID                     NOT NULL,
    photo_count             INTEGER                  NOT NULL,
    model_id                TEXT,
    model_version           TEXT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_analysis_group_request FOREIGN KEY (request_id) REFERENCES analysis_request (id),
    CONSTRAINT ck_analysis_group_photo_count CHECK (photo_count >= 1)
);
CREATE INDEX ix_analysis_group_request ON analysis_group (request_id);

-- Lock order exception (design 0.3, consistency-rules). Intake inserts its request, group, job and item rows after the
-- usage reservation, which is later than their place in the lock order. This is the "insert that can only conflict
-- under a parent row already locked" exception: uk_analysis_job_photo_active can only conflict under the principal's
-- intake lock, which the transaction takes before photo.
-- Invariant: every path that inserts analysis_job (SERVER_BATCH from stage 5-2 included) takes the principal's intake
-- lock first.
-- Existing jobs: an IN_PROGRESS item may point at a job of another request. Its FK check takes KEY SHARE on that job
-- after the reservation, against the order analysis_job -> usage allocation (a cancel transaction locks the job, then
-- releases the reservation). Intake therefore locks those jobs after photo and before the reservation with
-- SELECT ... WHERE id IN (...) ORDER BY id FOR SHARE, so the FK checks on those jobs wait for nothing. FOR SHARE keeps
-- the rechecked job status until commit; claim (FOR NO KEY UPDATE SKIP LOCKED) skips such a job instead of waiting.
-- Rule for transactions that lock a job before the usage lock (stage 5-2 transitions and claim): FOR NO KEY UPDATE
-- [SKIP LOCKED] only, never FOR UPDATE, DELETE or an UPDATE of id or group_id, unless they hold the principal's intake
-- lock first. Under this rule an IN_PROGRESS item made from an AlreadyReserved reservation result (plan 5d), which
-- points at a job that was not pre-locked and so takes its KEY SHARE after the usage lock, cannot deadlock: KEY SHARE
-- does not conflict with FOR NO KEY UPDATE. Pinned by C-8 (AnalysisIntakeConcurrencyTests).
-- The other FK parents are new rows of the same transaction (analysis_request, analysis_group, new jobs), immutable
-- rows (analysis_call_manifest, analysis_grouping_rule) and photo rows already locked FOR UPDATE (photo_vector).
-- IN_PROGRESS items never point at an existing group (ck_analysis_request_item_in_progress).

-- Analysis job of one group, created AWAITING_UPLOAD at intake (analysis module). id is issued before the reservation.
-- photo_id is the representative photo and photo_version its version observed at intake. The status CHECK already
-- lists the states of stage 5-2, which adds the execution columns. At most one live job per owner and photo: any
-- status except FAILED, CANCELLED, INELIGIBLE; SUCCEEDED counts as live (ADR-0024 #4-1, ADR-0031 #18).
-- base_priority: NEW_PHOTOS 0, ORGANIZE 1, PRO_BACKLOG 2 (ADR-0031 #10). The upload policy comes from the manifest.
CREATE TABLE analysis_job
(
    id                  UUID                     NOT NULL,
    owner_id            UUID                     NOT NULL,
    request_id          UUID                     NOT NULL,
    group_id            UUID                     NOT NULL,
    photo_id            UUID                     NOT NULL,
    photo_version       TEXT                     NOT NULL,
    funding             TEXT                     NOT NULL,
    manifest_id         TEXT                     NOT NULL,
    status              TEXT                     NOT NULL,
    base_priority       SMALLINT                 NOT NULL,
    queued_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    upload_deadline     TIMESTAMP WITH TIME ZONE NOT NULL,
    processing_deadline TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT                   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_analysis_job_group UNIQUE (group_id),
    CONSTRAINT fk_analysis_job_request FOREIGN KEY (request_id) REFERENCES analysis_request (id),
    CONSTRAINT fk_analysis_job_group FOREIGN KEY (group_id) REFERENCES analysis_group (id),
    CONSTRAINT fk_analysis_job_manifest FOREIGN KEY (manifest_id) REFERENCES analysis_call_manifest (id),
    CONSTRAINT ck_analysis_job_funding CHECK (funding IN ('TRIAL', 'PRO')),
    CONSTRAINT ck_analysis_job_status CHECK (status IN ('AWAITING_UPLOAD', 'READY', 'RUNNING', 'SUCCEEDED', 'FAILED',
                                                        'CANCELLED', 'INELIGIBLE')),
    CONSTRAINT ck_analysis_job_base_priority CHECK (base_priority BETWEEN 0 AND 2)
);
CREATE UNIQUE INDEX uk_analysis_job_photo_active ON analysis_job (owner_id, photo_id)
    WHERE status NOT IN ('FAILED', 'CANCELLED', 'INELIGIBLE');
CREATE INDEX ix_analysis_job_request ON analysis_job (request_id);

-- Admission of each photo of a request (analysis module, immutable). photo_version, basis_change_seq, taken_at,
-- text_status and ocr_version are the values at intake, so grouping can be reproduced (ADR-0031 #4). client_ref is
-- the photoId of the response. ACCEPTED items point at the new job and group; IN_PROGRESS items point at the job in
-- progress, possibly of another request (see the lock order note above), and at no group. vector_id is the
-- photo_vector version grouping used; a vector that was ignored has a reason instead.
CREATE TABLE analysis_request_item
(
    request_id            UUID                     NOT NULL,
    photo_id              UUID                     NOT NULL,
    owner_id              UUID                     NOT NULL,
    client_ref            UUID                     NOT NULL,
    photo_version         TEXT                     NOT NULL,
    basis_change_seq      BIGINT                   NOT NULL,
    taken_at              TIMESTAMP WITH TIME ZONE,
    text_status           TEXT                     NOT NULL,
    ocr_version           TEXT,
    admission_result      TEXT                     NOT NULL,
    code                  TEXT,
    reason                TEXT,
    retryable             BOOLEAN                  NOT NULL,
    group_id              UUID,
    group_role            TEXT,
    analysis_job_id       UUID,
    vector_id             UUID,
    vector_ignored_reason TEXT,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (request_id, photo_id),
    CONSTRAINT fk_analysis_request_item_request FOREIGN KEY (request_id) REFERENCES analysis_request (id),
    CONSTRAINT fk_analysis_request_item_group FOREIGN KEY (group_id) REFERENCES analysis_group (id),
    CONSTRAINT fk_analysis_request_item_job FOREIGN KEY (analysis_job_id) REFERENCES analysis_job (id),
    CONSTRAINT ck_analysis_request_item_basis_change_seq CHECK (basis_change_seq >= 0),
    CONSTRAINT ck_analysis_request_item_text_status CHECK (text_status IN ('UNKNOWN', 'NO_TEXT', 'TEXT')),
    CONSTRAINT ck_analysis_request_item_admission_result CHECK (admission_result IN ('ACCEPTED', 'EXISTING_RESULT',
                                                                                     'IN_PROGRESS', 'NOT_ACCEPTED')),
    CONSTRAINT ck_analysis_request_item_group_role CHECK (group_role IN ('REPRESENTATIVE', 'MEMBER')),
    CONSTRAINT ck_analysis_request_item_vector_ignored_reason CHECK (vector_ignored_reason IN (
        'MALFORMED_BASE64', 'UNKNOWN_MODEL', 'BAD_DIMENSION', 'NOT_FINITE', 'ZERO_NORM')),
    CONSTRAINT ck_analysis_request_item_accepted CHECK (admission_result <> 'ACCEPTED'
        OR (analysis_job_id IS NOT NULL AND group_id IS NOT NULL AND group_role IS NOT NULL)),
    CONSTRAINT ck_analysis_request_item_in_progress CHECK (admission_result <> 'IN_PROGRESS'
        OR (analysis_job_id IS NOT NULL AND group_id IS NULL)),
    CONSTRAINT ck_analysis_request_item_not_accepted CHECK (admission_result <> 'NOT_ACCEPTED'
        OR (code IS NOT NULL AND reason IS NOT NULL)),
    CONSTRAINT ck_analysis_request_item_group CHECK ((group_role IS NULL) = (group_id IS NULL)),
    CONSTRAINT ck_analysis_request_item_vector CHECK (vector_id IS NULL OR vector_ignored_reason IS NULL)
);
CREATE INDEX ix_analysis_request_item_owner_photo ON analysis_request_item (owner_id, photo_id);
CREATE INDEX ix_analysis_request_item_job ON analysis_request_item (analysis_job_id);
CREATE INDEX ix_analysis_request_item_group ON analysis_request_item (group_id);
