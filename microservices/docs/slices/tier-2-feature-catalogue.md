# TIER-2 — the catalogue as data: Product line → Module → Feature → Action · standards §1e (V1–V4, F1–F4)

Status: **DESIGN — vocabulary agreed with the user 2026-10-15; the Business list below awaits the user's review.**
(Tier-1 operator 2FA: deferred by the user.)

## Vocabulary (standards §1e)
**Product line** = Business / Education / … · **Module** = a main menu · **Feature** = a sub-menu (SCREEN) or a
BEHAVIOUR · **Action** = View / Create / Edit / Delete / Approve · **Permission** = feature × action.
Grants are stored per FEATURE; "grant the whole module" is a shortcut (V3) — so any feature can later MOVE to
another module without changing who has it.

## Data model (auth service)
| Table | Columns | Note |
|---|---|---|
| `module` | module_key PK · product_line · name_key · icon · sort_order · status | a main menu |
| `feature` | feature_key PK · module_key FK · kind (SCREEN / BEHAVIOUR) · name_key · description_key · sort_order · status | moving = one UPDATE of module_key |
| `plan_feature` | plan · feature_key | replaces the presets coded in `Plan.java` |
| `org_entitlement` | (exists) org · feature_key · ACTIVE/SUSPENDED · source · dates · reason · granted_by | the operator's grant, per feature |
| `ui_menu` | (exists) …, `capability` = the feature key on EVERY row | the screen entry point(s) of a feature |
| `permission` | (exists) code · `capability` = feature key · action | the matrix cells |

Code keeps typed keys for the 157 `Capability.X` checks; start-up fails loudly if a key has no `feature` row.
Every existing tenant is granted every feature it can see today (source GRANDFATHERED) — nobody loses access (F4).

## Business — modules and features, from the LIVE menus (2026-10-15) — for review
SCREEN features (sub-menus as users see them). ⚑ = a question or a proposed move for the user.

| Module (main menu) | Features (sub-menus) | Today's gate |
|---|---|---|
| **Register** | Company · Suppliers · Customers · Products · Stock count · Labels · Quarantine | menu rows; Quarantine needs batch/expiry tracking |
| **Purchase** | New purchase · Debit notes · Price approvals | rows; Price approvals hardcoded owner/admin |
| **Sale** | New sale · Parked sales · Sale detail report · Credit notes · Quotes · Installments · Marketplace selling | rows; ⚑ Quotes is gated by `dealerPricing` today — should Quotes be its own feature? |
| **Store** (marketplace / distribution only) | Book order · Orders · Round sheet · Territory · Driver settlement · Order settings | partly hardcoded (owner/admin); field sales, journey planning, collections |
| **Pharmacy** (pharmacy only) | Prescriptions · Clinical & safety · Alerts & register · Clinic | rows; Rx required, expiry tracking, clinic |
| **Clinic** (clinic dashboard) | Reception · Queue · Doctors · Clinic settings · To pharmacy | rows; clinic |
| **Till** | Cash drawer · ⚑ Expenses | Expenses hardcoded `data-capability`; ⚑ propose MOVING Expenses to its own module (vouchers, advances, bills — it outgrew the till) |
| **Finance** | Trial balance · Profit & loss · Balance sheet · Tax register · Stock & sales (SSR) · Audit log | ALL hardcoded owner/admin today |
| **Settings** | Stores · Tax settings · Price rules · Markup by category · Bonus offers · Document designer · Configuration · Opening balances | ALL hardcoded; ⚑ tenant administration — see below |
| **Team** | Manage users | hardcoded owner/admin |

**BEHAVIOUR features** (granted by the operator, no menu, not in the members' matrix — V2), by module:
Inventory-wide: batch tracking · expiry tracking · FEFO allocation · serial tracking · condition grading ·
Sale: loose selling · dealer pricing · bonus schemes · made-to-order · order types ·
Pharmacy: Rx required · Store: field sales · journey planning · collections · Expense: expense claims.

### ⚑ Decisions for the user
1. **Settings and Team** — every product keeps tenant ADMINISTRATION always available to the owner (Shopify
   "Settings", Salesforce "Setup"): proposed **always-on modules the operator cannot remove**, whose features the
   OWNER can still delegate to admins through the matrix (e.g. Price rules to a manager, never Team).
2. **Expenses** — move from Till to its own module (proposed).
3. **Quotes** — its own feature, no longer hidden behind dealer pricing (proposed).
4. **Store / Pharmacy / Clinic** are product-line specific — they only appear in the catalogue for tenants whose
   business type uses them.

## Order of work after the list is agreed
T2a `module` + `feature` + `plan_feature` tables seeded from this list; grandfathering; start-up key check.
T2b every hardcoded Business menu → `ui_menu` rows with their feature (Finance, Settings, Team, Till/Expenses, Store).
T2c operator console: catalogue grouped by module, per-feature grant with "whole module" shortcut, history.
T2d permissions = feature × action for every SCREEN feature (the owner's matrix regrouped by module).
Then education (slice 3) and server-side enforcement (slice 5).

## Gate (per sub-slice, red first)
Catalogue lists every module/feature above; every existing tenant keeps every feature it has today; moving a feature
between modules changes the menu only; suspending a feature hides its sub-menu AND refuses its API; a main menu with
no visible feature disappears; behaviour features never appear in the members' matrix.

## Decisions (user, 2026-10-15)
1. Settings and Team are NOT always-on: the operator may withhold them — but member removal/deactivation and password
   reset are never blocked (standards V5).
2. Expenses moves out of Till into its own **Expenses** module.
3. Quotes becomes its own feature (no longer behind dealer pricing).
4. Store / Pharmacy / Clinic: replaced by the **Healthcare** product line with two business types — *Hospital / Clinic*
   and *Pharmacy & Pharmaceutical* — as SEPARATE, LINKED tenants (V6); the combined clinic-inside-pharmacy tenant is
   dropped. Design: `slices/hc-1-healthcare-split.md`. Store stays with the distribution/marketplace business types.
5. One email per tenant owner today (V7).
