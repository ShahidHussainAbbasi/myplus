-- Tenant-scope indexes. Every org-scoped read in this service filters on the standard NULL-fallback
-- predicate
--     (organization_id = :orgId OR (organization_id IS NULL AND user_id = :userId))
-- and there was no index on organization_id at all, so each scoped read was a full table scan. The
-- composite (organization_id, user_id) covers both legs of that predicate with one index.
--
-- Idempotent: each CREATE is guarded on information_schema.STATISTICS, so re-running — or a database
-- where the index already exists — is a no-op.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='alert' AND INDEX_NAME='idx_alert_org_user')=0,
    'CREATE INDEX idx_alert_org_user ON alert (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='alert_channel' AND INDEX_NAME='idx_alert_channel_org_user')=0,
    'CREATE INDEX idx_alert_channel_org_user ON alert_channel (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='attendance' AND INDEX_NAME='idx_attendance_org_user')=0,
    'CREATE INDEX idx_attendance_org_user ON attendance (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='discount' AND INDEX_NAME='idx_discount_org_user')=0,
    'CREATE INDEX idx_discount_org_user ON discount (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='fee_collection' AND INDEX_NAME='idx_fee_collection_org_user')=0,
    'CREATE INDEX idx_fee_collection_org_user ON fee_collection (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='grade' AND INDEX_NAME='idx_grade_org_user')=0,
    'CREATE INDEX idx_grade_org_user ON grade (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='guardian' AND INDEX_NAME='idx_guardian_org_user')=0,
    'CREATE INDEX idx_guardian_org_user ON guardian (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='school' AND INDEX_NAME='idx_school_org_user')=0,
    'CREATE INDEX idx_school_org_user ON school (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='staff' AND INDEX_NAME='idx_staff_org_user')=0,
    'CREATE INDEX idx_staff_org_user ON staff (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='student' AND INDEX_NAME='idx_student_org_user')=0,
    'CREATE INDEX idx_student_org_user ON student (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='subject' AND INDEX_NAME='idx_subject_org_user')=0,
    'CREATE INDEX idx_subject_org_user ON subject (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='vehicle' AND INDEX_NAME='idx_vehicle_org_user')=0,
    'CREATE INDEX idx_vehicle_org_user ON vehicle (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

