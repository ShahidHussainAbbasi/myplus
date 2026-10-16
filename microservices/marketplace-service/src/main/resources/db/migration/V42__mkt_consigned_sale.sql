-- MKT-5c — the MaxTheService warehouse sells consigned stock; the owner is paid on sell-through (slice doc
-- docs/slices/mkt-5c-consigned-sale.md). Two changes:
--
-- 1. mkt_offer: one offer per seller per product AND OWNER. The warehouse already lists its own stock of a product
--    (PLATFORM, owner = the warehouse); consigned stock of the same product is a second offer of the warehouse whose
--    owner is the consignor, one per consignor. Every row before this has owner = its seller org or the one supplier it
--    is restamped to, so every existing row satisfies the wider key.
-- 2. mkt_consigned_stock: a SOLD movement per order line and lot (the transfer it came in on), with the owner's
--    invoice, the warehouse's purchase and the price owed a unit. A sale that had to be voided leaves a VOIDED row and
--    the next try is a new attempt, so its keys never replay the voided invoice. RECEIVED rows keep order_line_id 0 and
--    attempt 0, so "once per transfer" still holds for them.
--
-- VARCHAR statuses (never MySQL ENUM), DECIMAL(19,2) money, ddl-auto=validate. Idempotent (D7).

SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_offer' AND INDEX_NAME = 'uk_mkt_offer_org_product_owner');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_offer ADD UNIQUE KEY uk_mkt_offer_org_product_owner (organization_id, mkt_product_id, stock_owner_organization_id)', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_offer' AND INDEX_NAME = 'uk_mkt_offer_org_product');
SET @s := IF(@c > 0, 'ALTER TABLE mkt_offer DROP INDEX uk_mkt_offer_org_product', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_consigned_stock' AND COLUMN_NAME = 'order_line_id');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_consigned_stock ADD COLUMN order_line_id BIGINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_consigned_stock' AND COLUMN_NAME = 'attempt');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_consigned_stock ADD COLUMN attempt INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_consigned_stock' AND COLUMN_NAME = 'sale_invoice_no');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_consigned_stock ADD COLUMN sale_invoice_no VARCHAR(64) NULL', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_consigned_stock' AND COLUMN_NAME = 'purchase_id');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_consigned_stock ADD COLUMN purchase_id BIGINT NULL', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_consigned_stock' AND COLUMN_NAME = 'unit_price');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_consigned_stock ADD COLUMN unit_price DECIMAL(19,2) NULL', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_consigned_stock' AND INDEX_NAME = 'uq_mkt_consigned_move');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_consigned_stock ADD UNIQUE KEY uq_mkt_consigned_move (transfer_id, movement, order_line_id, attempt)', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_consigned_stock' AND INDEX_NAME = 'uq_mkt_consigned_transfer');
SET @s := IF(@c > 0, 'ALTER TABLE mkt_consigned_stock DROP INDEX uq_mkt_consigned_transfer', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
