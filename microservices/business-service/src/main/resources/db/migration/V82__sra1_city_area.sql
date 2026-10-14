-- SRA-1 (2026-10-14) — areas: a business's own list of CITIES and the AREAS inside them, and the area a customer or
-- supplier is in. Design: microservices/docs/slices/sra-sales-by-rep-and-area.md §4.
--
-- WHY A LIST and not the free-text `customer.city`: reports group sales by area (SRA-4), and free text splits one area
-- into several groups ("Khanpur", "KHANPUR(1ST)", "Khan pur"). The O8 round sheet already had to use the shipping
-- address as its "area" because nothing better existed.
--
-- Rows are DATA, owned by the business: no city or area is seeded, and nothing is deleted (an INACTIVE row leaves the
-- pickers but still names the customers filed under it).
--
-- ⭐ The UNIQUE keys are case-insensitive BY COLLATION (utf8mb4_0900_ai_ci), so "zahir pir" is refused beside
-- "Zahir Pir" without a separate lower-cased key column. The service trims before saving; the key does the rest,
-- including under two clicks at once.
--
-- `customer.city` (free text, printed on documents) STAYS: the customer screen writes it from the picked city, so a
-- printed invoice reads the same column it always did. Customers with a typed city and no area are untouched.

CREATE TABLE IF NOT EXISTS city (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NOT NULL,
    name             VARCHAR(80)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    user_id          BIGINT       NULL,
    created_at       DATETIME     NULL,
    updated_at       DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_city_org_name (organization_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS area (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NOT NULL,
    city_id          BIGINT       NOT NULL,
    name             VARCHAR(80)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    user_id          BIGINT       NULL,
    created_at       DATETIME     NULL,
    updated_at       DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_area_org_city_name (organization_id, city_id, name),
    KEY idx_area_city (city_id),
    CONSTRAINT fk_area_city FOREIGN KEY (city_id) REFERENCES city (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The area a customer / supplier is filed under. NULL = not filed yet (every existing row). No FK to `area`: the two
-- registers predate it and an FK would make a customer save depend on a lookup table's state. The service validates
-- the id against the caller's own business instead (anti-IDOR), which is the check that matters.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer' AND COLUMN_NAME = 'area_id') = 0,
    'ALTER TABLE customer ADD COLUMN area_id BIGINT NULL',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer' AND INDEX_NAME = 'idx_customer_org_area') = 0,
    'CREATE INDEX idx_customer_org_area ON customer (organization_id, area_id)',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vender' AND COLUMN_NAME = 'area_id') = 0,
    'ALTER TABLE vender ADD COLUMN area_id BIGINT NULL',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
