-- HMS S1 — clinical-service baseline (docs/hms-phase1-design.md). A new database owned by Flyway from V1.

-- A patient of one clinic (organisation). The PERSON is party-service's (party_id); the pharmacy CUSTOMER is
-- business-service's (customer_id). This row owns only what is clinical identity: the MRN and demographics.
--
-- ONE PATIENT PER PHONE (client rule, design §1): enforced HERE, by uq_patient_phone, never by a pre-check alone —
-- two front desks typing the same number at once both pass any SELECT, and only the index refuses the second.
-- phone_key is PartyKeys.phoneKey (last 10 digits), so 0300-1234567, 03001234567 and +923001234567 are one number.
-- family_seq is 0 for the phone's holder; 1, 2 … only when the clinic switched on "family on one phone".
CREATE TABLE patient (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NOT NULL,
    mrn              VARCHAR(32)   NOT NULL,
    phone            VARCHAR(20)   NOT NULL,
    phone_key        VARCHAR(10)   NOT NULL,
    family_seq       INT           NOT NULL DEFAULT 0,
    name             VARCHAR(120)  NOT NULL,
    cnic             VARCHAR(15)   NULL,
    date_of_birth    DATE          NULL,
    sex              VARCHAR(1)    NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    party_id         BIGINT        NULL,
    customer_id      BIGINT        NULL,
    created_by       BIGINT        NULL,
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_patient_phone (organization_id, phone_key, family_seq),
    UNIQUE KEY uq_patient_mrn (organization_id, mrn),
    KEY idx_patient_party (organization_id, party_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Per-tenant clinic settings (the shared common-settings engine). Same shape as every service's org_setting.
CREATE TABLE org_setting (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NOT NULL,
    user_id          BIGINT       NULL,
    setting_key      VARCHAR(100) NOT NULL,
    setting_value    VARCHAR(500) NULL,
    updated          DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_org_setting (organization_id, setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Per-org document counters (common-docnum): the MRN sequence.
CREATE TABLE org_document_seq (
    organization_id  BIGINT        NOT NULL,
    doc_type         VARCHAR(16)   NOT NULL,
    next_val         BIGINT        NOT NULL DEFAULT 0,
    updated          DATETIME      DEFAULT NULL,
    PRIMARY KEY (organization_id, doc_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The shared audit trail's local outbox (common-audit AbstractAuditOutbox — lengths copied from the entity).
CREATE TABLE audit_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    action           VARCHAR(32)   NOT NULL,
    entity_type      VARCHAR(32)   NULL,
    entity_ref       VARCHAR(64)   NULL,
    amount           DECIMAL(19,2) NULL,
    details          VARCHAR(500)  NULL,
    reason           VARCHAR(255)  NULL,
    before_value     VARCHAR(64)   NULL,
    after_value      VARCHAR(64)   NULL,
    actor_org_id     BIGINT        NULL,
    actor_type       VARCHAR(24)   NULL,
    actor_email      VARCHAR(160)  NULL,
    event_key        VARCHAR(64)   NULL,
    occurred_at      DATETIME      NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts         INT           NOT NULL DEFAULT 0,
    last_error       VARCHAR(500)  NULL,
    organization_id  BIGINT        NULL,
    user_id          BIGINT        NULL,
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    PRIMARY KEY (id),
    KEY idx_audit_outbox_pending (status, id),
    KEY idx_audit_outbox_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
