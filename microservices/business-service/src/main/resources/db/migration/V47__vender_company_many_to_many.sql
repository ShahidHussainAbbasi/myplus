-- A supplier can represent more than one brand.
--
-- THE LIMITATION BEING REMOVED
-- `vender.company_id` is a single FK, so "Shahzad Mobile Shop" could be registered as the Nokia distributor or
-- the Samsung one, never both. The shop's answer today is to create the same supplier twice, which then splits
-- their payables across two rows and makes the statement lie about what is owed to one business.
--
-- WHY THIS IS A SMALL CHANGE DESPITE BEING A CARDINALITY CHANGE
-- The link is DESCRIPTIVE, not load-bearing. Checked before writing this:
--   * `VenderRepository.findByCompanyId` exists and is called by NOTHING;
--   * `Purchase`'s own company mapping is commented out — purchases do not reference a company at all;
--   * nothing filters, reports, ages or posts by a vendor's company.
-- So there is no reporting or ledger consequence to widening it. The only readers are the vendor grid and the
-- vendor form.

CREATE TABLE IF NOT EXISTS vender_company (
    vender_id   BIGINT NOT NULL,
    company_id  BIGINT NOT NULL,

    -- The pair IS the row: a supplier represents a brand once or not at all. This also makes the backfill and
    -- any re-save idempotent, so a double-submitted form cannot create a duplicate link.
    PRIMARY KEY (vender_id, company_id),

    -- "which suppliers represent Nokia" — the reverse lookup the picker will want next. The primary key
    -- already serves the forward direction.
    KEY idx_vender_company_company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- BACKFILL: every existing supplier keeps exactly the brand it already had.
--
-- All 94 vendors currently carry a company_id, so this produces 94 rows and nobody's data changes meaning.
-- INSERT IGNORE because FlywayConfig repairs and migrates on every start — a re-run must not fail.
-- `vender_id`, not `id`: the table's primary key column is vender_id even though the entity exposes it as
-- getId(). Assuming `id` failed the Flyway migration test with "Unknown column 'id' in 'field list'" — which
-- is precisely why that test runs every migration against an empty database rather than trusting the dev one.
INSERT IGNORE INTO vender_company (vender_id, company_id)
SELECT vender_id, company_id
FROM vender
WHERE company_id IS NOT NULL;

-- ⚠ `vender.company_id` IS DELIBERATELY LEFT IN PLACE, and left populated.
--
-- Standard D5: never drop on inference — count it in every environment first. I can count dev (94 rows, all
-- set); I cannot count production from here, and the incident D5 records is exactly this shape — `company`
-- looked like a dead leftover and held 336 rows nobody had migrated.
--
-- From this migration onward the JOIN TABLE IS AUTHORITATIVE and the column is no longer written. It is kept
-- as a historical record and as a safety net for any reader outside this service that I have not found.
-- Dropping it is a separate, counted piece of work — see docs/installments-todo.md style backlog entry.
