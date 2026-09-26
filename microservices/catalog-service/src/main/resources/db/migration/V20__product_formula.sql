-- PH-FORMULA — a medicine's formula (generic / salt composition), e.g. "Paracetamol 500mg + Caffeine 65mg".
-- Design: microservices/docs/slices/pharma-formula.md
--
-- NULL = no formula (never ''). VARCHAR(191) so (organization_id, formula) stays inside the 1000-byte index limit
-- at utf8mb4 (the same reason every other indexed text column here is 191). The index serves the distinct list
-- behind the form's autocomplete and the "same formula" lookups; the existing product search uses LIKE '%q%',
-- which cannot use it anyway, so it costs nothing that search needed.
-- Both steps are information_schema-guarded: safe to re-run, no manual step on any database.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='formula')=0,
    'ALTER TABLE products ADD COLUMN formula VARCHAR(191) NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND INDEX_NAME='idx_products_org_formula')=0,
    'CREATE INDEX idx_products_org_formula ON products (organization_id, formula)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
