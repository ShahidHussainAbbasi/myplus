-- EX-7b — advances to staff. What each member holds of the business's money (1300 Employee Advance), and every give and
-- take-back that moved it. A claim settled FROM an advance is the claim's own payment (expense_bill_payment, method
-- ADVANCE); it moves the same balance.
--
-- The balance row is the concurrency guard: every change locks it (SELECT … FOR UPDATE), a settlement or take-back
-- reserves before finance is called and a refusal releases, so the same advance can never be spent twice.
CREATE TABLE expense_advance_balance (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id BIGINT        NOT NULL,
    user_id         BIGINT        NOT NULL,
    member_name     VARCHAR(160)  NULL,
    balance         DECIMAL(19,2) NOT NULL DEFAULT 0,
    version         INT           NOT NULL DEFAULT 0,
    updated_at      DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_expense_advance_balance (organization_id, user_id)
);

-- kind: GIVE | TAKE_BACK. status: PENDING (reserved, finance not yet confirmed) | RECORDED | FAILED | REVERSED.
CREATE TABLE expense_advance_movement (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id    BIGINT        NOT NULL,
    user_id            BIGINT        NOT NULL,
    member_name        VARCHAR(160)  NULL,
    kind               VARCHAR(16)   NOT NULL,
    amount             DECIMAL(19,2) NOT NULL,
    method             VARCHAR(16)   NOT NULL,
    moved_on           DATE          NOT NULL,
    status             VARCHAR(16)   NOT NULL,
    reference          VARCHAR(40)   NULL,
    receipt_no         VARCHAR(40)   NULL,
    finance_payment_id BIGINT        NULL,
    idempotency_key    VARCHAR(80)   NOT NULL,
    note               VARCHAR(255)  NULL,
    last_error         VARCHAR(500)  NULL,
    created_by         BIGINT        NULL,
    created_at         DATETIME      NOT NULL,
    updated_at         DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_expense_advance_key (organization_id, idempotency_key),
    KEY idx_expense_advance_member (organization_id, user_id),
    KEY idx_expense_advance_status (status, created_at)
);
