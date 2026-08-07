-- Slice 3.3 — the student portal. GENERALISE the access table; do not add a second one.
-- Design: microservices/docs/slices/edu-3.3-student-portal.md (D3)
--
-- ── Why one table and not student_portal_access ────────────────────────────────────────────────────
-- A second table would duplicate the entity, the repository, the invite/revoke controller, the admin
-- screen and the revoke semantics — and cost a domain entity against a 35/~40 split trigger, spent on a
-- copy. What differs between a guardian's access and a student's is WHO the row is about, which is a
-- column, not a table.
--
-- ── DESIGN CORRECTION, found in implementation ─────────────────────────────────────────────────────
-- The design says "portal_access gains subject_type". The table is actually `guardian_portal_access`
-- (V22), and it is NOT renamed here. Renaming a live table is precisely what standard D5 exists to
-- prevent, and the name is not worth a migration that can fail on a tenant holding data. The cost is an
-- honest one: the table name now under-describes its contents. Recorded rather than silently accepted.
--
-- ── ADD and BACKFILL only. Nothing is dropped, nothing is renamed (D3/D5) ──────────────────────────
-- Every existing row IS a guardian's access, so the backfill is exact rather than inferred: subject_type
-- is GUARDIAN and subject_id is the guardian_id already in the row. A rollback loses only new rows.
--
-- guardian_id is WIDENED to nullable rather than being faked for student rows. Putting a student id in a
-- column named guardian_id would collide in uk_portal_access_guardian the moment guardian 5 and student 5
-- both exist in one org — two unrelated people sharing a unique key. MySQL permits many NULLs in a unique
-- index, so student rows simply do not participate in the old key.
--
-- Idempotent throughout (information_schema guards), matching V22 and the party-service V3 precedent, so
-- a re-run or a dev DB that ddl-auto already touched is a no-op.

-- 1. subject_type — what kind of person this access is about.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'guardian_portal_access'
                  AND column_name = 'subject_type') = 0,
    'ALTER TABLE guardian_portal_access
        ADD COLUMN subject_type ENUM(''GUARDIAN'',''STUDENT'') NOT NULL DEFAULT ''GUARDIAN''
        AFTER guardian_portal_access_id',
    'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 2. subject_id — the guardian_id or the student_id, depending on subject_type.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'guardian_portal_access'
                  AND column_name = 'subject_id') = 0,
    'ALTER TABLE guardian_portal_access ADD COLUMN subject_id BIGINT NULL AFTER subject_type',
    'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 3. Backfill. Exact, not inferred: before this slice every row was a guardian's.
UPDATE guardian_portal_access
   SET subject_id = guardian_id, subject_type = 'GUARDIAN'
 WHERE subject_id IS NULL;

-- 4. Widen guardian_id so a student row need not fake one (see the header).
SET @sql := IF((SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'guardian_portal_access'
                  AND column_name = 'guardian_id') = 'NO',
    'ALTER TABLE guardian_portal_access MODIFY COLUMN guardian_id BIGINT NULL',
    'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 5. THE key that now expresses the rule: one live access row per person per org, whoever they are.
--    Doubles as the index for the admin list, so no separate KEY is added (standard D3b: index the query).
SET @sql := IF((SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'guardian_portal_access'
                  AND index_name = 'uk_portal_access_subject') = 0,
    'ALTER TABLE guardian_portal_access
        ADD UNIQUE KEY uk_portal_access_subject (organization_id, subject_type, subject_id)',
    'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
