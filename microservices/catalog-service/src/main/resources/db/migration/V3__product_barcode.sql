-- Barcode-first sell: a product's scannable code (EAN/UPC), distinct from the internal sku. Nullable — a product with
-- no barcode is still found by sku. Idempotent (dev ddl-auto:update also adds it): guarded ADD COLUMN + index.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='barcode')=0,
    'ALTER TABLE products ADD COLUMN barcode VARCHAR(64) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND INDEX_NAME='idx_products_barcode')=0,
    'CREATE INDEX idx_products_barcode ON products (barcode)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
