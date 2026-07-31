-- Slice 1.4 — grading scales: the band table behind "is 37/50 good?".
-- Design: microservices/docs/slices/edu-1.4-grading-scales.md
--
-- Bands are an ENTITY, not a setting (D1). common-settings stores scalars (BOOL/INT/TEXT/SELECT); a band
-- table encoded as delimited TEXT would be a parser nobody can validate and a UI nobody can render. The
-- two scalar POLICIES (edu.grading.absentCountsAsZero, edu.grading.roundHalfUp) do live in settings — the
-- split follows shape, exactly as 1.1 D2 and 1.2 D6 concluded for term counts and exam types.
--
-- Ranges are INCLUSIVE at both ends and a valid scale covers 0-100 with no gap and no overlap; that is
-- enforced in BandValidator rather than by a constraint, because the rule is about the SET, not a row.
--
-- NO ROWS ARE SEEDED (D2). Seeding A/B/C would impose a jurisdiction the platform deliberately does not
-- know, and a school that then added its own would inherit a hidden mix. The screen offers a preset the
-- owner explicitly chooses — that is the difference between a default and an assumption.
--
-- There is deliberately no "is passing" column: pass/fail comes from exam_paper.pass_marks (1.2 D4),
-- which is per-paper and therefore more precise. Two sources of truth for pass/fail would be worse.
--
-- Index per DB standard D3. Idempotent so a re-run, or a dev DB ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS grade_band (
    grade_band_id    BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(255) NOT NULL,
    min_percent      INT          NOT NULL,
    max_percent      INT          NOT NULL,
    -- NULL when the school runs letters without a GPA, which is common.
    gpa_points       DOUBLE       NULL,
    user_id          BIGINT       NOT NULL,
    organization_id  BIGINT       NULL,
    dated            DATETIME     NULL,
    updated          DATETIME     NULL,
    PRIMARY KEY (grade_band_id),
    KEY idx_grade_band_org (organization_id),
    -- the scale is always read whole, lowest band first
    KEY idx_grade_band_org_min (organization_id, min_percent)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
