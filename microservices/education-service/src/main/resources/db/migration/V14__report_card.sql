-- Slice 1.5 — report cards: the point where a computed result stops being computed.
-- Design: microservices/docs/slices/edu-1.5-report-cards.md
--
-- D1 — a card is DERIVED until PUBLISHED, then SNAPSHOTTED. 1.4 made the grade derived so that re-banding
-- updates live results; correct for live results, unacceptable for issued ones. These two tables hold what
-- was ACTUALLY AWARDED, and nothing here is ever recomputed.
--
-- Note what report_card_line does NOT have: subject_id, exam_paper_id, grade_band_id. Storing ids and
-- re-reading the names through a join would reintroduce the drift the snapshot exists to prevent —
-- renaming a subject would silently retitle a card issued three years ago. Names are VALUES here.
--
-- D5 — immutable + versioned. A correction publishes version + 1 and marks the previous row SUPERSEDED;
-- the old row is kept and stays readable, so the school can answer "what did we send you in March?".
-- The UNIQUE key is what makes "one card per version" true under a double-clicked Publish — the database,
-- not the application code (1.3 D1's lesson).
--
-- status is a MySQL enum against @Enumerated(STRING): adding a value later needs an explicit
-- ALTER ... MODIFY, because ddl-auto will not do it and fails with "Data truncated".
--
-- Indexes per DB standard D3. Idempotent so a re-run, or a dev DB ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS report_card (
    report_card_id      BIGINT       NOT NULL AUTO_INCREMENT,
    student_enroll_no   VARCHAR(255) NOT NULL,
    -- snapshotted: a card must stay printable after the student is renamed, transferred or removed
    student_name        VARCHAR(255) NULL,
    term_id             BIGINT       NOT NULL,
    term_name           VARCHAR(255) NULL,
    grade_id            BIGINT       NULL,
    grade_name          VARCHAR(255) NULL,
    -- NULL is legitimate: a term with no marked papers has no percentage (D3)
    term_percent        DOUBLE       NULL,
    term_grade_name     VARCHAR(255) NULL,
    term_gpa            DOUBLE       NULL,
    -- stored even when edu.reportCard.showRank is off, so enabling the setting cannot retroactively
    -- invent a rank for cards issued while it was off (D4)
    class_rank          INT          NULL,
    class_size          INT          NULL,
    attendance_present  INT          NULL,
    attendance_total    INT          NULL,
    version             INT          NOT NULL DEFAULT 1,
    status              ENUM('PUBLISHED','SUPERSEDED','WITHDRAWN') NOT NULL DEFAULT 'PUBLISHED',
    issued_on           DATE         NULL,
    user_id             BIGINT       NOT NULL,
    organization_id     BIGINT       NULL,
    dated               DATETIME     NULL,
    updated             DATETIME     NULL,
    PRIMARY KEY (report_card_id),
    UNIQUE KEY uk_report_card_student_term_version (organization_id, student_enroll_no, term_id, version),
    -- the transcript read: one student, every term, newest first
    KEY idx_report_card_student (organization_id, student_enroll_no, term_id),
    -- the class read: "show me Term 2 for Class 5"
    KEY idx_report_card_term_grade (organization_id, term_id, grade_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS report_card_line (
    report_card_line_id BIGINT       NOT NULL AUTO_INCREMENT,
    report_card_id      BIGINT       NOT NULL,
    exam_name           VARCHAR(255) NULL,
    subject_name        VARCHAR(255) NULL,
    max_marks           INT          NULL,
    -- NULL when absent — 1.3 D2's distinction survives into the printed card
    marks_obtained      INT          NULL,
    absent              TINYINT(1)   NOT NULL DEFAULT 0,
    percent             DOUBLE       NULL,
    grade_name          VARCHAR(255) NULL,
    gpa_points          DOUBLE       NULL,
    sequence            INT          NULL,
    PRIMARY KEY (report_card_line_id),
    KEY idx_report_card_line_card (report_card_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
