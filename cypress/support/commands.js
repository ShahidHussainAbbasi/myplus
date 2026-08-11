// ─── Shared helpers (single source — avoid duplicating login/nav per module) ───

// CSRF: the monolith uses cookie-based CSRF (XSRF-TOKEN cookie). Browser-driven calls get the token
// via $.ajaxSetup, but direct cy.request POST/PUT/DELETE/PATCH bypass the browser — so inject the
// X-XSRF-TOKEN header from the cookie here, once, for every spec.
Cypress.Commands.overwrite('request', (originalFn, ...args) => {
  let options;
  if (args.length === 1 && typeof args[0] === 'object') options = { ...args[0] };
  else if (args.length === 1) options = { url: args[0] };
  else if (args.length === 2) options = { method: args[0], url: args[1] };
  else options = { method: args[0], url: args[1], body: args[2] };

  const method = (options.method || 'GET').toUpperCase();
  if (['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)) return originalFn(options);

  // The .then wrapper would otherwise impose defaultCommandTimeout (5s) on the inner request, which
  // breaks slow endpoints (e.g. the demo-request email send); give it room.
  return cy.getCookie('XSRF-TOKEN').then({ timeout: 60000 }, (cookie) => {
    if (cookie && cookie.value) {
      options.headers = Object.assign({}, options.headers, { 'X-XSRF-TOKEN': decodeURIComponent(cookie.value) });
    }
    return originalFn(options);
  });
});

// Generic session-based login. Module helpers below just supply credentials + a
// validate endpoint, so there is one login implementation for the whole suite.
// `cacheKeyExtra` (optional) joins the session key so a caller can invalidate cached sessions when the
// SERVER-SIDE identity behind those credentials changes — a role rename, a reseed. Without it, cy.session
// happily replays a token minted under the old identity and the spec tests a principal that no longer
// exists. See loginAsPortalGuardian for the case that cost six gate runs.
Cypress.Commands.add('loginAs', (email, password, validatePath, cacheKeyExtra) => {
  cy.session([email, password, validatePath, cacheKeyExtra || ''], () => {
    cy.visit('/login')
    cy.get('input[name="username"]').type(email)
    cy.get('input[name="password"]').type(password)
    // Login redesign uses <button id="loginSubmit" type="submit">
    cy.get('#loginSubmit').click()
    cy.url().should('not.include', '/login')
  }, {
    // The monolith runs `maximumSessions(1)` (SecSecurityConfig ~line 135). cy.session's cache is
    // per-SPEC-FILE by default, so every spec re-logs-in as the same account — and the concurrent-session
    // control then leaves one of the two sessions dead. The victim does not fail cleanly: authenticated
    // requests come back as a 302 to /login, or with the literal body "This session has been expired…",
    // which blows up any test that JSON.parses the response. That is what broke dashboard.cy.js (302 where
    // 200 was expected) and exams.cy.js / fees-to-gl.cy.js (JSON.parse on "This sessi…").
    //
    // cacheAcrossSpecs keeps ONE server session per account for the whole run, so the second login never
    // happens. It fixes the symptom at its source rather than teaching each spec to tolerate a dead session.
    // (Per-user, so specs using different accounts are unaffected — the cap is per principal.)
    //
    // NOTE: this makes the suite tolerate the cap; it does NOT make the cap correct. maximumSessions(1)
    // also means a real user cannot be signed in on two devices — see the session-cap decision recorded
    // with slice 106.
    cacheAcrossSpecs: true,
    validate: () => {
      // Re-login if the session was invalidated (e.g. after a server restart).
      // followRedirect:false ensures an expired session returns 302 (not the 200 login page).
      cy.request({ url: validatePath, failOnStatusCode: false, followRedirect: false }).then((res) => {
        expect(res.status).to.eq(200)
      })
    },
  })
})

// Per-service DEMO accounts (seeded, each with its own organization) — reliable logins for the suite.
// Hardcoded personal accounts were flaky and lacked a clean org context after org-scoping.
const DEMO_PW = 'Demo@2025!';

Cypress.Commands.add('loginAsBusiness', (email = 'demo.business@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getBusinessDashboardStats')
})

// BUSINESS OWNER (ROLE_OWNER, seeded in auth-service SetupDataLoader) — needed to see owner-gated UI
// like the Finance reports page, Settings and Team. Not a demo account (no write cap).
Cypress.Commands.add('loginAsOwner', (email = 'owner.business@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getBusinessDashboardStats')
})

// Pharmacy (slice 33) — the PHARMA vertical reuses the business/trade backend, so it validates via the
// same business stats endpoint; userType PHARMA routes the user to the shared /businessDashboard.
Cypress.Commands.add('loginAsPharma', (email = 'demo.pharma@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getBusinessDashboardStats')
})

// E-commerce (slice 46) — MARKETPLACE userType reuses the trade dashboard (relabeled "Store"); validates via the
// orders endpoint it owns.
Cypress.Commands.add('loginAsMarketplace', (email = 'demo.marketplace@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getOrders')
})

// Education — seeded EDUCATION demo user; routes to /educationDashboard.
Cypress.Commands.add('loginAsEducation', (email = 'demo.education@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getDashboardData')
})

// Validate via an AJAX endpoint (not the dashboard HTML page) so login is independent of template state.
Cypress.Commands.add('loginAsWelfare', (email = 'demo.welfare@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getUserDonator')
})

Cypress.Commands.add('loginAsAgriculture', (email = 'demo.agriculture@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/agricultureDashboard')
})

Cypress.Commands.add('loginAsAppointment', (email = 'demo.appointment@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/appointmentDashboard')
})

// Multi-location team members (seeded dev-only in auth-service SetupDataLoader, all in the
// owner.business org): one ADMIN + two cashiers. Store grants are NOT seeded — multi-location.cy.js
// creates the stores and grants them at runtime, because stores live in business-service.
// A member's store claims only reach the services on a fresh token, i.e. at login — so grant BEFORE
// logging in as them.
Cypress.Commands.add('loginAsStoreAdmin', (email = 'admin.store@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getBusinessDashboardStats')
})

Cypress.Commands.add('loginAsCashierA', (email = 'cashier.a@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getBusinessDashboardStats')
})

Cypress.Commands.add('loginAsCashierB', (email = 'cashier.b@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getBusinessDashboardStats')
})

// Multi-BRANCH education fixture (P4), seeded dev-only alongside the business one: an EDUCATION owner and
// two teachers in the owner's org. Branch grants point at school ids and are assigned at runtime by
// multi-branch.cy.js — and, as with stores, they only reach the service on a fresh token, so grant BEFORE login.
Cypress.Commands.add('loginAsEduOwner', (email = 'owner.education@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getDashboardData')
})

// ── Per-module OWNER logins (ROLE_OWNER, demo=false, own org; seeded in auth-service SetupDataLoader) ──
// Prefer these over the demo.* logins for any spec that seeds more than a handful of rows: a demo account is
// capped at 50 writes per module, and the cap surfaces as an arbitrary later write failing rather than as a
// quota message. Each owner also has its OWN organization, so they double as cross-tenant isolation fixtures.
Cypress.Commands.add('loginAsPharmaOwner', (email = 'owner.pharma@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getBusinessDashboardStats')   // PHARMA reuses the trade backend
})
Cypress.Commands.add('loginAsWelfareOwner', (email = 'owner.welfare@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getUserDonator')
})
Cypress.Commands.add('loginAsAgricultureOwner', (email = 'owner.agriculture@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/agricultureDashboard')
})
Cypress.Commands.add('loginAsAppointmentOwner', (email = 'owner.appointment@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/appointmentDashboard')
})
Cypress.Commands.add('loginAsMarketplaceOwner', (email = 'owner.marketplace@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getOrders')
})

// OMS O7 D2 — the ORDER BOOKER: a field sales rep, and a MEMBER of the marketplace owner's org (so the booker,
// the admin who reviews their orders and the outlets they book for are all in one tenant — otherwise a refusal
// would prove org-scoping worked rather than that the approval gate did).
//
// Its value as a fixture is what it CANNOT do: ROLE_ORDER_BOOKER carries no ADMIN_PRIVILEGE, so this is the
// account that proves a rep cannot confirm their own order.
Cypress.Commands.add('loginAsOrderBooker', (email = 'booker.marketplace@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getOrders')
})

// NOTE: owner.inventory@ / owner.campaign@ / owner.analytics@ ARE seeded, but get no login command here on
// purpose. Those user types have no monolith dashboard (MySimpleUrlAuthenticationSuccessHandler falls them back
// to "/"), so a UI-session command would need a validate endpoint none of them owns. Test those services the way
// method-authz.cy.js does — POST /api/auth/login at the gateway and send a Bearer token.

// ── The full account ladder: <tier>.<module>@myplus.com ───────────────────────────────────────────────
// Every module is seeded with four tiers (auth-service SetupDataLoader), all on the same password:
//   demo   DEMO_ROLE     full module privileges, but CAPPED at 50 writes/module
//   user   ROLE_*_USER   write/update; no DELETE_PRIVILEGE, no ADMIN_PRIVILEGE
//   admin  ADMIN_ROLE    adds DELETE_PRIVILEGE + ADMIN_PRIVILEGE + VOID_INVOICE
//   owner  ROLE_OWNER    the super set, uncapped
// user/admin/owner of one module share ONE organization, so a privilege test varies role while holding tenant
// constant. One command instead of 40 near-identical ones — pass the tier and module.
//
//   cy.loginAsTier('admin', 'pharma')     → admin.pharma@myplus.com
//   cy.loginAsTier('user', 'welfare')     → user.welfare@myplus.com
//
// Modules whose userType has no monolith dashboard (inventory/campaign/analytics) are intentionally absent —
// use the gateway Bearer-token flow for those (see cypress/e2e/security/method-authz.cy.js).
const MODULE_VALIDATE_PATH = {
  business: '/getBusinessDashboardStats',
  pharma: '/getBusinessDashboardStats',      // PHARMA reuses the trade backend
  marketplace: '/getOrders',
  education: '/getDashboardData',
  welfare: '/getUserDonator',
  agriculture: '/agricultureDashboard',
  appointment: '/appointmentDashboard',
}

Cypress.Commands.add('loginAsTier', (tier, module, password = DEMO_PW) => {
  const validatePath = MODULE_VALIDATE_PATH[module]
  // Fail loudly rather than silently logging in and validating against the wrong endpoint.
  expect(validatePath, `no monolith validate path for module "${module}" — use the gateway token flow instead`)
    .to.be.a('string')
  cy.loginAs(`${tier}.${module}@myplus.com`, password, validatePath)
})

Cypress.Commands.add('loginAsTeacherA', (email = 'teacher.a@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getDashboardData')
})

Cypress.Commands.add('loginAsTeacherB', (email = 'teacher.b@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getDashboardData')
})

// Slice 3.1b — a PORTAL guardian (ROLE_PORTAL, seeded dev-only in the education owner's org).
// Validates against a PORTAL path on purpose: a guardian cannot reach /getDashboardData like the logins
// above — PortalScopeFilter refuses it — so validating there would fail every session for the right reason
// at the wrong moment. /portal/me answers 200 whether or not an access row exists yet, which is what
// session validation needs.
Cypress.Commands.add('loginAsPortalGuardian', (email = 'guardian.education@myplus.com', password = DEMO_PW) => {
  // ⚠ The 4th key element is CACHE-BUSTING and is load-bearing — do not remove it.
  //
  // cy.session caches by key. The guardian's authority travels in the JWT minted AT LOGIN, so a session
  // cached before the seeded role changed keeps replaying a token with the OLD role. Validation still
  // passes (below), because the portal resolves a guardian by EMAIL, not by role — so the portal reads
  // work while the deny rule silently does nothing. That is what made the 3.1b gate fail identically
  // across six rebuilds: the server was fine, the CLIENT was replaying a stale principal.
  //
  // Bumping the expected role here invalidates every cached session, forcing a fresh token.
  cy.loginAs(email, password, '/portal/me', 'ROLE_GUARDIAN')
})

// Show a registration section on a dashboard (business by default). Both dashboards use the
// same off-screen #registrationType <select>, so one command serves the whole app.
Cypress.Commands.add('openSection', (sectionValue, dashboard = '/businessDashboard') => {
  cy.visit(dashboard)
  cy.get('#registrationType').select(sectionValue, { force: true })
  cy.get(`#${sectionValue}`).should('be.visible')
})

// Business sale sub-sections (sellDiv, SRDiv)
Cypress.Commands.add('openSellSection', (sectionValue) => {
  cy.visit('/businessDashboard')
  cy.get('#sellType').select(sectionValue, { force: true })
  cy.get(`#${sectionValue}`).should('be.visible')
})

// Business purchase sub-sections (purchaseDiv)
Cypress.Commands.add('openPurchaseSection', (sectionValue) => {
  cy.visit('/businessDashboard')
  cy.get('#purchaseType').select(sectionValue, { force: true })
  cy.get(`#${sectionValue}`).should('be.visible')
})

// M4e.d (slice 104): seed a product via the catalog MASTER — the single creation path. Creates a catalog Product
// and optionally seeds opening inventory (inventory-service). Yields { productId, name, sku }. The legacy Item
// bridge is GONE: there is no itemId — sell/purchase/pharmacy pickers are all productId-native.
// Usage: cy.seedProduct({ name, sku, sellingPrice, taxRate, unit, category, stock, purchaseRate, batchNo })
//        .then(({ productId }) => { ... })
Cypress.Commands.add('seedProduct', (overrides = {}) => {
  const stamp = `${Date.now()}${Math.floor(Math.random() * 1000)}`
  const name = overrides.name || `Prod_${stamp}`
  const sku = overrides.sku || `SKU${stamp}`
  const body = {
    name,
    sku,
    sellingPrice: overrides.sellingPrice != null ? overrides.sellingPrice : 100,
    taxRate: overrides.taxRate != null ? overrides.taxRate : 0,
    unit: overrides.unit || 'pcs',
    categoryName: overrides.category || 'General',
  }
  if (overrides.manufacturer) body.manufacturer = overrides.manufacturer
  if (overrides.description) body.description = overrides.description
  if (overrides.taxCodeId != null) body.taxCodeId = overrides.taxCodeId   // multi-rate tax: assign a tax code
  if (overrides.barcode) body.barcode = overrides.barcode                 // barcode-first sell: scannable code

  return cy.request({
    method: 'POST', url: '/addProduct', body,
    headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `addProduct ${name}`).to.eq(200)
    expect(r.body && r.body.success, JSON.stringify(r.body)).to.eq(true)
    const productId = r.body.data.id
    const result = { productId, name, sku }

    if (!overrides.stock) return result
    // opening inventory (local Stock is gone — stock lives in inventory-service)
    const stockBody = { productId, quantity: overrides.stock }
    stockBody.batchNo = overrides.batchNo || `B${stamp}`
    if (overrides.expiryDate) stockBody.expiryDate = overrides.expiryDate
    return cy.request({
      method: 'POST', url: '/addProductStock', body: stockBody,
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    }).then(() => result)
  })
})

/**
 * Run a block AS A DIFFERENT TENANT, without touching the current browser session.
 *
 * Why this exists: the monolith runs `maximumSessions(1)` with `sessionFixation.none()`, so logging a
 * second identity in from inside a test expires the first session — and the assertion then fails with
 * "This session has been expired", which has nothing to do with what was being tested. A Bearer token
 * through the gateway is stateless, so the calling test's session survives and no switch-back is needed.
 *
 * It is also the STRONGER assertion: it proves the SERVICE enforces org scoping, independently of any
 * UI session at all.
 *
 * Usage:
 *   asOtherTenant((auth) => {
 *     cy.request({ url: `${GW}/api/education/getThings`, headers: auth, failOnStatusCode: false })
 *   })
 */
Cypress.Commands.add('asOtherTenant', (fn, email = 'demo.education@myplus.com') => {
  return cy.request({
    method: 'POST', url: 'http://localhost:8765/api/auth/login',
    headers: { 'Content-Type': 'application/json' },
    body: { email, password: DEMO_PW }, failOnStatusCode: false,
  }).then((login) => {
    // Assert the positive first: a failed login here would otherwise surface as a confusing 401 later.
    expect(login.status, `login as ${email}: ${JSON.stringify(login.body)}`).to.eq(200)
    const token = login.body && login.body.data && login.body.data.accessToken
    expect(token, `no access token in ${JSON.stringify(login.body).slice(0, 200)}`).to.be.a('string')
    return fn({ Authorization: `Bearer ${token}` })
  })
})

/**
 * Slice 106 (workstream A) — place a storefront order through the CURRENT checkout contract.
 *
 * WHY THIS EXISTS: slice 68 (`e2b18f16`, "persistent server-side cart") moved checkout from *items in the
 * request body* to *a server-side cart addressed by a cartToken*. `CheckoutService.place()` now reads
 * `activeCart(org, req.getCartToken())` and rejects an empty one, so seven specs that still POSTed
 * `items: [...]` inline all failed with "Your cart is empty" — their real assertions never ran.
 *
 * It lives HERE, once, because all seven need the identical two-step; a copy per spec is exactly how they
 * drifted apart the first time.
 *
 * Fails loudly at every step (house rule): a helper that quietly yielded undefined would turn 14 honest
 * failures into 14 vacuous passes, which is strictly worse than the red we started with.
 *
 * @param items  one line or an array of { productId, quantity } — added to the cart in order
 * @param order  the rest of the checkout body (customerName, customerContact, shippingAddress,
 *               paymentMode, cardToken, ...) merged over { organizationId, cartToken }
 * @returns the raw /storefront/checkout response, so each spec keeps its own assertions unchanged
 */
Cypress.Commands.add('storefrontOrder', (orgId, items, order = {}) => {
  const lines = Array.isArray(items) ? items : [items]
  expect(lines.length, 'storefrontOrder needs at least one line').to.be.greaterThan(0)

  // 1) Build the server cart. The first add has no token — the server mints one and every later add reuses it.
  let cartToken = null
  cy.wrap(null).then(() => {
    const addLine = (i) => {
      if (i >= lines.length) return
      // A signed-in shopper's cart must bind to their account, so customerToken (when the caller passes one
      // for checkout) has to travel on the ADD too — otherwise the order never links to the account and
      // /storefront/myorders cannot see it.
      const body = Object.assign({ organizationId: orgId, cartToken }, lines[i])
      if (order && order.customerToken) body.customerToken = order.customerToken
      return cy.request({
        method: 'POST', url: '/storefront/cart/add', body,
        headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body, `cart/add returned no body: ${JSON.stringify(r.body)}`).to.exist
        expect(r.body.success, `cart/add failed: ${JSON.stringify(r.body)}`).to.eq(true)
        expect(r.body.data, `cart/add returned no data: ${JSON.stringify(r.body)}`).to.exist
        cartToken = r.body.data.cartToken
        expect(cartToken, `cart/add minted no cartToken: ${JSON.stringify(r.body)}`).to.be.a('string')
        return addLine(i + 1)
      })
    }
    return addLine(0)
  })

  // 2) Check out against that cart. failOnStatusCode stays off: several specs assert a REJECTED checkout
  //    (no stock, wrong contact), and those are 200-with-success:false, not transport errors.
  return cy.wrap(null).then(() =>
    cy.request({
      method: 'POST', url: '/storefront/checkout',
      body: Object.assign({ organizationId: orgId, cartToken }, order),
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    }))
})

// Slice 3.3 — the STUDENT portal fixture, seeded dev-only in auth-service alongside the guardian one.
// Same reason it exists: D5 makes the emailed set-password token the only real way in, and Cypress cannot
// read email, so without a known-password account the deny rule could only ever be unit-tested.
Cypress.Commands.add('loginAsPortalStudent', (email = 'student.education@myplus.com', password = DEMO_PW) => {
  // The 4th key element is CACHE-BUSTING and load-bearing — see loginAsPortalGuardian for the six gate
  // runs that paid for it. Authority is minted in the JWT AT LOGIN, so a session cached before the seeded
  // role changed keeps replaying the old one; validation alone cannot detect that, because /portal/my/me
  // resolves by EMAIL while the deny rule keys on the ROLE.
  cy.loginAs(email, password, '/portal/my/me', 'ROLE_STUDENT')
})

/**
 * Yield the id of a company in the current tenant, creating one if the tenant has none.
 *
 * Exists because a VENDOR cannot be seeded without one. VenderController.addVender ends with
 * `obj.setCompany(companyService.getReferenceById(dto.getCompanyId()))` — an UNGUARDED dereference,
 * so a null companyId throws and the catch-all reports "An unexpected error occurred. Please contact
 * support." rather than naming the missing field. Nothing in VenderDTO's annotations says company is
 * required; the requirement lives in the method body. Read the refusal branches, not just the
 * validation annotations.
 *
 * NB: GenericResponse has no `data` field — a List lands in `collection` (the Collection<?> constructor
 * overload wins). Reading r.body.data here silently sees nothing and creates a company every run.
 *
 * A near-identical local helper still lives in b2b-customer-type.cy.js; it should migrate to this one,
 * but that spec is green and is not being touched as part of P6.
 */
Cypress.Commands.add('ensureCompany', () => {
  // `collection` is the usual landing spot, but some endpoints answer in `object` or `data` — check all
  // three in the order the proven b2b helper does rather than betting on one and silently seeing nothing.
  const pick = (body) => {
    for (const key of ['collection', 'data', 'object']) {
      if (Array.isArray(body && body[key])) return body[key]
    }
    return []
  }
  return cy.request({ url: '/getUserCompany', failOnStatusCode: false }).then((r) => {
    const found = pick(r.body)
    if (found.length && found[0].id) return cy.wrap(found[0].id)
    const stamp = `${Date.now()}${Math.floor(Math.random() * 1000)}`
    return cy.request({
      method: 'POST', url: '/addCompany', form: true, failOnStatusCode: false,
      body: { name: `Co_${stamp}`, phone: '042-1234567', email: `co${stamp}@test.com`, address: 'Lahore' },
    }).then((c) => {
      expect(c.body.status, `addCompany: ${JSON.stringify(c.body)}`).to.be.oneOf(['SUCCESS', 'FOUND'])
      return cy.request({ url: '/getUserCompany', failOnStatusCode: false })
    }).then((r2) => {
      const made = pick(r2.body)
      expect(made.length, 'a company exists for the vendor to belong to').to.be.greaterThan(0)
      return cy.wrap(made[0].id)
    })
  })
})
