-- Slice 1.2 — examinations: the thing marks (1.3) are recorded against.
-- Design: microservices/docs/slices/edu-1.2-examinations.md
--
-- Two levels (D1): the EXAM is the event ("Term 2 Mid-Term", weighted 30% of the term) and EXAM_PAPER is
-- one subject's paper within it (out of 50, on 14 November). A flat row-per-subject could not carry the
-- weight without copying it onto every row, where the copies drift apart.
--
-- exam_paper deliberately has NO grade_id (D2): `subject` already has a grade_id, so storing the class
-- again would be a second source of truth that can contradict the first. The class — and through it the
-- branch — is DERIVED. Same reasoning as the branch-scope slice.
--
-- exam.term_id is NOT NULL (D3). 1.1 made term_id nullable on attendance and fee_collection so a school
-- without terms keeps working; exams are where that stops, because "which term does this count toward?"
-- has no safe default and guessing would attach results to the wrong reporting period.
--
-- status is a MySQL ENUM matching @Enumerated(STRING). Adding a value later needs an explicit
-- ALTER ... MODIFY — ddl-auto will not do it and fails with "Data truncated".
--
-- Indexes per DB standard D3: every scoped read's predicate is covered.
-- Idempotent so a re-run, or a dev DB that ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS exam (
    exam_id          BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(255) NOT NULL,
    -- D6: free text on purpose. No exam_type table and no edu.exam.types setting — nothing in the code
    -- branches on the value, so a catalog would be a second, weaker way to say what this column says.
    type             VARCHAR(255) NULL,
    term_id          BIGINT       NOT NULL,
    weight_percent   INT          NULL,
    status           ENUM('DRAFT','PUBLISHED','LOCKED') NOT NULL DEFAULT 'DRAFT',
    user_id          BIGINT       NOT NULL,
    organization_id  BIGINT       NULL,
    dated            DATETIME     NULL,
    updated          DATETIME     NULL,
    PRIMARY KEY (exam_id),
    KEY idx_exam_org (organization_id),
    KEY idx_exam_term (term_id),
    -- the term filter every later slice runs (1.4 weights, 1.5 report cards)
    KEY idx_exam_org_term (organization_id, term_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS exam_paper (
    exam_paper_id    BIGINT       NOT NULL AUTO_INCREMENT,
    exam_id          BIGINT       NOT NULL,
    subject_id       BIGINT       NOT NULL,
    max_marks        INT          NULL,
    pass_marks       INT          NULL,
    exam_date        DATE         NULL,
    time_from        TIME         NULL,
    time_to          TIME         NULL,
    user_id          BIGINT       NOT NULL,
    -- denormalised from the parent exam so papers can be read and scoped without a join
    organization_id  BIGINT       NULL,
    dated            DATETIME     NULL,
    updated          DATETIME     NULL,
    PRIMARY KEY (exam_paper_id),
    KEY idx_exam_paper_org (organization_id),
    KEY idx_exam_paper_exam (exam_id),
    KEY idx_exam_paper_subject (subject_id),
    -- the datesheet read: a tenant's papers in date order
    KEY idx_exam_paper_org_date (organization_id, exam_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
