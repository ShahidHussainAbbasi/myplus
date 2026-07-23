-- Multi-rate tax: per-org tax-code (tax-class) master. A product references a code; the code supplies the rate, so a
-- statutory rate change updates ONE row, not every product. Backward compatible: products.tax_code_id NULL keeps the
-- legacy products.tax_rate / org-default behaviour, so single-rate orgs are unaffected. Idempotent (dev uses
-- ddl-auto:update, which also creates these from the entities — CREATE TABLE IF NOT EXISTS + guarded ADD COLUMN).

CREATE TABLE IF NOT EXISTS tax_code (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        DEFAULT NULL,
    user_id          BIGINT        DEFAULT NULL,
    name             VARCHAR(64)   NOT NULL,
    rate             DECIMAL(19,2) DEFAULT NULL,   -- % applied to the taxable base; null treated as 0
    is_default       BIT(1)        DEFAULT NULL,   -- at most one default per org (the fallback for unassigned products)
    active           BIT(1)        DEFAULT NULL,
    created_at       DATETIME      DEFAULT NULL,
    updated_at       DATETIME      DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tax_code_org_name (organization_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- products reference a tax code (loose local id). NULL = fall back to products.tax_rate / org default.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='tax_code_id')=0,
    'ALTER TABLE products ADD COLUMN tax_code_id BIGINT DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
