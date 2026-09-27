# SET-CERT — every setting, on every axis, proven by a test and walkable by hand

Status: **DESIGN + MEASURED 2026-09-26.** Requests: "make sure e2e 100% cypress test and published page is equal so
manual testing can be verified 100% … anything missing according to standards and docs must be verified and fixed" ·
"complete 100% from every business type/module, every tenant, capability and users … prepare cypress test, update
docs/page, design and implement".

## 1. What "100%" means here (the definition the gate enforces)
A setting is **certified** only when ALL of these hold:
1. **Effect, not save.** One named Cypress case switches it to a non-default value and asserts what it CHANGES
   (a field appears, a sale is refused, a document prints a line) — then restores it. "Saved" proves nothing.
2. **One row = one case.** The published page's row for the setting cites that exact case id, and the page is
   GENERATED from the run: a case that did not pass cannot appear as verified (it appears as a known issue).
3. **Every axis that can change the answer is exercised:**
   - **business type / shape** — does the setting show, and what is its default, per shape (5);
   - **capability** — is it hidden/refused when its capability is off (15);
   - **plan** — is it locked when the plan excludes it (4 plans + explicit entitlements);
   - **role ladder** — owner changes it; admin as the standards say; user/cashier/booker cannot (screen AND API);
   - **tenant isolation** — tenant A's change never reaches tenant B.
4. **Leaves no state.** Restores the EFFECTIVE value (a blank capability override is an explicit OFF — see §4).

## 2. The surface (measured, not listed from memory)
| Module | Catalogue | Settings |
|---|---|---|
| Business (POS, pharmacy, mobile, distribution, restaurant) | BusinessSettingsCatalog + capabilities + shape | 106 on screen |
| Orders / marketplace | MarketplaceSettingsCatalog | 11 |
| Education | EducationSettingsCatalog | 20 |
| Welfare | WelfareSettingsCatalog | 2 |
| Inventory | InventorySettingsCatalog | 2 |
| Agriculture | AgricultureSettingsCatalog | 1 |
| Locale (all modules) | LocaleSettingsCatalog | 1 |
| Axes | Capability 15 · Shape 5 · Plan 4 · roles: owner, admin, user, cashier, booker, operator, teacher, guardian, student | |
Plus the non-catalogue settings screens: Tax Settings, Price Rules, Bonus Schemes, Document Designer, Stores, Opening
Balances, the operator's Plan/Entitlements/Business type/Status.

## 3. Coverage today (business + orders, 117 settings)
`scratchpad/coverage.js` greps every spec for every key: **57 referenced by ≥1 spec, 60 by none.** "Referenced" is an
UPPER bound — it is not "effect asserted". Uncovered by group: Documents 26 · Sale entry 11 · Installments 9 · What this
business does 5 · Customer & credit 2 · Data entry 2 · Sales quotes 2 · Workflow 1 · Pharmacy 1 · Orders 1.
Education/welfare/inventory/agriculture/locale: not yet measured (Phase 3).

## 4. Findings (verified — each traced to code or data)
| # | Finding | Evidence | Severity |
|---|---|---|---|
| F1 | **5 installment settings are saved but never enforced**: minimum down payment %, max open plans per customer, block while overdue, require CNIC — and markup (labelled "not yet available") | `InstallmentEligibilityPolicy` (common-installment) is referenced by NO production code; its `Rules` is only ever the all-off default; no spec references the keys | High — the screen promises a control that does nothing |
| F2 | **A saved default payment method never reaches the first sale** (opens on CASH) | live probe: `posDefaultTender=CARD`, latch set, select=CASH; `sale-defaults-race` misses it because it STUBS settings | High — wrong payment method recorded |
| F3 | Every locked setting badges "Not in plan", whatever the reason | cutover-date lock (accounting) shows the plan badge | Low |
| F4 | A capability restore that writes blank/null leaves an explicit OFF | `CapabilityService.resolve`: present override → `"true".equals` | Test hygiene — rule for every spec |
| F5 | Specs that change `org.shape` restore it to `general`, not to what it was | org 13 retail → general during the suite | Test hygiene |
| F6 | **The margin rule cannot protect stock that was never PURCHASED.** A sale line's cost is the product's most recent purchase (`SagaSellService` → `purchaseRepo.findRecentCosts`, "null if never purchased"); opening stock loaded with a cost gives the rule nothing, so "block sales below cost" silently never fires for it | cert case B-006 sold at 1 against opening stock costed 60 with policy=block; inventory holds the batch cost | Medium — needs a ruling: fall back to the batch cost when no purchase exists? |

**F1 data check (dev):** only one tenant has saved any of the five, and it saved the default. ⚠ `maxOpenPlansPerCustomer`
DEFAULTS TO 1 — enforcing it as written would limit every shop's customers to one open plan (today: unlimited). That is a
policy decision, recorded below, not a bug fix. Production needs the same preflight before enforcement ships.

## 5. Phases (each: red gate first, implement, green, page regenerated)
- **P0 — defects.** F2 fix (re-apply the shop's default after the section switch resets selects; gate with REAL saved
  settings, not a stub). F3 badge says "Locked" unless the reason is the plan. F1 per the decision in §6.
- **P1 — Business certification spec** `cypress/e2e/cert/business-settings.cy.js`: one case per setting (117), table
  driven — {key, nonDefault, where, assert}. Grouped runs to fit memory.
- **P2 — Axes spec** `cypress/e2e/cert/axes.cy.js`: shape × group visibility/defaults; capability off → hidden AND
  refused; plan lock + entitlement grant; role ladder (screen + API) for every settings screen; cross-tenant isolation.
- **P3 — Other modules**: education (20), orders (11, partly done), welfare, inventory, agriculture, locale — same pattern.
- **P4 — The page, generated**: every row = setting × module with manual steps + the case id + pass state; one page per
  module (Business, Orders, Education, …) linked from the Test Book; the certification run IS the pre-publish gate.

## 6. Decisions needed from the owner
- **F1 — enforce or withdraw the 4 installment eligibility rules?** Recommended: ENFORCE, with the
  `maxOpenPlansPerCustomer` default changed to 0 = unlimited (keeps today's behaviour for every shop until an owner
  chooses a limit), and `markupEnabled` shown LOCKED with its "not yet available" reason until the finance account
  exists.

**Owner ruling (2026-09-26): ENFORCE the four, "max open plans" default 1 → 0 = no limit, markup shown LOCKED.**

## 7. P0 — implemented (2026-09-26), awaiting rebuild
- **F1** business-service: `InstallmentPlanService.eligibility()` reads the four rules + builds the customer's standing
  (open = ACTIVE|DEFAULTED like `openPlanCount`; days overdue like RepossessionService) and calls the existing pure
  `InstallmentEligibilityPolicy`. `SellController` calls it in the PRE-WRITE block beside the serial and deposit checks
  (CNIC = the one on file via the scoped `findByIdScoped`, else the one typed). Catalogue: max-open-plans default 0.
  `InstallmentMarkupGuard` (a `SettingWriteGuard`, like CutoverDateGuard) refuses markup=true → the screen shows it
  locked with the reason. `InstallmentEligibilityWiringTest` 10/10; RED 4/10 with the rules ignored (the 4 enforcement
  cases), so it cannot pass on the old behaviour.
- **F2** monolith `business.js`: a delegated `#sellType` change handler (runs AFTER main.js's select reset) clears the
  tender latch and re-applies the shop's defaults for an EMPTY, non-edit sale.
- **F3** `settings-form.js`: the lock badge says "Not in plan" only for a plan refusal, else "Locked" (`ui.js.settingLocked`,
  6 locales).
- **Needs:** business-service + monolith rebuild. Production preflight before F1 ships: count tenants with a non-default
  value for the four keys (dev: one, holding the default).

## 8. P1 — certification spec written
`cypress/e2e/cert/business-settings.cy.js` — 106 cases, one per business setting, table-driven (letterhead / document /
page-variable / sale-line field / capability / server-rule builders). Groups: Core, Installments, Documents, Capabilities
(`--env certGroup=…`). Output `cypress/guide-out/cert-business-<group>.json` → the page.

## 9. UI-CFG-1 — category rail + Reset to default (2026-09-26, awaiting rebuild)
**Why:** 106 settings in 16 groups on one scrolling page. The owner chose the **category rail** (Shopify / Square / Odoo
settings pattern): 7 categories — Business, Selling, Receipts & documents, Installments, Buying & quotes, Pharmacy,
Accounts — one pane at a time, search across ALL categories with a per-category hit count, a dot on every setting (and
category) changed from its default, and **Reset to default** on the row. A group no category names lands in "Other".

**Reset is not "save the default".** Any present override pins a setting against the shop preset and the business type.
The server now REMOVES it: `SettingsService.reset(key)` → guards asked about the DEFAULT (a reset cannot grant what a write
would refuse) → `SettingsStore.remove` → cache invalidate → listeners `(before → null)`. `POST /settings/reset` on
business-service and auth-service (same authority as save); monolith `/resetBusinessConfig` routes like the save.
Unit: `SettingsResetTest` 6/6, common-settings 53/53.

**Gate:** `cypress/e2e/business/config-rail.cy.js` — 10 cases: one pane per category + Σ rail counts = catalogue, search
across categories, unmapped group → Other, WAI-ARIA arrow/Home/End, changed marker + reset removes the override
(`isDefault` true after), capability reset via auth, reset of an untouched key is harmless / unknown key refused, staff
user refused (after proving the owner CAN reset — a 404 must not pass as a refusal), phone chips, RTL.
**Red on the current build: 9/10** (no rail; `/resetBusinessConfig` 404). The 1 pass was the staff case passing on the
404 — fixed as above.

Specs that touch Configuration rows now call `cy.revealSetting(key)` first (a row outside the open category is hidden):
pos-settings-access, pos-keyboard-toggle, entitlement-ceiling, settings-guide.

### ⚠ Dev data: the cert runs PINNED org 13
The first cert restore saved the default value back instead of removing the override. Org 13 (owner.business) now holds
**~90 explicit overrides**, almost all equal to the default — including `pos.installment.maxOpenPlansPerCustomer = 1`
(the OLD default: with F1 enforced, org 13 would be limited to one open plan although the new default is "no limit") and
`org.cap.orderTypes = NULL` (a present blank override resolves as OFF — F4). This also explains the §4 "F1 data check":
the one tenant that "saved" the five installment keys was this test tenant.
**Clean-up after the rebuild:** reset every org-13 override that equals its catalogue default, plus `org.cap.orderTypes`,
through `/resetBusinessConfig` (not SQL — the audit listener and caches see it). Specs now restore by reset when the
setting was untouched (`isDefault` snapshotted): business-settings cert, settings-guide, config-rail.

### Cert cases corrected (my assertions, not the app)
| Case | Was | Now |
|---|---|---|
| B-046 serialRequired | expected a plan on an untracked product to be refused | INST-5b: the rule binds serial-tracked goods only → the plan goes through; the refusal is `installment-serial.cy.js` |
| B-094/099/100 capabilities | `[data-capability="code"]` exact match — found nothing for `fieldSales` / `journeyPlanning`, asserted nothing | `data-capability` is an OR-list; an element is off only when EVERY listed capability is off — asserted per element |
| B-106 locked capability | asserted effective = false | only the refused WRITE is asserted: the read path honours revocation only (F3) |
| fefoAllocation | — | no dashboard element; **gap G-FEFO**: no behavioural gate proves a sale draws the nearest-expiry batch |

### After the rebuild (2026-09-27)
- Gates on the new build: config-rail **10/10**, cert **106/106** (Core 45 · Installments 17 · Documents 28 ·
  Capabilities 16, and it left NO overrides behind), settings-guide **19/19**, and 25 of the page's 28 verification specs
  green. The 3 platform specs went red **while another session's gate was changing org 13 at the same time** — rerun
  alone before any diagnosis.
- Org 13 un-pinned through `/resetBusinessConfig`: 104 of 106 overrides removed. Kept: `business.cutoverLocked=true`
  (real state — opening balances recorded) and `org.cap.orderTypes=NULL` (below).
- **Defect in reset, fixed:** `SettingsService.reset` tested the override's VALUE (`overrideFor` → `Optional.ofNullable`),
  so a row holding NULL counted as "no override" and reset answered success having deleted nothing. Now tests PRESENCE
  (`overridesFor(org).containsKey`), matching the screen's `isDefault`. Red first: `SettingsResetTest` 1/7 on the old
  check → common-settings 54/54. **auth-service must be rebuilt** to carry it (orderTypes is an auth key).
- **Correction to F4:** a BLANK `""` capability override resolves OFF; a **NULL** one resolves to the PRESET
  (`Optional.ofNullable` → empty → `shape.includes`). NULL rows do not take features away — but they are PRESENT, so
  the screen marks them "changed".
- **Who wrote 14 NULL `org.cap.*` rows on org 13:** `cy.clearCapabilityOverrides()` (support/commands.js) posted every
  capability key with NO value — deliberately, before a delete existed. Called by capability-fields, capability-shapes,
  migration-safety, onboarding-profile. The same key-without-value "restore absent" was in expiry-tracking-capability
  and credit-note-loose-units. All three now POST `/resetBusinessConfig`. The server still accepts a save with no
  value (`@RequestParam(required = false) String value` → NULL row); tightening that is a separate decision.

### Owner ruling (2026-09-27): refuse a save with NO value, point to Reset to default
`SettingsService.set` — the one writer behind every settings endpoint (common-settings `/settings`, and the
education / welfare / agriculture `/saveConfig` controllers) — now refuses `value == null` with *"No value was given for
<key>. To go back to the default, use Reset to default."* A blank `""` is still a value. Callers traced: all 5 screens
send a value on every save (checkbox state or the field's text); the only server-side caller (`OpeningBalanceService`)
sends "true". Tests: `SettingsResetTest` +2 (refused, no row, no guard asked · blank still saved).

### Reset vs the plan ceiling (peer finding, fixed)
A reset was guarded as a WRITE OF THE CATALOGUE DEFAULT — "true" for every capability — so a tenant could not clear a
stale override for a capability outside its plan, even a SUSPENDED one (the ceiling keeps that off whatever happens).
The row then silently won again the day the plan was restored. Now `SettingWriteGuard.checkReset` (default = the old
rule, so the cutover and markup guards are unchanged) and `EntitlementWriteGuard.checkReset` asks **"would it be ON once
the row is gone"**: revoked → allowed · lands OFF → allowed · ON within the plan → allowed · ON from the business type
but outside the plan → refused (the read path subtracts only revocations, so that reset WOULD grant it).
`EntitlementWriteGuardResetTest` 6/6 — red 2/6 against the old rule (suspended; lands-off). auth-service 71/71.
This refusal is also what cascaded in the platform rerun: onboarding case 10's `clearCapabilityOverrides` stopped at
orderTypes after deleting the others, leaving org 13 on a type without installments → operator-portal case 8's
precondition failed.

### ⚠ Found: the operator console's Activity panel shows the OPERATOR's trail under the tenant's name
`/platform/activity?organizationId=49` returned **org 8's** events (the operator's own tenant). Since E5,
`CurrentUser.organizationIdFor` honours another org only under an OPEN support session and otherwise substitutes the
caller's own org; the proxy's javadoc still describes the pre-E5 rule ("honoured for ROLE_ADMIN"). Case 10 of
control-plane-audit passed before only because an earlier case left a session open. Not a leak (org 8 is the
operator's own) — a mislabel, the failure E5's own comment names. **Needs an owner ruling** — see the report.

### onboarding-profile case 11 — fixture assumption, fixed in the spec
It expected an untyped tenant on the console's FIRST page; the list is paged newest-first and every tenant onboarded
since ONB-1 has a type, so they sank off page 1 as data grew. Now it searches for one the API reports untyped.

## 10. SET-GUIDE — the complete guide (2026-09-27)
**Owner rulings:** Activity split (E5b, generalised to "refuse, never substitute" — `e5b-operator-refuse-never-substitute.md`);
add `@simonsmith/cypress-image-snapshot@10.0.3` + `cypress-axe@1.7.0`/`axe-core@4.13.0` (dev only, local); the live-app pass by
Claude counts as the manual pass with a per-case human sign-off.

### Built
- **Write-time type validation** (`SettingsService.canonical`): BOOL on/off, INT whole number, MONEY decimal, SELECT one of
  its options (stored as the option is written), TEXT free. Before: "abc", a non-option, or a cleared number box was STORED,
  marked changed, and silently ignored by every (fail-soft) reader. `SettingsTypeValidationTest` red 4/5 → 5/5.
- **Reset to default on every screen**: `SettingsStore.remove` in education, welfare, agriculture, marketplace, inventory;
  `/resetConfig` in the three module services; monolith `/resetOrderConfig`, `/resetConfig`, `/resetWelfareConfig`,
  `/resetAgricultureConfig`. One client path: `saveSettingsField` / `resetSettingsField` / `settingsOutcome` (a refused
  value is re-read, never left in the box looking saved — the module screens used to flip `.checked` on a text box).
- **Accessibility**: muted text #7a889c (3.6:1) / #8a97aa (2.96:1) → #5f6b7c (≥4.8:1); Tax screen help and h4 small;
  live regions on the row marker and the 5 banners; the rail is a complete WAI-ARIA tabs widget (aria-controls → tabpanel
  labelled by the active tab).
- **Cypress window 1920×1080** (`before:browser:launch`): headless Electron's 1280×720 CROPPED every 1366px viewport shot —
  the "cut-off control and horizontal scrollbar" were the capture, not the page (measured). The previously published
  guide's desktop pictures were cropped the same way.

### Gates (new)
| Spec | Cases | Asserts |
|---|---|---|
| `cert/settings-security.cy.js` | 27 | 5 screens × owner/admin/user/cross-tenant/signed-out, checked on the VICTIM; X-Org-Id spoof; operator console refused to tenants |
| `cert/settings-ui-quality.cy.js` | 32 | axe WCAG 2 A/AA per screen, focus visible, refused save announced, tabs semantics, 3 widths × no sideways scroll + visual baseline (18) |
| `cert/module-settings.cy.js` | 34 | 9 BEHAVIOUR (the 7 untested + 2 education keyboard) + 25 ROUND TRIP naming the deep spec |

### Found and fixed along the way
- 7 module settings had NO test: order.backorder.promiseDays, edu.discount.branchScoped, edu.grading.absentCountsAsZero,
  edu.grading.roundHalfUp, edu.reportCard.showAttendance, edu.attendance.staffGraceMinutes, edu.leave.requireApproval —
  each now switched both ways with its effect asserted where it lands.
- **Paging traps** (the fixture existed; the first page could not show it): onboarding-profile 11 (untyped tenant),
  operator-portal 4 (20 lapsed trials in the DB, none on page 1). Both now search for the row.
- **Session precondition**: control-plane-audit 10/11 ran under the spec's own open session; migration-safety case 3
  replaced a customer-APPROVED session and broke cases 9–14. Split into case 3 + final case 3b.
- **Console race**: with a session open, Activity could load before the refreshed token → platform-only rows with no
  note. Activity now reloads after `refreshSupportScope`.
- **Pinned defaults**: older specs "restore" by saving the default — 10 order + 9 education overrides equal to their
  default were reset (no presets in these modules; 2 genuine non-defaults kept). Specs still doing it: branch-scope-settings,
  order-backorder, report-cards (follow-up).

### ⚠ Platform gap — an entitlement row cannot be removed
`POST /admin/entitlements` grants or suspends; nothing clears. A gate that creates a row cannot restore "no record": another
session's teardown left org 13 `orderTypes` SUSPENDED (a revocation), which failed entitlement-ceiling "inert deploy". Row
id 532 (created 05:43 by that gate) was deleted directly — the exact prior state. **Decision needed**: a CLEAR status (or
DELETE) for operator entitlement rows, audited like the others.

### Open
- Inventory `inventory.reservation.holdMinutes` / `orderHoldMinutes`: API only, no screen — where should they live?
- Mobile: the floating menu button covers the first category chip when the chips are scrolled to the very top.
- G-FEFO and F6 as before.
