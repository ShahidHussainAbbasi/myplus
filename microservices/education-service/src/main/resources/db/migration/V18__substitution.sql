-- Slice 2.2 — substitution: covering the teacher who is out today.
-- Design: microservices/docs/slices/edu-2.2-substitution.md
--
-- ── Why staff_absence lives HERE and not in 2.3 ────────────────────────────────────────────────────
-- The programme said "2.3 staff attendance is what makes a substitution necessary — so the order is a
-- dependency chain", which read literally puts 2.3 BEFORE 2.2. There is no staff-attendance data today:
-- `attendance` is student-only (enroll_no / student_name). Rather than reorder, or couple a five-second
-- operational screen to an unbuilt HR model, 2.2 owns the ONE fact a substitution needs.
--
-- staff_absence is deliberately THIN: no leave type, no balance, no approval. Those are 2.3's, and a
-- `type` column here would create a second vocabulary 2.3 must then reconcile. `leave_id` is reserved so
-- 2.3 can link its own record without a migration that rewrites history.
-- CARRIED REQUIREMENT: 2.3 must WRITE these rows, not build a parallel absence concept.
--
-- ── Why substitution carries a DATE as well as a timetable entry ───────────────────────────────────
-- 2.1 D5 made the timetable a weekly PATTERN — the same timetable_entry recurs every Tuesday. Without
-- sub_date, cover would silently apply to every Tuesday. First real consequence of that decision.
--
-- ── UNCOVERED is a row, not a missing row ──────────────────────────────────────────────────────────
-- A lesson nobody can cover means a class is unsupervised. As "no row" it is invisible to every query and
-- unreportable; as a status it can be shown loudly this morning and counted this term.
--
-- Both UNIQUE keys exist because a double-clicked button is the realistic failure here (1.3 D1).
-- status is a MySQL enum against @Enumerated(STRING): a new value later needs ALTER ... MODIFY.
-- Indexes follow standard D3b — index the query, not just the scope.
-- Idempotent so a re-run, or a dev DB ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS staff_absence (
    staff_absence_id  BIGINT       NOT NULL AUTO_INCREMENT,
    staff_id          BIGINT       NOT NULL,
    -- snapshotted so the day's list survives a staff member leaving
    staff_name        VARCHAR(255) NULL,
    absence_date      DATE         NOT NULL,
    -- free text on purpose: the vocabulary is 2.3's to define, not this slice's
    reason            VARCHAR(255) NULL,
    -- reserved for 2.3's leave record; NULL for a manually-marked day
    leave_id          BIGINT       NULL,
    user_id           BIGINT       NOT NULL,
    organization_id   BIGINT       NULL,
    dated             DATETIME     NULL,
    updated           DATETIME     NULL,
    PRIMARY KEY (staff_absence_id),
    UNIQUE KEY uk_staff_absence_day (organization_id, staff_id, absence_date),
    -- "who is out today" — the query the whole screen opens with
    KEY idx_staff_absence_org_date (organization_id, absence_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS substitution (
    substitution_id     BIGINT       NOT NULL AUTO_INCREMENT,
    timetable_entry_id  BIGINT       NOT NULL,
    -- which OCCURRENCE of the recurring lesson (see the header)
    sub_date            DATE         NOT NULL,
    absent_staff_id     BIGINT       NULL,
    -- NULL while UNCOVERED: the state that means a class is unsupervised
    cover_staff_id      BIGINT       NULL,
    cover_staff_name    VARCHAR(255) NULL,
    status              ENUM('ASSIGNED','UNCOVERED','CANCELLED') NOT NULL DEFAULT 'UNCOVERED',
    user_id             BIGINT       NOT NULL,
    organization_id     BIGINT       NULL,
    dated               DATETIME     NULL,
    updated             DATETIME     NULL,
    PRIMARY KEY (substitution_id),
    UNIQUE KEY uk_substitution_lesson_day (organization_id, timetable_entry_id, sub_date),
    -- the morning list: everything happening on one date
    KEY idx_substitution_org_date (organization_id, sub_date),
    -- "am I covering anything?" / the free-teacher exclusion
    KEY idx_substitution_org_cover (organization_id, sub_date, cover_staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
