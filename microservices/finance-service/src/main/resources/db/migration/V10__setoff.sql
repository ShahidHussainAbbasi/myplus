-- DR-4 — a set-off: ONE document joining the two payments it records (a RECEIPT from the customer and a DISBURSEMENT
-- to the supplier, both method SETOFF, posted through 1900 Set-off clearing). Design:
-- microservices/docs/party-dual-role-customer-supplier-analysis.md (DR-4).
--
-- idempotency_key UNIQUE per org: business-service retries with the same key after a lost commit, and gets the FIRST
-- document back instead of a second pair of payments. reversal_key UNIQUE per org does the same for the reversal.
-- A reversal writes two MIRROR payments (negative amounts), so statements and party totals net back without any
-- reader learning a new rule; their ids are kept here.
CREATE TABLE IF NOT EXISTS setoff (
    id                        BIGINT         NOT NULL AUTO_INCREMENT,
    organization_id           BIGINT         NOT NULL,
    user_id                   BIGINT         NULL,
    idempotency_key           VARCHAR(80)    NOT NULL,
    setoff_no                 VARCHAR(20)    NOT NULL,
    amount                    DECIMAL(19,2)  NOT NULL,
    receipt_payment_id        BIGINT         NOT NULL,
    disbursement_payment_id   BIGINT         NOT NULL,
    created_at                DATETIME       NULL,
    reversal_key              VARCHAR(80)    NULL,
    reversal_reason           VARCHAR(255)   NULL,
    reversed_at               DATETIME       NULL,
    reversal_receipt_id       BIGINT         NULL,
    reversal_disbursement_id  BIGINT         NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_setoff_org_key (organization_id, idempotency_key),
    UNIQUE KEY uq_setoff_org_rev (organization_id, reversal_key),
    KEY idx_setoff_org_no (organization_id, setoff_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
