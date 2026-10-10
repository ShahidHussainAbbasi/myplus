-- HMS S3b-1 — a prescription written by the clinic's doctor (docs/hms-phase1-design.md §4d). Additive; every existing
-- prescription becomes source COUNTER (what it was: recorded at the pharmacy counter).
--
--   source        DOCTOR (submitted from the consultation) | COUNTER (recorded by the pharmacist)
--   token_label   the clinic token of the visit, e.g. A-040 — what the pharmacist searches by
--   encounter_id  the visit in clinical-service
--   external_ref  "enc-<encounter id>": UNIQUE per organisation, so a retried Submit returns the same prescription
--                 instead of making a second one. NULL for counter prescriptions (NULLs never collide).
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescriptions' AND COLUMN_NAME='source')=0,
    'ALTER TABLE prescriptions ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT ''COUNTER'', ADD COLUMN token_label VARCHAR(16) NULL, ADD COLUMN encounter_id BIGINT NULL, ADD COLUMN external_ref VARCHAR(64) NULL',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescriptions' AND INDEX_NAME='uq_rx_external_ref')=0,
    'CREATE UNIQUE INDEX uq_rx_external_ref ON prescriptions (organization_id, external_ref)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
