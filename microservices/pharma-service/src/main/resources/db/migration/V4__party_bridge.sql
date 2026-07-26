-- Party bridge (P3): link a pharmacy Prescription's patient to the shared party/contact master (party-service). The
-- patient is denormalized (patient_name/patient_phone) — there's no Patient entity — so we stamp party_id on the
-- prescription, found by patient_phone. So a prescription patient dedupes to the same party as their POS customer
-- (same phone, same org). NULL until bridged — additive. Idempotent (dev ddl-auto:update also adds it): guarded ADD.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescriptions' AND COLUMN_NAME='party_id')=0,
    'ALTER TABLE prescriptions ADD COLUMN party_id BIGINT DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
