/**
 * AN-1 — analytics-service wired end to end for the finance figures. Analytics asks finance for the P&L month by month,
 * stores each month (finance.revenue / finance.expenses) for the caller's org and serves them; the P&L screen shows the
 * last 12 months under the period's statement. Analytics' month IS the P&L's month, to the cent.
 *
 * Design: microservices/docs/slices/an-1-analytics-wiring.md §4. School tenant (owner/user.education).
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const MGMT = 'org.cap.expenseManagement'
const OWNER = 'owner.education@myplus.com', USER = 'user.education@myplus.com'
const run = String(Date.now()).slice(-6)

const signIn = (email) => cy.loginAs(email, PW, '/getDashboardData', 'an1-' + Date.now())
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const r2 = (n) => Math.round(Number(n) * 100) / 100
const ym = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
const TODAY = () => localIsoDate(new Date())
const monthsBack = (n) => { const d = new Date(); return ym(new Date(d.getFullYear(), d.getMonth() - n, 1)) }

const monthly = (t, from, to, opts = {}) => cy.request({ url: `${GW}/api/analytics/financial/monthly?from=${from}&to=${to}`,
  headers: hdr(t), failOnStatusCode: false, ...opts })
const pnl = (t, from, to) => cy.request({ url: `${GW}/api/finance/gl/pnl?from=${from}&to=${to}`, headers: hdr(t) }).its('body')
const thisMonth = (t) => monthly(t, monthsBack(0), monthsBack(0)).then((r) => {
  expect(r.status, JSON.stringify(r.body)).to.eq(200)
  return r.body.data.months[0]
})

describe('AN-1 — analytics wired to the books', () => {
  before(() => {
    token(OWNER).then((t) => cy.request({ method: 'POST', url: `${GW}/api/auth/settings?key=${MGMT}&value=true`, headers: { Authorization: `Bearer ${t}` } })
      .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true)))
  })

  after(() => {
    token(OWNER).then((t) => {
      cy.request({ url: `${GW}/api/expense/vouchers?size=200`, headers: hdr(t) }).its('body.data.content').then((l) =>
        l.filter((v) => (v.payeeName || '').includes(run) && v.status === 'POSTED').forEach((v) =>
          cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${v.id}/void`, headers: hdr(t), body: { reason: 'AN-1 gate' }, failOnStatusCode: false })))
      cy.request({ method: 'POST', url: `${GW}/api/auth/settings/reset?key=${MGMT}`, headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false })
    })
  })

  it('⭐ 1 — each of the last 12 months in analytics equals the P&L for that month, to the cent; the summary is their sum', () => {
    token(OWNER).then((t) => monthly(t, monthsBack(11), monthsBack(0)).then((r) => {
      expect(r.status, JSON.stringify(r.body)).to.eq(200)
      const d = r.body.data
      expect(d.stale, 'fresh from finance').to.eq(false)
      expect(d.months.map((m) => m.month)).to.deep.eq([...Array(12).keys()].map((i) => monthsBack(11 - i)))
      let rev = 0, exp = 0
      d.months.forEach((m) => {
        pnl(t, m.from, m.to).then((p) => {
          expect(r2(m.revenue), `${m.month} revenue`).to.eq(r2(p.totalIncome))
          expect(r2(m.expenses), `${m.month} expenses`).to.eq(r2(p.totalExpense))
          expect(r2(m.net), `${m.month} net`).to.eq(r2(p.netProfit))
        })
        rev += Number(m.revenue); exp += Number(m.expenses)
      })
      const first = d.months[0].from
      pnl(t, first, TODAY()).then((p) => {
        expect(r2(rev), 'the year\'s revenue').to.eq(r2(p.totalIncome))
        expect(r2(exp), 'the year\'s expenses').to.eq(r2(p.totalExpense))
      })
      cy.request({ url: `${GW}/api/analytics/financial/summary?startDate=${first}&endDate=${TODAY()}`, headers: hdr(t) }).its('body.data').then((s) => {
        expect(r2(s.totalRevenue), 'summary revenue').to.eq(r2(rev))
        expect(r2(s.totalExpenses), 'summary expenses').to.eq(r2(exp))
      })
    }))
  })

  it('⭐ 2 — fresh on read: an expense today raises this month by exactly its amount; its void puts it back', () => {
    token(OWNER).then((t) => thisMonth(t).then((m0) =>
      cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).its('body.data').then((cats) =>
        cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, headers: hdr(t, { 'Idempotency-Key': `an1-${run}` }),
          body: { voucherDate: TODAY(), paidFrom: 'CASH', payeeName: `AN1 trend ${run}`,
            lines: [{ categoryId: cats.find((c) => c.accountCode === '6000' && c.active).id, amount: 37.45 }] } })
          .its('body.data').then((v) => {
            cy.wait(3000)                                        // the posting leaves through expense's outbox
            thisMonth(t).then((m1) => expect(r2(m1.expenses - m0.expenses), 'this month after the expense').to.eq(37.45))
            cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${v.id}/void`, headers: hdr(t), body: { reason: 'AN-1 gate' } })
            cy.wait(3000)
            thisMonth(t).then((m2) => expect(r2(m2.expenses), 'back after the void').to.eq(r2(m0.expenses)))
          }))))
  })

  it('⭐ 3 — who may read: the plain user is refused by analytics, as finance refuses them', () => {
    token(USER).then((u) => {
      cy.request({ url: `${GW}/api/finance/gl/pnl`, headers: hdr(u), failOnStatusCode: false }).its('status').should('eq', 403)
      monthly(u, monthsBack(11), monthsBack(0)).its('status').should('eq', 403)
      cy.request({ url: `${GW}/api/analytics/financial/summary?startDate=2026-01-01&endDate=${TODAY()}`, headers: hdr(u), failOnStatusCode: false })
        .its('status').should('eq', 403)
    })
  })

  it('⭐ 4 — on screen: the P&L shows the last 12 months; this month\'s row matches the books', () => {
    signIn(OWNER)
    cy.visit('/educationDashboard'); cy.waitForAppReady()
    cy.get('#snavFinance').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavFinance .snav-btn').click() })
    cy.get('[data-cy=nav-finance-pnl]').should('be.visible').click()
    cy.get('[data-cy=pnl-trend]', { timeout: 25000 }).should('be.visible').and('contain', 'Last 12 months')
    cy.get('[data-cy=pnl-trend] tbody tr').should('have.length', 12)
    token(OWNER).then((t) => thisMonth(t).then((m) =>
      cy.get(`[data-cy=pnl-trend] tr[data-month="${m.month}"]`).should('contain', Number(m.expenses).toFixed(2))
        .and('contain', Number(m.revenue).toFixed(2))))
  })

  it('5 — the range: more than 24 months, or from after to, is refused in words', () => {
    token(OWNER).then((t) => {
      monthly(t, monthsBack(30), monthsBack(0)).then((r) => {
        expect(r.status).to.eq(400)
        expect(r.body.message).to.contain('24 months')
      })
      monthly(t, monthsBack(0), monthsBack(2)).then((r) => {
        expect(r.status).to.eq(400)
        expect(r.body.message).to.contain('after')
      })
    })
  })
})
