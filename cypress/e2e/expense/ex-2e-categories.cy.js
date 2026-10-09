/**
 * EX-2e — owners manage their own expense categories (E6).
 *
 * Design: microservices/docs/slices/ex-2e-category-screen.md §4.
 * Runs on the FARM tenant (owner.agriculture), so the business specs' categories are never touched. Each run adds one
 * category named with the run number and leaves it switched OFF (categories are never deleted: past expenses point at
 * them). Accounts are proved through the API, not the screen alone.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const KEY = 'org.cap.expenseManagement'
const OWNER = 'owner.agriculture@myplus.com', USER = 'user.agriculture@myplus.com'
const run = String(Date.now()).slice(-6)
const NAME = `EX2E Staff tea ${run}`, RENAMED = `EX2E Staff refreshments ${run}`

const signIn = (email, fresh) => cy.loginAs(email, PW, '/agricultureDashboard', fresh ? 'ex2e-' + Date.now() : undefined)
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' })
const categories = () => cy.request('/expense/categories').its('body.data')
const openExpenses = () => {
  cy.visit('/agricultureDashboard'); cy.waitForAppReady()
  cy.get('#navExpenses').click({ force: true })
  cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
}
const openPanel = () => {
  cy.get('[data-cy=expense-categories-open]').click()
  cy.get('[data-cy=expense-categories]').should('be.visible')
  cy.get('#expCatTable tbody [data-cy=cat-row]', { timeout: 20000 }).should('have.length.greaterThan', 0)
}
const rowOf = (name) => cy.get('#expCatTable tbody [data-cy=cat-row]').filter((i, tr) => Cypress.$(tr).find('[data-cy=cat-name]').val() === name)
const row = (id) => `#expCatTable tbody tr[data-id="${id}"]`

describe('EX-2e — the Categories screen', () => {
  let catId = null, voucherId = null

  before(() => {
    signIn(OWNER)
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: KEY, enabled: 'true' } }).its('body.success').should('eq', true)
  })

  after(() => {
    signIn(OWNER)
    if (voucherId) cy.request({ method: 'POST', url: `/expense/vouchers/${voucherId}/void`, body: { reason: 'EX-2e gate' }, failOnStatusCode: false })
    if (catId) cy.request({ method: 'PATCH', url: `/expense/categories/${catId}`, body: { active: false }, failOnStatusCode: false })
    cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: KEY }, failOnStatusCode: false })
  })

  it('1 — an owner opens Categories: every category with its account; only expense accounts are offered', () => {
    signIn(OWNER, true)
    openExpenses()
    openPanel()
    categories().then((cats) => cy.get('#expCatTable tbody [data-cy=cat-row]').should('have.length', cats.length))
    rowOf('Rent').find('[data-cy=cat-account]').should('have.value', '6000')
    // exactly the chart's EXPENSE accounts except 5000 — compared with finance's own chart, not a code prefix
    token(OWNER).then((t) => cy.request({ url: `${GW}/api/finance/gl/accounts`, headers: hdr(t) }).then((r) => {
      const chart = r.body.data || r.body
      const want = chart.filter((a) => a.type === 'EXPENSE' && a.code !== '5000').map((a) => a.code).sort()
      cy.get('#expCatNewAccount option').then(($o) => {
        const codes = [...$o].map((o) => o.value)
        expect(codes, 'the expense accounts, in code order').to.deep.eq(want)
        expect(codes, 'never Cost of Goods Sold').not.to.include('5000')
        expect(codes, 'never an asset').not.to.include('1000')
      })
    }))
  })

  it('⭐ 2 — add a category; the form offers it; an expense recorded with it posts to the chosen account', () => {
    signIn(OWNER, true)
    openExpenses()
    openPanel()
    cy.get('[data-cy=cat-new-name]').type(NAME)
    cy.get('[data-cy=cat-new-account]').select('6100')
    cy.get('[data-cy=cat-add]').click()
    cy.get('#expCatMsg').should('contain', 'Category saved')
    rowOf(NAME).should('have.length', 1).find('[data-cy=cat-account]').should('have.value', '6100')
    categories().then((cats) => { catId = cats.find((c) => c.name === NAME).id })
    cy.get('#expCategory option').should('contain', NAME)
    cy.get('#expCategory option').contains(NAME).then(($o) => cy.get('#expCategory').select($o.val(), { force: true }))
    cy.get('#expAmount').clear().type('4')
    cy.get('#expPayee').clear().type(`EX2E ${run}`)
    cy.get('[data-cy=save-expense]').click()
    cy.contains('#tableExpense tbody tr', `EX2E ${run}`, { timeout: 20000 }).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
    cy.contains('#tableExpense tbody tr', `EX2E ${run}`).invoke('attr', 'data-id').then((id) => {
      voucherId = id
      cy.request(`/expense/vouchers/${id}`).its('body.data.lines.0').should((l) => {
        expect(l.accountCode).to.eq('6100')
        expect(l.categoryName).to.eq(NAME)
      })
    })
  })

  it('⭐ 3 — rename it and switch it off: the form no longer offers it; the past expense keeps its name and account', () => {
    signIn(OWNER, true)
    openExpenses()
    openPanel()
    cy.then(() => {
      cy.get(`${row(catId)} [data-cy=cat-name]`).should('have.value', NAME).clear().type(RENAMED)
      cy.get(`${row(catId)} [data-cy=cat-on]`).uncheck()
      cy.get(`${row(catId)} [data-cy=cat-save]`).click()
      cy.get('#expCatMsg').should('contain', 'Category saved')
      cy.get(`${row(catId)} [data-cy=cat-name]`).should('have.value', RENAMED)
      cy.get(`${row(catId)} [data-cy=cat-on]`).should('not.be.checked')
    })
    cy.get('#expCategory option').should('not.contain', RENAMED).and('not.contain', NAME)
    cy.then(() => cy.request(`/expense/vouchers/${voucherId}`).its('body.data.lines.0').should((l) => {
      expect(l.categoryName, 'history is not rewritten').to.eq(NAME)
      expect(l.accountCode).to.eq('6100')
    }))
    cy.contains('#tableExpense tbody tr', `EX2E ${run}`).should('contain', NAME)
  })

  it('4 — the server\'s refusals are shown in its words: a duplicate name, an account that is not an expense account', () => {
    signIn(OWNER, true)
    openExpenses()
    openPanel()
    cy.get('[data-cy=cat-new-name]').type('rent')
    cy.get('[data-cy=cat-add]').click()
    cy.get('#expCatMsg').should('contain', 'already a category called rent')
    cy.then(() => cy.request({ method: 'PATCH', url: `/expense/categories/${catId}`, body: { accountCode: '5000' }, failOnStatusCode: false })
      .its('body.message').should('contain', 'not an expense account'))
    cy.then(() => cy.request({ method: 'PATCH', url: `/expense/categories/${catId}`, body: { accountCode: '1000' }, failOnStatusCode: false })
      .its('body.success').should('eq', false))
  })

  it('⭐ 5 — security: a user has no Categories button and cannot change one; the account list is refused to them', () => {
    signIn(USER, true)
    openExpenses()
    cy.get('[data-cy=expense-categories-open]').should('not.exist')
    cy.then(() => cy.request({ method: 'PATCH', url: `/expense/categories/${catId}`, body: { active: true }, failOnStatusCode: false })
      .then((r) => expect(r.status >= 400 || r.body.success === false, JSON.stringify(r.body).slice(0, 160)).to.eq(true)))
    token(USER).then((t) => cy.request({ url: `${GW}/api/expense/categories/accounts`, headers: hdr(t), failOnStatusCode: false })
      .its('status').should('eq', 403))
    // and nothing changed: still off
    signIn(OWNER)
    categories().then((cats) => expect(cats.find((c) => c.id === catId).active).to.eq(false))
  })
})
