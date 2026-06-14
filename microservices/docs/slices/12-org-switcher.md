# Slice 12 — Organization switcher (UI + auth-service)

Status: **DONE** ✅ (spans monolith auth + auth-service). Follows `../ARCHITECTURE-MULTITENANCY.md`.
Also converted the education subnav to the businessDashboard `snav-dd` button-dropdown pattern (UI
parity) — off-screen `<select>`s keep `main.js`/Cypress working; privilege gating preserved per menu item.

## Document — what & why

The tenancy chain is live end-to-end, but the front end never shows *which* tenant you're in, and a
future multi-org user (a teacher in two schools) has no way to switch. This slice surfaces the **active
organization** in `educationDashboard` and lets a user **switch** between the organizations they belong
to. Switching must re-issue the JWT (server-derived `activeOrgId`) — never a client-supplied id (see
the security rule in the architecture doc).

Today every user has exactly one org (auto-created "tenant #1"), so the switcher shows one entry; the
plumbing is built so it "just works" once join flows add more memberships.

## Design

### Transport note
The gateway routes `/api/auth/**` **without** the JWT filter or StripPrefix, so these endpoints reach
auth-service with the full path and no `X-User-*` headers. auth-service therefore reads identity from
the **Bearer token itself** (as `/api/auth/validate` already does), via `JwtService.extractUserId` and
`extractAllClaims().get("activeOrgId")`. No gateway change needed.

### auth-service
- **`OrganizationService`**
  - `List<OrgView> listForUser(Long userId, Long activeOrgId)` — for each `Membership`, resolve the
    org name + role, mark `active = (orgId == activeOrgId)`.
  - `boolean isMember(Long userId, Long orgId)`.
  - `OrgView` = `{ id, name, role, active }`.
- **`AuthService`**
  - Refactor `buildClaims(User)` → `buildClaims(User, Long activeOrgId)`; existing callers pass
    `getOrCreatePrimaryOrg(user).getId()` (no behavioural change).
  - `AuthResponse switchOrganization(Long userId, Long orgId)` — load user, **validate membership**
    (else 403), build claims with `activeOrgId = orgId`, generate access + refresh, `buildAuthResponse`.
- **`OrganizationController`** (`@RequestMapping("/api/auth")`)
  - `GET /organizations` (`@RequestHeader Authorization`) → `ApiResponse<List<OrgView>>`.
  - `POST /switch-organization` `{organizationId}` (+ Authorization) → `ApiResponse<AuthResponse>`
    (new tokens). 403 if not a member.

### monolith
- **`AuthServerClient`**: `organizations(accessToken)` (GET) and
  `switchOrganization(accessToken, orgId)` (POST) through the gateway `/api/auth/**`.
- **`OrganizationController`** (monolith, same-origin for the dashboard JS):
  - `GET /getMyOrganizations` → calls `authServerClient.organizations(tokenStore.accessToken)` →
    returns `GenericResponse`-shaped JSON `{status, collection:[{id,name,role,active}]}`.
  - `POST /switchOrganization` `{organizationId}` → calls `authServerClient.switchOrganization(...)`,
    on success **updates `TokenStore`** (new access + refresh) so every later gateway call is scoped to
    the new org → returns `{status:SUCCESS}`.
- Tokens stay server-side in `TokenStore` (browser never sees them) — unchanged security posture.

### UI — educationDashboard.html + education.js
- Add an **org chip/dropdown in the dashboard header** (consistent with the shared look-and-feel).
- On load: `GET /getMyOrganizations` → fill the dropdown, preselect the `active` one, show its name.
- On change: `POST /switchOrganization {organizationId}` → on SUCCESS, reload the dashboard so all
  sections refetch under the new tenant.
- `escHtml()` org names before injecting (XSS-safe rendering rule).

## Implement (checklist)
- [x] auth-service: `OrgView`, `OrganizationService.listForUser/isMember`, `AuthService.buildClaims(user,orgId)` + `switchOrganization`, `OrganizationController`
- [x] monolith: `AuthServerClient.organizations/switchOrganization`, `OrganizationController` (`/getMyOrganizations`, `/switchOrganization` updating TokenStore)
- [x] UI: org switcher in educationDashboard subnav + JS load/switch (escHtml); subnav converted to `snav-dd` for parity
- [x] (user) rebuild & restart auth-service + monolith

## Test — all green
- [x] `GET /api/auth/organizations` (Bearer) → SUCCESS, org `active=true`, role OWNER
- [x] `POST /api/auth/switch-organization`: non-member org → blocked ("Not a member", 400); own org → new token
- [x] monolith `/getMyOrganizations` → SUCCESS list; `/switchOrganization` → SUCCESS (session token swapped)
- [x] Cypress education suite 42/42 still green after the snav conversion
- [x] new `cypress/e2e/education/organization.cy.js` 2/2 (org chip renders + /getMyOrganizations) — run headed

> Note: the non-member guard returns 400 (`ValidationException`) rather than 403 — different status, same
> protection (scope can't be widened). Fine to leave; revisit if a 403 is preferred.
