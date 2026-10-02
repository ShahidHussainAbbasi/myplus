-- FP-1 — the supplier payables subledger moves to finance (ruling 2026-10-02: "Move payables to finance").
-- Design: microservices/docs/finance-payables-subledger-design.md · slices/fp-1-2-payables-in-finance.md
--
-- One row per payable document (a supplier purchase line today; an expense bill from EX-4). In FP-1/FP-2 the
-- SOURCE is still authoritative: each row is the latest snapshot it reported (amount, paid). The phases after
-- this one move reads and then settlement here.
--
-- UNIQUE (organization_id, source, source_ref): the idempotent intake — a redelivered or replayed (backfill)
-- snapshot updates the same row. source_version is monotonic per document: an older snapshot is ignored, so a
-- late redelivery never overwrites newer figures.
CREATE TABLE IF NOT EXISTS payable_doc (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NOT NULL,
    party_type       VARCHAR(16)   NOT NULL,
    party_id         BIGINT        NULL,
    party_name       VARCHAR(160)  NULL,
    source           VARCHAR(24)   NOT NULL,
    source_ref       VARCHAR(64)   NOT NULL,
    source_version   BIGINT        NOT NULL DEFAULT 0,
    doc_no           VARCHAR(64)   NULL,
    doc_date         DATE          NULL,
    amount           DECIMAL(19,2) NOT NULL,
    paid             DECIMAL(19,2) NOT NULL DEFAULT 0,
    status           VARCHAR(12)   NOT NULL,           -- OPEN | SETTLED | VOID
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_payable_doc_source (organization_id, source, source_ref),
    KEY idx_payable_doc_party (organization_id, party_type, party_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
