-- Common infrastructure tables (ERD group "공통 인프라", ADR-0023, ADR-0026).

-- Change-request replay protection. Saved in the business transaction, so no in-progress rows exist.
CREATE TABLE idempotency_record
(
    owner_id        UUID                     NOT NULL,
    idempotency_key UUID                     NOT NULL,
    operation       TEXT                     NOT NULL,
    request_hash    TEXT                     NOT NULL,
    response_code   INT                      NOT NULL,
    response_body   JSONB                    NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (owner_id, idempotency_key)
);
CREATE INDEX ix_idempotency_record_expires_at ON idempotency_record (expires_at);

-- Per-owner change numbers issued in commit order (FR-A-02).
CREATE TABLE owner_change_counter
(
    owner_id UUID   NOT NULL,
    last_seq BIGINT NOT NULL,
    PRIMARY KEY (owner_id)
);

-- Spring Modulith 2.1.2 event publication registry, v2 schema-postgresql.sql as shipped.
CREATE TABLE event_publication
(
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);

-- ShedLock with usingDbTime() writes UTC wall-clock values, hence TIMESTAMP without time zone.
CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
