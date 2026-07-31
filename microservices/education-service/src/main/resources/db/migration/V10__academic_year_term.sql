-- Slice 1.1 — academic year & term: the spine every later academic record hangs off.
-- Design: microservices/docs/slices/edu-1.1-academic-year-term.md
--
-- Two tables, not one (D1): promotion is a YEAR-level event while exams are TERM-level, and a single
-- conflated entity cannot express "the third term of 2026-27".
--
-- There is deliberately no term-count setting (D2): the ENTITY is the configuration. Two semesters or
-- four quarters is simply two or four rows.
--
-- "Current term" is DERIVED from dates (D3), so nothing here stores an is_current flag that a nightly
-- job would have to maintain. pinned_current is the one explicit, visible override.
--
-- attendance.term_id and fee_collection.term_id are ADDITIVE and NULLABLE (D4). Existing rows are NOT
-- backfilled: guessing which term a two-year-old attendance row belonged to would be inventing history,
-- and DB standard D5 says never to act on inference about live data. Reports treat NULL as
-- "before terms existed" and still show the row.
--
-- Indexes per DB standard D3: every scoped read's predicate is covered.
-- Idempotent so a re-run, or a dev DB that ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS academic_year (
    academic_year_id BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(255) NOT NULL,
    start_date       DATE         NULL,
    end_date         DATE         NULL,
    user_id          BIGINT       NOT NULL,
    organization_id  BIGINT       NULL,
    status           VARCHAR(255) NULL,
    dated            DATETIME     NULL,
    updated          DATETIME     NULL,
    PRIMARY KEY (academic_year_id),
    KEY idx_academic_year_org (organization_id),
    KEY idx_academic_year_org_user (organization_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS term (
    term_id          BIGINT       NOT NULL AUTO_INCREMENT,
    academic_year_id BIGINT       NOT NULL,
    name             VARCHAR(255) NOT NULL,
    sequence         INT          NULL,
    start_date       DATE         NULL,
    end_date         DATE         NULL,
    -- D3 override: wins over the date comparison when a school holds a term open.
    pinned_current   BIT(1)       NOT NULL DEFAULT b'0',
    user_id          BIGINT       NOT NULL,
    organization_id  BIGINT       NULL,
    dated            DATETIME     NULL,
    updated          DATETIME     NULL,
    PRIMARY KEY (term_id),
    KEY idx_term_org (organization_id),
    KEY idx_term_year (academic_year_id),
    -- currentTerm() reads every term for the tenant and resolves in memory; this covers that read.
    KEY idx_term_org_dates (organization_id, start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── stamp columns on the two live tables ────────────────────────────────────────────────────────
-- Additive + nullable, so existing rows and every existing code path are unaffected. MySQL has no
-- "ADD COLUMN IF NOT EXISTS", so guard on information_schema to stay re-runnable.

SET @add_att := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attendance' AND COLUMN_NAME = 'term_id') = 0,
    'ALTER TABLE attendance ADD COLUMN term_id BIGINT NULL, ADD KEY idx_attendance_term (term_id)',
    'SELECT 1'));
PREPARE s FROM @add_att; EXECUTE s; DEALLOCATE PREPARE s;

SET @add_fc := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fee_collection' AND COLUMN_NAME = 'term_id') = 0,
    'ALTER TABLE fee_collection ADD COLUMN term_id BIGINT NULL, ADD KEY idx_fee_collection_term (term_id)',
    'SELECT 1'));
PREPARE s FROM @add_fc; EXECUTE s; DEALLOCATE PREPARE s;
