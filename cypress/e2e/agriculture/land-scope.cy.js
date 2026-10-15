/**
 * Farm entries may name only the farm's OWN land. The old add paths resolved the land with an unscoped findById: another
 * farm business's land id was accepted and its name stored on this farm's expense or income; an unknown id was dropped
 * silently. Both are now refused in words, and nothing is saved.
 *
 * Two farm businesses: owner.agriculture (org 10, owns the lands) and demo.agriculture (org 25, none). Expense management
 * stays OFF for org 25 here (with it on, the old expense screen refuses everything — EX-9b — and this rule is moot there).
 */

const PW = 'Demo@2025!'
const OTHER = { email: 'demo.agriculture@myplus.com', check: '/agricultureDashboard' }
const OWNER = { email: 'owner.agriculture@myplus.com', check: '/agricultureDashboard' }
const run = String(Date.now()).slice(-6)

const as = (u) => cy.loginAs(u.email, PW, u.check, 'land-' + Date.now())
const body = (r) => (typeof r.body === 'string' ? JSON.parse(r.body) : r.body)
const list = (url) => cy.request(url).then((r) => body(r).object || [])

describe('Farm entries name only the farm’s own land', () => {
  let foreignLand = null

  before(() => {
    as(OWNER)
    list('/getUserLand').then((l) => {
      foreignLand = l[0]
      expect(foreignLand, 'owner.agriculture has a land').to.exist
    })
  })

  it('⭐ 1 — another farm business’s land is refused on an expense; nothing is saved', () => {
    as(OTHER)
    cy.request({ method: 'POST', url: '/addAgricultureExpense', form: true, failOnStatusCode: false,
      body: { expenseName: `LS expense ${run}`, amount: 12, landId: foreignLand.id } }).then((r) => {
      expect(body(r).status).to.not.eq('SUCCESS')
      expect(body(r).message).to.contain('not one of yours')
    })
    list('/getUserAgricultureExpense').then((l) => expect(l.filter((x) => (x.expenseName || '').includes(run))).to.have.length(0))
  })

  it('⭐ 2 — and on an income', () => {
    as(OTHER)
    cy.request({ method: 'POST', url: '/addAgricultureIncome', form: true, failOnStatusCode: false,
      body: { incomeName: `LS income ${run}`, amount: 12, landId: foreignLand.id } }).then((r) => {
      expect(body(r).status).to.not.eq('SUCCESS')
      expect(body(r).message).to.contain('not one of yours')
    })
  })

  it('3 — an id that is no land at all is refused, not dropped silently', () => {
    as(OTHER)
    cy.request({ method: 'POST', url: '/addAgricultureExpense', form: true, failOnStatusCode: false,
      body: { expenseName: `LS ghost ${run}`, amount: 3, landId: 999999999 } }).then((r) => expect(body(r).message).to.contain('not one of yours'))
  })
})
