-- SF-3: idempotent sale submission — one invoice per (organization_id, idempotency_key), so a double-click /
-- network retry of the same checkout records ONE invoice. IDEMPOTENT: guarded by information_schema so re-running
-- on an already-migrated DB is a no-op. MySQL treats multiple NULLs as distinct, so legacy non-saga rows are safe.

-- 1) Ensure the column exists, and NORMALIZE it to VARCHAR(191): at utf8mb4 (4 bytes/char) a 255-char column is
--    1020 bytes and overflows this table's 1000-byte index limit. 191*4 = 764 bytes fits; keys are ~36-char UUIDs.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='idempotency_key')=0,
    'ALTER TABLE customer_history ADD COLUMN idempotency_key VARCHAR(191) NULL',
    'ALTER TABLE customer_history MODIFY COLUMN idempotency_key VARCHAR(191) NULL');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) Unique index on (organization_id, idempotency_key).
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND INDEX_NAME='uq_ch_org_idempotency')=0,
    'CREATE UNIQUE INDEX uq_ch_org_idempotency ON customer_history (organization_id, idempotency_key)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
