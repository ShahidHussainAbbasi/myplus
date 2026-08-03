-- Slice 2.4 — homework: set, submit, mark.
-- Design: microservices/docs/slices/edu-2.4-homework.md
--
-- The Exam/Mark shape from 1.2/1.3: the task set ONCE, and one row per child. A flat row-per-student would
-- copy the due date onto every row, where copies drift.
--
-- ── Rows in homework_submission are created LAZILY (D2) ────────────────────────────────────────────
-- Setting homework for a class of 40 writes ZERO submission rows. A row appears when there is something to
-- record. Pre-seeding would assert 40 facts that are not yet true, and any student who joins the class
-- afterwards would be silently missing. The roster is read from students at display time and submissions
-- are joined onto it — the same shape the marks grid already uses.
--
-- "Not submitted" is therefore the ABSENCE of a row. NOT_DONE is an explicit teacher judgement, never
-- written on a timer: an uncovered lesson (2.2) must be visible BEFORE it happens, but a missing homework
-- only becomes a fact once someone decides the deadline passed and it counts.
--
-- ── No `late` column, and no grade column (D4/D5) ──────────────────────────────────────────────────
-- Late is submitted_on > due_on, DERIVED — extending a deadline must un-late everyone who beat the new
-- date, which a stored flag cannot do. The percentage and band come from the 1.4 grading scale at read
-- time, the same call the marksheet and report card make.
-- Homework deliberately does NOT feed the report card: 1.5's term aggregate is a PUBLISHED number, and
-- adding a source would change its meaning with nothing showing it had changed (continuous assessment is
-- its own slice).
--
-- ── document_ref: a column nothing writes yet (D6) ─────────────────────────────────────────────────
-- Attachments need `document-service`, which gates on blocking decision D-5. This is an opaque reference a
-- future client will populate — NOT a local attachment table, which would be the duplication §1.2 forbids
-- and would have to be migrated away. Held now so adding attachments does not require altering a table that
-- will by then hold real data. Tracked in the programme's carried-requirements table.
--
-- Indexes follow standard D3b — index the query, not just the scope.
-- Idempotent so a re-run, or a dev DB ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS homework (
    homework_id      BIGINT        NOT NULL AUTO_INCREMENT,
    -- the class is derived through subject -> grade (1.2 D2); never stored twice
    subject_id       BIGINT        NOT NULL,
    -- nullable per 1.1: a school with no terms keeps working
    term_id          BIGINT        NULL,
    title            VARCHAR(255)  NOT NULL,
    instructions     VARCHAR(2000) NULL,
    set_on           DATE          NULL,
    due_on           DATE          NULL,
    -- NULL = not graded out of anything; a task can be set without marks
    max_marks        INT           NULL,
    user_id          BIGINT        NOT NULL,
    organization_id  BIGINT        NULL,
    dated            DATETIME      NULL,
    updated          DATETIME      NULL,
    PRIMARY KEY (homework_id),
    -- "what is set for this subject, soonest due first" — the list the screen opens with
    KEY idx_homework_org_subject_due (organization_id, subject_id, due_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS homework_submission (
    homework_submission_id BIGINT        NOT NULL AUTO_INCREMENT,
    homework_id            BIGINT        NOT NULL,
    student_enroll_no      VARCHAR(255)  NOT NULL,
    state                  ENUM('SUBMITTED','NOT_DONE','MARKED') NOT NULL,
    submitted_on           DATE          NULL,
    -- NULL until graded: a graded zero and an ungraded submission are different facts (1.3 D2's rule)
    marks_obtained         INT           NULL,
    feedback               VARCHAR(1000) NULL,
    -- awaiting D-5 / document-service; nothing writes this yet (see the header)
    document_ref           VARCHAR(500)  NULL,
    user_id                BIGINT        NOT NULL,
    organization_id        BIGINT        NULL,
    dated                  DATETIME      NULL,
    updated                DATETIME      NULL,
    PRIMARY KEY (homework_submission_id),
    UNIQUE KEY uk_homework_submission_student (organization_id, homework_id, student_enroll_no),
    -- the mark sheet: every row for one task
    KEY idx_homework_sub_org_homework (organization_id, homework_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
