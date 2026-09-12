-- DUP-1: idempotent product creation — one product per (organization_id, idempotency_key), so a held Enter,
-- a double-click or a network retry of the same form-fill registers ONE product.
--
-- WHY THIS EXISTS: a production shop registered 148 products from one submit. Nothing stopped it at any layer —
-- the product name is deliberately NOT unique (see V10), and the SKU duplicate check is skipped for a blank SKU
-- (ProductService.create: `if (sku != null && ...)`), which is exactly the shape that burst had. This index is
-- the only place that can arbitrate between requests that are all IN FLIGHT AT ONCE: every one of those 148
-- pre-checks would have found nothing, because none of them had committed yet.
--
-- Mirrors business-service V10__ch_idempotency_unique.sql (SF-3, the same defect on the sale) rather than
-- inventing a second mechanism. IDEMPOTENT: guarded by information_schema, so re-running on an already-migrated
-- database is a no-op. MySQL treats multiple NULLs as distinct, so every existing product row stays legal.

-- 1) Ensure the column exists, and NORMALIZE it to VARCHAR(191): at utf8mb4 (4 bytes/char) a 255-char column is
--    1020 bytes and overflows the 1000-byte index limit. 191*4 = 764 bytes fits; keys are ~36-char UUIDs.
--    ⚠ The length must stay in step with Product.idempotencyKey (@Column length=191) — catalog-service runs
--    ddl-auto=update, and a mismatch invites Hibernate to ALTER the column behind Flyway's back.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='idempotency_key')=0,
    'ALTER TABLE products ADD COLUMN idempotency_key VARCHAR(191) NULL',
    'ALTER TABLE products MODIFY COLUMN idempotency_key VARCHAR(191) NULL');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) Unique index on (organization_id, idempotency_key).
--    Scoped by org rather than unique on the key alone, deliberately: the replay READS BACK a product, so an
--    unscoped key would let a caller who guessed one read another tenant's product. The cost of that choice is
--    that a legacy row with organization_id IS NULL is not covered (NULLs are distinct) — create() stamps the
--    org from CurrentUser, so every row written through the UI is.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND INDEX_NAME='uq_products_org_idempotency')=0,
    'CREATE UNIQUE INDEX uq_products_org_idempotency ON products (organization_id, idempotency_key)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
