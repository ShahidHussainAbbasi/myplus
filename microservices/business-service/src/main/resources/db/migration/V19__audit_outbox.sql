-- Audit #6: business-service is a producer for the standalone audit-service. Events are captured here in the SAME tx
-- as the business change (atomic — never lost, never a rolled-back event), then delivered to audit-service
-- asynchronously + reliably (AFTER_COMMIT + @Scheduled relay). Mirrors gl_outbox (#4).

CREATE TABLE audit_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    action           VARCHAR(32)   NOT NULL,
    entity_type      VARCHAR(32)   NULL,
    entity_ref       VARCHAR(64)   NULL,
    amount           DECIMAL(19,2) NULL,
    details          VARCHAR(500)  NULL,
    event_key        VARCHAR(64)   NULL,
    occurred_at      DATETIME      NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',   -- PENDING | POSTED | FAILED
    attempts         INT           NOT NULL DEFAULT 0,
    last_error       VARCHAR(500)  NULL,
    organization_id  BIGINT        NULL,
    user_id          BIGINT        NULL,
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    PRIMARY KEY (id),
    KEY idx_audit_outbox_pending (status, id)
);
