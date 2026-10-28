-- Owning principals, anonymous installs and devices (ERD group "계정과 구독", ADR-0020, ADR-0023, ADR-0027).
-- account and refresh_token follow with login; device.trial_mark_id gets no FK because device_trial_mark belongs to
-- another module (ADR-0022 #3).

-- One row per owner of Pik data, for both accounts and anonymous installs. Kept as a minimal DELETED row after
-- account deletion so that late results can be rejected (FR-A-05).
CREATE TABLE principal
(
    id         UUID                     NOT NULL,
    kind       TEXT                     NOT NULL,
    status     TEXT                     NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL,
    PRIMARY KEY (id)
);

-- Install token of an anonymous principal, stored as a SHA-256 hash. It never expires and is exchanged for access
-- tokens until revoked.
CREATE TABLE anonymous_install
(
    principal_id     UUID                     NOT NULL,
    token_hash       TEXT                     NOT NULL,
    platform         TEXT                     NOT NULL,
    attestation_kind TEXT,
    attested_at      TIMESTAMP WITH TIME ZONE,
    last_seen_at     TIMESTAMP WITH TIME ZONE,
    revoked_at       TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (principal_id),
    CONSTRAINT uk_anonymous_install_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_anonymous_install_principal FOREIGN KEY (principal_id) REFERENCES principal (id)
);

-- Devices per principal. Logging in on an install adds a row under the account principal with the same install_id.
CREATE TABLE device
(
    id            UUID                     NOT NULL,
    principal_id  UUID                     NOT NULL,
    install_id    UUID                     NOT NULL,
    platform      TEXT                     NOT NULL,
    trial_mark_id UUID,
    app_version   TEXT,
    last_seen_at  TIMESTAMP WITH TIME ZONE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    version       BIGINT                   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_device_principal_install UNIQUE (principal_id, install_id),
    CONSTRAINT fk_device_principal FOREIGN KEY (principal_id) REFERENCES principal (id)
);
