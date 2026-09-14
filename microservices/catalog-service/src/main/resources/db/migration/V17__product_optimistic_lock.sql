-- BLK-4 — OPTIMISTIC LOCKING ON THE PRODUCT MASTER.
--
-- THE LOST UPDATE THIS STOPS
-- An operator opens a product. Meanwhile a purchase is received, and the purchase flow re-prices the product
-- (PUT /products/{id}/price sets selling_price and stamps the last rates). The operator then saves their form —
-- and writes the OLD selling price back over the one the purchase just set. Two staff editing one product lose
-- one edit the same way. Nothing errors and nothing is logged. Customer had this defect until V62 (business).
--
-- WHY A COUNTER AND NOT updated_at
-- updated_at is set BY the write being validated and two writes inside one second compare equal. A counter JPA
-- owns has neither ambiguity: Hibernate puts it in the UPDATE's WHERE clause and the row count says whether
-- anyone got there first.
--
-- ⚠ NOT NULL DEFAULT 0, AND THAT COMBINATION IS LOAD-BEARING
-- A NULL version makes Hibernate treat an existing row as TRANSIENT and attempt an INSERT, which would
-- DUPLICATE every existing product on its next edit. The DEFAULT is what keeps every row already in the table
-- editable. BIGINT because the entity field is Long; FlywayMigrationTest validates the two against each other.
--
-- Idempotent in V11's idiom: dev runs ddl-auto:update, which may already have added the column from the entity.
-- (Next version picked with a NUMERIC sort — see V11's note on how a lexical sort once hid a V10.)

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='version')=0,
    'ALTER TABLE products ADD COLUMN version BIGINT NOT NULL DEFAULT 0', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
