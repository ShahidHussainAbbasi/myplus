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
Cypress.Commands.add('loginAs', (email, password, validatePath) => {
  cy.session([email, password, validatePath], () => {
    cy.visit('/login')
    cy.get('input[name="username"]').type(email)
    cy.get('input[name="password"]').type(password)
    // Login redesign uses <button id="loginSubmit" type="submit">
    cy.get('#loginSubmit').click()
    cy.url().should('not.include', '/login')
  }, {
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

// Pharmacy (slice 33) — the PHARMA vertical reuses the business/trade backend, so it validates via the
// same business stats endpoint; userType PHARMA routes the user to /pharmaDashboard.
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

// M4 (slice 90): seed a product via the catalog MASTER (the single creation path), replacing the legacy
// /addItem + /addStock seeding. Creates a catalog Product (which auto-syncs to a bridged business Item via
// ProductSyncService), optionally seeds opening inventory, and yields { productId, itemId, name, sku }.
// itemId is resolved from getUserItem because the sell/purchase pickers are still itemId-based until M4b.
// Usage: cy.seedProduct({ name, sku, sellingPrice, taxRate, unit, category, stock, purchaseRate, batchNo })
//        .then(({ productId, itemId }) => { ... })
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

  return cy.request({
    method: 'POST', url: '/addProduct', body,
    headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `addProduct ${name}`).to.eq(200)
    expect(r.body && r.body.success, JSON.stringify(r.body)).to.eq(true)
    const productId = r.body.data.id

    // resolve the synced itemId. /getUserItem is a business GenericResponse — its list lives under `collection`
    // (fields: message/error/status/object/collection — there is NO `data`).
    return cy.request('/getUserItem').then((res) => {
      const b = res.body || {}
      const items = b.collection || b.object || b.data || []
      const item = items.find((it) => it.icode === sku || it.iname === name)
      expect(item, `seedProduct: synced item not found for sku=${sku} (Product→Item sync)`).to.exist
      const result = { productId, itemId: item.id, name, sku }

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
})
