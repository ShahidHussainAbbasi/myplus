-- Review finding D2 — retire the pre-catalog medicine schema.
--
-- Before the catalog convergence (slice 100 / M5) pharma owned its own product world: `medicines` (+ its
-- `drug_categories` lookup), `pharmacy_stock`, and the short-lived `medicine_profile` from the abandoned P0b
-- work. A medicine is now a catalog Product and pharmacy stock is an inventory StockEntry, so none of these four
-- tables has an entity, a repository, or a single query anywhere in the service.
--
-- The part that actually matters is NOT the dead tables — it is that three LIVE tables still carry dead FK
-- columns into `medicines`:
--     prescription_items.medicine_id, dispensing.medicine_id,
--     drug_interactions.medicine1_id, drug_interactions.medicine2_id
-- Hibernate stopped mapping them at the rebase, so every row written since inserts NULL, but the constraints are
-- still enforced by MySQL. Verified before writing this: 0 non-NULL values across 74 prescription_items,
-- 18 dispensing and 5 drug_interactions rows on dev. Same shape (and same guarded style) as inventory's
-- V3__drop_stale_product_fks.sql, which cleaned up the identical leftover after the catalog/inventory split.
--
-- SAFE TO DROP THE TABLES: pharmacy is pre-production — see the note in V3__pharma_itemid_to_productid.sql
-- ("Pharmacy is pre-production; re-seed ... via the productId-native screens"). Dev row counts at the time of
-- writing: medicines 0, pharmacy_stock 0, drug_categories 0, medicine_profile 1 (Cypress debris: 'CyBrand' /
-- 'CyMed_1782237989720'). Nothing reads any of them.
--
-- IDEMPOTENT throughout: FK names are auto-generated and differ per environment, so every drop is resolved from
-- information_schema and skipped when already gone. Re-runnable; a fresh database no-ops the whole script.

-- 1. Drop the stale FKs on the LIVE tables ---------------------------------------------------------------
SET @fk := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescription_items' AND COLUMN_NAME='medicine_id'
    AND REFERENCED_TABLE_NAME='medicines' LIMIT 1);
SET @sql := IF(@fk IS NOT NULL, CONCAT('ALTER TABLE prescription_items DROP FOREIGN KEY ', @fk), 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @fk := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dispensing' AND COLUMN_NAME='medicine_id'
    AND REFERENCED_TABLE_NAME='medicines' LIMIT 1);
SET @sql := IF(@fk IS NOT NULL, CONCAT('ALTER TABLE dispensing DROP FOREIGN KEY ', @fk), 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @fk := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='drug_interactions' AND COLUMN_NAME='medicine1_id'
    AND REFERENCED_TABLE_NAME='medicines' LIMIT 1);
SET @sql := IF(@fk IS NOT NULL, CONCAT('ALTER TABLE drug_interactions DROP FOREIGN KEY ', @fk), 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @fk := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='drug_interactions' AND COLUMN_NAME='medicine2_id'
    AND REFERENCED_TABLE_NAME='medicines' LIMIT 1);
SET @sql := IF(@fk IS NOT NULL, CONCAT('ALTER TABLE drug_interactions DROP FOREIGN KEY ', @fk), 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 2. Drop the dead columns they guarded, from the LIVE tables ---------------------------------------------
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME='prescription_items' AND COLUMN_NAME='medicine_id')>0,
    'ALTER TABLE prescription_items DROP COLUMN medicine_id', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME='dispensing' AND COLUMN_NAME='medicine_id')>0,
    'ALTER TABLE dispensing DROP COLUMN medicine_id', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME='drug_interactions' AND COLUMN_NAME='medicine1_id')>0,
    'ALTER TABLE drug_interactions DROP COLUMN medicine1_id', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME='drug_interactions' AND COLUMN_NAME='medicine2_id')>0,
    'ALTER TABLE drug_interactions DROP COLUMN medicine2_id', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 3. Drop the dead tables. Order matters: pharmacy_stock and drug_categories reference `medicines` /
--    `drug_categories`, so children go first. IF EXISTS keeps it re-runnable.
DROP TABLE IF EXISTS pharmacy_stock;
DROP TABLE IF EXISTS medicine_profile;
DROP TABLE IF EXISTS medicines;
DROP TABLE IF EXISTS drug_categories;
