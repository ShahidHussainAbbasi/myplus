-- FP-6a — the payables reconciliation HISTORY: one row per tenant per day, written by the automatic daily check.
-- Design: microservices/docs/slices/fp-6-retire-business-source.md
--
-- FP-6 may retire business as a tenant's payables source only after 28 CLEAN days in a row; until this table there was
-- no record at all, so that gate could not be proven. "clean" = no repair was needed that day: business's supplier
-- purchases equalled finance's documents AND finance's ledger equalled GL 2000. A day that needed a repair is recorded
-- with what was repaired (documents re-reported, ledger aligned) and is NOT clean. Append-only by day (UNIQUE).
CREATE TABLE IF NOT EXISTS payables_recon_day (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id   BIGINT        NOT NULL,
    recon_day         DATE          NOT NULL,
    business_owed     DECIMAL(19,2) NULL,
    finance_purchase  DECIMAL(19,2) NULL,
    finance_net       DECIMAL(19,2) NULL,
    gl_payable        DECIMAL(19,2) NULL,
    shadow_diff       DECIMAL(19,2) NULL,
    ledger_diff       DECIMAL(19,2) NULL,
    docs_resent       INT           NOT NULL DEFAULT 0,
    ledger_aligned    DECIMAL(19,2) NULL,
    clean             bit(1)        NOT NULL DEFAULT 0,
    error             VARCHAR(500)  NULL,
    ran_at            DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_payables_recon_day (organization_id, recon_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
