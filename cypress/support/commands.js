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

        // ── A 200 is NOT proof the session still works ────────────────────────────────────────────
        //
        // Two different tokens are in play. The JSESSIONID keeps the MONOLITH session alive; inside it,
        // a session-scoped TokenStore holds the auth-service JWT used for every proxied call. The
        // access token lives 15 minutes (jwt.access-token-expiration-ms=900000) while this cache, by
        // design, holds ONE login for the whole run — so on any run longer than 15 minutes the JWT can
        // die while the monolith session is still perfectly healthy.
        //
        // When that happens the proxy controllers do not fail loudly. They catch the downstream 401 and
        // answer `200 {"status":"ERROR"}` (see CompanyController.getUserCompany and its siblings), so a
        // status-only check passes, the dead session is kept, and EVERY spec from that point on fails
        // with ERROR bodies that look like application bugs. That is what made a full-suite run go
        // green early and red from the middle onward, for a reason no individual spec could explain.
        //
        // So validate the thing that actually matters — that a call THROUGH the proxy still works. A
        // failure here simply makes cy.session re-run the login, which mints a fresh JWT.
        const body = res.body
        if (body && typeof body === 'object' && !Array.isArray(body)) {
          // GenericResponse (monolith) says status:'ERROR'; ApiResponse (services) says success:false.
          expect(body.status, 'downstream token still valid (GenericResponse)').to.not.eq('ERROR')
          if (body.success === false) {
            throw new Error('session validate: downstream rejected the call — ' + JSON.stringify(body).slice(0, 200))
          }
        }
        // HTML validate paths (e.g. /agricultureDashboard) yield a string; there is no envelope to
        // inspect, and a 200 page really is proof enough for those.
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
// The cacheKeyExtra is not decoration. This principal was CREATED by D2 and its privilege set is expected to
// move as O7 lands (D3 packing, D4 delivery), so a session cached under the old identity would replay a token
// whose authorities no longer match the role — the failure that cost loginAsPortalGuardian six gate runs.
// Bump the tag whenever ROLE_ORDER_BOOKER's privileges change.
Cypress.Commands.add('loginAsOrderBooker', (email = 'booker.marketplace@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getOrders', 'o7d2-booker')
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

/**
 * Wait until the global AJAX overlay has let go of the page.
 *
 * `/js/common/ajax-overlay.js` shows `.ao-box` on jQuery's `ajaxStart` and hides it on `ajaxStop`, and it
 * covers the whole viewport — so a click issued while a section is still loading lands on the overlay and
 * Cypress refuses with "is being covered by another element: `<div class="ao-box">`". It is **machine-speed
 * dependent**, which is why it hides for months and then fails a dozen specs on a loaded box.
 *
 * Wait on **`.ao-box`**, the element Cypress actually names as the blocker — waiting on `#appAjaxOverlay`
 * losing a class was tried before and still left the gap.
 *
 * Safe when the overlay never appears: Cypress satisfies `not.be.visible` for an element that does not exist.
 *
 * NEVER `{force:true}` past this instead. Forcing clicks through an overlay a real user cannot click through
 * means a stuck spinner would pass the gate and fail in production.
 */
/**
 * Wait until the app is genuinely idle.
 *
 * WHY THIS IS NOT JUST "IS THE OVERLAY HIDDEN?" — the premise of the first two versions was wrong.
 *
 * The overlay is driven by jQuery's GLOBAL ajaxStart/ajaxStop, which fire when the first request starts
 * and the LAST one finishes. So in any screen that loads in two waves — a request whose follow-up depends
 * on its response, e.g. business.js:4115 issuing /customerAccountGroup once it knows a customerId — there
 * is a real moment between the waves when zero requests are in flight. jQuery fires ajaxStop, the overlay
 * hides, this helper said "ready", and the follow-up then re-raised the overlay underneath the next click.
 *
 * That produced a race whose VICTIM MOVED between runs: three consecutive runs of sell.cy.js failed on
 * three different tests, each refused because `<div class="ao-box">` covered the element. A defect in one
 * test does not move to another test; a timing-dependent race does.
 *
 * Chasing the chained requests out of the app is the wrong fix — a follow-up that needs an id from a prior
 * response is legitimate, and every real application has some. What was wrong is this helper's question:
 * IDLENESS IS NOT OBSERVABLE FROM ONE INSTANTANEOUS CHECK.
 *
 * So it now waits for the network to be QUIET — `jQuery.active === 0` sustained for longer than the
 * overlay's own show-delay — which is what "the app has finished loading" actually means. `jQuery.active`
 * is the exact counter ajaxStart/ajaxStop are derived from, so this reads the real signal rather than a
 * rendered symptom of it.
 *
 * Cost: a floor of ~QUIET_MS per call. Paid deliberately, because the alternative is a suite that is green
 * on a re-run and therefore proves nothing.
 */
Cypress.Commands.add('waitForAppReady', () => {
  // 300ms, and it is EMPIRICALLY chosen — do not tune it down without re-running sell.cy.js.
  //
  // What it must exceed is the sampling interval below (50ms), so a gap between two waves cannot be
  // mistaken for quiet. Chained requests fire synchronously from success handlers — verified: there is no
  // setTimeout-deferred AJAX anywhere in the app — so the gap itself is sub-millisecond.
  //
  // I once "tuned" this to 250 on the reasoning that it only had to clear the overlay's own SHOW_DELAY_MS
  // (220). That reasoning was wrong AND the change regressed sell.cy.js from 31/31 to 30/31, on the very
  // test the overlay race originally broke. 300 is the value with evidence behind it.
  const QUIET_MS = 300
  const DEADLINE_MS = 30000

  // MEASURED COST (business dashboard, 1 249 products), so nobody has to guess why the suite got slower:
  //   first call after cy.visit  ~6.6s
  //   every call after that      ~0.4s
  // Only ~1.6s of that first wait is network — all 8 XHRs are complete by then. The rest is main-thread
  // work the browser does before it is genuinely idle (building a 1 200-option <select> among it). So this
  // helper did not make the suite slow; it stopped it racing past a screen that really does take that long,
  // which is what let a click land on a button the overlay was still covering.

  // Wait on BOTH the overlay and its box. Cypress names whichever element is actually on top at the
  // point of the click, and it has named each of them on different runs: `.ao-box` when a modal-sized
  // spinner sits over a button, `#appAjaxOverlay` (the full-viewport parent, `class="show"`) when the
  // whole page is masked. Waiting on only one leaves the other gap open — which is how the DataTables
  // search box and #newProduct still failed after the first version of this helper shipped.
  cy.get('#appAjaxOverlay', { timeout: DEADLINE_MS }).should('not.be.visible')
  cy.get('.ao-box', { timeout: DEADLINE_MS }).should('not.be.visible')

  // Then the part the first two versions were missing: no NEW wave may start.
  // NOTE the explicit timeout on .then(). Cypress caps a callback that returns a promise at the DEFAULT
  // command timeout (5s) regardless of any deadline inside the promise, so the first version of this died
  // with "your callback returned a promise that never resolved" on exactly the pages it exists for — a
  // dashboard carrying 1 249 products legitimately takes longer than 5s to settle.
  cy.window({ log: false }).then({ timeout: DEADLINE_MS }, (win) =>
    new Cypress.Promise((resolve, reject) => {
      const deadline = Date.now() + DEADLINE_MS
      let quietSince = null

      const tick = () => {
        // No jQuery on the page (a plain template) means nothing can be in flight — treat as quiet
        // rather than hanging for 30s on a page this helper has no opinion about.
        const active = (win.jQuery && typeof win.jQuery.active === 'number') ? win.jQuery.active : 0

        if (active === 0) {
          if (quietSince === null) quietSince = Date.now()
          if (Date.now() - quietSince >= QUIET_MS) return resolve()
        } else {
          quietSince = null          // a new wave started — the clock restarts, it does not accumulate
        }

        if (Date.now() > deadline) {
          return reject(new Error(
            `waitForAppReady: the app never went quiet for ${QUIET_MS}ms (jQuery.active=${active}). ` +
            'Something is polling, or a request is hanging.'))
        }
        setTimeout(tick, 50)
      }
      tick()
    }))

  // Belt and braces: quiet network AND overlay down, asserted last so a late wave cannot slip past.
  cy.get('#appAjaxOverlay', { timeout: DEADLINE_MS }).should('not.be.visible')
})

// Show a registration section on a dashboard (business by default). Both dashboards use the
// same off-screen #registrationType <select>, so one command serves the whole app.
//
// The three nav commands below all end with waitForAppReady(): section switches fire the AJAX that raises
// the overlay, so this is exactly where the blocker appears. Putting it here rather than in each spec is
// what stops the next spec forgetting it — purchase.cy.js did, and lost a whole suite to a `before each`.
Cypress.Commands.add('openSection', (sectionValue, dashboard = '/businessDashboard') => {
  cy.visit(dashboard)
  cy.get('#registrationType').select(sectionValue, { force: true })
  cy.get(`#${sectionValue}`).should('be.visible')
  cy.waitForAppReady()
})

// Business sale sub-sections (sellDiv, SRDiv)
Cypress.Commands.add('openSellSection', (sectionValue) => {
  cy.visit('/businessDashboard')
  cy.get('#sellType').select(sectionValue, { force: true })
  cy.get(`#${sectionValue}`).should('be.visible')
  cy.waitForAppReady()
})

// Business purchase sub-sections (purchaseDiv)
Cypress.Commands.add('openPurchaseSection', (sectionValue) => {
  cy.visit('/businessDashboard')
  cy.get('#purchaseType').select(sectionValue, { force: true })
  cy.get(`#${sectionValue}`).should('be.visible')
  cy.waitForAppReady()
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

/**
 * Open the sale screen and WAIT for the tenant's POS feature flags to land.
 *
 * The race this closes: businessDashboard fires loadPosFeatureFlags() on load (business.js ~line 160),
 * an async GET /getBusinessConfig whose handler OVERWRITES every window.pos* flag — and which fails
 * CLOSED, setting posKeyboardEnabled/posShortcutsEnabled/posQuickPickEnabled to false when the call
 * errors. A spec that pins those flags straight after cy.visit() is racing that response: land late and
 * the server's value silently replaces the test's, so the feature under test is simply OFF and the
 * assertions fail somewhere unrelated — a missing <option>, an empty cart, focus that will not move.
 *
 * That is exactly what a burst of gateway 503s produced during the P6 gate: five failures in three
 * different shapes, one cause, none of them where the real problem was.
 *
 * Waiting for the response first means the test's assignment is always the LAST write. It also makes a
 * failed config call visible as a failed wait, instead of as a puzzle three assertions later.
 *
 * Each spec keeps its own module assertions and its own flag pinning — only the racy part is shared.
 */
/**
 * Visit the business dashboard and wait until it has stopped MOVING.
 *
 * <h4>The failure this exists for</h4>
 * The KPI tiles and the charts load in the BACKGROUND — tier-1a/1b made them non-blocking deliberately, so
 * the screen is usable while they arrive. They render at the TOP of the page, so each one that lands pushes
 * everything below it down. A click aimed at anything further down then fails with "could not determine the
 * actionability of this element" (`ensureNotAnimating`) — Cypress is right: the button really is moving.
 *
 * Waiting the two reads out is the honest fix. It is also the moment a real operator can reliably hit a
 * control, whereas `{force: true}` would paper over layout instability a person would feel as a misclick.
 *
 * Registering the intercepts BEFORE the visit is what makes the waits reliable — a load-time request issued
 * before the intercept exists is simply missed.
 */
Cypress.Commands.add('visitDashboardSettled', () => {
  cy.intercept('GET', '**/getBusinessDashboardStats*').as('dashStatsSettle')
  cy.intercept('GET', '**/getDashboardChartData*').as('dashChartsSettle')
  cy.visit('/businessDashboard')
  cy.get('#sellType', { timeout: 30000 }).should('exist')
  cy.wait('@dashStatsSettle', { timeout: 30000 })
  cy.wait('@dashChartsSettle', { timeout: 30000 })
})

Cypress.Commands.add('visitSaleScreen', () => {
  // Registered BEFORE the visit, or the load-time request is missed entirely.
  cy.intercept('GET', '**/getBusinessConfig').as('posFeatureFlags')
  cy.visit('/businessDashboard')
  cy.get('#sellType').select('sellDiv', { force: true })   // nav select is off-screen
  cy.get('#sellDiv').should('be.visible')
  // Resolves for an error response too — the point is that the handler has finished writing.
  cy.wait('@posFeatureFlags', { timeout: 30000 })
})

// ── C3: capabilities ──────────────────────────────────────────────────────────────────────────────
/**
 * Switch one capability on or off for the LOGGED-IN owner's tenant.
 *
 * A capability is an org_setting under the reserved `org.cap.*` namespace, so this goes through the same
 * owner-gated endpoint the Configuration screen uses. Deliberately the product's own path rather than a
 * direct DB write: a fixture that takes a shortcut proves the shortcut works, and this codebase has been
 * caught by that before — rows written by a reflective fixture that no scoped query could then see.
 *
 * ASSERTS the response. `/saveBusinessConfig` returns 200 with an error body when the key is unknown or the
 * caller is not owner/admin, so a silent failure here would leave the capability at its previous value and
 * the assertions downstream would test nothing at all.
 *
 * @param code    short capability code, e.g. 'installments' (NOT the full org.cap.* key)
 * @param enabled true / false
 */
Cypress.Commands.add('setCapability', (code, enabled) => {
  return cy
    .request({
      method: 'POST',
      url: '/saveBusinessConfig',
      form: true,
      body: { key: 'org.cap.' + code, value: String(enabled) },
    })
    .then((res) => {
      expect(res.status, `setCapability(${code}) HTTP`).to.eq(200)
      /*
       * Assert on `success`, which is the field this envelope actually carries.
       *
       * The app has TWO envelopes: the monolith's own GenericResponse uses {status:"SUCCESS"}, while this
       * route proxies business-service's ApiResponse straight through, which uses {success:true}. Reading
       * `status` here yielded undefined and the assertion failed against a save that had worked perfectly.
       *
       * `success` discriminates both outcomes on this route: ProxyErrors.failure deliberately keeps the
       * {success:false} shape, so a proxy-level error is caught by the same check as a service-level refusal.
       *
       * The HTTP code cannot carry this on its own — the refusal that started all this was 200 with
       * {success:false, message:"Unknown setting: org.cap.installments"}. A fixture that treats 200 as
       * success would have sailed past it and left every assertion downstream testing nothing.
       */
      expect(res.body && res.body.success, `setCapability(${code}) body: ${JSON.stringify(res.body)}`)
        .to.eq(true)
    })
})

/**
 * CLEAR a tenant's explicit capability overrides, so the SHAPE PRESET decides again.
 *
 * -- Why this exists ----------------------------------------------------------------------------
 * `CapabilityService.resolve` gives an explicit tenant override precedence over the shape preset -- by
 * design, so that picking a profile never destroys a deliberate choice. The consequence for tests is that
 * `cy.setShape('general')` does NOT restore a pristine tenant: any `org.cap.*` row an earlier spec wrote
 * still wins.
 *
 * That is exactly what made capability-shapes.cy.js fail. `owner.mobile@` had eleven leftover rows --
 * serialTracking/conditionGrading true from serial-register.cy.js, the rest false -- so "a tenant that has
 * chosen no shape still sees everything" was asserting the GENERAL preset against a tenant that had
 * overridden most of it. Green or red depending on what had run before, which is not a gate.
 *
 * -- WARNING: the value must be ABSENT, not empty -----------------------------------------------
 * Sending `value=''` stores an empty string, and `resolve` reads it as `"true".equalsIgnoreCase("")` = FALSE.
 * That would switch every capability OFF while looking like a reset. Omitting the parameter entirely makes
 * the monolith proxy leave `&value=` off the query string, auth stores NULL, and `overrideFor` yields
 * Optional.empty() -- the only thing that hands the decision back to the preset.
 */
Cypress.Commands.add('clearCapabilityOverrides', () => {
  return cy.getCapabilities().then((caps) => {
    Object.keys(caps).forEach((code) => {
      cy.request({
        method: 'POST',
        url: '/saveBusinessConfig',
        form: true,
        body: { key: 'org.cap.' + code },   // NO value -- see the warning above
      }).then((res) => {
        expect(res.body && res.body.success, `clear ${code}: ${JSON.stringify(res.body)}`).to.eq(true)
      })
    })
  })
})

/** Read this tenant's capability map. Fails loudly rather than yielding undefined into an assertion. */
Cypress.Commands.add('getCapabilities', () => {
  return cy.request('/getCapabilities').then((res) => {
    expect(res.status, 'getCapabilities HTTP').to.eq(200)
    const caps = res.body && res.body.data
    expect(caps, `getCapabilities payload: ${JSON.stringify(res.body)}`).to.be.an('object')
    return caps
  })
})

// ── E2: the PLATFORM OPERATOR ──────────────────────────────────────────────────
/**
 * Log in as MaxTheService's own operator — NOT a tenant account.
 *
 * `admin@myplus.com` holds ROLE_ADMIN and userType ADMIN. It is deliberately absent from the per-module
 * `demo./user./admin./owner.` ladder in dev-test-accounts.md, because that ladder is four privilege TIERS
 * INSIDE a customer's organization and this account is not a customer at all. `loginAsTier('admin', ...)`
 * yields `admin.<module>@` — a tenant admin — which is the opposite of what an operator test needs.
 *
 * The validate path is a PLATFORM endpoint on purpose. Validating against a tenant path (say
 * /getBusinessDashboardStats) would appear to work — the operator has an accidental org from
 * getOrCreatePrimaryOrg's legacy path — and would prove nothing about operator access.
 *
 * The password comes from APP_ADMIN_PASSWORD; the dev default is Admin@2025!. Override with
 *   npx cypress run --env adminPassword=...
 */
Cypress.Commands.add('loginAsOperator', (email = 'admin@myplus.com', password) => {
  const pw = password || Cypress.env('adminPassword') || 'Admin@2025!'
  cy.loginAs(email, pw, '/platform/organizations')
})

// ── C4: per-shape tenants ─────────────────────────────────────────────────────────────────────────
/**
 * Mobile shop and pesticide dealer. Both are userType BUSINESS with their OWN organizations — they differ
 * by SHAPE and capabilities, not by module, which is the whole point of the two-axis model. A separate
 * userType per trade would hardcode a customer into the platform.
 *
 * Own orgs, deliberately: capability gating cannot be proven on a single tenant. "Turning it off for A does
 * not affect B" has no meaning without a B, and a bug that hid a section for EVERY tenant would pass a
 * one-tenant suite perfectly.
 *
 * They validate through the same business endpoint every BUSINESS tenant does.
 */
Cypress.Commands.add('loginAsMobileOwner', (email = 'owner.mobile@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getBusinessDashboardStats')
})
Cypress.Commands.add('loginAsPesticideOwner', (email = 'owner.pesticide@myplus.com', password = DEMO_PW) => {
  cy.loginAs(email, password, '/getBusinessDashboardStats')
})

/** Set the logged-in tenant's SHAPE. Seeds capability defaults; explicit overrides still win. */
Cypress.Commands.add('setShape', (code) => {
  return cy
    .request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: 'org.shape', value: code } })
    .then((res) => {
      expect(res.status, `setShape(${code}) HTTP`).to.eq(200)
      // Same envelope note as setCapability: this route proxies ApiResponse ({success}), not GenericResponse.
      expect(res.body && res.body.success, `setShape(${code}) body: ${JSON.stringify(res.body)}`).to.eq(true)
    })
})
