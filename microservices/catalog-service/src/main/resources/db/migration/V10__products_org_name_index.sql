-- Slice I2 (revision) — index behind the import's NAME fallback for products with no SKU.
--
-- Q2 was reversed on 2026-08-20: `sku` is no longer mandatory in the product template. A row with no SKU
-- still needs a duplicate key, or re-importing the same file would create the product again every time —
-- a catalogue that silently doubles, which is the failure the required-SKU rule existed to prevent.
--
-- So the import keys on `sku` when present and falls back to `name`, and that fallback needs the same
-- batched IN lookup (one query per FILE, never one per row) that the SKU path gets from V9.
--
-- InnoDB, so no prefix is needed: organization_id (8) + name varchar(255) utf8mb4 (1020) = ~1028 bytes
-- against a 3072-byte limit. (Contrast business-service's V41, where `customer` is MyISAM at 1000 bytes and
-- contact(64) is load-bearing — the engines differ, so the arithmetic differs.)
--
-- NOT unique: duplicate product names are allowed by design in the master — /name-check warns, it does not
-- refuse — so a UNIQUE index would fail this migration on live data.

SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema = DATABASE()
               AND table_name = 'products'
               AND index_name = 'idx_products_org_name');

SET @sql := IF(@idx = 0,
    'CREATE INDEX idx_products_org_name ON products (organization_id, name)',
    'DO 0');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
