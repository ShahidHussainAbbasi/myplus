/**
 * EX-8d — recoverable input tax on expenses. Off by default (an expense is a cost including its tax). Switched on in
 * Expenses → Settings, the form asks how much of each amount is tax: that part is Dr 2100 and counted as INPUT tax in the
 * tax register; only the rest is an expense (in the P&L and in the report). A void undoes both.
 *
 * Design: microservices/docs/slices/ex-8d-input-tax.md §4. School tenant (owner.education).
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const MGMT = 'org.cap.expenseManagement', TAX = 'expense.tax.inputRecoverable'
const OWNER = 'owner.education@myplus.com'
const run = String(Date.now()).slice(-6)

const signIn = (email) => cy.loginAs(email, PW, '/getDashboardData', 'ex8d-' + Date.now())
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const key = () => `ex8d-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
const TODAY = () => localIsoDate(new Date())
const r2 = (n) => Math.round(Number(n) * 100) / 100

const netByCode = (t) => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: hdr(t) }).then((r) => {
  expect(r.body.balanced, 'trial balance is balanced').to.eq(true)
  const m = {}
  ;(r.body.rows || []).forEach((row) => { m[row.code] = Number(row.debit || 0) - Number(row.credit || 0) })
  return m
})
const delta = (b, a, code) => r2((a[code] || 0) - (b[code] || 0))
const register = (t) => cy.request({ url: `${GW}/api/finance/gl/tax-register?from=${TODAY()}&to=${TODAY()}`, headers: hdr(t) }).its('body')
const report = (t) => cy.request({ url: `${GW}/api/expense/reports/summary?from=${TODAY()}&to=${TODAY()}`, headers: hdr(t) }).its('body.data.total').then(Number)
const pnl = (t) => cy.request({ url: `${GW}/api/finance/gl/pnl?from=${TODAY()}&to=${TODAY()}`, headers: hdr(t) }).its('body.totalExpense').then(Number)
const rentId = (t) => cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).its('body.data').then((l) => l.find((c) => c.accountCode === '6000' && c.active).id)
const record = (t, amount, tax, payee) => rentId(t).then((cat) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`,
  headers: hdr(t, { 'Idempotency-Key': key() }), failOnStatusCode: false,
  body: { voucherDate: TODAY(), paidFrom: 'CASH', payeeName: payee, lines: [{ categoryId: cat, amount, taxAmount: tax }] } }))
const voucher = (t, id) => cy.request({ url: `${GW}/api/expense/vouchers/${id}`, headers: hdr(t) }).its('body.data')
const inBooks = (t, id, tries = 20) => voucher(t, id).then((v) => {
  if (v.postingStatus === 'POSTED_GL' || tries <= 0) return v
  cy.wait(1000)
  return inBooks(t, id, tries - 1)
})
const openExpenses = () => {
  cy.visit('/educationDashboard'); cy.waitForAppReady()
  cy.get('#snavFee').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavFee .snav-btn').click() })
  cy.get('#navExpenses').should('be.visible').click()
  cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
}

describe('EX-8d — recoverable input tax', () => {
  before(() => {
    token(OWNER).then((t) => {
      cy.request({ method: 'POST', url: `${GW}/api/auth/settings?key=${MGMT}&value=true`, headers: { Authorization: `Bearer ${t}` } })
        .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
      cy.request({ method: 'POST', url: `${GW}/api/expense/settings/reset?key=${TAX}`, headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false })
    })
  })

  after(() => {
    token(OWNER).then((t) => {
      cy.request({ url: `${GW}/api/expense/vouchers?size=200`, headers: hdr(t) }).its('body.data.content').then((l) =>
        l.filter((v) => (v.payeeName || '').includes(run) && v.status === 'POSTED').forEach((v) =>
          cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${v.id}/void`, headers: hdr(t), body: { reason: 'EX-8d gate' }, failOnStatusCode: false })))
      cy.request({ method: 'POST', url: `${GW}/api/expense/settings/reset?key=${TAX}`, headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false })
      cy.request({ method: 'POST', url: `${GW}/api/auth/settings/reset?key=${MGMT}`, headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false })
    })
  })

  it('⭐ 1 — off by default: no tax field on the form, and a tax part is refused by the server', () => {
    signIn(OWNER)
    openExpenses()
    cy.get('#expTaxGroup').should('not.be.visible')
    token(OWNER).then((t) => record(t, 115, 15, `EX8D off ${run}`).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.contain('switched off')
    }))
  })

  it('⭐ 2 — switched on: 115 paid with 15 tax is Dr 6000 100, Dr 2100 15, Cr cash 115; input tax in the register; the report and P&L count 100', () => {
    signIn(OWNER)
    openExpenses()
    cy.get('[data-cy=expense-settings-open]').click()
    cy.get('[data-cy=set-input-tax]').should('not.be.checked').check()
    cy.get('#expSetMsg').should('contain', 'Setting saved')
    cy.get('#expTaxGroup').should('be.visible')
    token(OWNER).then((t) => netByCode(t).then((b) => register(t).then((g0) => report(t).then((r0) => pnl(t).then((p0) => {
      const payee = `EX8D power ${run}`
      cy.get('#expCategory option').contains('Rent').then(($o) => cy.get('#expCategory').select($o.val(), { force: true }))
      cy.get('#expAmount').clear().type('115')
      cy.get('#expTax').clear().type('15')
      cy.get('#expPaidFrom').select('CASH', { force: true })
      cy.get('#expPayee').clear().type(payee)
      cy.get('[data-cy=save-expense]').click()
      cy.contains('#tableExpense tbody tr.expense-row', payee, { timeout: 25000 }).find('.exp-chip').should('contain', 'In the books')
      netByCode(t).then((a) => {
        expect(delta(b, a, '6000'), 'the cost, net of tax').to.eq(100)
        expect(delta(b, a, '2100'), 'input tax (Dr 2100)').to.eq(15)
        expect(delta(b, a, '1000'), 'what was paid').to.eq(-115)
      })
      register(t).then((g1) => {
        expect(r2(g1.inputTax - g0.inputTax), 'counted as input tax in the register').to.eq(15)
        expect(r2(g1.netPayable - g0.netPayable), 'the tax payable goes down by it').to.eq(-15)
      })
      report(t).then((r1) => expect(r2(r1 - r0), 'the report counts the cost').to.eq(100))
      pnl(t).then((p1) => expect(r2(p1 - p0), 'the P&L counts the cost').to.eq(100))
    })))))
  })

  it('⭐ 3 — voided: the books, the register and the report are as before', () => {
    token(OWNER).then((t) => cy.request({ url: `${GW}/api/expense/vouchers?size=50`, headers: hdr(t) }).its('body.data.content').then((l) => {
      const v = l.find((x) => x.payeeName === `EX8D power ${run}`)
      expect(v.lines[0].taxAmount, 'the line keeps its tax part').to.eq(15)
      netByCode(t).then((b) => register(t).then((g0) => {
        cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${v.id}/void`, headers: hdr(t), body: { reason: 'EX-8d gate' } })
        cy.wait(3000)
        netByCode(t).then((a) => {
          expect(delta(b, a, '6000')).to.eq(-100)
          expect(delta(b, a, '2100')).to.eq(-15)
          expect(delta(b, a, '1000')).to.eq(115)
        })
        register(t).then((g1) => expect(r2(g1.netInput - g0.netInput), 'the void is an input adjustment').to.eq(-15))
      }))
    }))
  })

  it('4 — the tax part must be less than its amount, and not negative', () => {
    token(OWNER).then((t) => {
      record(t, 15, 15, `EX8D all ${run}`).its('body.message').should('contain', 'less than the amount')
      record(t, 15, -1, `EX8D neg ${run}`).its('body.message').should('contain', 'cannot be negative')
    })
  })
})
