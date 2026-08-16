-- Chart of accounts: enforce ONE account per (organization_id, code).
--
-- ── The defect ──────────────────────────────────────────────────────────────────────────────────────
-- GlService seeded the chart with a check-then-insert:
--
--     if (accountRepository.findByOrganizationIdAndCode(org, code).isEmpty()) { save(...); }
--
-- and the table carried only a NON-unique index (idx_acct_org_code). Two concurrent postings for a
-- tenant whose chart did not exist yet both saw "empty" and both inserted the whole chart. Nothing
-- rejected the second copy.
--
-- The damage is not the wasted rows. `findByOrganizationIdAndCode` returns Optional<Account>, so from
-- the instant a code is duplicated EVERY lookup of it throws NonUniqueResultException — GL posting and
-- several finance reports 500 for that tenant, permanently, until someone deletes a row by hand.
-- Observed in dev: organization 14 with all 14 codes duplicated exactly twice, and its education fee
-- postings silently landing nowhere (Cash/Bank/AR/2200 all reading 0.00).
--
-- ── Order matters ───────────────────────────────────────────────────────────────────────────────────
-- journal_lines.account_id already referenced non-survivor rows (2 of them in dev), so the duplicates
-- cannot simply be deleted — that would orphan posted ledger lines. Repoint first, delete second,
-- constrain third.

-- 1. Repoint every journal line that references a duplicate onto the surviving row for that
--    (organization_id, code). The survivor is the LOWEST id — the copy that was there first.
UPDATE journal_lines jl
  JOIN accounts dup
    ON dup.id = jl.account_id
  JOIN (SELECT organization_id, code, MIN(id) AS keep_id
          FROM accounts
         GROUP BY organization_id, code) k
    ON k.organization_id = dup.organization_id
   AND k.code            = dup.code
   SET jl.account_id = k.keep_id
 WHERE dup.id <> k.keep_id;

-- 2. Now the duplicates are unreferenced, drop them.
DELETE a
  FROM accounts a
  JOIN (SELECT organization_id, code, MIN(id) AS keep_id
          FROM accounts
         GROUP BY organization_id, code) k
    ON k.organization_id = a.organization_id
   AND k.code            = a.code
 WHERE a.id <> k.keep_id;

-- 3. Make the race impossible rather than merely unlikely. The application-side guard is a second
--    line of defence (GlService now treats a duplicate-key insert as "someone else won, re-read");
--    this is the one that actually holds under concurrency.
ALTER TABLE accounts
  ADD CONSTRAINT uq_accounts_org_code UNIQUE (organization_id, code);

-- 4. idx_acct_org_code covered exactly these columns in the same order, so the unique constraint's
--    index supersedes it entirely. Dropping it keeps one index where one is needed.
DROP INDEX idx_acct_org_code ON accounts;
