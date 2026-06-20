-- Slice 33, Phase 4.5 — bring inventory-service to the multi-tenant standard (org-scoping).
-- Adds organization_id (tenant) + user_id/user_type (audit + NULL-fallback read) to each tenant-owned
-- table. Mirrors business-service V2: IDEMPOTENT guarded ADDs (information_schema check) so re-running on
-- a DB where ddl-auto:update already added the columns is a no-op. Safe on fresh, dev, and prod.
-- Columns are nullable; existing rows keep NULL (visible only to their author via the NULL-fallback read).

-- helper macro is inlined per column (MySQL has no ADD COLUMN IF NOT EXISTS).
SET @t := 'products';
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE products ADD COLUMN organization_id BIGINT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='user_id')=0, 'ALTER TABLE products ADD COLUMN user_id BIGINT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='user_type')=0, 'ALTER TABLE products ADD COLUMN user_type VARCHAR(255) NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='categories' AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE categories ADD COLUMN organization_id BIGINT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='categories' AND COLUMN_NAME='user_id')=0, 'ALTER TABLE categories ADD COLUMN user_id BIGINT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='categories' AND COLUMN_NAME='user_type')=0, 'ALTER TABLE categories ADD COLUMN user_type VARCHAR(255) NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='suppliers' AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE suppliers ADD COLUMN organization_id BIGINT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='suppliers' AND COLUMN_NAME='user_id')=0, 'ALTER TABLE suppliers ADD COLUMN user_id BIGINT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='suppliers' AND COLUMN_NAME='user_type')=0, 'ALTER TABLE suppliers ADD COLUMN user_type VARCHAR(255) NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='warehouses' AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE warehouses ADD COLUMN organization_id BIGINT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='warehouses' AND COLUMN_NAME='user_id')=0, 'ALTER TABLE warehouses ADD COLUMN user_id BIGINT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='warehouses' AND COLUMN_NAME='user_type')=0, 'ALTER TABLE warehouses ADD COLUMN user_type VARCHAR(255) NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_entries' AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE stock_entries ADD COLUMN organization_id BIGINT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_entries' AND COLUMN_NAME='user_id')=0, 'ALTER TABLE stock_entries ADD COLUMN user_id BIGINT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_entries' AND COLUMN_NAME='user_type')=0, 'ALTER TABLE stock_entries ADD COLUMN user_type VARCHAR(255) NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
