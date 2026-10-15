# Release readiness review — 2026-10-11

Branch `feature/expense-management`, **447 commits ahead of `master`**, plus uncommitted HMS work (H2–H6, RX-FILL).
Question asked: *can this go live without a customer seeing a failure?* Answer: **not yet — 4 blockers, each with a
fix below.** Everything here was checked against the code and the running Docker stack on this date; what could
only be checked on the DEV database is marked so — DEV counts do not prove production (see
`ZERO-DATA-LOSS-MIGRATION-PLAN.md`, "every 'this is safe' was measured on DEV and stored in a COMMENT").

## Verified OK

| Check | Result |
|---|---|
| Migrations on disk vs applied (Docker DB) | auth V21, clinical V8, pharma V11, marketplace V34, appointment V6 — all applied, 0 failed |
| The 4 uncommitted migrations (auth V21, clinical V8, pharma V10/V11) | additive only: new rows, a new table, nullable columns, an index; V11 idempotent |
| New global JS names (`fmtDateTime`, `fmtDate`, `fmtTime`, `serverInstant`, `viewerZone`, `typedToServer`) | 0 collisions in any script or template |
| Pages using clinic.js / pharma.js load the shared formatter | both (shared header); proven live by `hms-h6b-times` |
| Owners after V21 | hold every code of their module (`PermissionService.everything(module)`) — cannot be locked out |
| Token refresh re-reads permissions | yes (`AuthService.refreshToken` → `buildClaims`); access token lives 15 min |
| HMS gates on today's stack | H6b 5/5, H3b 5/5, S3a 5/5, coupon 2/2 (2026-10-11). Earlier slices last ran 10-09…10-11 — **not re-run since later deploys** (see V2) |

## Blockers

| # | Risk to a customer | Evidence | Fix (automatic — no manual step in production) |
|---|---|---|---|
| **B1** | **Pharmacists refused at dispense for up to 15 min after deploy** | pharma now requires `rx.dispense`; tokens minted before auth V21 do not carry it. Happened once already (H3 deploy order) | Deploy order in the runbook: **auth-service → wait ≥ 15 min → pharma-service**. Or one compose `up`, out of hours |
| **B2** | **Staff who dispensed yesterday cannot today**: members on a tenant's CUSTOM set, or on NO set (ADMIN/USER), in a business that uses prescriptions | V21 grants `rx.dispense` to built-in Standard/Administrator only. DEV: 1 custom set in use (org 50, 2 users, 0 prescriptions); 17 no-set staff, all in demo orgs with 0 prescriptions → **nobody on DEV; production unmeasured** | A pre-flight check (below) measures it on production; if any, a V22 migration grants `rx.dispense` to every custom set that today can sell in an org with prescriptions — keeps yesterday's behaviour, owner can remove it |
| **B3** | **analytics-service does not start** if production holds a duplicate metric period | analytics `V3` creates a UNIQUE index with no de-duplication first; "nothing has ever written a row" is a DEV comment | Pre-flight check for duplicates; if any, a de-dup step goes before the index (keep the latest row) |
| **B4** | Uncommitted work cannot be deployed reproducibly | 39 files uncommitted incl. 4 migrations and RX-FILL (HMS depends on it) | Commit (awaiting the client's go) |

## Also before go-live

| # | Item |
|---|---|
| V1 | Run `preflight-data-loss-check.sql` (+ the new checks B2/B3) on the production database — any STOP blocks the deploy. 9 data-changing migrations were added after the pre-flight was written (2026-09-23); the ones reviewed: auth V16 (false match), V17/V18 (intended set moves), business V67 (has `preflight-paid1.sql`), V73 (widens 2000→8000, safe), clinical V4 (generated column), expense V11, party V4 (non-unique indexes, safe), analytics V3 (**B3**) |
| V2 | Full regression on today's stack: unit tests of every changed service; all HMS specs (17); a cross-module smoke of all 21 pages that load the shared header (it now loads `datetime.js`) |
| V3 | Backup of all 16 schemas (2 are MyISAM — see the zero-data-loss plan) |
| V4 | Known, customer-visible, not fixed in this release: times on screens other than clinic/pharmacy still show UTC (H6 phase B in progress); doctor set-password e-mail not verified end to end (dev mail does not reach Mailpit); no way to remove a team member |

## Work done on the blockers (2026-10-11, not yet deployed)

| # | Done | Proof |
|---|---|---|
| B1 | Deploy order written into the pre-flight (R2 row) and this doc: auth-service → wait 15 min → pharma-service | — |
| B2 | auth `V22__custom_sets_keep_dispensing.sql` (custom business sets made before V21 get rx.dispense); `PermissionService.keepsDispensingWithoutASet` + `AuthService.buildClaims` (no-set business member with WRITE_PRIVILEGE keeps it) | `KeepsDispensingTest` 5/5; V22 dry-run on Docker selects 43 sets (orgs 13, 50) = every custom set |
| B3 | analytics `db/migration/beforeMigrate.sql` — de-duplicates before V3's unique index, only while the index is absent | throwaway schema: empty DB no-op → V3 FAILS on duplicates without it → callback keeps newest of each group (5 rows, NULL keys untouched) → V3 succeeds → callback again = no-op |
| V1 | `preflight-data-loss-check.sql` gains R1 (analytics duplicates) and R2 (pharmacy staff kept by B2) | run read-only on Docker: R1 N/A (index already there), R2 0; X1/X2 STOP = the seeded demo accounts, expected on DEV only |
| V2b | Cypress on the DEPLOYED stack: all 17 HMS specs + coupon window + new `ui/shared-header-smoke` (11 pages) + new `hms/hms-setup-before-you-start` — **106/106 runnable pass**, 21 phase-1 pending by design. Found on the way: H2 4/6 red = the H5 rename of `.clin-link` → `.clin-login-link` not applied to the H2 spec (fixed, 6/6); smoke expected the formatter on the 2 public pages, which do not load the shared header's scripts and call no formatter (expectation corrected, 11/11); SET-01's first assertion read the panel BEFORE its re-render (fixed: waits for the grant, reads the Entitled badge). **No product defect.** 9 dashboards: 0 console errors | `cypress/results/shared-header-smoke.json` |
| V2 | Unit tests: auth 82, analytics 6, clinical 78, pharma 68, marketplace 441, appointment 23, monolith 97 — **795/795, 0 skipped** | surefire reports |

## HMS test page (artifact 52ZRf5…) — review for manual testing

**Status after fixes (v5, 2026-10-11): READY for manual testing.** 99 cases (94 pass, 5 pending), unique ids
(`<slice>-<id>`, e.g. H3-B-03) with the spec named on every card; a "Before you start" section with accounts,
roles and the recorded setup SET-01..03; real cleanup on every case; "By hand" lines for the 10 cases that use a
request; pending cases cross-referenced. Known for testers: test doctors accumulate (no remove yet).

The original findings, kept for the record:

96 cases: 91 pass, 5 pending. Every case has steps and an expected result; every passing case maps to a spec.
**Not ready for a manual tester until:**
1. **Case ids are not unique** — B-01…B-05 (baseline and H3), C-01/C-02 (H3 and H6), T-05 (S3b2 and H6): a report
   "B-03 failed" is ambiguous. → prefix each id with its slice (H3-B-03, H6-C-01).
2. **No "Before you start"**: accounts and passwords, switching the clinic on for the organisation (operator
   entitlement + capability), the doctor/pharmacist permission sets, a pharmacy branch.
3. **22 cases say only "—" for cleanup** although several write (cancel, note, Use template): the specs clean up in
   their shared `after()`, but the page does not tell a person what to undo.
4. **11 cases have steps a person cannot perform on screen** (a request by id, a cross-organisation request, the
   coupons): mark them "automated only" or give the screen route.
5. **5 pending** (03, M-04, 04c, M-14, SEC-007): M-04/04c/M-14 are partly proven by S1/S2/S3a cases — say so on the card.
