-- Slice 1.6 — promotion: the end-of-year decision, recorded.
-- Design: microservices/docs/slices/edu-1.6-promotion.md
--
-- Why this table exists at all (D3): promotion overwrites student.grade_id, which is the ONLY copy of
-- where a child was. Without a record, "which class was this child in last year?" becomes unanswerable —
-- and attendance/fee_collection denormalise the class name, so last year's rows would disagree with the
-- student's current class with nothing to explain the gap. That reads as data corruption, not history.
--
-- Names are stored as VALUES, not resolved through joins (same rule as report_card_line): a class renamed
-- next year must not retitle last year's history.
--
-- D6 — the UNIQUE key is the real guarantee. A double-clicked "Promote class" must not move a child TWO
-- classes up; a pre-check in application code loses that race, a constraint does not (1.3 D1).
--
-- A RETENTION writes a row even though nothing moves: "we considered this child and kept them back" and
-- "we never got to this child" are different facts, and only a recorded decision distinguishes them.
--
-- outcome/status are MySQL enums against @Enumerated(STRING): adding a value later needs an explicit
-- ALTER ... MODIFY, because ddl-auto will not do it and fails with "Data truncated".
--
-- Indexes per DB standard D3. Idempotent so a re-run, or a dev DB ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS promotion (
    promotion_id        BIGINT       NOT NULL AUTO_INCREMENT,
    student_enroll_no   VARCHAR(255) NOT NULL,
    student_name        VARCHAR(255) NULL,
    from_grade_id       BIGINT       NULL,
    from_grade_name     VARCHAR(255) NULL,
    -- NULL for a retention (nowhere to go) and for a graduation (nowhere left)
    to_grade_id         BIGINT       NULL,
    to_grade_name       VARCHAR(255) NULL,
    academic_year_id    BIGINT       NOT NULL,
    academic_year_name  VARCHAR(255) NULL,
    outcome             ENUM('PROMOTED','RETAINED','GRADUATED') NOT NULL,
    status              ENUM('APPLIED','REVERSED') NOT NULL DEFAULT 'APPLIED',
    -- the policy's own words, or "decided by <user>" on an override; a decision without its reason is
    -- unreviewable a year later, which is exactly when it gets questioned
    reason              VARCHAR(500) NULL,
    overridden          TINYINT(1)   NOT NULL DEFAULT 0,
    user_id             BIGINT       NOT NULL,
    organization_id     BIGINT       NULL,
    dated               DATETIME     NULL,
    updated             DATETIME     NULL,
    PRIMARY KEY (promotion_id),
    UNIQUE KEY uk_promotion_student_year (organization_id, student_enroll_no, academic_year_id),
    -- "what did we do with Class 5A last June?"
    KEY idx_promotion_year_from (organization_id, academic_year_id, from_grade_id),
    -- one student's progression across years
    KEY idx_promotion_student (organization_id, student_enroll_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
