-- Product list "last rates": the purchase flow (Option B, extended) stamps what a product was last BOUGHT at and
-- what it is to be SOLD at onto the product master itself, so the Product screen reads them off the row it already
-- loads instead of deriving them from purchase/sell history on every open.
--
-- last_sale_rate is a RECORD of what the last purchase set the price to; selling_price stays the LIVE master price
-- (editable on the Product form). They start equal at each purchase and diverge only on a direct price edit.
--
-- Nullable on purpose: a product not yet purchased has nothing stamped, and the screen shows a dash — a DEFAULT 0
-- would read as "bought for nothing". Existing rows therefore start NULL and fill on their next purchase/edit;
-- backfilling history is not possible here (that data lives in business-service's own schema).
--
-- Idempotent (dev ddl-auto:update also adds these): each ADD COLUMN is guarded on information_schema.COLUMNS.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='last_purchase_rate')=0,
    'ALTER TABLE products ADD COLUMN last_purchase_rate DECIMAL(19,2) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='last_sale_rate')=0,
    'ALTER TABLE products ADD COLUMN last_sale_rate DECIMAL(19,2) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='last_rate_at')=0,
    'ALTER TABLE products ADD COLUMN last_rate_at DATETIME DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
