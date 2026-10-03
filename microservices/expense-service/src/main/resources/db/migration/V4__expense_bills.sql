-- FP-3 = EX-4 — expense bills: an expense owed to a supplier (paid_from = AP), paid later.
-- Design: microservices/docs/slices/fp-3-ex-4-expense-bills.md
--
-- supplier_id / supplier_name  the business supplier (vender) the bill is owed to — CONFIRMED with business-service
--                              through the expense-tag SPI at save; the name is that module's label, snapshotted.
-- due_date                     when the supplier expects to be paid (optional; never before the bill's date).
-- paid_amount                  STAMPED by each recorded payment, in the payment's own transaction — never summed on
--                              read. open = total − paid_amount.
ALTER TABLE expense_voucher
    ADD COLUMN supplier_id   BIGINT        NULL,
    ADD COLUMN supplier_name VARCHAR(160)  NULL,
    ADD COLUMN due_date      DATE          NULL,
    ADD COLUMN paid_amount   DECIMAL(19,2) NOT NULL DEFAULT 0;

CREATE INDEX idx_expense_voucher_supplier ON expense_voucher (organization_id, supplier_id);

-- One payment against a bill. finance-service records the money (PV- voucher, Dr 2000 / Cr cash·bank); this row is
-- expense-service's side of it and carries the DUPLICATE guarantee finance's payment write does not have:
--   UNIQUE (organization_id, idempotency_key)  a replayed Pay never reaches finance twice.
--   reference                                  EXPB-<org>-<id>, sent to finance as the payment's reference, so a row
--                                              whose outcome is unknown (a timeout) is found there before any resend.
--   status  PENDING   reserved, finance not yet confirmed (counts against the bill's open amount)
--           RECORDED  finance answered with its PV number
--           FAILED    finance refused, or nothing reached it — released, the bill is open again
CREATE TABLE IF NOT EXISTS expense_bill_payment (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id     BIGINT        NOT NULL,
    user_id             BIGINT        NULL,
    voucher_id          BIGINT        NOT NULL,
    amount              DECIMAL(19,2) NOT NULL,
    method              VARCHAR(16)   NOT NULL,
    paid_on             DATE          NOT NULL,
    status              VARCHAR(16)   NOT NULL,
    reference           VARCHAR(40)   NULL,
    receipt_no          VARCHAR(20)   NULL,
    finance_payment_id  BIGINT        NULL,
    idempotency_key     VARCHAR(80)   NOT NULL,
    last_error          VARCHAR(500)  NULL,
    created_at          DATETIME      NULL,
    updated_at          DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_expense_bill_payment_key (organization_id, idempotency_key),
    KEY idx_expense_bill_payment_voucher (voucher_id, status),
    KEY idx_expense_bill_payment_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
