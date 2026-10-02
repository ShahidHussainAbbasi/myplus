# EX-2a — Expenses on the school, welfare and farm dashboards

**Status:** DESIGN + gate written first → implementing. Branch `feature/expense-management`. Follows EX-1 (304b86b0).
Programme: [`../expense-management-design.md`](../expense-management-design.md) §7. EX-2 is split in two vertical
slices: **EX-2a** (this) gives every domain the switch and the screen; **EX-2b** adds tags (school / vehicle / land)
validated server-side by per-module providers.

## 1. Document
R3: Expense Management must cover every business type, plug-and-play. After EX-1 only the business dashboard can
use it: the Expenses screen exists only there, and the **capability switch exists only there** — the education,
welfare and agriculture Configuration screens render their own service's catalog, while `org.cap.*` lives in
auth-service. An owner of a school could not turn the module on at all.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Business | Same voucher, same ledger rules, every vertical. No vertical-specific code in expense-service |
| Tenancy | Unchanged: org from the token; the switch writes the caller's own org (auth `SettingsController`, owner/admin) |
| Live modules | Opt-in stays OFF; nothing appears for a tenant that has not switched it on |
| Boundaries | No service changes. Monolith only: one card, one controller, three dashboard insertions |
| Patterns | **Single source of truth** for "which modules are opt-in": auth's catalog default (EX-0a guarantees catalog default = `Capability.defaultOn()`), filtered as `org.cap.*` with default `false` — no list in the monolith. **Allow-list on write**: the card's endpoint writes only keys in that filtered set, so it cannot flip a trade capability |
| DRY | The auth routing (`authGet`, `authPost`, token re-mint) moves from `BusinessConfigController` into `AuthSettingsClient`, used by both. Expenses screen = the EX-1 fragment + `expense.js`, unchanged |
| Testing | Cypress per domain with its own owner (gate per DOMAIN rule) + a tier refusal; EX-0a / capability regression re-run because the business config controller is refactored |

## 2. Design
- `com.web.util.AuthSettingsClient`: `catalog()`, `save(key, value)`, `reset(key)` — the last two re-mint the
  session token (`gateway.refreshNow()`), exactly as `BusinessConfigController` did.
- `com.web.controller.ModuleSwitchController`: `GET /moduleSwitches` → `{success, data:[{key, code, label, help,
  enabled}]}` (opt-in only); `POST /saveModuleSwitch` (`key`, `enabled`) → refuses any key not in that set
  (`success:false`), else `AuthSettingsClient.save`. Owner/admin by auth's own `@PreAuthorize`.
- `fragments/module-switches.html` + `/js/common/module-switches.js`: a "Modules" card (checkbox per module,
  saved on change, reloads capabilities so menus appear without a reload).
- Dashboards: education (Fees menu → Expenses; card in its Configuration), welfare and agriculture (sidebar link →
  Expenses; card in `#ConfigDiv`). Each includes `fragments/expense :: expenseSection` and `expense.js`.

## 4. Gate (written first) — `cypress/e2e/expense/ex-2a-every-dashboard.cy.js`
For **owner.education, owner.welfare, owner.agriculture**, each its own tenant:
1. Configuration shows a **Modules** card with **Expense management**, unticked; no Expenses entry in the menu.
2. Tick it → saved; `/getCapabilities` says on; the Expenses entry appears.
3. Record a Rent expense through the screen → `EXP-` number, chip reaches **In the books**.
4. `POST /saveModuleSwitch key=org.cap.installments` → refused (allow-list).
Plus: **user.education** saving the switch is refused. `after()` resets each tenant's override.
Regression: `ex-0a-capability-opt-in`, `capability-shapes`, `capability-gating` (business config refactor).
