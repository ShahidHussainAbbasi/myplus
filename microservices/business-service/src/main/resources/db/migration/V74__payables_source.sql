-- FP-4b — where a tenant's supplier screens read from: BUSINESS (today's path) or FINANCE (the payables subledger).
-- Design: microservices/docs/slices/fp-4-payables-reads-switch.md section 4
--
-- One row per tenant that has ever been switched; NO row = BUSINESS. Flipped only by a platform operator, with a
-- reason, and the EVIDENCE at that moment is kept on the row: what business said was owed, what finance said, and the
-- GL 2000 difference the operator was warned about. A switch to FINANCE is refused unless business = finance.
CREATE TABLE IF NOT EXISTS payables_source (
    organization_id      BIGINT        NOT NULL,
    source               VARCHAR(16)   NOT NULL,
    reason               VARCHAR(255)  NOT NULL,
    switched_by          BIGINT        NULL,
    switched_at          DATETIME      NOT NULL,
    business_due         DECIMAL(19,2) NULL,
    finance_purchase_net DECIMAL(19,2) NULL,
    gl_difference        DECIMAL(19,2) NULL,
    PRIMARY KEY (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
