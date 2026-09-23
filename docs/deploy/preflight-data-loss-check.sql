-- ============================================================================================================
-- PRE-FLIGHT DATA-LOSS CHECK — run this against PRODUCTION *before* deploying, and read every row.
--
-- WHY THIS FILE EXISTS
-- Four migrations in this release are IRREVERSIBLE: they drop 7 tables and 14 columns.
--     business-service  V7__drop_local_stock.sql        DROP TABLE stock; sell/purchase/item.stock_id
--     business-service  V8__drop_item_tables.sql        DROP TABLE item, item_catalog_map; purchase/sell.item_id
--     pharma-service    V3__pharma_itemid_to_productid  item_id -> product_id (DROPS item_id when both exist)
--     pharma-service    V6__drop_dead_medicine_schema   DROP TABLE medicines, pharmacy_stock,
--                                                       drug_categories, medicine_profile; 4 medicine_id columns
-- Three more CHANGE STORED VALUES in place: money DOUBLE -> DECIMAL(19,2) rounds to 2dp, education
-- grade.fee/student.fee FLOAT -> INT rounds to whole, and idempotency_key VARCHAR(255) -> VARCHAR(191) truncates.
--
-- Each of those migrations is annotated as safe. Every one of those annotations was measured ON DEV, and is
-- recorded in a COMMENT rather than enforced by a guard:
--     V6: "Dev row counts at the time of writing: medicines 0, pharmacy_stock 0 ... Pharmacy is pre-production"
--     V5: "Covers items already in item_catalog_map ... Items NOT yet mapped need a catalog import first"
-- A comment cannot inspect the production database. This file does. Every check below is READ-ONLY.
--
-- ⚠ THE ONE THAT CANNOT BE FIXED AFTER THE FACT (check B1/B2). V5 backfills sell.product_id / purchase.product_id
-- only for items present in item_catalog_map. V7 then drops stock_id and V8 drops item_catalog_map itself, so any
-- row V5 could not map loses BOTH its old link and the mapping that could have rebuilt it — permanently. The
-- documented completeness tool (POST /api/business/admin/migrate-catalog, then /backfill-product-ids) runs over
-- HTTP against a RUNNING service, but Flyway runs at bean-init, before the service accepts requests. So there is
-- no moment during a normal deploy at which that tool can be run. It must be run on the CURRENT production
-- version, BEFORE this release is deployed.
--
-- HOW TO RUN (read-only; safe on a live database):
--     docker exec -i myplus-mysql mysql -u"$DB_USER" -p"$DB_PASSWORD" < preflight-data-loss-check.sql
--     # or against RDS:  mysql -h <host> -u <user> -p < preflight-data-loss-check.sql
--
-- HOW TO READ THE RESULT
--     PASS  — nothing at risk for that check; the migration is provably lossless here.
--     STOP  — rows WILL be destroyed or altered. Do not deploy. Act on the note, then re-run.
--     N/A   — table/column absent (already migrated, or a fresh database). Nothing to lose.
--     INFO  — a count for the record, not a verdict.
-- A deploy is authorised only when NO row says STOP.
-- ============================================================================================================

-- A default schema is needed only so the TEMPORARY results table has somewhere to live; every check below
-- names its own database explicitly, so this line does not limit what is inspected.
USE myplusdb;

DROP TEMPORARY TABLE IF EXISTS preflight;
CREATE TEMPORARY TABLE preflight (
  seq        INT AUTO_INCREMENT PRIMARY KEY,
  scope      VARCHAR(24),
  check_id   VARCHAR(8),
  what       VARCHAR(110),
  at_risk    BIGINT,
  verdict    VARCHAR(6),
  act_on_it  VARCHAR(300)
) ENGINE=MEMORY;

-- ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────
-- Every check is guarded on the table/column existing, so this file runs unchanged against a fresh database,
-- a half-migrated one, or one already past these versions.

-- ============================================================================================================
-- BUSINESS  (myplusdb)
-- ============================================================================================================

-- B1 ─ SELL rows that would lose their product identity for ever (V5 cannot map them; V7/V8 remove the evidence)
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA='myplusdb' AND TABLE_NAME='sell' AND COLUMN_NAME='stock_id') > 0
  AND (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA='myplusdb' AND TABLE_NAME='stock') > 0,
  'SELECT COUNT(*) INTO @n FROM myplusdb.sell s
     JOIN myplusdb.stock st ON s.stock_id = st.stock_id
     LEFT JOIN myplusdb.item_catalog_map m ON m.item_id = st.item_id
    WHERE s.product_id IS NULL AND s.stock_id IS NOT NULL AND m.product_id IS NULL',
  'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('business','B1','SALE lines whose product link V5 cannot rebuild (unmapped item)', @n,
  IF(@n IS NULL,'N/A',IF(@n=0,'PASS','STOP')),
  'On the CURRENT prod version (still running): POST /api/business/admin/migrate-catalog then /backfill-product-ids. Re-run this check until 0. After V7/V8 these rows are unrecoverable.');

-- B2 ─ PURCHASE rows, same exposure
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA='myplusdb' AND TABLE_NAME='purchase' AND COLUMN_NAME='stock_id') > 0
  AND (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA='myplusdb' AND TABLE_NAME='stock') > 0,
  'SELECT COUNT(*) INTO @n FROM myplusdb.purchase p
     JOIN myplusdb.stock st ON p.stock_id = st.stock_id
     LEFT JOIN myplusdb.item_catalog_map m ON m.item_id = st.item_id
    WHERE p.product_id IS NULL AND p.stock_id IS NOT NULL AND m.product_id IS NULL',
  'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('business','B2','PURCHASE lines whose product link V5 cannot rebuild (unmapped item)', @n,
  IF(@n IS NULL,'N/A',IF(@n=0,'PASS','STOP')),
  'Same remedy as B1. V6 also copies batch/rate onto the purchase row, so the MONEY survives; the product identity does not.');

-- B3 ─ Stock batches whose item was never mapped (the population B1/B2 draw from)
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA='myplusdb' AND TABLE_NAME='item_catalog_map') > 0
  AND (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA='myplusdb' AND TABLE_NAME='stock') > 0,
  'SELECT COUNT(*) INTO @n FROM myplusdb.stock st
     LEFT JOIN myplusdb.item_catalog_map m ON m.item_id = st.item_id
    WHERE m.product_id IS NULL',
  'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('business','B3','stock batches with no catalog mapping', @n,
  IF(@n IS NULL,'N/A',IF(@n=0,'PASS','INFO')),
  'Not fatal by itself. It is the pool B1/B2 are drawn from: while this is > 0 the catalog import is incomplete.');

-- B4 ─ stock / item row counts: what V7 and V8 delete outright
SET @sql := IF((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='myplusdb' AND TABLE_NAME='stock')>0,
  'SELECT COUNT(*) INTO @n FROM myplusdb.stock', 'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('business','B4','rows in `stock` — V7 DROPs this table entirely', @n,
  IF(@n IS NULL,'N/A','INFO'),
  'On-hand now lives in inventory-service (a SEPARATE database), so no migration can move these rows. Confirm inventory holds this tenant''s stock BEFORE deploying; keep the dump from step 1 regardless.');

-- B5 ─ money values that DOUBLE -> DECIMAL(19,2) would silently round
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA='myplusdb' AND TABLE_NAME='sell' AND COLUMN_NAME='net_amount'
       AND DATA_TYPE IN ('double','float')) > 0,
  'SELECT COUNT(*) INTO @n FROM myplusdb.sell
    WHERE (net_amount        IS NOT NULL AND net_amount        <> ROUND(net_amount,2))
       OR (total_amount      IS NOT NULL AND total_amount      <> ROUND(total_amount,2))
       OR (sell_rate         IS NOT NULL AND sell_rate         <> ROUND(sell_rate,2))
       OR (discount          IS NOT NULL AND discount          <> ROUND(discount,2))
       OR (sell_return_profit IS NOT NULL AND sell_return_profit <> ROUND(sell_return_profit,2))',
  'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('business','B5','SALE money values that V2 would round to 2dp', @n,
  IF(@n IS NULL,'N/A',IF(@n=0,'PASS','INFO')),
  'Rounding to 2dp is the intended fix (see project_money_types). INFO, not STOP — but the figures change, so reconcile revenue totals before and after and keep the evidence.');

-- B6 ─ customer_history money, same conversion
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA='myplusdb' AND TABLE_NAME='customer_history' AND COLUMN_NAME='due_amount'
       AND DATA_TYPE IN ('double','float')) > 0,
  'SELECT COUNT(*) INTO @n FROM myplusdb.customer_history
    WHERE (due_amount  IS NOT NULL AND due_amount  <> ROUND(due_amount,2))
       OR (paid_amount IS NOT NULL AND paid_amount <> ROUND(paid_amount,2))',
  'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('business','B6','INVOICE due/paid values that V2 would round to 2dp', @n,
  IF(@n IS NULL,'N/A',IF(@n=0,'PASS','INFO')),
  'Customer balances. Reconcile total receivables before and after; a rounding difference here is money owed.');

-- B7 ─ idempotency keys that VARCHAR(191) would truncate
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA='myplusdb' AND TABLE_NAME='customer_history' AND COLUMN_NAME='idempotency_key') > 0,
  'SELECT COUNT(*) INTO @n FROM myplusdb.customer_history WHERE CHAR_LENGTH(idempotency_key) > 191',
  'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('business','B7','idempotency keys longer than 191 chars (V10 narrows the column)', @n,
  IF(@n IS NULL,'N/A',IF(@n=0,'PASS','STOP')),
  'Keys are ~36-char UUIDs, so this should be 0. If not, truncation could make two different keys collide on the UNIQUE index — a duplicate-sale guard that starts refusing real sales.');

-- ============================================================================================================
-- PHARMA  (myplusdb_pharma)   — every drop here is justified by "pharmacy is pre-production". Verify that.
-- ============================================================================================================

-- P1 ─ live clinical rows still pointing at the medicine master V6 deletes
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA='myplusdb_pharma' AND TABLE_NAME='prescription_items' AND COLUMN_NAME='medicine_id') > 0,
  'SELECT COUNT(*) INTO @n FROM myplusdb_pharma.prescription_items WHERE medicine_id IS NOT NULL',
  'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('pharma','P1','prescription_items still referencing a medicine', @n,
  IF(@n IS NULL,'N/A',IF(@n=0,'PASS','STOP')),
  'V6 drops this column AND the medicines table. Dev measured 0 of 74 rows; production is a different database. If > 0, export the join before deploying.');

-- P2 ─ dispensing, same
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA='myplusdb_pharma' AND TABLE_NAME='dispensing' AND COLUMN_NAME='medicine_id') > 0,
  'SELECT COUNT(*) INTO @n FROM myplusdb_pharma.dispensing WHERE medicine_id IS NOT NULL',
  'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('pharma','P2','dispensing records still referencing a medicine', @n,
  IF(@n IS NULL,'N/A',IF(@n=0,'PASS','STOP')),
  'A dispensing record is a controlled-substance audit trail. Losing what was dispensed is a regulatory problem, not only a data one.');

-- P3 ─ the medicine master itself
SET @sql := IF((SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA='myplusdb_pharma' AND TABLE_NAME='medicines') > 0,
  'SELECT COUNT(*) INTO @n FROM myplusdb_pharma.medicines', 'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('pharma','P3','rows in `medicines` — V6 DROPs the table', @n,
  IF(@n IS NULL,'N/A',IF(@n=0,'PASS','STOP')),
  'Dev had 0. Any row here is a product master that no migration copies into catalog-service; it is simply gone.');

-- P4 ─ pharmacy stock
SET @sql := IF((SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA='myplusdb_pharma' AND TABLE_NAME='pharmacy_stock') > 0,
  'SELECT COUNT(*) INTO @n FROM myplusdb_pharma.pharmacy_stock', 'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('pharma','P4','rows in `pharmacy_stock` — V6 DROPs the table', @n,
  IF(@n IS NULL,'N/A',IF(@n=0,'PASS','STOP')),
  'On-hand quantities. Nothing migrates these into inventory-service.');

-- P5 ─ V3 DROPS a populated item_id when product_id already exists but is empty
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA='myplusdb_pharma' AND TABLE_NAME='prescription_items' AND COLUMN_NAME='item_id') > 0
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA='myplusdb_pharma' AND TABLE_NAME='prescription_items' AND COLUMN_NAME='product_id') > 0,
  'SELECT COUNT(*) INTO @n FROM myplusdb_pharma.prescription_items
    WHERE item_id IS NOT NULL AND product_id IS NULL',
  'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('pharma','P5','rows where V3 drops a POPULATED item_id in favour of an EMPTY product_id', @n,
  IF(@n IS NULL,'N/A',IF(@n=0,'PASS','STOP')),
  'V3 chooses DROP over RENAME whenever both columns exist. Where product_id is NULL that discards the only link. Backfill product_id from the catalog first.');

-- ============================================================================================================
-- EDUCATION  (myplusdb_education)
-- ============================================================================================================

-- E1 ─ fees with a fractional part, which FLOAT -> INT rounds away
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA='myplusdb_education' AND TABLE_NAME='student' AND COLUMN_NAME='fee'
       AND DATA_TYPE IN ('double','float')) > 0,
  'SELECT COUNT(*) INTO @n FROM myplusdb_education.student WHERE fee IS NOT NULL AND fee <> ROUND(fee)',
  'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('education','E1','student fees with a fractional part (V3 rounds FLOAT -> INT)', @n,
  IF(@n IS NULL,'N/A',IF(@n=0,'PASS','STOP')),
  'Each such row has the amount a parent owes CHANGED by the deploy. Export student_id + fee first; decide per school whether rounding up or down is acceptable.');

-- E2 ─ the same for the grade-level default fee
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA='myplusdb_education' AND TABLE_NAME='grade' AND COLUMN_NAME='fee'
       AND DATA_TYPE IN ('double','float')) > 0,
  'SELECT COUNT(*) INTO @n FROM myplusdb_education.grade WHERE fee IS NOT NULL AND fee <> ROUND(fee)',
  'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('education','E2','grade default fees with a fractional part', @n,
  IF(@n IS NULL,'N/A',IF(@n=0,'PASS','STOP')),
  'Same as E1, one level up: it sets what every new student in that grade is charged.');

-- ============================================================================================================
-- WHO COULD WIPE A TENANT AT RUNTIME (not a migration — a live endpoint)
-- ============================================================================================================

-- X1 ─ accounts that can call /demo/reset, which purges their whole org across every service
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA='myplusdb_auth' AND TABLE_NAME='users' AND COLUMN_NAME='demo') > 0,
  'SELECT COUNT(*) INTO @n FROM myplusdb_auth.users WHERE demo = 1', 'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('auth','X1','accounts with demo=1 — each can purge its ENTIRE organisation from a button', @n,
  IF(@n IS NULL,'N/A',IF(@n=0,'PASS','STOP')),
  'Expected 0 in production (SEED_DEMO=false). If > 0, a real tenant is one confirm-dialog away from losing everything: DemoPurgeController deletes every row carrying its organizationId in all 13 services, with no backup and no undo.');

-- X2 ─ the same via the privilege, independent of the demo flag
SET @sql := IF((SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA='myplusdb_auth' AND TABLE_NAME='privileges') > 0,
  'SELECT COUNT(DISTINCT ur.user_id) INTO @n
     FROM myplusdb_auth.users_roles ur
     JOIN myplusdb_auth.roles_privileges rp ON rp.role_id = ur.role_id
     JOIN myplusdb_auth.privileges p ON p.id = rp.privilege_id
    WHERE p.name = ''DEMO_RESET_PRIVILEGE''',
  'SELECT NULL INTO @n');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
INSERT INTO preflight (scope, check_id, what, at_risk, verdict, act_on_it) VALUES
 ('auth','X2','accounts holding DEMO_RESET_PRIVILEGE', @n,
  IF(@n IS NULL,'N/A',IF(@n=0,'PASS','STOP')),
  'Only DEMO_ROLE and DEMO_RESET_ROLE grant it, and both are seeded only when app.seed-demo=true. Any holder in production is a seeded test account with a known password — remove the role, then the account.');

-- ============================================================================================================
-- VERDICT
-- ============================================================================================================
SELECT scope, check_id, what, at_risk, verdict, act_on_it FROM preflight ORDER BY seq;

SELECT
  SUM(verdict = 'STOP') AS blocking_issues,
  SUM(verdict = 'INFO') AS review_these,
  SUM(verdict = 'PASS') AS proven_safe,
  SUM(verdict = 'N/A')  AS not_applicable,
  IF(SUM(verdict = 'STOP') = 0,
     'CLEARED — no check found data this release would destroy. Take the dump anyway (runbook step 1).',
     'DO NOT DEPLOY — act on every STOP row above, then re-run this file.') AS decision
FROM preflight;
