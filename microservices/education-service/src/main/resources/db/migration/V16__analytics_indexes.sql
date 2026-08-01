-- Finding D — indexes for the dashboard aggregates and the duplicate checks.
-- Design: microservices/docs/slices/edu-D-analytics-perf.md
--
-- Two groups of index here, for two different problems:
--
-- 1. DUPLICATE CHECKS. Twelve save endpoints used to load every row in the tenant and scan it in Java
--    to answer "does this name already exist". They now ask the database, which needs an index on
--    (organization_id, <the checked column>) or it is the same full scan with fewer objects allocated.
--
-- 2. DATE-WINDOWED AGGREGATES. The 12-month fee/enrolment trends and the attendance summaries filter
--    and group by a date. Without an index on (organization_id, <date>) those become full scans of the
--    two biggest tables in the service.
--
-- ── A NOTE THAT IS LOAD-BEARING, NOT DECORATIVE ────────────────────────────────────────────────────
-- The duplicate checks are CASE-INSENSITIVE. They rely on these columns having a case-insensitive
-- collation (utf8mb4_0900_ai_ci is the MySQL 8 default; utf8mb4_general_ci on older servers). The Java
-- code they replaced used equalsIgnoreCase(); the SQL uses a plain `=` and gets the same answer ONLY
-- because of the collation.
--
-- The alternative, `where lower(name) = lower(?)`, is explicit but cannot use these indexes — it would
-- look more careful and still scan the table, which is the exact opposite of the point of this slice.
--
-- So: IF THIS SCHEMA IS EVER MOVED to a case-sensitive collation, or to an engine whose default differs,
-- these twelve checks silently become case-SENSITIVE and start admitting duplicates that differ only in
-- case. Change them to an explicit lower() comparison (and accept the scan, or add a functional index)
-- at the same time.
--
-- NOT INCLUDED, deliberately: UNIQUE constraints. They are the real fix for the check-then-act race in
-- these twelve endpoints, but a tenant whose data already contains duplicates would fail the migration
-- and break the deploy (DB standard D5 — never act on inference about live data). The repositories now
-- expose findDuplicate*Scoped() so the data can be audited first; the constraints are a follow-up.
--
-- Idempotent-by-convention: MySQL has no CREATE INDEX IF NOT EXISTS, so these run once via Flyway's
-- version tracking. A dev DB that ddl-auto already indexed may need the duplicate dropped by hand.

-- ── 1. duplicate-check support ─────────────────────────────────────────────────────────────────────
CREATE INDEX idx_student_org_enroll     ON student          (organization_id, enroll_no);
CREATE INDEX idx_grade_org_name         ON grade            (organization_id, name, school_id);
CREATE INDEX idx_guardian_org_name      ON guardian         (organization_id, name, cnic);
CREATE INDEX idx_staff_org_name         ON staff            (organization_id, name);
CREATE INDEX idx_subject_org_name       ON subject          (organization_id, name);
CREATE INDEX idx_discount_org_name      ON discount         (organization_id, name);
CREATE INDEX idx_owner_org_name         ON owner            (organization_id, name);
CREATE INDEX idx_school_org_branch      ON school           (organization_id, branch_name);
-- NB: the column is `vehicle_number`, not `number` — the ENTITY field is Vehicle.number, which is why
-- the JPQL reads v.number while the index below must name the real column.
CREATE INDEX idx_vehicle_org_number     ON vehicle          (organization_id, vehicle_number);

-- ── 2. aggregate support ───────────────────────────────────────────────────────────────────────────
-- attendance is the big one: one row per student per day, ~400k rows a year for a 2,000-student school.
CREATE INDEX idx_attendance_org_date    ON attendance       (organization_id, att_date);
CREATE INDEX idx_attendance_org_grade   ON attendance       (organization_id, grade_name);
CREATE INDEX idx_fee_org_paymentdate    ON fee_collection   (organization_id, payment_date);
-- the fee → student join in collectionByClass resolves on enrolment number within the tenant
CREATE INDEX idx_fee_org_enroll         ON fee_collection   (organization_id, enroll_no);
CREATE INDEX idx_student_org_enrolldate ON student          (organization_id, enroll_date);
CREATE INDEX idx_student_org_grade      ON student          (organization_id, grade_id);
