-- Consent to external AI transfers (ERD group "계정과 구독", consent module, FR-O-02, ADR-0021 #5·6, ADR-0028).
-- principal_id gets no FK because principal belongs to the account module (ADR-0022 #3).

-- Decisions are only added; a principal's consent for a purpose is its row with the highest version. version is the
-- data version clients send back with their next decision, and the unique constraint rejects two decisions made on the
-- same version. device_id stays empty until requests identify the device (stage 4).
CREATE TABLE ai_consent
(
    id             UUID                     NOT NULL,
    principal_id   UUID                     NOT NULL,
    device_id      UUID,
    purpose        TEXT                     NOT NULL,
    decision       TEXT                     NOT NULL,
    notice_version INTEGER                  NOT NULL,
    version        BIGINT                   NOT NULL,
    decided_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ai_consent_principal_purpose_version UNIQUE (principal_id, purpose, version)
);
