-- SSR-0c (doc slices/110-stock-ledger-ssr.md) — the product's MAIN SUPPLIER: the group it sits under on the Stock & Sales
-- Report. A business-service vender id (no FK: another service's table). NULL until the product's first purchase
-- stamps it, or an admin sets it on the product form. Products bought before this ships: the report falls back to the
-- supplier of their first purchase (business-service's own history), so no cross-service back-fill is needed.
-- Indexed with the tenant column: the report groups one business's products by it.
-- Idempotent (D7). V27: V26 is the highest catalog version - checked.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='main_supplier_id')=0,
    'ALTER TABLE products ADD COLUMN main_supplier_id BIGINT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND INDEX_NAME='idx_products_org_main_supplier')=0,
    'CREATE INDEX idx_products_org_main_supplier ON products (organization_id, main_supplier_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
