-- DOC-INT (A + C) — two natural-key indexes this service relied on without owning.
-- Design: microservices/docs/slices/doc-int-numbers-and-bills.md
--
-- ── 1. THE INVOICE SERIES IS UNIQUE PER TENANT — and Flyway, not Hibernate, now says so ─────────────────
--
-- WHAT WAS WRONG
-- uq_ch_org_invoice_seq existed only as @Table(uniqueConstraints) on CustomerHistory, created by Hibernate's
-- ddl-auto: update. No migration created it. So it existed on every database that had ever booted with
-- `update`, and on NO database built the way a fresh install is: FlywayMigrationTest runs ddl-auto=validate,
-- which never creates anything, and the platform-wide flip to validate would have dropped it everywhere new.
--
-- WHY IT MATTERS MORE THAN AN INDEX USUALLY DOES
-- It is the arbiter. DocumentNumberService serialises INVOICE numbers with a counter row, and V63 exists
-- because a counter can drift behind the documents — when it does, THIS index is what refuses the second
-- invoice with the same number. Without it the drift is silent: two customers hold invoice 958.
--
-- CHECKED BY COLUMNS, NOT BY NAME
-- V2 records that index names vary per environment. Any UNIQUE index covering exactly
-- (organization_id, invoice_seq), whatever it is called, already does the job, and a second one would only
-- double the write cost of every sale.
--
-- ⚠ FAILS LOUDLY ON DUPLICATE DATA — ON PURPOSE
-- If an environment already holds two invoices with one number (possible only where the index never existed),
-- the CREATE stops with  Duplicate entry '<org>-<seq>' for key 'uq_ch_org_invoice_seq'  — naming the tenant and
-- the number. Skipping silently would leave the arbiter missing in exactly the database that needs it. Those
-- invoices are on paper in customers' hands and must not be renumbered by a script; see docs/migrations.md #10.
--
-- MyISAM key size: 8 + 8 bytes. NULL invoice_seq (opening balances, legacy rows) are distinct under MySQL, so
-- they are unaffected — the same property uq_ch_org_idempotency relies on.

SET @have := (
    SELECT COUNT(*) FROM (
        SELECT INDEX_NAME
          FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_history' AND NON_UNIQUE = 0
         GROUP BY INDEX_NAME
        HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'organization_id,invoice_seq'
    ) u);
SET @ddl := IF(@have = 0,
    'CREATE UNIQUE INDEX uq_ch_org_invoice_seq ON customer_history (organization_id, invoice_seq)',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 2. THE BILL-LINE LOOKUP — so the duplicate-supplier-bill guard costs one index probe ─────────────────
--
-- PurchaseService asks, on every NEW purchase line: "does this vendor's bill <n> already have this product?"
-- (PurchaseRepo.findBillLinesScoped). Without an index that is a scan of the tenant's purchases per line saved,
-- on the screen where an operator keys a delivery at speed.
--
-- NON-unique, deliberately: one bill is SEVERAL rows (one per product line), and a confirmed repeat is allowed.
-- The decision is made in code (DuplicateBillLine), where "same line" includes the batch.
--
-- purchase_invoice_no(64): the column is VARCHAR(255) utf8mb4 = 1020 bytes, over MyISAM's 1000-byte key limit
-- on its own. A 64-character prefix is 256 bytes (+8 +8 = 272) and no real bill number is longer.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase'
                  AND INDEX_NAME = 'idx_purchase_bill_line') = 0,
    'CREATE INDEX idx_purchase_bill_line ON purchase (organization_id, vender_id, purchase_invoice_no(64))',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
