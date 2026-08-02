-- Slice 2.1 — timetable: who is where, when.
-- Design: microservices/docs/slices/edu-2.1-timetable.md
--
-- Two tables. `period` is the school day (D1): fixed slots the school defines, which is what turns
-- "two things at once" into an equality test — and therefore into something a UNIQUE key can enforce.
-- Free start/end times per lesson would make clash detection interval arithmetic no constraint can express.
--
-- ── The one deliberate deviation from an established precedent (D2) ────────────────────────────────
-- timetable_entry stores BOTH subject_id and grade_id, even though Subject already has a grade, and
-- 1.2 D2 says never to store the class twice. Two reasons that did not apply to exam_paper:
--   1. the class is the primary query axis — "show 5A's timetable" is the busiest read in Phase 2;
--   2. the UNIQUE key below cannot be built on a derived value, and a class being in two rooms at once
--      is exactly the thing a double-clicked save produces (the 1.3 D1 / 1.6 D6 lesson).
-- The cost is a second source of truth, contained by validating grade_id against the subject's grade on
-- EVERY write (ClashDetector.gradeMatchesSubject). If that check is ever dropped, this becomes the drift
-- 1.2 D2 warned about.
--
-- ── A hole in the UNIQUE keys, stated rather than discovered ───────────────────────────────────────
-- term_id is NULLABLE (1.1: a school with no terms must keep working). MySQL does not treat NULLs as
-- equal, so for those tenants BOTH unique keys silently never fire. ClashDetector refuses the clash in
-- application code regardless — for a term-less tenant that validator is the only line of defence.
--
-- Room is deliberately NOT constrained: it is free text with no room master (D6), so a room clash warns.
--
-- Indexes cover the two grids per standard D3b (index the query, not just the scope).
-- Idempotent so a re-run, or a dev DB ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS period (
    period_id        BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(255) NOT NULL,
    sequence         INT          NULL,
    start_time       TIME         NULL,
    end_time         TIME         NULL,
    -- false = break/assembly/lunch: the band renders, nothing schedules into it
    teaching         TINYINT(1)   NOT NULL DEFAULT 1,
    user_id          BIGINT       NOT NULL,
    organization_id  BIGINT       NULL,
    dated            DATETIME     NULL,
    updated          DATETIME     NULL,
    PRIMARY KEY (period_id),
    KEY idx_period_org_seq (organization_id, sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS timetable_entry (
    timetable_entry_id BIGINT     NOT NULL AUTO_INCREMENT,
    term_id            BIGINT     NULL,
    day_of_week        ENUM('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY') NOT NULL,
    period_id          BIGINT     NOT NULL,
    subject_id         BIGINT     NOT NULL,
    -- derived from the subject, stored deliberately (see the header), validated on every write
    grade_id           BIGINT     NOT NULL,
    -- nullable: a slot can be timetabled before the teacher is decided
    staff_id           BIGINT     NULL,
    room               VARCHAR(255) NULL,
    user_id            BIGINT     NOT NULL,
    organization_id    BIGINT     NULL,
    dated              DATETIME   NULL,
    updated            DATETIME   NULL,
    PRIMARY KEY (timetable_entry_id),
    UNIQUE KEY uk_tt_staff_slot (organization_id, term_id, day_of_week, period_id, staff_id),
    UNIQUE KEY uk_tt_grade_slot (organization_id, term_id, day_of_week, period_id, grade_id),
    -- the class grid: "show 5A's week"
    KEY idx_tt_org_term_grade (organization_id, term_id, grade_id),
    -- the teacher grid: "where am I today"
    KEY idx_tt_org_term_staff (organization_id, term_id, staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
