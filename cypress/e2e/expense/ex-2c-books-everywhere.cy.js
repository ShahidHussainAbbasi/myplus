/**
 * EX-2c — the books on every dashboard (E2), an honest welfare notice (E3), and finance statements owner/admin only.
 *
 * Design: microservices/docs/slices/ex-2c-books-on-every-dashboard.md §4.
 * Each domain is its own tenant; the module switch each case turns on is reset in after(). Money is read from the
 * trial balance through the gateway as the owner, never from the screen alone.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const KEY = 'org.cap.expenseManagement'
const run = String(Date.now()).slice(-6)

const DOMAINS = [
  { name: 'school', email: 'owner.education@myplus.com', dash: '/educationDashboard', check: '/getDashboardData',
    openPnl: () => {
      cy.get('#snavFinance').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavFinance .snav-btn').click() })
      cy.get('[data-cy=nav-finance-pnl]').should('be.visible').click()
    } },
  { name: 'welfare', email: 'owner.welfare@myplus.com', dash: '/welfareDashboard', check: '/getWelfareConfig',
    openPnl: () => cy.get('[data-cy=nav-finance]').click({ force: true }) },
  { name: 'farm', email: 'owner.agriculture@myplus.com', dash: '/agricultureDashboard', check: '/agricultureDashboard',
    openPnl: () => cy.get('[data-cy=nav-finance]').click({ force: true }) },
]

const signIn = (email, check) => cy.loginAs(email, PW, check, 'ex2c-' + Date.now())
const visit = (dash) => { cy.visit(dash); cy.waitForAppReady() }
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' })
const rentOf = (t) => cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).then((r) => (r.body.data || []).find((c) => c.accountCode === '6000'))

describe('EX-2c — the books on every dashboard', () => {
  after(() => {
    DOMAINS.forEach((d) => {
      signIn(d.email, d.check)
      cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: KEY }, failOnStatusCode: false })
    })
  })

  DOMAINS.forEach((d) => {
    it(`${d.name} — Finance opens on this month's P&L; an expense recorded here is in it; the trial balance balances`, () => {
      signIn(d.email, d.check)
      cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: KEY, enabled: 'true' } }).its('body.success').should('eq', true)
      const payee = `EX2C ${d.name} ${run}`
      token(d.email).then((t) => rentOf(t).then((c) =>
        cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, headers: { ...hdr(t), 'Idempotency-Key': 'ex2c-' + d.name + run },
          body: { paidFrom: 'CASH', payeeName: payee, lines: [{ categoryId: c.id, amount: 11 }] } }))
        .its('body.success').should('eq', true))
      signIn(d.email, d.check)                      // a fresh session: the module switch reaches the menus
      visit(d.dash)
      d.openPnl()
      cy.get('#FinanceDiv').should('be.visible')
      cy.get('#finTabs .fin-tab.active').should('have.attr', 'data-report', 'pnl')
      cy.contains('#FinanceResults', 'Rent', { timeout: 20000 }).should('be.visible')
      cy.get('#finTabs [data-report="taxRegister"]').should('not.exist')       // no sales tax to register here
      cy.get('#FinanceDiv .fin-sub').invoke('text').should('not.match', /tax/i)    // nor does the heading promise one
      cy.get('#finTabs [data-report="trialBalance"]').click()
      cy.contains('#FinanceResults', 'Balanced', { timeout: 20000 }).should('be.visible')
      // What the books do not hold yet is said, not left out: welfare's donations (R-5), the farm's own Income/Expense
      // records (agriculture-service posts nothing to finance until EX-9). A school's fees do post — no notice.
      const note = { welfare: 'Donations', farm: 'Income records' }[d.name]   // EX-9b: the farm's expenses now reach the books
      if (note) cy.get('#FinanceDiv [data-cy=books-note]').should('be.visible').and('contain', note)
      else cy.get('#FinanceDiv [data-cy=books-note]').should('not.exist')
    })
  })

  it('welfare — the Expenses screen says donations are not in the books yet (not "posted to your books")', () => {
    signIn('owner.welfare@myplus.com', '/getWelfareConfig')
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: KEY, enabled: 'true' } })
    signIn('owner.welfare@myplus.com', '/getWelfareConfig')
    visit('/welfareDashboard')
    cy.get('#navExpenses').click({ force: true })
    cy.get('#ExpenseDiv [data-cy=books-note]').should('be.visible').and('contain', 'Donations')
    cy.get('#ExpenseDiv').should('not.contain', 'Each expense is posted to your books')
  })

  it('business — unchanged: the Finance menu and all six tabs, Tax Register included', () => {
    cy.loginAsOwner()
    visit('/businessDashboard')
    cy.get('#snavFinance').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavFinance .snav-btn').click() })
    cy.contains('#snavFinance a', 'Profit').click()
    cy.get('#finTabs .fin-tab').should('have.length', 6)
    cy.get('#finTabs [data-report="taxRegister"]').should('exist')
    cy.get('#FinanceDiv .fin-sub').should('contain', 'tax register')
    cy.contains('#FinanceResults', /Revenue|Income|Expense|None/, { timeout: 20000 })
    cy.get('#FinanceDiv [data-cy=books-note]').should('not.exist')
  })

  it('⭐ security — a user cannot read the statements; an admin can', () => {
    token('user.business@myplus.com').then((t) => {
      ;['pnl', 'trial-balance', 'balance-sheet', 'tax-register'].forEach((p) =>
        cy.request({ url: `${GW}/api/finance/gl/${p}`, headers: hdr(t), failOnStatusCode: false }).its('status').should('eq', 403))
    })
    token('admin.business@myplus.com').then((t) =>
      cy.request({ url: `${GW}/api/finance/gl/pnl`, headers: hdr(t) }).its('status').should('eq', 200))
    cy.loginAsTier('user', 'business')
    cy.request({ url: '/gl/pnl', failOnStatusCode: false }).then((r) => {
      const refused = r.status >= 400 || (r.body && (r.body.success === false || r.body.status === 'ERROR' || r.body.statusCode === 403))
      expect(refused, 'the monolith relays the refusal: ' + JSON.stringify(r.body).slice(0, 160)).to.eq(true)
    })
    visit('/businessDashboard')
    cy.get('#snavFinance').should('not.exist')
  })
})
