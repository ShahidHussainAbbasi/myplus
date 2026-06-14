-- Forward migration for slice 28 org-scoping (tech-debt #13/#18): add organization_id to the welfare
-- domain tables. IDEMPOTENT — guarded by information_schema so it's a no-op where ddl-auto already
-- added the column (dev) and correct on a fresh/prod (validate) DB. Runs after the V1 baseline.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='donation' AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE donation ADD COLUMN organization_id BIGINT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='donator' AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE donator ADD COLUMN organization_id BIGINT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
