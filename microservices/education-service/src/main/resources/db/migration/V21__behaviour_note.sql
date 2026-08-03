-- Slice 2.5 — the behaviour / discipline log. The last slice of Phase 2.
-- Design: microservices/docs/slices/edu-2.5-discipline-log.md
--
-- ── This is the most sensitive table in the education schema ───────────────────────────────────────
-- Everything else in Phase 2 records a fact (who teaches when, who was in, who did the homework). This
-- records a JUDGEMENT about a child, made by one person, read years later by people who were not there,
-- and disputed by the student or the parent when it matters. The schema is shaped by that, not by CRUD.
--
-- ── Append-only: no UPDATE of a description, no DELETE (D3) ────────────────────────────────────────
-- A correction INSERTS a new note and marks the original SUPERSEDED, linked via superseded_by_note_id.
-- A silently edited account is worse than no record: it carries the authority of a contemporaneous note
-- without being one. Same rule as a superseded report card (1.5 D5) and a reversed promotion (1.6 D7).
--
-- ── POSITIVE is a first-class type, not decoration (D2) ────────────────────────────────────────────
-- A log that can only record problems becomes a punishment ledger teachers stop opening — which also makes
-- the CONCERN entries less credible when they appear. NEUTRAL exists so a factual note need not be
-- mis-classified to be recorded.
--
-- ── Author is not the typist (D4) ──────────────────────────────────────────────────────────────────
-- recorded_by_staff_id = who witnessed/reported it. user_id = who typed it. An office clerk entering what
-- a teacher reported is normal; conflating them attributes an account to the wrong person exactly when it
-- is being questioned.
--
-- ── NO UNIQUE KEY, deliberately ────────────────────────────────────────────────────────────────────
-- Every other Phase 2 table has one. Two genuine incidents for one child on one day is ordinary here, so
-- uniqueness would be a bug rather than a guarantee. Stated so a future audit does not read it as an
-- oversight next to staff_attendance and homework_submission.
--
-- category is free text with a UI datalist, NOT a taxonomy table (1.2 D6's reasoning: nothing branches on
-- the value, and twenty invented categories make the log unsummarisable).
--
-- Indexes follow standard D3b — index the query, not just the scope.
-- Idempotent so a re-run, or a dev DB ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS behaviour_note (
    behaviour_note_id      BIGINT        NOT NULL AUTO_INCREMENT,
    student_enroll_no      VARCHAR(255)  NOT NULL,
    -- snapshotted: the note must stay readable after the student leaves
    student_name           VARCHAR(255)  NULL,
    type                   ENUM('POSITIVE','CONCERN','NEUTRAL') NOT NULL DEFAULT 'NEUTRAL',
    category               VARCHAR(255)  NULL,
    -- when it HAPPENED; `dated` below is when it was typed
    occurred_on            DATE          NULL,
    -- the account itself; never updated after insert (see the header)
    description            VARCHAR(2000) NOT NULL,
    -- what the school did, if anything: recording an outcome, not running a workflow
    action                 VARCHAR(1000) NULL,
    -- D4: who witnessed/reported it …
    recorded_by_staff_id   BIGINT        NULL,
    recorded_by_staff_name VARCHAR(255)  NULL,
    -- D5: a recorded FACT. Nothing is sent — the notification path is still a stub (2.2, 2.4)
    parent_informed        TINYINT(1)    NOT NULL DEFAULT 0,
    parent_informed_on     DATE          NULL,
    status                 ENUM('ACTIVE','SUPERSEDED') NOT NULL DEFAULT 'ACTIVE',
    superseded_by_note_id  BIGINT        NULL,
    -- D4: … and who TYPED it
    user_id                BIGINT        NOT NULL,
    organization_id        BIGINT        NULL,
    dated                  DATETIME      NULL,
    updated                DATETIME      NULL,
    PRIMARY KEY (behaviour_note_id),
    -- one student's history, newest first
    KEY idx_behaviour_org_student (organization_id, student_enroll_no, occurred_on),
    -- the school-wide recent view
    KEY idx_behaviour_org_date (organization_id, occurred_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
