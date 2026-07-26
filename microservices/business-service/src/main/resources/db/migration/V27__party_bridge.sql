-- Party bridge (P1): link business Customer + Vender to the shared party/contact master (party-service). party_id is
-- the stable cross-module identity; it's stamped best-effort on write (find-or-create a party by contact/email). NULL
-- until bridged — additive, nothing depends on it yet. Idempotent (dev ddl-auto:update also adds it): guarded ADD.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer' AND COLUMN_NAME='party_id')=0,
    'ALTER TABLE customer ADD COLUMN party_id BIGINT DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='vender' AND COLUMN_NAME='party_id')=0,
    'ALTER TABLE vender ADD COLUMN party_id BIGINT DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
