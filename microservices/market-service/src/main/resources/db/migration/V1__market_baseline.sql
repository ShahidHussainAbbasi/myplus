-- MP-0b — market-service baseline: versioned marketplace policies, seller onboarding, the audit outbox.
-- Design: microservices/docs/platform-marketplace-design.md · slice: slices/mp-0-marketplace-foundation.md
--
-- Column types are written against the entities (STANDARDS §0 / D2): statuses are VARCHAR, never MySQL ENUM;
-- no TEXT/@Lob; money would be DECIMAL(19,2). MarketFlywayMigrationTest boots the service on this file under
-- ddl-auto=validate.

-- One row per VERSION of a marketplace policy (seller agreement, data sharing, commission, returns ...).
-- organization_id is the platform org: these are the operator's own documents.
-- published_slot = policy_type while PUBLISHED, NULL otherwise, so the UNIQUE key below lets the DATABASE hold
-- the rule "at most one published version per type" — two concurrent publishes cannot both win.
CREATE TABLE IF NOT EXISTS market_policy (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NOT NULL,
    policy_type      VARCHAR(32)   NOT NULL,
    version_no       INT           NOT NULL,
    title            VARCHAR(160)  NOT NULL,
    summary          VARCHAR(2000) NOT NULL,
    document_url     VARCHAR(500)  NULL,
    status           VARCHAR(16)   NOT NULL,
    published_slot   VARCHAR(32)   NULL,
    effective_from   DATE          NULL,
    published_at     DATETIME      NULL,
    published_by     BIGINT        NULL,
    created_by       BIGINT        NULL,
    created_at       DATETIME      NOT NULL,
    version          INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_market_policy_version (organization_id, policy_type, version_no),
    UNIQUE KEY uq_market_policy_published (organization_id, published_slot),
    KEY idx_market_policy_type (organization_id, policy_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One row per selling tenant. seller_organization_id is taken from the caller's token, never from a body; the
-- UNIQUE key makes "apply" an upsert, so a double-click cannot create two applications.
CREATE TABLE IF NOT EXISTS seller_profile (
    id                       BIGINT        NOT NULL AUTO_INCREMENT,
    seller_organization_id   BIGINT        NOT NULL,
    display_name             VARCHAR(120)  NOT NULL,
    contact_phone            VARCHAR(32)   NOT NULL,
    contact_email            VARCHAR(160)  NULL,
    city                     VARCHAR(80)   NOT NULL,
    pickup_address           VARCHAR(300)  NOT NULL,
    service_radius_km        INT           NOT NULL,
    status                   VARCHAR(20)   NOT NULL,
    status_reason            VARCHAR(255)  NULL,
    applied_by               BIGINT        NULL,
    applied_at               DATETIME      NOT NULL,
    reviewed_by              BIGINT        NULL,
    reviewed_at              DATETIME      NULL,
    created_at               DATETIME      NOT NULL,
    updated_at               DATETIME      NULL,
    version                  INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_seller_profile_org (seller_organization_id),
    KEY idx_seller_profile_status (status, applied_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Which policy VERSION a seller accepted, who accepted it, and when. Append-only: a re-application after a new
-- version adds rows; nothing is overwritten, so "what had this seller agreed to on date X" stays answerable.
CREATE TABLE IF NOT EXISTS seller_agreement (
    id                       BIGINT        NOT NULL AUTO_INCREMENT,
    seller_profile_id        BIGINT        NOT NULL,
    seller_organization_id   BIGINT        NOT NULL,
    policy_id                BIGINT        NOT NULL,
    policy_type              VARCHAR(32)   NOT NULL,
    version_no               INT           NOT NULL,
    accepted_by              BIGINT        NULL,
    accepted_at              DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_seller_agreement (seller_profile_id, policy_id),
    KEY idx_seller_agreement_org (seller_organization_id),
    CONSTRAINT fk_seller_agreement_profile FOREIGN KEY (seller_profile_id) REFERENCES seller_profile (id),
    CONSTRAINT fk_seller_agreement_policy FOREIGN KEY (policy_id) REFERENCES market_policy (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The shared audit trail's local outbox (common-audit AbstractAuditOutbox — lengths copied from the entity,
-- identical to expense-service V1).
CREATE TABLE IF NOT EXISTS audit_outbox (
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
