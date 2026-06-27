-- Forward migration: add the `demo` flag to users (slice 32 SaaS signup — marks seeded demo accounts).
-- IDEMPOTENT — guarded by information_schema so it's a no-op where ddl-auto already added the column (dev)
-- and correct on a fresh/prod (validate) DB. Runs after the V2 org-plan migration.
-- NOT NULL DEFAULT b'0' so existing/legacy users are treated as real (non-demo) accounts.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='users' AND COLUMN_NAME='demo')=0, 'ALTER TABLE users ADD COLUMN demo bit(1) NOT NULL DEFAULT b''0''', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
