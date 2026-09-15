# SALE-DEF — a late settings load must never undo the cashier's choice

**✅ GATED GREEN — `sale-defaults-race.cy.js` 4/4 (was 1/4 before the fix) and `sell.cy.js` 31/31, headed, solo,
2026-09-15 17:15-17:20, monolith 17:14:03 (served `business.js` == src). Committed in the user's `25304ae2` (17:17).**
Regressions on the same build: in progress (see the run list below). **Test Book: ✅ done** — v30 §1 "The till keeps
the choice you made" (published by myplus-11), including both known limits below.
**Status 2026-09-15 (history):** consented (the user: "Yes, fix it"; order: finish PSEL-1's regressions FIRST, then this fix →
monolith rebuild → `sell.cy.js` + the gate). **Gate proven BEFORE the fix** — `sale-defaults-race.cy.js` on the 14:22
builds = 1/4 exactly as designed: case 1 read `CARD` for `CREDIT`, case 2 lost Manual mode, case 3 lost Select mode,
the control passed, and no "settings have not landed" precondition fired. **Fix WRITTEN** (both `business.js` edits,
`node --check` OK), **NOT yet built.** The user chose: finish now, commit the slices separately later (index-only
patch per slice — `business.js` also holds PSEL-1's two hunks; never back a hunk out).
Owner: session myplus-f9 (agreed with myplus-11, who reviewed the design).

## 1. What broke, and why only now

`sell.cy.js` went 29/31 on the PSEL-1 build (13:05). Both reds, traced independently by both sessions:

- `loadPosFeatureFlags` → `applyPosFieldVisibility` (`business.js:5003` success / `:5025` failure) applies the
  tenant's **defaults for a fresh sale** whenever `/getBusinessConfig` lands:
  - `:5277-5280` sets `#sellPayMethod` to `posDefaultTender`. Its `posDefaulted` latch is set only when the LOADER
    applies it, so a tender the cashier picked earlier is overwritten.
  - `:5283-5286` calls `onCustomerModeChange(defaultMode)` with no latch — and `onCustomerModeChange` (`:2415`) also
    **clears `#sellCustomerDD`, `#sellCN`, `#sellCC`**, so a chosen customer or a typed walk-in name is erased.
- The existing guard (`:5271-5272`, `saleInProgress = data.length > 0 || editingInvoice`) protects a part-rung sale,
  not choices made on an **empty cart** — which is exactly when a cashier picks the tender or the customer.
- **Pre-existing:** `43a4ba82` (08-09) and `916d5f3a` (08-10). PSEL-1 changed neither (its `business.js` hunks are
  only `:656-658` and `:676`). The 7–17 s New Sale freeze used to guarantee the settings landed before any click;
  PSEL-1 removed the freeze and exposed the race.
- **Impact (corrected by myplus-11):** not a money loss — with nothing received, change < 0, so `owesBalance` stays true
  (`main.js:476`) and the due is still recorded. It is a wrong tender LABEL (Cash for Credit), `#sellRecWrap`
  reappearing, and an **erased walk-in name** (a fully paid sale then completes unattributed).

## 2. Every caller (RULE 0)

| Thing | Caller | Kind | After the fix |
|---|---|---|---|
| `onCustomerModeChange` | template `#btnModeSelect` / `#btnModeManual` onclick (`:2421/:2425`) | **the cashier** | sets the mode latch |
| | `business.js:562` invoice edit load | code | unchanged (and `editingInvoice` returns early) |
| | `business.js:1756` `resetCart` | code | unchanged; also **clears** the latch (a new sale is fresh) |
| | `business.js:5286` the default | code | skipped when the latch is set or the customer block is in use |
| | `park.js:108` resume a parked sale | code | unchanged; the "customer block in use" check covers it even before `data[]` fills |
| `onSellPayMethodChange` | template inline `onchange` (`:2841`) | **the cashier** | the latch is set by a separate `change` listener, not here |
| | template `:4658` New Sale opened | code | ⚠ why the latch can NOT live inside this function — it would latch before the settings land |
| | `business.js:5280` after the default | code | unchanged |
| `posDefaulted` | set `:5278`, cleared `:5674` (`saveBusinessConfigToggle`) | — | a SETTINGS SAVE still re-applies the new default on an empty cart — intended ("not about ignoring the owner") |

Checked and ruled out: `#sellPayMethod` is outside every `<form>` (`form#Sell` = template 2433–2708, the select is at
2841), so the line form's native reset never touches it.

## 3. The fix (to write after the 16)

- A delegated `change` listener on `#sellPayMethod` sets `posDefaulted` — real change events only; code paths use
  `.val()` and fire none.
- A delegated `click` on `#btnModeSelect, #btnModeManual` sets `window.posCustomerModeChosen`; `resetCart` clears it.
- `applyPosFieldVisibility` applies the default mode only if `!posCustomerModeChosen` AND the customer block is not in
  use (no chosen `#sellCustomerDD`, no typed `#sellCN`).
- Not touched: PSEL-1's lines, `resetCart`'s own reset to Select, the edit/park paths, the settings-save re-default.

## 4. Gate — `cypress/e2e/business/sale-defaults-race.cy.js` (written before the fix)

The `/getBusinessConfig` reply is held 5 s by the intercept and its defaults rewritten in the browser (tenant config
untouched). Each case first asserts the settings have NOT landed (`posDefaultTender` undefined) — or it would pass
without testing anything — then waits for them to land and asserts:

1. Credit chosen before → still Credit (default rewritten to CARD).
2. Enter Manually + typed name before → still manual, name kept (default `select`).
3. Customer chosen in Select mode before → still chosen (default `manual`).
4. **Control:** an untouched sale still gets CARD + manual — the fix must not switch defaults off.

Expected before the fix: 1–3 red, 4 green. After: 4/4.

**Post-fix runs (solo, headed):** `sale-defaults-race`, `sell`, `sale-customer-first`, `pos-shortcuts`,
`pos-checkout-chain`, `park-hold`.

**`mvn test`:** does not apply — the slice is client-side only (`business.js` + a spec); no Java is touched.

**Test Book (manual, after green, before the commit):** open New Sale and at once choose **Credit**, click **Enter
Manually** and type a walk-in name; wait 5 s — both survive. Open a fresh New Sale and touch nothing — the shop's
default tender and customer mode are applied.

**Known limit (pre-existing, NOT changed here):** `resetCart` forces Select mode after every sale, and nothing
re-applies a tenant's `manual` default to the next sale — only a page load or a settings save does. Likewise the
TENDER latch (`posDefaulted`) is never cleared by `resetCart`: after the first sale on a page the tender stays whatever
was last used until a reload or a settings save (already true before — the loader latched after its first
application; SALE-DEF does not change it; noted by myplus-11 in review). Recorded so they are not lost; a separate ruling.

**Commit:** `business.js` also holds PSEL-1's uncommitted hunks — stage by hunk, or one commit once both are green.
