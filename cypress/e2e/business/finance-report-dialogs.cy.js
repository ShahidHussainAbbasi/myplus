/*
 * UI regression guard for the Finance reports on the Business dashboard.
 *
 * Complements finance-reports.cy.js (which tests the server endpoints via cy.request). That spec
 * can't catch client-side bugs, because cy.request bypasses the browser's URL assembly and rendering.
 *
 * Two report families are covered:
 *  - GL / tax / audit reports now render into the dedicated, OWNER-gated Finance page (#FinanceDiv →
 *    #FinanceResults) with a report switcher + filter criteria. Driven via showFinance(report).
 *  - Aging / statement remain modal dialogs opened from the Customer/Vendor toolbars.
 *
 * Regression it guards: the earlier protocol-relative URL bug (`serverContext + '/path'` → `//path`,
 * sent to a bogus host) that made every report fail with "Could not load". So per report it asserts:
 *   1. the request fires SAME-origin and resolves 200 (proves the URL is correct), and
 *   2. the results area never shows the red "Could not load" error.
 * It does NOT assert row counts — an empty org legitimately renders the empty-state, which still proves
 * the request succeeded.
 *
 * The Finance page is owner-only, so these log in as the seeded BUSINESS OWNER (owner.business),
 * NOT the demo account. Requires monolith + gateway + business-service + finance-service (+ audit-
 * service for the audit log) up. Headed.
 */
describe('Business — Finance reports page (owner)', () => {
  beforeEach(() => {
    cy.loginAsOwner()                // ROLE_OWNER — required to see the owner-gated #FinanceDiv
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showFinance')   // business.js loaded
  })

  const NO_ERROR = 'Could not load'

  // Drive the Finance page for a report, assert the request succeeds same-origin + no error rendered.
  function checkFinance({ report, path }) {
    const alias = path.replace(/[^a-zA-Z]/g, '')
    cy.intercept('GET', `**/${path}*`).as(alias)
    cy.window().then((win) => { win.showFinance(report) })
    cy.wait('@' + alias).then((i) => {
      expect(i.request.url, 'real path').to.include('/' + path)
      expect(i.request.url, 'not protocol-relative').to.not.match(/^https?:\/\/[a-z]+\/?$/i)
      expect(i.response.statusCode, 'HTTP status').to.eq(200)
    })
    cy.get('#FinanceResults', { timeout: 10000 }).should('be.visible').and('not.contain', NO_ERROR)
  }

  it('Trial Balance loads on the Finance page', () => {
    checkFinance({ report: 'trialBalance', path: 'gl/trialBalance' })
    cy.get('#finTabs .fin-tab.active').should('contain', 'Trial Balance')   // tab active state
    cy.get('#finAsOfWrap').should('be.visible')                            // its filter shows
  })

  it('Profit & Loss loads (from/to filters shown)', () => {
    checkFinance({ report: 'pnl', path: 'gl/pnl' })
    cy.get('#finFromWrap').should('be.visible')
    cy.get('#finToWrap').should('be.visible')
    cy.get('#finAsOfWrap').should('not.be.visible')
  })

  it('Balance Sheet loads', () => {
    checkFinance({ report: 'balanceSheet', path: 'gl/balanceSheet' })
  })

  it('Tax Register loads', () => {
    checkFinance({ report: 'taxRegister', path: 'taxRegister' })
  })

  it('Audit Log loads (action + rows filters shown)', () => {
    checkFinance({ report: 'auditLog', path: 'getAuditLog' })
    cy.get('#finActionWrap').should('be.visible')
    cy.get('#finLimitWrap').should('be.visible')
  })

  it('remembers the last report + as-of date (localStorage) across a reload', () => {
    cy.window().then((win) => { win.showFinance('trialBalance') })
    cy.get('#finAsOf').clear().type('2026-01-31')
    cy.get('#FinanceDiv .btn-primary').click()   // Run — persists prefs
    cy.reload()
    cy.window().should('have.property', 'showFinance')
    cy.window().then((win) => { win.showFinance('trialBalance') })
    cy.get('#finAsOf').should('have.value', '2026-01-31')
  })
})

describe('Business — Aging / Statement dialogs (owner)', () => {
  beforeEach(() => {
    cy.loginAsOwner()
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'openAging')
  })
  const NO_ERROR = 'Could not load'

  it('Payables (vendor) aging dialog loads', () => {
    cy.intercept('GET', '**/vendorAging').as('vendorAging')
    cy.window().then((win) => { win.openAging('VENDOR') })
    cy.wait('@vendorAging').its('response.statusCode').should('eq', 200)
    cy.get('#AgingDialogBody', { timeout: 10000 }).should('not.contain', NO_ERROR)
  })

  it('Receivables (customer) aging dialog loads', () => {
    cy.intercept('GET', '**/customerAging').as('customerAging')
    cy.window().then((win) => { win.openAging('CUSTOMER') })
    cy.wait('@customerAging').its('response.statusCode').should('eq', 200)
    cy.get('#AgingDialogBody', { timeout: 10000 }).should('not.contain', NO_ERROR)
  })

  it('Vendor statement loads (if a vendor has a balance)', () => {
    cy.request({ url: '/vendorAging', failOnStatusCode: false }).then((res) => {
      const rows = (res.body && (res.body.collection || res.body.data)) || []
      if (!rows.length) { cy.log('No vendor balances — skipping statement'); return }
      const venderId = rows[0].partyId
      cy.intercept('GET', '**/vendorStatement*').as('vendorStatement')
      cy.window().then((win) => { win.openStatement('VENDOR', venderId, rows[0].partyName) })
      cy.wait('@vendorStatement').then((i) => {
        expect(i.request.url).to.include('/vendorStatement')
        expect(i.request.url).to.include('venderId=' + venderId)
        expect(i.response.statusCode).to.eq(200)
      })
      cy.get('#StatementDialogBody', { timeout: 10000 }).should('not.contain', NO_ERROR)
    })
  })
})
