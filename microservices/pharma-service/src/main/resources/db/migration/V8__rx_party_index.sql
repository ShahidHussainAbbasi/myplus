-- HMS S4-lite — the pharmacy finds a patient's prescriptions by PERSON (party), resolved from a clinic token, an MRN
-- or a phone. (organization_id, party_id, created_at) serves "this person's prescriptions, newest first" inside one
-- tenant without a scan. Guarded, like V5, so a re-run or a database that already has it changes nothing.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescriptions' AND INDEX_NAME='idx_rx_org_party')=0,
    'CREATE INDEX idx_rx_org_party ON prescriptions (organization_id, party_id, created_at)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
