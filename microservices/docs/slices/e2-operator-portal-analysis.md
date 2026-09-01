# E2 — the operator portal: analysis before design

**Status:** ANALYSIS, shared for review. No design, no code — per `SAAS-BUILD-STANDARDS.md`, *"The standards
analysis is shared for review **before** documenting or designing, not alongside it."*
**Programme:** [`saas-control-plane-review.md`](../saas-control-plane-review.md) — E2 of E0..E6.
**Predecessor:** [`e1-entitlement-ceiling.md`](e1-entitlement-ceiling.md) — ✅ green, shipped the API this
slice puts a screen on.

Every claim names the artefact or query it was read from (evidence standard,
`vertical-profile-any-business-design.md` §8). Live data was read from the **Docker** MySQL
(`myplus-mysql`, `myplusdb_auth`) on 2026-09-01 — the host MySQL on :3306 is a stale copy and is not what the
app reads.

---

## 1. What E2 is for

E1 closed the licensing hole but left the platform operator with **no screen at all**. Onboarding a customer
and pricing them are both curl commands today:

| Operation | API | Screen |
|---|---|---|
| Create a tenant | `POST /api/auth/admin/provision-tenant` (`ROLE_ADMIN`) | **none** |
| Read a tenant's entitlements | `GET /api/auth/admin/entitlements` (`ROLE_ADMIN`) | **none** |
| Grant / revoke a capability | `POST /api/auth/admin/entitlements` (`ROLE_ADMIN`) | **none** |
| Change a tenant's plan | **no API** | **none** |
| List all tenants | **no API** | **none** |

This is the platform's default failure mode, named in the standards: *a slice is not done until something
CALLS it.* Seven capabilities have shipped working and unreachable. E1 is currently the eighth.

---

## 2. Verified state

### 2a. The operator lands on a tenant's POS screen 🔴

`ModuleRouter.DASHBOARD_BY_TYPE` (`src/main/java/com/web/util/ModuleRouter.java:82`) has **7 entries** and
`ADMIN` is not one of them. `dashboardForModule` ends:

```java
return DASHBOARD_BY_TYPE.getOrDefault(key, COMMERCE_DASHBOARD);
```

`admin@myplus.com` has `user_type = ADMIN` (verified by query), so **the platform operator is routed to
`/businessDashboard`** — a shopkeeper's till, scoped to the operator's own organization.

Not a security hole: org scoping holds and every read is that org's. It is the wrong *product*, and it is why
the operator portal has nowhere to live yet. The C2 fallback that produces this was a deliberate improvement
(failing to a working screen beats the landing page) — it simply never anticipated a non-tenant user.

### 2b. `admin@myplus.com` is accidentally a tenant 🟠

| Query | Result |
|---|---|
| organizations owned by user 59 | **1** |
| memberships of user 59 | **1** |

`SetupDataLoader` creates the admin with `userRepository.save(...)` and never calls `createTenant`, so this org
came from `getOrCreatePrimaryOrg`'s legacy safety net on first login. **A platform operator is not a customer**,
and giving them an org means every tenant-scoped screen answers them with plausible, empty data instead of
refusing. Worth a decision (§5 Q4), not necessarily a fix in this slice.

### 2c. There is no way to list tenants 🔴 — the one genuinely new endpoint

`OrganizationController.organizations` returns `organizationService.listForUser(userId, activeOrgId)` — the
**caller's own memberships**. Nothing anywhere returns all organizations: a repo-wide grep for `findAll()` in
`auth-service/src/main/java` returns **zero hits**.

So E2 needs a `GET /api/auth/admin/organizations` — and it is worth being explicit that this is
**the platform's first deliberate cross-tenant read**. Everything in `ARCHITECTURE-MULTITENANCY.md` says
"scope every read by org"; this endpoint's whole purpose is not to. That makes its gate the most important
line in the slice.

### 2d. No monolith screen has ever been gated on `ROLE_ADMIN` 🟠

`businessDashboard.html` uses `sec:authorize` **19 times**: `ROLE_OWNER` ×15, `ADMIN_PRIVILEGE` ×3,
`VOID_INVOICE` ×1. A repo-wide grep for `ROLE_ADMIN` across `src/main/resources/templates/` returns
**no files**. E2 introduces the first platform-operator surface in the UI, so the gating idiom has no
precedent here to copy — it has to be established correctly once.

The reasoning is already settled server-side and must be mirrored: `ADMIN_PRIVILEGE` is held by **every tenant
owner inside their own org**, so gating any operator surface on it hands the platform to every customer. Both
`provision-tenant` and E1's entitlement API say so in their javadoc. The screen must use `ROLE_ADMIN`.

### 2e. `admin.html` is not a foundation 🟢

25 lines, Bootstrap **3.3.2 from a CDN**, one `<h1>` gated on `WRITE_PRIVILEGE`, registered as a bare view
controller in `MvcConfig:83`. It is scaffolding from the original template, unrelated to this platform's
design system. E2 should not build on it.

### 2f. Live tenant data — what the screen must actually show

Read from `myplusdb_auth` on 2026-09-01:

| Fact | Value |
|---|---|
| organizations | **40** |
| plan `FREE` / `TRIAL` | **20 / 20** |
| **TRIAL organizations whose `trial_ends_at` has passed** | **14 of 20** |
| organizations carrying entitlement rows | **40 (all)** |

Two things follow, and both change the design:

1. **40 rows is past the point where an unfiltered list is usable**, and it only grows. Server-side search and
   paging, not a client-side filter over everything.
2. **14 lapsed trials exist right now and are invisible.** They are harmless *today* only because E1's seeder
   grandfathered all 40 orgs with explicit `ACTIVE` rows, so `grantable` never consults the lapsed plan. A new
   tenant on a lapsed trial would be refused every capability with nothing on any screen explaining why. **The
   operator's list must surface lapsed trials as a first-class state**, not bury the date in a detail panel.

### 2g. The plumbing E2 needs already works

| Need | Exists | Evidence |
|---|---|---|
| Gateway route to the admin API | ✅ | `- Path=/api/auth/**` with **no** `StripPrefix`, so `/api/auth/admin/entitlements` arrives unchanged — which is what `EntitlementAdminController`'s mapping expects |
| Monolith → auth with the caller's identity | ✅ | `GatewayClient` + `TokenStore`; `BusinessConfigController.authGet/authPost` is the working precedent from C3c |
| Self-rendering config screens | ✅ | `settings-form.js` — but see §4, it is the wrong shape here |
| Envelope handling | ✅ | `api-response.js` — `apiOk` / `apiData` / `apiFailMessage`; the admin API answers `ApiResponse` |
| Grids, dialogs, responsive, i18n | ✅ | `datatable-defaults.js`, `confirm-dialog.js`, `responsive-tables.js`, `ui.js.*` |

### 2h. Cypress cannot log in as the operator today 🟠

`MODULE_VALIDATE_PATH` (`cypress/support/commands.js:214`) has seven entries and **no `admin`**, and
`loginAsTier` fails loudly for a module without one — correctly. `dev-test-accounts.md` documents the
`demo./user./admin./owner.` ladder as *per-module tenant* accounts; `admin@myplus.com` is a different animal
and is not in that ladder.

E1's gate reached the operator through the **gateway token flow** (`POST /api/auth/login` → Bearer), the same
approach `security/method-authz.cy.js` uses. E2 needs a *session* login as well, because it asserts a screen.
That is a new command plus a validate path, and the validate path cannot be a tenant endpoint.

---

## 3. What I would build

| | Work | Why it is in E2 and not later |
|---|---|---|
| **E2a** | `GET /api/auth/admin/organizations` — id, name, type, plan, trial state, owner email, member count; server-side `q` search + paging. `ROLE_ADMIN`. | Nothing can list tenants; every screen below starts here |
| **E2b** | `POST /api/auth/admin/organizations/{id}/plan` — change plan, validated against the `Plan` enum. `ROLE_ADMIN`. | `organizations.plan` is free text (F2). An operator screen that can grant capabilities but not set a plan makes every customer a per-capability override |
| **E2c** | `/platformDashboard` — a **new** monolith page, plus `ADMIN` in `ModuleRouter.DASHBOARD_BY_TYPE` so the operator stops landing on a till | §2a. One map entry; the same fix shape `vertical-profile` §3b recommends |
| **E2d** | Tenants list — search, plan badge, **lapsed-trial badge**, row → detail | §2f |
| **E2e** | Tenant detail — plan control + the 13 capabilities with `inPlan` / `grantable` / `revoked`, grant + revoke with a **required reason** | Puts a screen on E1's API |
| **E2f** | Provision tenant — a form over the endpoint that has existed since slice 32 with no UI | Onboarding is the other half; without it E2 is half a portal |
| **E2g** | `cy.loginAsOperator()` + a platform validate path | §2h |

**Deliberately NOT in E2**, each already argued elsewhere: audit of operator actions (**E4** — but E2b/E2e must
be *written so E4 is a listener, not a rewrite*), support sessions (**E5**), billing (never — E1 §13), and any
tenant-facing surface.

---

## 4. Three design questions I would answer NO to

**R1 — do not reuse `settings-form.js`.** It renders a flat catalog of independent switches. An entitlement is
not a switch: it carries status, source, dates and a reason, and the operator is acting *on someone else's
tenant*. Bending the self-rendering form to carry that would make both screens worse. Reuse the *shell*
(cards, rows, `.cfg-*` styling) and write the control.

**R2 — do not put the portal inside `businessDashboard.html`.** It is already 3,947 lines and 36 `.formDiv`
sections shipped to every tenant. Adding a platform surface to a tenant page means one CSS mistake away from a
shopkeeper seeing the tenant list. Separate page, separate route, separate gate.

**R3 — do not let the operator edit tenant *data*.** Plan and entitlement are platform facts. Products,
customers and invoices are the tenant's, and reaching them is what **E5's audited support session** is for.
Building a "just look at their products" shortcut here is how a support backdoor gets created by accident.

---

## 5. Questions for you, before I design

**Q1 — scope.** Does E2 include **provision tenant** (E2f) and **plan change** (E2b), or is it read + entitlements
only? *Recommendation: include both.* Without provisioning the portal cannot onboard, and without plan change
every customer becomes a pile of per-capability overrides — which is exactly the "no plan, only exceptions"
state that makes pricing unmanageable.

**Q2 — the tenant list is a cross-tenant read.** Confirm `ROLE_ADMIN` alone is the gate, with no org filter,
and that it may return tenant *names and owner emails*. *Recommendation: yes, `ROLE_ADMIN` only, and log every
call once E4 lands.* It is the first endpoint in the platform whose purpose is to ignore org scoping, so it
deserves an explicit decision rather than an assumption.

**Q3 — lapsed trials.** 14 exist. Should E2 only **show** them, or also offer "extend trial" / "convert to
PRO"? *Recommendation: show in E2, act in E2b* — changing the plan already covers conversion, and an
extend-trial button is a second way to write the same column.

**Q4 — `admin@myplus.com` owning an org.** Leave it, or stop the operator being a tenant? *Recommendation:
leave it in E2 and record it.* Changing `getOrCreatePrimaryOrg` touches every first-login path, which is a
bigger blast radius than this slice earns.

**Q5 — the gate's tenant.** `GATE-RUNBOOK.md` rule 1 says log in as the feature's own tenant. The operator has
no tenant. *Recommendation:* gate as `admin@myplus.com` for the operator path **and** walk the ladder from the
other side — `owner.business@`, `admin.business@`, `user.business@` must each be **refused** the portal, page
and API. For this slice the cross-tenant case is the important one: an owner reaching the tenant list is the
failure that matters, not a missing button.

---

## 6. Risks I can already name

| | Risk | Mitigation in the design |
|---|---|---|
| **1** | An operator screen that can read every tenant is the highest-value target in the product | `ROLE_ADMIN` server-side on **every** endpoint, never `ADMIN_PRIVILEGE`; the gate asserts an owner is refused |
| **2** | A grant with no reason is unauditable later | `reason` required by the **UI and the API**, not just the form |
| **3** | Entitlement changes take up to 15 min to reach the tenant (E1 D-1) | The screen must **say so** where the operator acts, or it reads as broken |
| **4** | E4 has to retrofit audit onto these writes | Route both writes through `EntitlementService` / a sibling so E4 adds a listener, not a rewrite |
| **5** | 40 tenants today, more later | Server-side search + paging from the first commit; a client-side filter would have to be undone |
| **6** | `ADMIN` in `ModuleRouter` changes login routing | One map entry, covered by the gate; the fallback stays `COMMERCE_DASHBOARD` for everything else |
