# Verify sweep — October 2026

**Purpose:** run every Cypress gate that was written but never run (or left red) on a real deployed stack, and turn
the Test Book's "unverified" areas into recorded step-by-step cases. For every red: real defect (fixed at the root,
with a case that was red first) or spec bug (fixed, with the reason). Nothing skipped, disabled or weakened.

**Stack:** fresh `docker compose` stack in a cloud container — fresh MySQL volume, `APP_SEED_*=true`, branch
`feature/expense-management` from `f0416517`. Cypress 13.17.0 (`cypress/included` image), Electron headless.

**A fresh database is the point.** Most reds below exist ONLY because a spec assumed something the long-lived
development database happens to have: a supplier, a sale this month, three invoices, a customer, a tenant shape, a
plan. Every one of those is now seeded or established by the spec itself (GATE-RUNBOOK §5, §7).

## Real defects found (4)

| # | Defect | Root cause | Fix | Red first |
|---|---|---|---|---|
| D1 | **A handset scanned after a quantity was typed was charged for the old quantity** — cart read `1 × 500 = 1000.00` | `applySerialQuantityLock` set QTY with `.val(1)`, which fires none of the box's `keyup`/`blur` handlers, so `calculateNetSell()` never ran. Goods-in's twin (`applyPurchaseSerialQuantityLock`) already recalculates — the till never got the same line | recompute when the lock changes the quantity (`business.js`) | `serial-till-entry` case 1: "line total is ONE unit at 500: expected 1000" on the base build |
| D2 | **Session expiry showed "parsererror" instead of the login page** (Test Book §13) | `invalidSessionUrl` 302'd every request to the *Session expired* HTML page; jQuery followed it and failed to parse it as JSON; `handleAjaxFailure` did not recognise that page | `XhrAwareInvalidSessionStrategy`: a script gets SESS-1's `401 {code:SESSION_EXPIRED}` + a fresh session; a page still gets the redirect | `session-expiry-xhr` 3 of 4 red on the base build |
| D2b | …and the first fix was incomplete | the first request to meet a dead session is usually a BACKGROUND read (`global:false`) — a `$(document).ajaxError` hook never saw it; it used the dead session up, and the person's click then got the LOGIN page as "JSON" | `$.ajaxPrefilter` in `main.js` — runs for every request, background included; recognises 401 `SESSION_EXPIRED` and the login page, one redirect | found by guide case V7; 2 new gate cases red on the first fix |
| D2c | …and Sign in then showed a BLANK banner | on the login-page shape there is no `SESSION_EXPIRED` code for `handleAjaxFailure` to swap for the sentence, so it passed an empty note | both shapes pass `ui.js.sessionEnded` (six locales) | seen on guide picture V7; gate red "expected '' to match /\S/" |
| D3 | **notification-service never starts on a fresh install** (crash-loop, MySQL 1044) | slice 105 gave it `myplusdb_notification`; `init-db.sql` never created or granted it, and the app user has no global CREATE | `init-db.sql` creates + grants it — verified on a throwaway MySQL; the live volume got the same CREATE + GRANT by hand | `docker logs myplus-notification`, RestartCount 2 |

## Gate results

| Spec | First run (fresh DB) | Verdict | After |
|---|---|---|---|
| `business/returns-list` | hung 10+ min on case 2 | harness gap: a hidden iframe's own `print()` was never stubbed (`support/e2e.js`) | **5/5** |
| `business/debit-note-supplier-filter` | 3/4 | spec: no supplier → `cy.ensureVendor()` | **4/4** ⚠ its Print-all case passes through the *nothing printable* branch on a DB with no purchase returns — guide V2 seeds one and walks the real path |
| `business/sale-report-invoices` | 1/4 | spec: no sale this month (a full return DELETES the line, by design) → `cy.ensureSale()` | **4/4** |
| `business/sale-report-by-company` | 3/5 | spec: as above | **5/5** ⚠ "the Company filter narrows" returns early when no product carries a manufacturer — vacuous on this DB |
| `business/dashboard-stock-value` | **4/4** | — | — |
| `business/sale-picker-speed` | 7/8 | environment: `demo.business@` has 3 products here, not ~2,500 | the large-catalogue claim stays **unverified** |
| `business/document-number-integrity` (receipt/voucher race) | **8/8** | first run ever, incl. 6 concurrent receipts → 6 distinct numbers | — |
| `business/installment-guarantors` | 18/19 | spec: 6b cleared the setting with `value:''`, which SET-GUIDE now refuses for a whole number; the refusal was never read → `/resetBusinessConfig`, asserted | **19/19** |
| `business/invoice-pdf-download` | 0/6 (before hook) | spec: one invoice, needs three → `cy.seedSale()` up to three | **6/6** — "Download PDF does nothing" is confirmed fixed (one file, a page per invoice, real pdfmake output) |
| `business/quote-visibility` (slices/quotes-visibility.md "awaiting gate") | 1/6 | spec: no customer/product; took row 0; seeding as the BOOKER is correctly refused, and a booker lists only customers they created → the owner seeds one eligible pair in `before()`, cases quote it by id | **6/6** |
| `business/keyboard-chain-order` | **7/7** | static audit of chain vs DOM incl. the serial box | — |
| `business/pos-keyboard` | **22/22** | Enter-walk | — |
| `business/pos-enter-chain` | 6/7 → **7/7** on one re-run | ⚠ NOT a root cause: the failed run's scan box held `P5C17912` — 8 of the 16 characters typed — with `/catalogProductPicker` in flight. Recorded as an open finding (a scan typed while the till loads may lose characters), not as a flake | open |
| `security/session-expiry-xhr` (NEW, SESS-2) | red 3/4 on base; 2 more red on the first fix; 1 red on the second (blank banner) | D2/D2b/D2c | **6/6** |
| `business/serial-till-entry` (NEW) | blocked, then red | D1; plus two fixture facts: a fresh seed puts `owner.mobile@` on the FREE plan (serial tracking outside it → lifted reversibly with `cy.setPlan`) and leaves `owner.business@` with NO shape (`general` = every capability) | **3/3** |
| `security/session-policy` (NEW, 107 Part 1) | 1/2 | my own fixation case assumed the login page opens a session (it does not; a failed sign-in does) | **2/2** |

## Notes for the runbook

- **`owner.business@` is not `retail` on a fresh seed** — it has no shape, so it is `general` and tracks serials.
  GATE-RUNBOOK §1/§3 describe it as retail; that is true only where someone set it.
- **`owner.mobile@` is seeded on FREE**, which excludes serial tracking. The seeded override makes the feature READ on,
  but any gate that re-asserts it in `before()` is refused at the plan ceiling. Every serial spec that calls
  `cy.setCapability('serialTracking', true)` is red on a fresh stack for that reason alone. Proposal (not done here —
  it changes seeded tenant state): `SetupDataLoader` seeds the C4 tenants on a plan that includes the capabilities it
  seeds for them.

## Item 5 — slice docs "awaiting gate" / "gate RED" (all headless Electron; "run headed" is not available in this container)

| Slice | Spec | Result |
|---|---|---|
| oms-O7 | `business/order-auto-dispatch` | **5/5** |
| oms-O7-D6a ("gate RED 2/7", 16 Sep) | `business/territory-assignment` | **7/7** |
| storefront-tax-alignment | `business/storefront-checkout`, `-coupon`, `-gl` | **5/5, 3/3, 2/2** |
| pharmacy-rx-enforcement ("headed gate") | `pharmacy/rx-enforcement` | **7/7** |
| onb-3-migration-safety | `platform/migration-safety` | **15/15** (plan-lifted batch) |
| 107-session-policy Part 1 | `security/session-policy` (new) | **2/2** |
| set-guide-l14-l18 ("not deployed, not gated") | `business/opening-balances`, `business/price-rules-screen` | **15/15, 8/8** |
| ui-form-1 ("gate RED 11/11 on old build") | `business/form-layout`, `product-own-stickers`, `purchase-rapid-entry` | **12/12, 2/2, 28/28** |
| 106-cypress-suite-health | `credit-limit` 13/14 → **14/14** (spec: assumed a company → `cy.ensureCompany()`), `order-cancel` 3/3, `purchase` 22/22, `sell` 31/31, `storefront-account` 2/2, `storefront` 4/4, `tax-register` 3/3, `team` 3/3, `team-picker` **5/5** (first run: education-service not in the stack, then not yet routed by the gateway), `vertical-profile` 2/2. `backfill-productids` and `m3c-prep-backfill` no longer exist. | all green |

## Guide cases — `cypress/e2e/docs/verify-sweep-guide.cy.js` (Test Book §28)

V1 returns register · V2 purchase returns filter + Print all · V3 report invoices + ONE PDF · V4 stock-value tile · V5 serial at
the till · V6 the hide switch on Configuration, Reset on screen · V7 session ended → Sign in. **7/7 passed**, each step performed,
asserted and photographed. V5 was red on the base build (the line-total defect D1); V7 found D2b.

## Batch 7 — serial and installment gates with the plans lifted

**Environment prep, not a spec change:** `owner.mobile@` (org 16) and `owner.business@` (org 6) moved FREE → PRO through the
operator's own `/platform/plan` (reason recorded), which is where the development database already has them; put back to
FREE afterwards. Without it every spec that re-asserts serial tracking or installments in `before()` stops at the plan
ceiling.

| Spec | Result |
|---|---|
| `business/purchase-multi-serial` (goods-in, SER-7) | **8/8** |
| `business/serial-register` | **18/18** |
| `business/serial-register-fixes` (incl. the goods-in hide switch) | **12/12** |
| `business/installment-serial` | **5/5** |
| `business/installment-screen` | **5/5** |
| `platform/migration-safety` (onb-3) | **15/15** |
| `business/dashboard-breakdown-cards` | **15/15** |

## Item 6 — marketplace (`--env mkt=all`, `cypress/e2e/marketplace/*.cy.js`)

**86/88.** MKT-0a 8/8 · 1b 9/9 · 1c 11/11 · 1d 10/10 · 1e 10/10 · 1e2 9/9 · 1f 12/12 · 1g 6/6 · 2a 8/8 · **MKT-2 3/5**: MKT-2-02
(shortage reassignment) and MKT-2-05 (routing deadline) are red. That spec's header says it is *written ahead as the Phase 2
requirement*, its cases moving into MKT-2x slice specs as each is designed; neither reassignment nor the deadline is built. Not
a regression, not diagnosed further.

## Open findings (not fixed here)

- **A scan typed while New Sale is still loading can lose characters** (`pos-enter-chain`, one run of three): the box held
  `P5C17912` of `P5C1791217797971` with `/catalogProductPicker` in flight. `#sellScan` submits only on Enter, so the missing
  characters went to another element — something moved the focus during the load and back before Enter. Not reproduced on
  demand. To reproduce: throttle the network, open New Sale and scan at once.
- **A fresh seed is not the tenant the runbook describes:** `owner.business@` has no shape (`general`); `owner.mobile@` is on
  FREE. Proposal: `SetupDataLoader` seeds the C4 / POS tenants on a plan that includes what it seeds for them, and the POS
  tenant as `retail`. Not done here — it changes seeded tenant state other suites rely on.
- **Vacuous greens on a fresh DB:** `debit-note-supplier-filter` Print all (no purchase returns → the *nothing printable*
  branch) and `sale-report-by-company` "narrows" (no product carries a manufacturer → returns early). Guide V2 walks the
  real Print-all path; the manufacturer case still needs a seeded manufacturer.
- **`sale-picker-speed` on a large catalogue** stays unverified (the fresh `demo.business@` has 3 products).

## Also run

- `business/quote-document` **11/11**, including case 8 (quote PDF), which its own comment expected red until the Download
  PDF defect was fixed. The Test Book's "PDF on quotes does not work yet" was stale.

## Test Book change (version 49, https://claude.ai/artifact/HRVanHXNBDMiCvJGK9vWhx)

Re-read in full (3,124 lines, the other session's §27 included) and merged, never forced:
- **New §28** — the seven guide cases above, built by `docs/guides/build-testbook-section.js verifysweep` (TB_NUMBER=28).
- §1 serial at the till, §2 goods-in, §3 register, §4 report invoices, §5 dashboard, §7 hide switch, §11 keyboard:
  `unverified` → `gated`, each naming its gate and guide case. Nothing moved to `verified`: no person has walked them.
- ⚠ items added: the till line total (D1), SESS-2 including the background-load and blank-message halves (§8), the scan
  that lost characters while the till loaded (§11, open).
- Corrected wording the product had outgrown: goods-in is SER-7 (many serials, quantity counted), not "one box, QTY
  locks to 1"; Download PDF and quote PDF work; "the keyboard path has no automated gate at all" was false; the
  guarantors gate is 19/19; the receipt race gate has run; the accounts table says what a FRESH install seeds.
- §13: the "not yet verified" table now carries a 5 Oct column with each result; new open items — fresh-install tenant
  shape/plan, booker quote picker on a new business (unverified), marketplace phase 2 written ahead, the scan loss.
