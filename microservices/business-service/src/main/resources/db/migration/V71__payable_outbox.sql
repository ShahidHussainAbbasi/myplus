-- FP-2 — every change to a supplier purchase is reported to finance's payables subledger.
-- Design: microservices/docs/finance-payables-subledger-design.md · slices/fp-1-2-payables-in-finance.md
--
-- payable_outbox: one row per changed purchase, written in the SAME transaction as the purchase (before commit),
-- delivered after commit and retried. payload = the whole PayableSnapshot as JSON — never field-by-field columns
-- (the gl_outbox lesson). VARCHAR so ddl-auto=validate is trivially true.
CREATE TABLE IF NOT EXISTS payable_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NULL,
    user_id          BIGINT        NULL,
    purchase_id      BIGINT        NOT NULL,
    payload          VARCHAR(2000) NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts         INT           NOT NULL DEFAULT 0,
    last_error       VARCHAR(500)  NULL,
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    PRIMARY KEY (id),
    KEY idx_payable_outbox_status (status, id),
    KEY idx_payable_outbox_purchase (purchase_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The automatic backfill (ruling: all tenants): a tenant's existing supplier purchases are replayed into finance
-- once. The marker makes it run-once per tenant; finance's intake is idempotent, so a re-run would change nothing.
CREATE TABLE IF NOT EXISTS payable_backfill (
    organization_id  BIGINT        NOT NULL,
    documents        INT           NOT NULL DEFAULT 0,
    done_at          DATETIME      NULL,
    PRIMARY KEY (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
