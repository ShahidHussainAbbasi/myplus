-- E5 — catalog-service's audit outbox.
--
-- catalog-service is the FIRST consumer to adopt common-audit without having written its own copy first,
-- which is the test of whether extracting it at E4 was worth doing.
--
-- Why it needs one at all: `POST /clear-tracking-flags` lets a platform operator clear the serial or batch
-- policy on a customer's products, in bulk -- ONB-3's own gate does nineteen in one call. It is the single
-- most consequential thing the platform can do to a customer's records, and until now it was the one thing
-- nothing recorded anywhere. Of nine services only business, education and auth emitted audit events, and
-- E4 covered auth's CONTROL PLANE, not catalog's data.

CREATE TABLE audit_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT,

    action           VARCHAR(32)   NOT NULL,   -- CATALOG_POLICY_CLEARED | ...
    entity_type      VARCHAR(32)   NULL,       -- PRODUCT_POLICY
    entity_ref       VARCHAR(64)   NULL,       -- the capability whose policy was cleared
    amount           DECIMAL(19,2) NULL,       -- unused here; part of the shared column set
    details          VARCHAR(500)  NULL,       -- how many products, and which

    reason           VARCHAR(255)  NULL,       -- the support session's reason, carried onto the record
    before_value     VARCHAR(64)   NULL,
    after_value      VARCHAR(64)   NULL,

    actor_org_id     BIGINT        NULL,       -- the OPERATOR's org; organization_id below is the customer's
    actor_type       VARCHAR(24)   NULL,       -- MEMBER | PLATFORM_OPERATOR | SYSTEM
    actor_email      VARCHAR(160)  NULL,

    event_key        VARCHAR(64)   NULL,
    occurred_at      DATETIME      NULL,

    status           VARCHAR(20)   NOT NULL,   -- PENDING | POSTED | FAILED
    attempts         INT           NOT NULL,
    last_error       VARCHAR(500)  NULL,

    organization_id  BIGINT        NULL,       -- the SUBJECT tenant; delivery impersonates it
    user_id          BIGINT        NULL,

    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,

    PRIMARY KEY (id),
    KEY idx_audit_outbox_pending (status, id)
);
