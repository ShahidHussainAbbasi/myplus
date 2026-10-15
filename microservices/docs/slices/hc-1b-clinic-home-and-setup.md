# HC-1b — the clinic runs on its own: Clinic home, Team, branches · parent `hc-1-healthcare-split.md`

Status: **CONSENTED 2026-10-15 ("go") — BUILT, not deployed.** Two corrections from the trace, below. HC-1a (deployed, gate 5/5) gives a Hospital / Clinic tenant its own
type, sign-in landing (`/clinicHome`) and tiles. This slice makes that tenant usable by a real clinic on day one.

## Review — what a NEW clinic tenant gets today (measured, 2026-10-15)
| # | Finding | Evidence |
|---|---|---|
| R1 | **The owner cannot add staff.** Team + permission sets exist only inside `businessDashboard.html` (and a copy in `educationDashboard.html`); a clinic never opens either. No receptionist, no doctor login → the clinic is one person. | `#TeamDiv` in businessDashboard:1321, educationDashboard:331; `team.js` + `permissions.js` load only there |
| R2 | **Team markup is already duplicated** (business + education, same ids). A third copy for the clinic breaks DRY. | `team.js` header: "the dashboards keep identical element ids" |
| R3 | **A new tenant has no branch (store).** Doctors are assigned to a branch (HMS H4 checks the branch is the business's store via `/getStores`); the store screen lives in the shop's Configuration. | `myplusdb.store`: orgs 154–157 have **0** stores; `BranchClient` |
| R4 | **Administration tile → `/overview`**, which also shows **Prescriptions waiting** and **Takings today**, both linking to `/businessDashboard` (the shop). For a clinic those are another tenant's (the linked pharmacy, HC-1d). | `overview.html`; `ui_menu overview.prescriptions` (cap `clinic,rxRequired` = ANY → shown), `overview.takings` (no cap) |
| R5 | **Registering a patient and issuing a token on a NEW clinic tenant has never been run.** The HC-1a gate only opened Reception. A patient is also created as a business customer of the clinic org (TradeClient) — unproven for type CLINIC. | gate C3 asserts `#ReceptionDiv` visible only; 0 patients in orgs 154/156 |
| R6 | The clinic's own menus are fine: Reception · My queue · Today's queue · Doctors · Clinic settings; the in-tenant "Pharmacy" link is already hidden (HC-1a). | `clinicDashboard.html` 29–55 |

Market check (vendor pages, not a standard): clinic products open on **today** — appointments / the queue
(waiting · in consultation · done) — and split by role: front desk (walk-ins, check-in, queue), doctor (own queue
only), admin/owner (all doctors, slots, staff). Sources: medicalmet dashboard, clinic1st, Odoo clinic modules.

## Design (decisions D1–D5 need your yes/no)
**D1 · The Clinic home is the owner's hub** (one page, sections gated like the tiles — data rows, F3):
- **Today** — the existing overview cards that are the clinic's: *Tokens today*, *Waiting now*, *Doctors on duty*
  (reuse `overview.js`; no new endpoint). *Prescriptions* and *Takings* are NOT shown in a clinic (they are the
  pharmacy's — HC-1d brings "prescriptions sent to my pharmacy").
- **Where to work** — the tiles (unchanged from HC-1a).
- **Set up your clinic** — a short checklist that disappears when done: ① add a branch ② add your doctors ③ add your
  staff ④ (optional) clinic settings. Each item links to its screen and ticks itself from real data.

**D2 · Administration tile → the Clinic home's owner sections** for a CLINIC tenant (ui_workspace data: a
`clinic_admin` row for CLINIC, the current `admin` row narrowed to PHARMA). A combined pharmacy (org 15) is unchanged.

**D3 · Team for the clinic, by extracting ONE shared fragment** `fragments/team :: team` (the Team list, invite,
remove/reactivate, permission matrix) used by the business, education AND clinic pages; `team.js` / `permissions.js`
unchanged. The clinic shows it as a **Team** item on its own page. Risk: it touches the shop and school dashboards →
their Team specs are the regression gate.

**D4 · Branches for the clinic** — a small **Branches** screen on the clinic page (name, address, phone; add /
rename / deactivate) over the SAME business-service stores, so doctors' branch and Team location grants work
unchanged. Label "Branch", never "Store".

**D5 · First-run proof (R5)** — the gate registers a patient, issues a token and calls them in on a brand-new CLINIC
tenant, so we find out now — not with Dr Tahseen — whether the customer link works for type CLINIC.

Not in this slice: linking to the pharmacy (HC-1c/d), self-signup (HC-1a-2), splitting org 15 (HC-1e).

## Gate (written FIRST, step-by-step + recorded, Test Book §33)
`cypress/e2e/docs/hc-1b-clinic-setup-guide.cy.js`, on a fresh Hospital / Clinic tenant provisioned by the run:
K1 the owner's Clinic home shows Today (3 cards, no Prescriptions / Takings) + the setup checklist ·
K2 add a branch → checklist ticks · K3 add a doctor on that branch · K4 Team: invite a receptionist on the
"Clinic reception" set; they set a password and land on Reception only · K5 the receptionist registers a patient
by phone and issues a token; the owner's *Waiting now* reads 1 · K6 the doctor (linked login) sees My queue and
calls the patient in · K7 regression: the shop's and school's Team screens still invite/remove (existing specs) ·
cleanup suspends the tenant on screen.

## Build order (after consent)
1. Gate red. 2. `fragments/team` extraction (business + education first, their specs green). 3. Clinic page: Team +
Branches. 4. Clinic home sections + checklist; auth migration for the `clinic_admin` workspace row (recheck the
number — parallel SRA session). 5. Deploy monolith (+ auth if D2). 6. Gate green, Test Book §33.

## Corrections made while building (Rule 0 — traced before coding)
- **D4 Branches DROPPED from this slice.** The doctor's "branch" (HMS H4, `pharmacyStoreId`) is the PHARMACY branch a
  doctor's prescriptions go to — optional, shown only with 2+ branches — and a Team member's location grants are
  optional too (an owner may grant none). Nothing on a clinic's first day needs a branch; a Branches screen waits for a
  multi-site clinic. R3 above over-stated it. The checklist has no "add a branch" step.
- **D2 adjusted:** the clinic's Administration tile opens `/clinicDashboard?open=TeamDiv` (Team, beside Clinic
  settings), not the Clinic home — the tile is SHOWN on the home, so pointing it there is a dead click.

## Built (2026-10-15)
| Layer | Change |
|---|---|
| monolith | `fragments/team.html` — ONE Team block, `team('shop'|'school'|'clinic')`; business + education dashboards now include it (the school copy lacked the Permission-set header that team.js draws a cell for — fixed by sharing). `team.js`: no `#teamStores` → no picker, no location column, no "Edit access", no getStores call. |
| monolith | `clinicDashboard.html`: **Team** menu (owner/admin, same gate as the shop's), the fragment, `api-response.js` + `team.js` + `permissions.js` + `permissions.css` (the school page lacked the CSS too — added); `clinic.js` opens `?open=TeamDiv` and runs `showTeam()`. |
| monolith | `fragments/clinic-today.html` — the 3 clinic cards (Tokens · Waiting · Doctors on duty), shared by `overview.html` and `clinicHome.html`; card CSS moved to `css/overview.css`. |
| monolith | `clinicHome.html`: **Today** (`#chToday`), **Set up your clinic** (`#chSetup`, owner/admin; steps `doctors`, `staff` ticked from `/clinic/doctors` + `/team/users` by `clinic-home.js`; hides when done), **Where to work**. Keys `ui.ch.today/setup/setupDoctors/setupStaff/setupSettings/optional`, `ui.allRecordsInClinic`. |
| auth **V36** | `admin` tile → PHARMA only; new `clinic_admin` tile (CLINIC) → `/clinicDashboard?open=TeamDiv`. |
| specs | NEW `cypress/e2e/docs/hc-1b-clinic-setup-guide-screens.cy.js` (K1–K6); `hc-1a` C2/C3 now use `clinic_admin` → Team. |

Known, not changed: the clinic owner's permission matrix lists the BUSINESS catalogue (moduleOf CLINIC → BUSINESS), so
shop areas appear in it; a clinic-only vocabulary is Tier-2 (feature catalogue).

## Deployed + GREEN — 2026-10-15
auth (V36, 14:26) + monolith (14:26) live. Gate `hc-1b-clinic-setup-guide-screens` **6/6** (first run 5/6: K3 matched the
set's option text without its " · built-in" suffix — spec fixed). Regression: `hc-1a` 5/5 (its Administration step now opens
Team), `team-invite-first-login` 10/10, `team-members-manage` 5/5, `hms-rw4b-overview` 4/4, `hms-rw3-workspaces` 5/5.
**D5 answered:** a patient registers, gets an MRN and a token on a CLINIC-type tenant. Test Book §33 published (Copy v10),
§32 re-recorded. School Team screen: no automated case — walk by hand. Test tenants SUSPENDED.
