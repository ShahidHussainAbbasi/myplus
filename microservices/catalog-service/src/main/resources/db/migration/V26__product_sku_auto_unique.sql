-- SKU-AUTO-1 (doc slices/109-sku-auto-code.md) — ONE code per product: the SKU. A blank SKU is filled on save with a
-- 12-digit code ('2' + an 11-digit per-business number, Lightspeed's format); the user may type or change it.
--
-- 1. org_document_seq: the per-business counter common-docnum allocates from (same table as business/expense/finance).
-- 2. UNIQUE (organization_id, sku) replacing the non-unique idx_products_org_sku — until now uniqueness was only a
--    service pre-check, which two simultaneous saves both pass. NULL stays allowed for any number of rows.
--    ⚠ Added ONLY when no business already holds a duplicate SKU (STANDARDS D5: count, never guess which row to
--    rename). Dev counted 0 on 2026-10-12; other environments: if they hold duplicates this step is skipped, the
--    non-unique index stays, and the deploy job (SKU-AUTO-2) reports them. Existing blank SKUs: SKU-AUTO-2's deploy job.
--    ⚠ And ONLY on InnoDB: (BIGINT + VARCHAR(255) utf8mb4) = 1028 bytes, over MyISAM's 1000-byte key limit.
-- Idempotent throughout (D7): every step checks information_schema first; dev runs ddl-auto:update too.

CREATE TABLE IF NOT EXISTS org_document_seq (
    organization_id  BIGINT        NOT NULL,
    doc_type         VARCHAR(16)   NOT NULL,
    next_val         BIGINT        NOT NULL DEFAULT 0,
    updated          DATETIME      DEFAULT NULL,
    PRIMARY KEY (organization_id, doc_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET @dups := (SELECT COUNT(*) FROM (SELECT 1 FROM products WHERE sku IS NOT NULL
                                    GROUP BY organization_id, sku HAVING COUNT(*) > 1) d);
SET @innodb := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND ENGINE='InnoDB');
SET @ddl := IF(@dups = 0 AND @innodb = 1 AND (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND INDEX_NAME='uq_products_org_sku')=0,
    'CREATE UNIQUE INDEX uq_products_org_sku ON products (organization_id, sku)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- The unique index serves every query the old one did (same leading columns); drop the old one only once it exists.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND INDEX_NAME='uq_products_org_sku') > 0
           AND (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND INDEX_NAME='idx_products_org_sku') > 0,
    'DROP INDEX idx_products_org_sku ON products', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
