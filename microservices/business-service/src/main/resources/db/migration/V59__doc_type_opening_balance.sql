-- OB-1 — telling an OPENING BALANCE apart from a sale, for ever.
--
-- WHAT THIS CLOSES
-- A shop that switches to MaxTheService on a Tuesday already had money owed to it on the Monday, and there
-- was nowhere to put it. Customer.due_amount is DERIVED — recomputeDue() sums the invoice headers and
-- overwrites the column on every sale and every receipt — so a figure typed into it survives until that
-- customer's next transaction and then vanishes, silently. An opening balance therefore has to be a
-- DOCUMENT, which is also the shape the statement, the aging, the FIFO allocator and the credit limit all
-- already read.
--
-- WHY A DISCRIMINATOR AND NOT A SEPARATE TABLE
-- The whole value of the document shape is that five existing readers pick it up with no change. A separate
-- opening_balance table would need each of those readers taught about it — five integrations, and the ones
-- that were missed would disagree with the customer card in ways nobody would notice for months. One column
-- on the row those readers already read is the entire point.
--
-- ⚠ NOT NULL WITH A DEFAULT OF 'SALE', AND EVERY EXISTING ROW BACKFILLED.
-- A nullable discriminator would be one `IS NULL` away from a report that counts opening balances as trade —
-- overstating a month's sales and inventing output tax on money that was never a sale here. That is the exact
-- failure this slice exists to prevent, so the column may not be able to express "unknown".
--
-- OPENING is deliberately NOT in an ENUM. project_ddl_validate_column_contract records what a MySQL enum
-- against a String field costs: business-service refused to boot and crash-looped nine times. VARCHAR(16),
-- matching installment_plan.status and serial_unit.status.

-- ── customer_history: the receivable side ───────────────────────────────────────────────────────
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'customer_history' AND column_name = 'doc_type') = 0,
    'ALTER TABLE customer_history ADD COLUMN doc_type VARCHAR(16) NOT NULL DEFAULT ''SALE''',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── purchase: the payable side ──────────────────────────────────────────────────────────────────
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'purchase' AND column_name = 'doc_type') = 0,
    'ALTER TABLE purchase ADD COLUMN doc_type VARCHAR(16) NOT NULL DEFAULT ''SALE''',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Backfill is what the DEFAULT already did for existing rows; this is belt and braces for a column added by
-- an earlier hand-run without one. Idempotent per D7.
UPDATE customer_history SET doc_type = 'SALE' WHERE doc_type IS NULL OR doc_type = '';
UPDATE purchase          SET doc_type = 'SALE' WHERE doc_type IS NULL OR doc_type = '';

-- The only read this column has: "everything that is NOT an opening balance" (the sale reports, the tax
-- register) and "the opening balances for this org" (the migration screen). Both are org-scoped already, so
-- the index leads with the org.
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'customer_history'
        AND index_name = 'ix_ch_org_doctype') = 0,
    'CREATE INDEX ix_ch_org_doctype ON customer_history (organization_id, doc_type)',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'purchase'
        AND index_name = 'ix_purchase_org_doctype') = 0,
    'CREATE INDEX ix_purchase_org_doctype ON purchase (organization_id, doc_type)',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
