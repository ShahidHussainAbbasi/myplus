-- BLK-5 — a stock correction says WHICH SHOP it belongs to, and carries the caller's key for ONE intended correction.
-- Design: microservices/docs/slices/blk-5-stock-adjust-guard.md
--
-- WHY
-- stock_adjustments is the only record of a manual stock correction, and it could not answer the two questions an
-- audit asks first. It had NO organization_id (V2's tenancy pass never reached this table), so a row could not be
-- scoped to a tenant; and adjusted_by was NULL on every live row (34 of 34 on 2026-09-15), because nothing sent it.
-- This migration adds the tenant; the service now stamps both from the authenticated caller.
--
-- And a retried correction was a second correction: neither the product screen's − nor the stock-count sheet sent a
-- key, so a timed-out save pressed again, a second tab, or an API client removed the stock twice. idempotency_key +
-- the UNIQUE index make the database the arbiter, as catalog V16 (DUP-1) and business V10 (the sale) already do: two
-- requests racing with one key cannot both insert, however they are timed. A pre-check alone cannot promise that.
--
-- ALL MODULES ARE LIVE: additive only — two nullable columns and two indexes. Existing rows keep NULL and are NOT
-- backfilled: their tenant is only knowable through catalog's database, and a migration reaching into another
-- service's schema is exactly the coupling the service split exists to prevent. NULL keys never collide (MySQL treats
-- NULLs in a UNIQUE index as distinct), so an older client that sends no key still saves exactly as it does today.
--
-- 191, not 255: utf8mb4 x 255 = 1020 bytes, over the 1000-byte index key limit (catalog V16's reasoning).
--
-- ⚠ quantity is deliberately NOT touched. Its live type on the dev database (decimal(38,2)) disagrees with V8's
-- DECIMAL(19,4); that is its own slice, with its own cause to find first.
--
-- Idempotent: information_schema-guarded, so a re-run — or a dev database where ddl-auto already added the columns
-- from the entity — is a no-op. (Next version picked with a NUMERIC sort: V10 was the highest.)

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_adjustments' AND COLUMN_NAME='organization_id')=0,
    'ALTER TABLE stock_adjustments ADD COLUMN organization_id BIGINT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_adjustments' AND COLUMN_NAME='idempotency_key')=0,
    'ALTER TABLE stock_adjustments ADD COLUMN idempotency_key VARCHAR(191) NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- The arbiter between racers. Per TENANT: a replay hands back a whole row, so an unscoped key would let a guesser read
-- another shop's correction (anti-IDOR).
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_adjustments' AND INDEX_NAME='uq_adj_org_idem')=0,
    'ALTER TABLE stock_adjustments ADD UNIQUE KEY uq_adj_org_idem (organization_id, idempotency_key)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- The scoped history read: a product's corrections within one tenant.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_adjustments' AND INDEX_NAME='idx_adj_org_product')=0,
    'ALTER TABLE stock_adjustments ADD INDEX idx_adj_org_product (organization_id, product_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
