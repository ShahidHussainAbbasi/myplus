-- EX-3 — a till pay-out reaches the books (through expense-service).
-- Design: microservices/docs/slices/ex-3-till-pay-outs.md
--
-- 1. cash_movement learns three things:
--      category_id        the expense category the cashier chose (only for a PAY_OUT with Expense management on)
--      idempotency_key    one per form submission; UNIQUE per org, so a double click or a retry replays the first
--                         movement instead of taking the cash out twice (the /cashMovement endpoint had NO guard)
--      expense_voucher_no the EXP- number expense-service gave it, STAMPED when the outbox is delivered
--    All NULL: every existing movement, and every movement while the module is off, is exactly as before.
--
-- 2. drawer_expense_outbox — the pay-out, captured in the movement's own transaction and delivered after commit.
--    payload is the whole contract request as JSON text, NOT field-by-field columns (the gl_outbox lesson: a field
--    with no column was dropped in silence and 4200 Sales Discount stayed empty for weeks). VARCHAR, so the
--    ddl-auto=validate contract is trivially true.
ALTER TABLE cash_movement
    ADD COLUMN category_id        BIGINT      NULL,
    ADD COLUMN idempotency_key    VARCHAR(80) NULL,
    ADD COLUMN expense_voucher_no VARCHAR(20) NULL;

CREATE UNIQUE INDEX uq_cash_movement_org_idem ON cash_movement (organization_id, idempotency_key);

CREATE TABLE IF NOT EXISTS drawer_expense_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NULL,
    user_id          BIGINT        NULL,
    movement_id      BIGINT        NOT NULL,
    payload          VARCHAR(2000) NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts         INT           NOT NULL DEFAULT 0,
    last_error       VARCHAR(500)  NULL,
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_drawer_expense_outbox_movement (movement_id),
    KEY idx_drawer_expense_outbox_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
