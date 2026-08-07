-- OMS O4 — snapshot the product name on the order line.
--
-- order_items held only product_id, so an order detail screen could show "Product 42" and nothing else. The name
-- was NOT missing at write time: the storefront cart carries product_name on every line (cart_item.product_name)
-- and the checkout threw it away when building the order.
--
-- Stamped at write rather than resolved on read, for two reasons beyond the round trip:
--   1. An order line must say what was SOLD. If the catalog is later renamed ("Blue Shirt" -> "Blue Shirt (old)")
--      or the product is deleted, a read-through name would rewrite history on an order that has already been
--      invoiced, printed and posted to the books.
--   2. Listing or opening orders must not depend on catalog-service being up. A back office that cannot show
--      yesterday's orders because another service is restarting is a back office that cannot be trusted.
--
-- Nullable on purpose: rows written before this migration have no name to backfill from a source that is
-- guaranteed to still agree with what was sold. The UI falls back to the product id for those, which is honest
-- about what is known rather than inventing a current name for a historical line.

SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_items' AND COLUMN_NAME='product_name')=0,
    'ALTER TABLE order_items ADD COLUMN product_name VARCHAR(255) NULL AFTER product_id', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
