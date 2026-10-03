-- FP-5a — a settlement's payment reaches finance's ledger exactly once (common-subledger LedgerOutbox).
-- Design: microservices/docs/slices/fp-5-one-settlement-path.md section 4
--
-- Written in the SAME transaction as the documents a payment settles (purchases for Pay Supplier, invoices for
-- Receive Payment). payload = the whole PaymentRecordRequest as JSON (VARCHAR, not TEXT/JSON: the validate contract
-- stays a plain length; a settlement's allocations fit far inside it). client_ref is the settlement's reference —
-- finance answers a repeat with the FIRST payment, so a redelivery can never pay twice. receipt_no is stamped when
-- finance answers. status: PENDING → POSTED, or FAILED after the relay's limit (dead letter, for an operator).
CREATE TABLE IF NOT EXISTS ledger_payment_outbox (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT         NULL,
    user_id          BIGINT         NULL,
    client_ref       VARCHAR(100)   NOT NULL,
    payload          VARCHAR(12000) NOT NULL,
    status           VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    attempts         INT            NOT NULL DEFAULT 0,
    last_error       VARCHAR(500)   NULL,
    receipt_no       VARCHAR(40)    NULL,
    created_at       DATETIME       NULL,
    updated_at       DATETIME       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ledger_payment_outbox_ref (client_ref),
    KEY idx_ledger_payment_outbox_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
