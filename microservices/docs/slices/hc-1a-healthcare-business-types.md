# HC-1a — Healthcare product line and its two business types · standards §1e (V6, V7)

Status: **CONSENTED 2026-10-15 — then CORRECTED by the reader trace (below) before any code.** Parent: `hc-1-healthcare-split.md`.

## Measured (dev, 2026-10-15)
- `organizations.type` already acts as the PRODUCT LINE: BUSINESS 130 · EDUCATION 2 · WELFARE 2 · AGRICULTURE 2 ·
  APPOINTMENT 2 · MARKETPLACE 2 · PHARMA 4 · INVENTORY/CAMPAIGN/ANALYTICS/ADMIN 1 each.
- `org.shape` already acts as the BUSINESS TYPE inside Business: retail 40 · pharmacy 50 · general 4 (enum `Shape`:
  general, retail, pharmacy, distribution, storefront).
- Pharmacies exist two ways: 4 orgs typed `PHARMA` (the LIVE type — see point 3), 50 typed `BUSINESS` + shape `pharmacy` (onboarding test leftovers).
- `ModuleRouter` maps type → landing page (`PHARMA` → /businessDashboard, `APPOINTMENT` → /appointmentDashboard …);
  there is no clinic type — the clinic runs inside a pharmacy tenant (org 15).
- Signup and operator provisioning take `userType` + `shape` (`ProvisionTenantRequest`).

## Design
1. **The list is DATA** (standards F3): `product_line` (key, name, sort) and `business_type` (key, product_line,
   name, description, org_type, shape, landing, sort, status). Signup and the operator's "new tenant" screen show
   product line → business type, from these rows. A new business type later = one migration row (+ its modules).
2. **Healthcare seeded with two business types:**
   | business type | org_type | shape | runs on | lands on |
   |---|---|---|---|---|
   | **Hospital / Clinic** | `CLINIC` (new) | `clinic` | clinical + appointment services | `/clinicDashboard` |
   | **Pharmacy & Pharmaceutical** | `PHARMA` (existing, unchanged) | `pharmacy` | business/catalog/inventory (unchanged) | `/businessDashboard` |
   The pharmacy needs no engine change — it IS today's pharmacy shape. The clinic is a new org type routed to the
   clinic application (made standalone in HC-1b).
3. ~~Normalise the 4 `PHARMA` orgs to `BUSINESS` + `pharmacy`~~ — **WITHDRAWN (Rule 0):** `PHARMA` is the LIVE pharmacy
   type, not a legacy one: signup offers it, SetupDataLoader seeds `owner.pharma@`/`demo.pharma@` with it,
   `ROLE_PHARMA_USER` exists, the Pharmacy menu is `data-vertical-only="PHARMA"`, `business.js` branches on it, and org 15
   has 92 members of user type PHARMA. Normalising would have broken the real pharmacy. Pharmacy = `PHARMA`, unchanged;
   the ~50 `BUSINESS` + shape `pharmacy` orgs are onboarding test leftovers (clean-up later).
4. **Existing product lines** are seeded as rows too (Business with its shapes, Education, Welfare, Agriculture …) so
   the picker is complete; nothing about them changes.
5. **One email per tenant owner** (V7): Dr Tahseen signs up twice with two emails.

## Gate (red first)
H1 the signup/provisioning picker shows Healthcare → Hospital / Clinic, Pharmacy & Pharmaceutical (from the rows);
H2 a new Hospital / Clinic tenant's owner lands on /clinicDashboard; H3 a new Pharmacy tenant's owner lands on the
pharmacy dashboard with today's pharmacy modules; H4 the same email cannot own both (V7) — a clear message;
H5 existing tenants land exactly where they did before.

## Modules per business type (user, 2026-10-15 — "as we have already implemented")
**Hospital / Clinic** — the five menus of today's `clinicDashboard.html` (lines 31–51), unchanged:
| Feature (sub-menu) | label key | today's menu row |
|---|---|---|
| Reception | `ui.clinicReception` | `clinic.reception` |
| My queue | `ui.clinicMyQueue` | (doctor's own queue) |
| Today's queue | `ui.clinicQueue` | `clinic.queue` |
| Doctors | `ui.clinicDoctors` | `clinic.doctors` (+ manage / day) |
| Clinic settings | `ui.clinicSettings` | `clinic.settings` |

**Pharmacy & Pharmaceutical** — today's pharmacy menus, **minus the clinic**: the Pharmacy module's "Clinic reception"
sub-menu (`pharmacy.clinic`, `ui.clinicOpen`) is REMOVED for pharmacy tenants (user, 2026-10-15). The clinic's in-tenant
"Pharmacy" link (`clinic.pharmacy`, `ui.clinicToPharmacy`) has no place in a clinic tenant — its job becomes "send to my
linked pharmacy" (HC-1d).

## Reader trace for the new `CLINIC` type (Rule 0, 2026-10-15) — 5 places, all must change together
| Reader | Today | With `CLINIC` unhandled | Change |
|---|---|---|---|
| `ModuleRouter` (monolith) | type → landing map | lands nowhere sensible | `CLINIC → /clinicDashboard` |
| `PermissionService.moduleOf` (auth) | BUSINESS/PHARMA → BUSINESS, EDUCATION → EDUCATION, else null | NO matrix, no built-in sets (Doctor, Clinic reception) | `CLINIC → BUSINESS` (the `clinic.*` codes live in the BUSINESS catalogue) |
| `AuthService.tradeTenant` (auth) | BUSINESS/PHARMA | new members get NO permission set → cannot see the code-gated clinic menus | include `CLINIC` |
| `Shape.byCode` (common-settings) | unknown → GENERAL ("every feature") | a clinic silently gets the general-shop preset | new `CLINIC("clinic")` shape whose preset is the clinic feature |
| signup `registration.html` / `registrationCaptcha.html`, operator `platform.js` shape list | hardcoded options | Healthcare never offered | read `business_type` rows (design point 1) |

## Build order
1. Gate `cypress/e2e/platform/hc-1a-business-types.cy.js` — H1–H5, red on today's build.
2. auth: V-next `product_line` + `business_type` (seeded: Healthcare → Hospital/Clinic, Pharmacy & Pharmaceutical;
   Business shapes; Education; Welfare; Agriculture) + read endpoint; `moduleOf`/`tradeTenant` include CLINIC.
3. common-settings: `Shape.CLINIC` with the clinic preset.
4. monolith: `ModuleRouter` CLINIC; signup + operator screens render the picker from the rows.
5. Deploy order: common-settings (install) → auth → monolith.

## Landing for Hospital / Clinic (user, 2026-10-15) — BUILT, not deployed
The clinic lands on the existing workspace tile page (`workspaces.html`, `id="wsTiles"`) WITHOUT the Pharmacy and Stock
tiles. Sign-in already routes by tiles (RW-3: one tile → there; several → the tile home), so no new page is needed.
- auth **V34** `ui_workspace.org_types`: frontdesk/doctor/admin → `CLINIC,PHARMA` (PHARMA until combined tenants are
  split, HC-1e); pharmacy/store("Stock") → `PHARMA` only.
- `PermissionVisibility.forOrgType(orgTypes, orgType)` + the `/api/auth/org/workspaces` filter on the token's
  `activeOrgType`; `UiWorkspace.orgTypes`; unit test `WorkspaceOrgTypeTest` (4 cases).
- ⚠ The parallel SRA session also writes auth migrations (V32, V33 today) — recheck V34 is still free at hand-over.

## Clinic home + business types — BUILT 2026-10-15, not compiled / deployed (user: "create new landing dashboard page")
The clinic gets its OWN landing page `/clinicHome` (not `workspaces.html`), so clinic-only options can be added later.

| Layer | Change |
|---|---|
| auth **V35** | `product_line` (5 rows) + `business_type` (9 rows: Business retail/distribution/storefront/general; Healthcare **hospital_clinic → CLINIC + clinic**, **pharmacy → PHARMA + pharmacy**; Education/Welfare/Agriculture seeded **INACTIVE** — never provisioned from the console, no gate yet). InnoDB, VARCHAR. |
| auth | `BusinessType`/`ProductLine` entities + repos; `BusinessTypeService` (`catalogue`, `forProvisioning` refuses unknown/inactive); `GET /api/auth/business-types`; `ProvisionTenantRequest.businessType` → org type AND shape from the row (old userType+shape path unchanged); `moduleOf`/`tradeTenant` include CLINIC (so `rowScopedTenant` too — same as the combined pharmacy today); `applyShape` refuses changing to/from an own-application shape. |
| common-settings | `Shape.CLINIC("clinic", "Hospital / Clinic")` — preset AND floor `Capability.CLINIC` (the one shape that presets an opt-in: its module IS the business); `Shape.ownApplication()`; the owner's Configuration list skips it. |
| monolith | `ModuleRouter` CLINIC → `/clinicHome`; sign-in: several workspaces → `/clinicHome` for a clinic (else `/workspaces`); `ClinicController /clinicHome` + `clinicHome.html` (`#chTiles`); console: `#provProductLine` → `#provBusinessType` from `/platform/businessTypes`, sends `businessType`; row badge = business type name by (org type, shape); refusal shown inline `#provError`; clinic detail shows its type, no change control; pharmacy "Clinic reception" (`#navClinic`) REMOVED; clinic screen's "Pharmacy" link hidden in a clinic tenant (`@perm.ownHome()`). |
| specs | `hms-s1-patient-desk` + `diag/clinic-on-for-manual-testing` enter via the Front desk tile (was `#navClinic`); `onboarding-profile` case 9 reads the new picker. |
| unit | `WorkspaceOrgTypeTest`, `BusinessTypeServiceTest`, `PermissionModuleTest` (CLINIC), `OptInCapabilityTest` (clinic shape floor), `CapabilityServiceTest` (list skips clinic), `ModuleRouterTest` (CLINIC). |

Measured before building: 1 tenant has the clinic on and it is PHARMA (org 15) → V34 hides nothing from anyone; 0 orgs have no type.

**Open / follow-on:** self-signup forms still hardcode their list (HC-1a-2 — needs a public read); `/overview` (Administration tile) is the shop owner's overview — clinic content is HC-1b; "send to linked pharmacy" HC-1d; `ui_menu` row `pharmacy.clinic` now unused; FREE plan excludes CLINIC on the write path (operator should provision clinics on TRIAL/PRO).
