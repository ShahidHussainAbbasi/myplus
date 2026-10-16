-- HC-1d — a prescription SENT by a linked Hospital / Clinic (a separate business) is written in this pharmacy's
-- organisation and remembers who sent it (docs/slices/hc-1cd-clinic-pharmacy-link.md, R3/R5).
--   from_org_id    the clinic's organisation; the clinic may read back ONLY rows carrying its own id. NULL = not sent
--                  by a linked clinic (counter prescriptions, and the combined org's own doctors — unchanged).
--   from_org_name  the clinic's name STAMPED at write, so the pharmacy's list shows "From clinic <name>" without
--                  asking auth per row, and still shows it after the clinic is renamed or the link ends.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescriptions' AND COLUMN_NAME='from_org_id')=0,
    'ALTER TABLE prescriptions ADD COLUMN from_org_id BIGINT NULL, ADD COLUMN from_org_name VARCHAR(200) NULL',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescriptions' AND INDEX_NAME='idx_rx_from_org')=0,
    'CREATE INDEX idx_rx_from_org ON prescriptions (from_org_id, id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
