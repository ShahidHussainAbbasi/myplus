# Dev test accounts — the per-module privilege ladder

**Dev only.** Seeded by `auth-service` · `SetupDataLoader`. Password locally: `Demo@2025!`.

## Seeding flags — three, because the risk differs

| Flag | Default (local) | Default (compose) | May run in prod? |
|------|-----------------|-------------------|------------------|
| `app.seed-admin` | true | **false** | only with an explicit `APP_ADMIN_PASSWORD` |
| `app.seed-demo` | true | **false** | **yes** — public demo tenants are a product feature, but only with an explicit `APP_DEMO_PASSWORD` |
| `app.seed-test-fixtures` | true | **false** | **never** — hard-blocked by the code under the `prod` profile |

Two independent controls, so no single mistake exposes an account:

1. **No password default.** `app.demo-password` / `app.admin-password` are empty by default. If a password is not
   supplied explicitly, seeding is **skipped and logged at ERROR** rather than falling back to the credential
   committed to this repo. That fallback — not the flags — was the real exposure. Locally the code substitutes
   `Demo@2025!` so dev and Cypress need no configuration.
2. **Profile block.** Under the `prod` profile, test fixtures are refused whatever the flag says.

> ⚠️ **The profile block only works where the profile is actually set.** Today `SPRING_PROFILES_ACTIVE: prod` is
> set on the **monolith container only** — no microservice gets it, so `application-prod.yml` never applies to
> `auth-service` and control (2) is currently inert in the VPS deploy. Control (1) and the compose defaults are
> what protect production right now. See the note in `DEPLOY-POS-RETAIL.md` §4.6.

> Before any deploy, run the "prove there is no known-password account" check in `DEPLOY-POS-RETAIL.md` §4.6.
> Turning a flag off stops accounts being *re-created*; it does not delete rows already written.

---

## The ladder

Four tiers per module, `<tier>.<module>@myplus.com`:

| Tier | Role | Privileges | Write cap |
|------|------|-----------|-----------|
| `demo.` | `DEMO_ROLE` | full module set + `DEMO_PRIVILEGE` | **50 per module** |
| `user.` | `ROLE_<MODULE>_USER` | write/update — **no** `DELETE_PRIVILEGE`, **no** `ADMIN_PRIVILEGE` | none |
| `admin.` | `ADMIN_ROLE` | adds `DELETE_PRIVILEGE`, `ADMIN_PRIVILEGE`, `VOID_INVOICE` | none |
| `owner.` | `ROLE_OWNER` | the super set (scoped to its own org) | none |

Modules: `business`, `education`, `pharma`, `welfare`, `agriculture`, `appointment`, `inventory`,
`marketplace`, `campaign`, `analytics`.

**`user.`, `admin.` and `owner.` of the same module share ONE organization.** That is the point: a privilege
test must vary the role while holding the tenant constant, or a refusal proves org-scoping worked rather than the
privilege gate. `demo.<module>@` sits in its own separate org.

## Which one to use

- **Any spec that seeds more than a handful of rows → `owner.` (or `admin.`), never `demo.`** The 50-write cap
  surfaces as an arbitrary later write failing, which reads like a product bug. Pharmacy hit exactly this: its
  specs seed products per test, and before this ladder existed there was no uncapped pharmacy login.
- **Privilege / `@PreAuthorize` tests → `user.` vs `admin.`/`owner.` of the same module.**
- **Owner-gated UI (Finance, Settings, Team, Configuration) → `owner.`**
- **Demo-quota and upsell behaviour → `demo.`** (that's what it is for).

## Named fixtures (kept, in addition)

These carry location/branch grants the generic ones deliberately do not, and existing specs reference them by
name:

| Account | Org | Purpose |
|---------|-----|---------|
| `admin.store@`, `cashier.a@`, `cashier.b@` | `owner.business@`'s | multi-location store grants |
| `teacher.a@`, `teacher.b@` | `owner.education@`'s | multi-branch (school) scoping |

`owner.business@` additionally carries `DEMO_RESET_ROLE` (the `DEMO_RESET_PRIVILEGE` to purge its own org). That
privilege rides a separate role on purpose — putting it on `ROLE_OWNER` would hand every real customer's owner a
one-click "delete my organisation" button.

## From Cypress

```js
cy.loginAsTier('admin', 'pharma')      // admin.pharma@myplus.com
cy.loginAsTier('user', 'welfare')      // user.welfare@myplus.com
cy.loginAsOwner()                      // owner.business@ (shorthand, still supported)
cy.loginAsPharmaOwner()                // owner.pharma@
```

`loginAsTier` resolves the module's validate endpoint from `MODULE_VALIDATE_PATH` in
`cypress/support/commands.js` and **fails loudly** for a module that has none.

**`inventory`, `campaign` and `analytics` have no monolith dashboard** — their userType falls back to `/` in
`MySimpleUrlAuthenticationSuccessHandler`, so there is no session-login command for them. Test those through the
gateway with a Bearer token, as `cypress/e2e/security/method-authz.cy.js` does:

```js
cy.request({ method: 'POST', url: 'http://localhost:8765/api/auth/login',
             body: { email: 'owner.inventory@myplus.com', password: 'Demo@2025!' } })
  .then((r) => r.body.data.accessToken)
```

## Adding a module

1. Add a row to `moduleOwners` (owner) **and** `moduleTeams` (admin + user) in `SetupDataLoader`.
2. Make sure its `ROLE_<MODULE>_USER` is in the role-seeding list above them, or startup fails loudly.
3. If it has a monolith dashboard, add its validate path to `MODULE_VALIDATE_PATH`.

`business` and `education` are seeded before those loops because they also anchor the named team fixtures — they
are in `moduleTeams` but not `moduleOwners`. All accounts go through the same `ensureOwner`/`ensureMember`
helpers, so the shape is identical either way.
