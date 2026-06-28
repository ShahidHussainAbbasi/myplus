-- M3c.4f (slice 88) — DESTRUCTIVE: retire the local `stock` table. The convergence is complete — inventory-service
-- is authoritative for on-hand/batches, sells/purchases carry product_id (V5) + self-describing snapshots (V6), and no
-- code reads or writes local Stock anymore. This drops the Sell/Purchase/Item → stock foreign keys + their stock_id
-- columns, then the stock table itself.
--
-- IRREVERSIBLE. This MUST run AFTER V5/V6 (which backfill product_id + the purchase snapshot FROM stock). On a fresh/prod
-- deploy of an existing pre-convergence DB, Flyway applies V1..V7 in order, so the backfills complete before this drop.
-- Idempotent: FK drops look the constraint up by name (no-op if already gone); column/table drops are guarded.

-- 1) Drop the FK constraints that reference `stock` (Hibernate-generated names → resolve dynamically).
SET @fk := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sell' AND COLUMN_NAME='stock_id' AND REFERENCED_TABLE_NAME='stock' LIMIT 1);
SET @ddl := IF(@fk IS NOT NULL, CONCAT('ALTER TABLE sell DROP FOREIGN KEY ', @fk), 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @fk := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='stock_id' AND REFERENCED_TABLE_NAME='stock' LIMIT 1);
SET @ddl := IF(@fk IS NOT NULL, CONCAT('ALTER TABLE purchase DROP FOREIGN KEY ', @fk), 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @fk := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='item' AND COLUMN_NAME='stock_id' AND REFERENCED_TABLE_NAME='stock' LIMIT 1);
SET @ddl := IF(@fk IS NOT NULL, CONCAT('ALTER TABLE item DROP FOREIGN KEY ', @fk), 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) Drop the now-orphan stock_id columns (guarded — no-op if already dropped).
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sell' AND COLUMN_NAME='stock_id')>0,
  'ALTER TABLE sell DROP COLUMN stock_id', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='stock_id')>0,
  'ALTER TABLE purchase DROP COLUMN stock_id', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='item' AND COLUMN_NAME='stock_id')>0,
  'ALTER TABLE item DROP COLUMN stock_id', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 3) Drop the table.
DROP TABLE IF EXISTS stock;
