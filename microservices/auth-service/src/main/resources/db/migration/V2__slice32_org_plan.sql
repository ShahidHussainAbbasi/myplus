-- Forward migration for slice 32 (SaaS signup / tenant entitlement): add plan, trial_ends_at, entry_cap
-- to organizations. IDEMPOTENT — guarded by information_schema so it's a no-op where ddl-auto already
-- added the column (dev) and correct on a fresh/prod (validate) DB. Runs after the V1 baseline.
-- plan defaults to FREE so legacy/existing orgs are neither capped nor treated as a trial.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='organizations' AND COLUMN_NAME='plan')=0, 'ALTER TABLE organizations ADD COLUMN plan VARCHAR(255) NULL DEFAULT ''FREE''', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='organizations' AND COLUMN_NAME='trial_ends_at')=0, 'ALTER TABLE organizations ADD COLUMN trial_ends_at DATETIME(6) NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='organizations' AND COLUMN_NAME='entry_cap')=0, 'ALTER TABLE organizations ADD COLUMN entry_cap INT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
-- Backfill any rows that predate the column (ddl-auto added it NULL in dev).
UPDATE organizations SET plan='FREE' WHERE plan IS NULL;
