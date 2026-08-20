-- Slice I2 (CSV product import) — the index behind the import's batched duplicate check.
--
-- D3/D3b: index the predicate the query actually RUNS. The import asks
--     WHERE sku IN (…) AND (organization_id = ? OR (organization_id IS NULL AND user_id = ?))
-- and the existing indexes do not serve it: idx_products_org_user is (organization_id, user_id), and the
-- Hibernate-generated index on `sku` alone cannot narrow by tenant. Shipped WITH the method that needs it.
--
-- NO PREFIX IS NEEDED HERE, and that is worth stating because slice I1's equivalent index required one.
-- `products` is InnoDB (verified against the live schema), whose key limit is 3072 bytes with DYNAMIC row
-- format. organization_id (8) + sku varchar(255) utf8mb4 (1020) = ~1028 bytes, comfortably inside it.
-- I1's customer.contact index needed contact(64) only because `customer` is MyISAM, capped at 1000 bytes.
-- Do not copy that prefix here by analogy — the engines differ, so the arithmetic differs.
--
-- NOT unique, deliberately: `sku` is optional in the product master and 10 of the 1581 live products have
-- none, so a UNIQUE index would fail this migration on real data (and NULLs would not collide anyway).
-- The import enforces uniqueness in application code and reports what it skipped.
--
-- Guarded because MySQL has no CREATE INDEX IF NOT EXISTS.

SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema = DATABASE()
               AND table_name = 'products'
               AND index_name = 'idx_products_org_sku');

SET @sql := IF(@idx = 0,
    'CREATE INDEX idx_products_org_sku ON products (organization_id, sku)',
    'DO 0');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
