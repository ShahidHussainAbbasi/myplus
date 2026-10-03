-- FP-5b — one Pay Supplier settles purchases AND expense bills; this carries each bill's share to expense-service.
-- Design: microservices/docs/slices/fp-5-one-settlement-path.md section 5
--
-- Written in the SAME transaction as the purchases the payment settles and the ledger request (V76), so the three
-- commit together. The money is already in finance's ledger as ONE payment; a row here only tells a bill it has been
-- paid. client_ref = that payment's reference: expense-service keys the application on (client_ref, bill), so a
-- redelivery applies once. The rows also prove the tenant has made a mixed payment — after which switching its
-- supplier figures back to business is refused (ruling 4).
CREATE TABLE IF NOT EXISTS bill_application_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NOT NULL,
    user_id          BIGINT        NULL,
    voucher_id       BIGINT        NOT NULL,
    amount           DECIMAL(19,2) NOT NULL,
    applied          DECIMAL(19,2) NULL,
    client_ref       VARCHAR(100)  NOT NULL,
    method           VARCHAR(16)   NULL,
    paid_on          DATE          NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts         INT           NOT NULL DEFAULT 0,
    last_error       VARCHAR(500)  NULL,
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    PRIMARY KEY (id),
    KEY idx_bill_application_outbox_status (status, id),
    KEY idx_bill_application_outbox_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
