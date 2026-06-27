-- Slice 33, Phase 5b/U2b — drop stale foreign keys left over from before the catalog/inventory split.
-- When inventory owned a Product entity, StockEntry/StockAdjustment/StockTransfer/StockAlert had a
-- @ManyToOne Product, so MySQL created `<table>.product_id -> products(id)` FKs. Phase 5b made product_id a
-- plain cross-service reference to catalog-service's Product; ddl-auto:update does NOT drop the old FK, so an
-- existing dev DB still rejects catalog productIds (they don't exist in inventory's orphan `products` table).
-- A fresh DB never creates these FKs, so this is purely an evolutionary cleanup. IDEMPOTENT: each drop is
-- guarded by information_schema (constraint names are auto-generated and vary per environment).

SET @t := 'stock_entries';
SET @fk := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=@t AND COLUMN_NAME='product_id' AND REFERENCED_TABLE_NAME='products' LIMIT 1);
SET @sql := IF(@fk IS NOT NULL, CONCAT('ALTER TABLE ', @t, ' DROP FOREIGN KEY ', @fk), 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t := 'stock_adjustments';
SET @fk := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=@t AND COLUMN_NAME='product_id' AND REFERENCED_TABLE_NAME='products' LIMIT 1);
SET @sql := IF(@fk IS NOT NULL, CONCAT('ALTER TABLE ', @t, ' DROP FOREIGN KEY ', @fk), 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t := 'stock_transfers';
SET @fk := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=@t AND COLUMN_NAME='product_id' AND REFERENCED_TABLE_NAME='products' LIMIT 1);
SET @sql := IF(@fk IS NOT NULL, CONCAT('ALTER TABLE ', @t, ' DROP FOREIGN KEY ', @fk), 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t := 'stock_alerts';
SET @fk := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=@t AND COLUMN_NAME='product_id' AND REFERENCED_TABLE_NAME='products' LIMIT 1);
SET @sql := IF(@fk IS NOT NULL, CONCAT('ALTER TABLE ', @t, ' DROP FOREIGN KEY ', @fk), 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
