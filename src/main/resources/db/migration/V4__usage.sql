-- Free trial usage (ERD group "계정과 구독", usage module, FR-B-01, ADR-0020, ADR-0021 #3·8, ADR-0024 #1·6-2, ADR-0029).
-- principal_id, photo_id and job_id get no FK because their tables belong to other modules (ADR-0022 #3).

-- Quota of a principal for one period. The trial has one fixed period (ONCE). limit_count is the setting when the row
-- was created.
CREATE TABLE usage_quota
(
    principal_id   UUID                     NOT NULL,
    quota_kind     TEXT                     NOT NULL,
    period_key     TEXT                     NOT NULL,
    limit_count    INTEGER                  NOT NULL,
    reserved_count INTEGER                  NOT NULL,
    consumed_count INTEGER                  NOT NULL,
    guided_at      TIMESTAMP WITH TIME ZONE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    version        BIGINT                   NOT NULL,
    PRIMARY KEY (principal_id, quota_kind, period_key),
    CONSTRAINT ck_usage_quota_counts CHECK (reserved_count >= 0 AND consumed_count >= 0
        AND reserved_count + consumed_count <= limit_count)
);

-- Trial use of one physical device, kept after account deletion and without the device ID itself (ADR-0020). Android
-- marks are found again by the HMAC of the device ID; iOS installs get a new mark each, and the DeviceCheck bits tell
-- whether the device started a trial before.
CREATE TABLE device_trial_mark
(
    id                 UUID                     NOT NULL,
    platform           TEXT                     NOT NULL,
    device_key_hmac    BYTEA,
    key_version        INTEGER,
    device_check_state TEXT,
    limit_count        INTEGER                  NOT NULL,
    reserved_count     INTEGER                  NOT NULL,
    consumed_count     INTEGER                  NOT NULL,
    exhausted_at       TIMESTAMP WITH TIME ZONE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    version            BIGINT                   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_device_trial_mark_counts CHECK (reserved_count >= 0 AND consumed_count >= 0
        AND reserved_count + consumed_count <= limit_count),
    CONSTRAINT ck_device_trial_mark_platform CHECK (
        (platform = 'ANDROID' AND device_key_hmac IS NOT NULL AND key_version IS NOT NULL
            AND device_check_state IS NULL)
            OR (platform = 'IOS' AND device_key_hmac IS NULL AND key_version IS NULL))
);

CREATE UNIQUE INDEX uk_device_trial_mark_key ON device_trial_mark (platform, key_version, device_key_hmac)
    WHERE device_key_hmac IS NOT NULL;

-- One row per photo counted against a quota. A photo is counted once: a reservation that is not released blocks
-- another, whatever the photo version (ADR-0024 #4-1·6-2).
CREATE TABLE usage_reservation
(
    id            UUID                     NOT NULL,
    principal_id  UUID                     NOT NULL,
    quota_kind    TEXT                     NOT NULL,
    period_key    TEXT                     NOT NULL,
    photo_id      UUID                     NOT NULL,
    photo_version TEXT                     NOT NULL,
    job_id        UUID                     NOT NULL,
    trial_mark_id UUID,
    status        TEXT                     NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_usage_reservation_quota FOREIGN KEY (principal_id, quota_kind, period_key)
        REFERENCES usage_quota (principal_id, quota_kind, period_key),
    CONSTRAINT fk_usage_reservation_trial_mark FOREIGN KEY (trial_mark_id) REFERENCES device_trial_mark (id)
);

CREATE UNIQUE INDEX uk_usage_reservation_photo ON usage_reservation (principal_id, photo_id)
    WHERE status <> 'RELEASED';

CREATE INDEX ix_usage_reservation_job ON usage_reservation (principal_id, job_id);
