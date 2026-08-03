-- Returns become DOCUMENTS in their own series (slice b2b-P3c = customer requirement #1).
--
-- THE ACCOUNTING RULE: a customer return is a CREDIT NOTE and a supplier return a DEBIT NOTE. Each is a
-- distinct document, in its own number series, that REFERENCES the document it reverses. Today a sale return
-- is stamped with the ORIGINAL invoice number (sale_return.invoice_no), so a credit note is indistinguishable
-- from the invoice it cancels and reconciliation is impossible. A purchase return leaves no document at all.
--
-- WHAT THIS DOES:
--   1. sale_return gains its OWN identity: credit_note_seq (per-org running number) + credit_note_no (display).
--      invoice_no is LEFT ALONE -- it is the reference to the reversed invoice, which is the whole point.
--   2. purchase_return is CREATED, because the supplier side has no record whatsoever.
--
-- Allocation is MAX(seq)+1 per org inside the return's transaction, exactly as invoice_seq has worked since
-- slice 22; the UNIQUE(organization_id, seq) below is what actually guarantees no duplicate can commit.
--
-- ALL MODULES ARE LIVE: purely additive, and deliberately NOT back-filled. An existing return keeps a NULL
-- note number and displays exactly as it does today. Inventing CRN- numbers for historical returns would
-- fabricate documents that were never issued to anyone.
--
-- Idempotent (D7): information_schema-guarded throughout.

-- ── 1. the customer side: give the credit note its own identity ─────────────────────────────
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sale_return' AND COLUMN_NAME='credit_note_seq')=0,
    'ALTER TABLE sale_return ADD COLUMN credit_note_seq BIGINT DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sale_return' AND COLUMN_NAME='credit_note_no')=0,
    'ALTER TABLE sale_return ADD COLUMN credit_note_no VARCHAR(32) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- The constraint IS the concurrency guarantee for MAX+1 allocation, per org.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sale_return' AND INDEX_NAME='uk_sale_return_crn')=0,
    'ALTER TABLE sale_return ADD CONSTRAINT uk_sale_return_crn UNIQUE (organization_id, credit_note_seq)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 2. the supplier side: there is no document at all, so create one ────────────────────────
CREATE TABLE IF NOT EXISTS purchase_return (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    debit_note_seq   BIGINT        DEFAULT NULL,
    debit_note_no    VARCHAR(32)   DEFAULT NULL,
    purchase_id      BIGINT        DEFAULT NULL,
    purchase_invoice_no VARCHAR(64) DEFAULT NULL,   -- the bill this reverses (the reference)
    product_id       BIGINT        DEFAULT NULL,
    vender_id        BIGINT        DEFAULT NULL,
    quantity         DECIMAL(19,3) DEFAULT NULL,
    reason           VARCHAR(255)  DEFAULT NULL,
    amount           DECIMAL(19,2) DEFAULT NULL,
    organization_id  BIGINT        DEFAULT NULL,
    user_id          BIGINT        DEFAULT NULL,
    store_id         BIGINT        DEFAULT NULL,
    dated            DATETIME      DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase_return' AND INDEX_NAME='uk_purchase_return_dbn')=0,
    'ALTER TABLE purchase_return ADD CONSTRAINT uk_purchase_return_dbn UNIQUE (organization_id, debit_note_seq)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- Every read here is org-scoped, and a supplier's return history is the common query.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase_return' AND INDEX_NAME='idx_purchase_return_org')=0,
    'CREATE INDEX idx_purchase_return_org ON purchase_return (organization_id, vender_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
