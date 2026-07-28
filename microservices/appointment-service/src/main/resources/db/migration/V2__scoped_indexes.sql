-- Tenant-scope indexes. Every read in this service filters by organization_id and none of the four tables had an
-- index on it, so each scoped read was a full table scan.
--
-- Single-column, unlike the other services' (organization_id, user_id): these tables have organization_id NOT NULL
-- and NO user_id at all, so there is no NULL-fallback leg to cover — appointment was org-scoped from day one
-- rather than retrofitted onto per-user rows.
--
-- Idempotent: guarded on information_schema.STATISTICS, so re-running is a no-op.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='appointment' AND INDEX_NAME='idx_appointment_org')=0,
    'CREATE INDEX idx_appointment_org ON appointment (organization_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='doctor' AND INDEX_NAME='idx_doctor_org')=0,
    'CREATE INDEX idx_doctor_org ON doctor (organization_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='hospital' AND INDEX_NAME='idx_hospital_org')=0,
    'CREATE INDEX idx_hospital_org ON hospital (organization_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='patient' AND INDEX_NAME='idx_patient_org')=0,
    'CREATE INDEX idx_patient_org ON patient (organization_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
