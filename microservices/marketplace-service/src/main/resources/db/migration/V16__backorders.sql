-- OMS O5c — accept an order you cannot fill today.
--
-- Until now, insufficient stock REJECTED the checkout: a shopper who wanted 10 and could have 8 got nothing and
-- the merchant lost a sale they could have filled in two days. O5b already built the mechanism to resolve it
-- (an order ships in parts); this adds permission to accept one.
--
-- KEY INVARIANT: quantity = (what was invoiced) + quantity_backordered.
-- The shortfall lives on the ORDER and never touches inventory — no negative stock, no phantom reservation.
-- Inventory learns nothing about backordered units until they physically exist. That is what keeps O1's
-- "record the sale at placement" true: the sale is recorded for what can be filled, and the remainder is
-- invoiced when it ships.

-- 1) What is owed but not yet invoiced.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_items' AND COLUMN_NAME='quantity_backordered')=0,
    'ALTER TABLE order_items ADD COLUMN quantity_backordered INT NOT NULL DEFAULT 0 AFTER quantity_shipped', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) When the merchant expects to complete the order. Nullable: every order placed before this migration was
--    filled immediately, and inventing a retrospective promise would make historical orders read as late.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='promised_date')=0,
    'ALTER TABLE orders ADD COLUMN promised_date DATE NULL AFTER shipping_method', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) The new derived state: nothing shipped yet and something is owed. Same ALTER the enum needed for
--    PARTIALLY_SHIPPED in V15 and the return lifecycle in V7 — a Java constant alone fails at runtime with
--    "Data truncated for column 'fulfilment_status'".
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders'
                  AND COLUMN_NAME='fulfilment_status' AND COLUMN_TYPE LIKE '%BACKORDERED%')=0,
    'ALTER TABLE orders MODIFY fulfilment_status enum(''NEW'',''PACKED'',''BACKORDERED'',''PARTIALLY_SHIPPED'',''SHIPPED'',''DELIVERED'',''CANCELLED'',''RETURN_REQUESTED'',''RETURNED'') DEFAULT NULL',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4) The sweeper's query is "orders with something backordered", and the aging view's is "promised before X and
--    not complete". Both are per-tenant.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND INDEX_NAME='idx_orders_org_promised')=0,
    'CREATE INDEX idx_orders_org_promised ON orders (organization_id, promised_date)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
