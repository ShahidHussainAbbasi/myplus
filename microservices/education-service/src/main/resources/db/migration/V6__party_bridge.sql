-- Party bridge (P3): link education Student to the shared party/contact master (party-service). party_id is the
-- stable cross-module identity, stamped best-effort on write (find-or-create a party by mobile/email). NULL until
-- bridged — additive, nothing depends on it yet. Idempotent (dev ddl-auto:update also adds it): guarded ADD.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='student' AND COLUMN_NAME='party_id')=0,
    'ALTER TABLE student ADD COLUMN party_id BIGINT DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
