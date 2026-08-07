-- Close the LAST open check-then-act race in education: one attendance row per student per day.
-- Carried since slice 2.3 §6 and the education review's finding D.
--
-- ══ WHY (organization_id, enroll_no, att_date) AND NOT (student, timestamp) ══════════════════════
--
-- The proposed key was "studentId + timestamp". Two corrections, both from the model rather than opinion:
--
-- 1. THERE IS NO studentId ON THIS TABLE. A student is identified here by `enroll_no` (VARCHAR) — the
--    entity has no student_id column at all, and `AttendanceController` upserts on
--    findFirstByOrganizationIdAndEnAndAttDate(org, enrollNo, date).
--
-- 2. **`dated_time` IS A RECORD TIMESTAMP, NOT THE ATTENDANCE DAY**, and including it would make this
--    constraint enforce NOTHING. It is set to now() on every write, so two marks for the same student on
--    the same day — saved a second apart — would carry different timestamps and BOTH be allowed. The
--    duplicate this key exists to prevent would sail straight through it.
--
--    The day the attendance is FOR is `att_date` (DATE). The entity says so in its own comment:
--    "The day the attendance is for (marking day). Upsert key with (organization_id, enroll_no)."
--
-- organization_id is included because `enroll_no` is only unique WITHIN a tenant — two schools both
-- numbering a child "EN-1001" is ordinary, and a key without the org would refuse the second school's
-- register entirely. That is the multi-tenancy standard, not a nicety.
--
-- ══ D5: AUDITED BEFORE CONSTRAINING ═════════════════════════════════════════════════════════════
-- Counted 2026-08-07 on the live schema: 20 rows across 2 organisations, **zero** duplicate
-- (organization_id, enroll_no, att_date) tuples, zero NULL dates, zero NULL enrolment numbers. So this
-- key cannot fail the migration on existing data.
--
-- This audit is the whole reason the key can ship now when finding D deliberately left twelve sibling
-- dup-checks unconstrained: those were left BECAUSE a tenant holding duplicates would break the deploy,
-- and nobody had checked. Checking is what made the difference.
--
-- ══ WHAT THIS FIXES ═════════════════════════════════════════════════════════════════════════════
-- AttendanceController marks a register with read-then-write:
--     findFirstByOrganizationIdAndEnAndAttDate(...)  -> present ? update : insert
-- Two teachers marking the same class at once both read "absent", both insert, and the student has two
-- attendance rows for one day. Every percentage derived from it — 1.5's report card, the dashboard KPI,
-- and now exam eligibility — is then wrong, quietly.
--
-- The constraint is the guarantee; the existing check stays as the friendly path.
--
-- Idempotent so a re-run, or a dev DB ddl-auto already touched, is a no-op.

SET @sql := IF((SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'attendance'
                  AND index_name = 'uk_attendance_student_day') = 0,
    'ALTER TABLE attendance
        ADD UNIQUE KEY uk_attendance_student_day (organization_id, enroll_no, att_date)',
    'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
