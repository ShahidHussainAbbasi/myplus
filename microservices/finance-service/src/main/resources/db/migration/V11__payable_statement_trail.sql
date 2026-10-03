-- FP-4a — finance holds what a supplier STATEMENT shows, not only what is still owed.
-- Design: microservices/docs/slices/fp-4-payables-reads-switch.md §3
--
-- issued_amount  the bill AS ISSUED (gross, before any return) — the statement's BILL line. amount stays what is
--                owed on it now (after returns), which every balance query (open, net, reconciliation) keeps reading.
-- due_date       when the supplier expects payment (expense bills); NULL = age by doc_date.
ALTER TABLE payable_doc
    ADD COLUMN issued_amount DECIMAL(19,2) NULL,
    ADD COLUMN due_date      DATE          NULL;

-- The debit notes against a document — a STATEMENT TRAIL only. No balance reads this table (the return already
-- reduced the document's amount), so adding it changes no figure anywhere. Replaced whole on each accepted
-- snapshot that carries notes, under the document's own version guard.
CREATE TABLE IF NOT EXISTS payable_note (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NOT NULL,
    payable_doc_id   BIGINT        NOT NULL,
    note_no          VARCHAR(64)   NULL,
    note_date        DATE          NULL,
    amount           DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_payable_note_doc (payable_doc_id),
    KEY idx_payable_note_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
