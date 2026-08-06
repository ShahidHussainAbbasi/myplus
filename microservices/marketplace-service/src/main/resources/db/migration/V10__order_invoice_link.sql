-- OMS O1 — link every storefront order to the trade sale it produced, and mark the ones that predate O1.
--
-- Before O1 a storefront order decremented stock and charged a card but never created a trade sale: no invoice,
-- no revenue journal, no tax-register line, no AR, no payment row. So P&L, trial balance, tax register, period
-- close and day close were silently wrong for every online sale. From O1 a storefront order goes through the
-- SAME sale path POS uses and comes back with an invoice number.
--
-- books_status makes the pre-O1 backlog VISIBLE instead of indistinguishable from a fresh order:
--   LEGACY_UNPOSTED — placed before O1; there is no invoice and there never will be automatically.
--   POSTED          — produced an invoice through business-service.
-- Existing rows default to LEGACY_UNPOSTED because that is what they are. They are deliberately NOT back-posted:
-- back-dating revenue would write into closed accounting periods (see the plan's open decision #3). The
-- reconciliation read (`booksStatus=LEGACY_UNPOSTED`) is how an operator finds and handles them.
--
-- Idempotent: each change is guarded on information_schema, so re-running is a no-op.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='books_status')=0,
    'ALTER TABLE orders ADD COLUMN books_status VARCHAR(20) NOT NULL DEFAULT ''LEGACY_UNPOSTED''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- The reconciliation read filters on it; the invoice lookup joins on invoice_no.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND INDEX_NAME='idx_orders_books_status')=0,
    'CREATE INDEX idx_orders_books_status ON orders (organization_id, books_status)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND INDEX_NAME='idx_orders_invoice_no')=0,
    'CREATE INDEX idx_orders_invoice_no ON orders (invoice_no)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
