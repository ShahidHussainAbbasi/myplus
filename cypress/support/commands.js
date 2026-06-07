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

Cypress.Commands.add('loginAsBusiness', (email = 'sameerfaisal29@gmail.com', password = '03453176525') => {
  cy.loginAs(email, password, '/getBusinessDashboardStats')
})

// Education (SMS) — seeded EDUCATION super user; routes to /educationDashboard.
// (verified: super@edu.com / super → 302 /educationDashboard)
Cypress.Commands.add('loginAsEducation', (email = 'super@edu.com', password = 'super') => {
  cy.loginAs(email, password, '/getDashboardData')
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
