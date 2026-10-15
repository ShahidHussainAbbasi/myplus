/**
 * EX-9b — the farm's expenses converge onto Expenses. With Expense management ON the old Add Expense screen is hidden and
 * its endpoint refuses; with it OFF nothing changes. Past farm expenses come into the books with the owner's consent
 * (R-4, as EX-9a does for the till): previewed, ticked, each once, tagged to its land. A row in the books is never
 * hard-deleted (F3).
 *
 * Design: microservices/docs/slices/ex-9b-farm-convergence.md §4. Farm tenant (owner.agriculture).
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const OWNER = { email: 'owner.agriculture@myplus.com', check: '/agricultureDashboard' }
const USER = 'user.agriculture@myplus.com'
const CAP = 'org.cap.expenseManagement'
const run = String(Date.now()).slice(-6)
const AMT = [70 + Number(run.slice(-2)) / 100, 81 + Number(run.slice(-2)) / 100]

const signIn = (fresh) => cy.loginAs(OWNER.email, PW, OWNER.check, fresh ? 'ex9b-' + Date.now() : undefined)
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const r2 = (n) => Math.round(Number(n) * 100) / 100
const oldAdd = (body) => cy.request({ method: 'POST', url: '/addAgricultureExpense', form: true, failOnStatusCode: false, body })
const oldRows = () => cy.request('/getUserAgricultureExpense').then((r) => {
  const b = typeof r.body === 'string' ? JSON.parse(r.body) : r.body
  return (b.object || b.data || []).filter((x) => (x.expenseName || '').includes(run))
})
const preview = (t) => cy.request({ url: `${GW}/api/expense/history/farm`, headers: hdr(t) }).its('body.data')
const mine = (rows) => rows.filter((x) => (x.reason || '').includes(run))
const cat6000 = (t) => cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).its('body.data').then((l) => l.find((c) => c.accountCode === '6000' && c.active).id)
const farmVoucher = (t, ref, tries = 25) => cy.request({ url: `${GW}/api/expense/vouchers?size=200`, headers: hdr(t) }).its('body.data.content').then((l) => {
  const v = l.find((x) => x.source === 'FARM' && String(x.sourceRef) === String(ref))
  if ((v && v.postingStatus === 'POSTED_GL') || tries <= 0) return v
  cy.wait(1000)
  return farmVoucher(t, ref, tries - 1)
})
const openFarmExpenses = () => {
  cy.visit('/agricultureDashboard'); cy.waitForAppReady()
  cy.get('#navExpenses').click({ force: true })
  cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
}

describe('EX-9b — farm expenses converge onto Expenses', () => {
  let land = null

  before(() => {
    signIn()
    cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: CAP }, failOnStatusCode: false })
    cy.request('/getUserLand').then((r) => {
      const b = typeof r.body === 'string' ? JSON.parse(r.body) : r.body
      land = (b.object || b.data || [])[0]
      expect(land, 'the farm has a land').to.exist
    })
  })

  after(() => {
    signIn()
    token(OWNER.email).then((t) => cy.request({ url: `${GW}/api/expense/vouchers?size=200`, headers: hdr(t), failOnStatusCode: false }).then((r) => {
      ;((r.body.data && r.body.data.content) || []).filter((v) => v.source === 'FARM' && (v.payeeName || '').includes(run) && v.status === 'POSTED')
        .forEach((v) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${v.id}/void`, headers: hdr(t), body: { reason: 'EX-9b gate' }, failOnStatusCode: false }))
    }))
    cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: CAP }, failOnStatusCode: false })
  })

  it('⭐ 1 — module off: unchanged — the old Add Expense records as before and is offered', () => {
    signIn(true)
    oldAdd({ expenseName: `EX9B diesel ${run}`, amount: AMT[0], landId: land.id, cropName: 'Wheat', expenseType: 'Fuel' })
      .its('body.status').should('eq', 'SUCCESS')
    oldAdd({ expenseName: `EX9B seed ${run}`, amount: AMT[1], landId: land.id, cropName: 'Wheat', expenseType: 'Seed' })
      .its('body.status').should('eq', 'SUCCESS')
    oldRows().its('length').should('eq', 2)
    cy.visit('/agricultureDashboard'); cy.waitForAppReady()
    cy.get('a.sb-link[onclick*="agricultureExpenseDiv"]').should('not.have.class', 'cap-off')
  })

  it('⭐ 2 — module on: the old Add Expense is hidden and its endpoint refuses in words', () => {
    signIn()
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: CAP, enabled: 'true' } }).its('body.success').should('eq', true)
    signIn(true)
    oldAdd({ expenseName: `EX9B late ${run}`, amount: 5, landId: land.id }).then((r) => {
      expect(r.body.status).to.not.eq('SUCCESS')
      expect(r.body.message).to.contain('Expenses')
    })
    cy.visit('/agricultureDashboard'); cy.waitForAppReady()
    cy.get('a.sb-link[onclick*="agricultureExpenseDiv"]', { timeout: 20000 }).should('have.class', 'cap-off')
  })

  it('⭐ 3 — past farm expenses: previewed, imported on screen once each, tagged to their land, stamped on the farm', () => {
    signIn(true)
    token(OWNER.email).then((t) => preview(t).then((rows) => {
      const m = mine(rows)
      expect(m.map((x) => r2(x.amount)).sort()).to.deep.eq([r2(AMT[0]), r2(AMT[1])].sort())
      m.forEach((x) => expect(x.landName).to.eq(land.landName))
      const refs = m.map((x) => x.ref)
      openFarmExpenses()
      cy.get('[data-cy=farm-history-open]').click()
      cy.get('[data-cy=farm-history-row] [data-cy=farm-history-pick]').uncheck({ force: true })
      refs.forEach((ref) => cy.get(`[data-cy=farm-history-row][data-ref="${ref}"]`).scrollIntoView().find('[data-cy=farm-history-pick]').check({ force: true }))
      cat6000(t).then((cat) => cy.get('[data-cy=farm-history-category]').select(String(cat), { force: true }))
      cy.get('[data-cy=farm-history-import]').click()
      cy.get('#expHistMsg-farm', { timeout: 20000 }).should('contain', '2 imported')
      refs.forEach((ref) => farmVoucher(t, ref).then((v) => {
        expect(v, 'a farm expense for row ' + ref).to.exist
        expect(v.postingStatus).to.eq('POSTED_GL')
        expect(v.lines[0].tagType).to.eq('LAND')
        expect(v.lines[0].tagId).to.eq(land.id)
        expect(v.lines[0].description).to.contain('Wheat')
      }))
      preview(t).then((after) => expect(mine(after), 'they leave the list').to.have.length(0))
      cat6000(t).then((cat) => cy.request({ method: 'POST', url: `${GW}/api/expense/history/farm/import`, headers: hdr(t), body: { categoryId: cat, refs } })
        .its('body.data.imported').should('eq', 0))
      oldRows().then((l) => l.forEach((x) => expect(x.expenseVoucherNo, 'stamped on the farm side').to.match(/^EXP-/)))
    }))
  })

  it('⭐ 4 — F3: a farm row in the books is not hard-deleted', () => {
    signIn(true)
    oldRows().then((l) => cy.request({ method: 'POST', url: '/deleteAgricultureExpense', form: true, body: { checked: String(l[0].id) }, failOnStatusCode: false })
      .then((r) => {
        expect(r.body.status).to.not.eq('SUCCESS')
        expect(r.body.message).to.contain('void it in Expenses')
      }))
    oldRows().its('length').should('eq', 2)
  })

  it('5 — only an owner or admin', () => {
    token(USER).then((u) => cy.request({ url: `${GW}/api/expense/history/farm`, headers: hdr(u), failOnStatusCode: false }).its('status').should('eq', 403))
  })
})
