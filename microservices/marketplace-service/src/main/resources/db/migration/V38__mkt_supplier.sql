-- MKT-4a — a supplier is an approved seller the operator marks as one (ruling, 2026-10-15: a MyPlus business, not an
-- API-only company). Its offers are SUPPLIER stock: the supplier owns, holds and ships it (R4.3). Its stock is shown
-- as confirmed only when the operator also marks its stock counts as reliable (R4.4).
-- slice: docs/slices/mkt-4a-supplier-offers.md
--
-- BIT(1) for Boolean (as mkt_offer.paused), ddl-auto=validate. Idempotent (D7).

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_seller_account' AND COLUMN_NAME = 'supplier');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_seller_account ADD COLUMN supplier BIT(1) NOT NULL DEFAULT b''0''', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_seller_account' AND COLUMN_NAME = 'supplier_stock_reliable');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_seller_account ADD COLUMN supplier_stock_reliable BIT(1) NOT NULL DEFAULT b''0''', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- The public read model carries whether an offer's stock is confirmed (R4.4), so the catalogue never joins the seller
-- account. 1 for every existing row: merchant and platform stock is confirmed by the last inventory sync, as before.
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_offer_projection' AND COLUMN_NAME = 'stock_confirmed');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_offer_projection ADD COLUMN stock_confirmed BIT(1) NOT NULL DEFAULT b''1''', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
