# Dedicated `VOID_INVOICE` privilege

Closes the `VOID_INVOICE privilege` item in `pos-retail-standards-audit.md` §5. Cadence: Document → **Design** →
Implement → headed Cypress → next.

## 1. Problem
Voiding a sale or a purchase is the most destructive books-affecting action a user can take (it reverses inventory,
AR/AP and the GL). Today both void endpoints are gated by the **coarse `ADMIN_PRIVILEGE`**:

- `SellController.voidSell` → `@PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")`
- `PurchaseController.voidPurchase` → `@PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")`

That conflates "can void" with "is a full admin". An owner cannot delegate *just* the ability to void (e.g. to a
shift lead / senior cashier) without handing over **all** admin power (deletes, tax settings, period close…). It also
reads poorly for least-privilege and audit ("who may void?" should map to one named right, not to "admin").

> Correction to the audit note: business-service **already has** `@EnableMethodSecurity(prePostEnabled=true)` and
> enforces `@PreAuthorize` across its controllers (deletes → `DELETE_*`, void/tax → `ADMIN_PRIVILEGE`). So this is a
> small, well-scoped refactor — introduce a fine-grained privilege — **not** a "build method security from scratch"
> slice. Privileges are seeded in **auth-service** `SetupDataLoader` and travel in the JWT → gateway
> `X-User-Privileges` → `authorities` (see [[project_privilege_model]], [[project_method_authz]]).

## 2. Design — one named right, no behaviour regression
Introduce a dedicated **`VOID_INVOICE`** privilege and gate the two void ops on it instead of `ADMIN_PRIVILEGE`.

- **auth-service** `SetupDataLoader`:
  - add `VOID_INVOICE` to the privilege catalog.
  - add it to **`adminPrivileges`** (which flows into `superSet`, `ROLE_OWNER`, `ROLE_*_ADMIN/SUPER`, demo accounts).
    → every principal that can void **today** (ADMIN/OWNER/SUPER/demo) still can → **zero regression**.
  - it is deliberately **NOT** in the `user` set, so a plain `ROLE_BUSINESS_USER` (cashier) **cannot** void.
- **business-service**: change the two `@PreAuthorize` from `ADMIN_PRIVILEGE` → `VOID_INVOICE`.
  - Tax settings + period-lock stay on `ADMIN_PRIVILEGE` (they are not void).
- **monolith UI**: the sale/purchase **Void** buttons are rendered unconditionally in `business.js` today and rely on
  the server 403. Gate them on a `window.canVoidInvoice` flag (set by a `sec:authorize="hasAuthority('VOID_INVOICE')"`
  inline script in `businessDashboard.html`) so a user who can't void doesn't see a button that will only fail —
  mirrors the `window.canClosePeriod` pattern from the period-close slice. The server `@PreAuthorize` remains the
  actual enforcement (defence in depth); the flag is UX only.

### Why not a separate "void" role?
There is no end-user role/privilege assignment UI yet (owner-driven user management is a future slice). So the
*practical* delegation ("give this one cashier void rights but not admin") lands when that form ships. This slice puts
the **correct foundation** in place now: the right is named, seeded, enforced, and audit-friendly — and the future
user-management form assigns `VOID_INVOICE` to a role/user without any code change. Modelling it correctly now costs
almost nothing; retrofitting a coarse check later costs a migration + re-test everywhere.

## 3. Enforcement flow (unchanged mechanism)
```
JWT privileges → api-gateway (X-User-Privileges) → HeaderAuthFilter → authorities
   → @PreAuthorize("hasAuthority('VOID_INVOICE')") on voidSell / voidPurchase
   → denial throws AccessDeniedException → handleAccessDenied → 403 FORBIDDEN envelope
```

## 4. Test (headed Cypress) — `void-invoice-privilege.cy.js`
- **Positive (no regression):** `loginAsOwner` (superSet ⊇ `VOID_INVOICE`) creates a sale then voids it → `SUCCESS`.
  (The existing `void-cancel.cy.js` already exercises the demo-business void end-to-end; this asserts the owner path.)
- **Negative (privilege actually gates):** `loginAsCashierA` (`ROLE_BUSINESS_USER`, no `VOID_INVOICE`) POSTs
  `/voidSell` → **403 / FORBIDDEN** (the `@PreAuthorize` fires before any tenant check, so even a bogus id is denied).

## 5. Build / deploy
- auth-service reseeds privileges/roles on every startup (idempotent) → `VOID_INVOICE` appears + is linked to admin/
  owner/super roles on boot. Existing tokens refresh on next login.
- No schema change (privileges are seeded rows, not migrations).
- Rebuild: **auth-service** + **business-service** + **monolith** (UI). Restart auth-service first so the privilege is
  seeded before tokens are minted.

## 6. Status: IMPLEMENTED
- auth-service `SetupDataLoader`: `VOID_INVOICE` in catalog + `adminPrivileges`.
- business-service: `voidSell` + `voidPurchase` `@PreAuthorize("hasAuthority('VOID_INVOICE')")`.
- monolith: `window.canVoidInvoice` flag + Void buttons gated in `business.js`.
- Cypress `void-invoice-privilege.cy.js`.
