-- M5 (slice 100): pharmacy migrates off the business itemId bridge onto the catalog productId (single Product master).
-- Converges item_id → product_id on the pharma tables so Hibernate `validate` matches the renamed entity fields.
--
-- State-aware + idempotent: on dev DBs `ddl-auto:update` already ADDED product_id alongside the legacy item_id on some
-- tables (never dropping item_id), while others still have only item_id. So per column we: if BOTH exist → DROP the old
-- item_id (product_id already present); else if only item_id exists → RENAME it to product_id; else no-op. Uses the real
-- (plural) table names. Re-runnable.
--
-- NOTE: RENAME preserves VALUES — legacy dev rows still hold old business itemId numbers, NOT catalog productIds.
-- Pharmacy is pre-production; re-seed clinical/prescription/interaction/dispensing data via the productId-native screens
-- (the Cypress specs create fresh data).

-- prescription_items.item_id -> product_id
SET @has_old := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescription_items' AND COLUMN_NAME='item_id');
SET @has_new := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescription_items' AND COLUMN_NAME='product_id');
SET @ddl := IF(@has_old>0 AND @has_new>0, 'ALTER TABLE prescription_items DROP COLUMN item_id',
             IF(@has_old>0, 'ALTER TABLE prescription_items RENAME COLUMN item_id TO product_id', 'DO 0'));
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- dispensing.item_id -> product_id
SET @has_old := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dispensing' AND COLUMN_NAME='item_id');
SET @has_new := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dispensing' AND COLUMN_NAME='product_id');
SET @ddl := IF(@has_old>0 AND @has_new>0, 'ALTER TABLE dispensing DROP COLUMN item_id',
             IF(@has_old>0, 'ALTER TABLE dispensing RENAME COLUMN item_id TO product_id', 'DO 0'));
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- medicine_clinical.item_id -> product_id
SET @has_old := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='medicine_clinical' AND COLUMN_NAME='item_id');
SET @has_new := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='medicine_clinical' AND COLUMN_NAME='product_id');
SET @ddl := IF(@has_old>0 AND @has_new>0, 'ALTER TABLE medicine_clinical DROP COLUMN item_id',
             IF(@has_old>0, 'ALTER TABLE medicine_clinical RENAME COLUMN item_id TO product_id', 'DO 0'));
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- drug_interactions.item_id1 -> product_id1
SET @has_old := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='drug_interactions' AND COLUMN_NAME='item_id1');
SET @has_new := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='drug_interactions' AND COLUMN_NAME='product_id1');
SET @ddl := IF(@has_old>0 AND @has_new>0, 'ALTER TABLE drug_interactions DROP COLUMN item_id1',
             IF(@has_old>0, 'ALTER TABLE drug_interactions RENAME COLUMN item_id1 TO product_id1', 'DO 0'));
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- drug_interactions.item_id2 -> product_id2
SET @has_old := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='drug_interactions' AND COLUMN_NAME='item_id2');
SET @has_new := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='drug_interactions' AND COLUMN_NAME='product_id2');
SET @ddl := IF(@has_old>0 AND @has_new>0, 'ALTER TABLE drug_interactions DROP COLUMN item_id2',
             IF(@has_old>0, 'ALTER TABLE drug_interactions RENAME COLUMN item_id2 TO product_id2', 'DO 0'));
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
