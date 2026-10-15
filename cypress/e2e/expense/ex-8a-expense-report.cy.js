/**
 * EX-8a — the expense report. Its total for a period is the P&L's expense total for that period: an expense in the books
 * counts on its own date, a void counts as a negative on the day its reversal is dated, an expense the books refused is
 * not in it. Grouped by category, member, month or paid from; a CSV of every line sums to it. And E8: the user path can
 * no longer stamp a branch.
 *
 * Design: microservices/docs/slices/ex-8a-expense-report.md §4. School tenant (owner/user.education). Every money check
 * compares the CHANGE in the report and in the P&L around this spec's own expenses, never the absolute totals.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const MGMT = 'org.cap.expenseManagement'
const OWNER = 'owner.education@myplus.com', USER = 'user.education@myplus.com'
const run = String(Date.now()).slice(-6)

const signIn = (email) => cy.loginAs(email, PW, '/getDashboardData', 'ex8a-' + Date.now())
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const key = () => `ex8a-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
const iso = (d) => localIsoDate(d)
const TODAY = () => iso(new Date())
const YESTERDAY = () => { const d = new Date(); d.setDate(d.getDate() - 1); return iso(d) }
const r2 = (n) => Math.round(Number(n) * 100) / 100

const report = (t, from, to, by = 'category') => cy.request({ url: `${GW}/api/expense/reports/summary?from=${from}&to=${to}&by=${by}`, headers: hdr(t) })
  .its('body.data')
const pnl = (t, from, to) => cy.request({ url: `${GW}/api/finance/gl/pnl?from=${from}&to=${to}`, headers: hdr(t) }).its('body.totalExpense').then(Number)
const category = (t, code) => cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).its('body.data')
  .then((l) => l.find((c) => c.accountCode === code && c.active))
const record = (t, body) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, headers: hdr(t, { 'Idempotency-Key': key() }), body, failOnStatusCode: false })
const voucher = (t, id) => cy.request({ url: `${GW}/api/expense/vouchers/${id}`, headers: hdr(t) }).its('body.data')
const settled = (t, id, tries = 20) => voucher(t, id).then((v) => {
  if ((v.postingStatus === 'POSTED_GL' || v.postingStatus === 'FAILED') || tries <= 0) return v
  cy.wait(1000)
  return settled(t, id, tries - 1)
})
const voidIt = (t, id, reason) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/void`, headers: hdr(t), body: { reason } })
const lock = (t, through) => cy.request({ method: 'POST', url: `${GW}/api/finance/gl/period-lock${through ? '?lockedThrough=' + through : ''}`, headers: hdr(t) }).its('status').should('eq', 200)
const lockNow = (t) => cy.request({ url: `${GW}/api/finance/gl/period-lock`, headers: hdr(t) }).then((r) => r.body.lockedThrough || null)
const openExpenses = () => {
  cy.visit('/educationDashboard'); cy.waitForAppReady()
  cy.get('#snavFee').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavFee .snav-btn').click() })
  cy.get('#navExpenses').should('be.visible').click()
  cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
}

describe('EX-8a — the expense report', () => {
  const made = []
  let lockWas

  before(() => {
    token(OWNER).then((t) => {
      cy.request({ method: 'POST', url: `${GW}/api/auth/settings?key=${MGMT}&value=true`, headers: { Authorization: `Bearer ${t}` } })
        .then((r) => expect(r.body.success, `switch on: ${JSON.stringify(r.body)}`).to.eq(true))
      lockNow(t).then((l) => { lockWas = l })
    })
  })

  after(() => {
    token(OWNER).then((t) => {
      made.forEach((id) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/void`, headers: hdr(t), body: { reason: 'EX-8a gate' }, failOnStatusCode: false }))
      cy.then(() => cy.request({ method: 'POST', url: `${GW}/api/finance/gl/period-lock${lockWas ? '?lockedThrough=' + lockWas : ''}`, headers: hdr(t), failOnStatusCode: false }))
      cy.request({ method: 'POST', url: `${GW}/api/auth/settings/reset?key=${MGMT}`, headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false })
    })
  })

  it('⭐ 1 — the report moves exactly as the P&L does: two expenses in, then a void out', () => {
    token(OWNER).then((t) => report(t, TODAY(), TODAY()).then((r0) => pnl(t, TODAY(), TODAY()).then((p0) => {
      category(t, '6000').then((rent) => category(t, '6200').then((fuel) => {
        record(t, { voucherDate: TODAY(), paidFrom: 'CASH', payeeName: `EX8A rent ${run}`, lines: [{ categoryId: rent.id, amount: 40 }] }).its('body.data.id').then((a) => {
          made.push(a)
          record(t, { voucherDate: TODAY(), paidFrom: 'BANK', payeeName: `EX8A fuel ${run}`, lines: [{ categoryId: fuel.id, amount: 25 }] }).its('body.data.id').then((b) => {
            made.push(b)
            settled(t, a); settled(t, b)
            report(t, TODAY(), TODAY()).then((r1) => pnl(t, TODAY(), TODAY()).then((p1) => {
              expect(r2(r1.total - r0.total), 'report +65').to.eq(65)
              expect(r2(p1 - p0), 'P&L +65').to.eq(65)
              voidIt(t, a, 'EX-8a: the void counts on its own day').its('body.success').should('eq', true)
              cy.wait(3000)
              report(t, TODAY(), TODAY()).then((r2x) => pnl(t, TODAY(), TODAY()).then((p2) => {
                expect(r2(r2x.total - r0.total), 'report +25 after the void').to.eq(25)
                expect(r2(p2 - p0), 'P&L +25 after the void').to.eq(25)
              }))
            }))
          })
        })
      }))
    })))
  })

  it('⭐ 2 — an expense the books refused (a closed period) is not in the report, as it is not in the P&L', () => {
    token(OWNER).then((t) => report(t, YESTERDAY(), YESTERDAY()).then((r0) => pnl(t, YESTERDAY(), YESTERDAY()).then((p0) => {
      lock(t, YESTERDAY())
      category(t, '6000').then((rent) => record(t, { voucherDate: YESTERDAY(), paidFrom: 'CASH', payeeName: `EX8A closed ${run}`, lines: [{ categoryId: rent.id, amount: 11 }] })
        .its('body.data.id').then((id) => {
          settled(t, id).its('postingStatus').should('eq', 'FAILED')
          report(t, YESTERDAY(), YESTERDAY()).then((r1) => expect(r2(r1.total - r0.total), 'not in the report').to.eq(0))
          pnl(t, YESTERDAY(), YESTERDAY()).then((p1) => expect(r2(p1 - p0), 'not in the P&L').to.eq(0))
          voidIt(t, id, 'EX-8a: refused by the books')
        }))
      cy.then(() => lock(t, lockWas))
    })))
  })

  it('⭐ 3 — grouped on screen by category, member, month and paid from; every grouping adds up to the same total', () => {
    signIn(OWNER)
    openExpenses()
    cy.get('[data-cy=expense-report-open]').click()
    cy.get('[data-cy=report-by]').select('category', { force: true })
    cy.get('[data-cy=report-run]').click()
    cy.get('[data-cy=report-row]').should('have.length.greaterThan', 0)
    cy.get('[data-cy=report-row]').contains('Fuel and transport').should('exist')
    token(OWNER).then((t) => {
      const from = TODAY().slice(0, 8) + '01', to = TODAY()
      report(t, from, to, 'category').then((byCat) => {
        ;['member', 'month', 'paidFrom'].forEach((by) => report(t, from, to, by).then((g) => {
          expect(r2(g.total), `${by} total = category total`).to.eq(r2(byCat.total))
          expect(r2(g.groups.reduce((s, x) => s + Number(x.amount), 0)), `${by} groups add up`).to.eq(r2(g.total))
        }))
        cy.get('[data-cy=report-total]').invoke('text').then((txt) => expect(txt.replace(/[^0-9.]/g, '')).to.contain(Number(byCat.total).toFixed(2)))
      })
    })
    cy.get('[data-cy=report-by]').select('paidFrom', { force: true })
    cy.get('[data-cy=report-run]').click()
    cy.get('[data-cy=report-row]').contains('Bank').should('exist')
  })

  it('4 — the CSV has every line, a void as its own negative row, and sums to the report', () => {
    token(OWNER).then((t) => report(t, TODAY(), TODAY()).then((r) => cy.request({ url: `${GW}/api/expense/reports/expenses.csv?from=${TODAY()}&to=${TODAY()}`, headers: hdr(t) })
      .then((res) => {
        expect(res.headers['content-type']).to.contain('text/csv')
        const lines = res.body.trim().split(/\r?\n/)
        expect(lines[0]).to.contain('Date').and.contain('Amount')
        expect(lines[0].split(',').slice(-2), 'Amount and Kind are the last two cells').to.deep.eq(['Amount', 'Kind'])
        // read from the end: a category or payee may hold a quoted comma
        const sum = lines.slice(1).reduce((s, l) => s + Number(l.split(',').slice(-2)[0]), 0)
        expect(r2(sum), 'CSV sums to the report').to.eq(r2(r.total))
        expect(lines.some((l) => l.includes(`EX8A rent ${run}`) && l.includes('-40.00')), 'the void is a negative row').to.eq(true)
      })))
  })

  it('⭐ 5 — a user\'s report is their own; the owner\'s names the member', () => {
    token(USER).then((u) => category(u, '6000').then((rent) => record(u, { voucherDate: TODAY(), paidFrom: 'CASH', payeeName: `EX8A user ${run}`, lines: [{ categoryId: rent.id, amount: 7 }] })
      .its('body.data.id').then((id) => {
        made.push(id)
        settled(u, id)
        report(u, TODAY(), TODAY(), 'member').then((g) => {
          expect(g.groups.length, 'only their own').to.eq(1)
          expect(g.groups[0].label).to.eq('You')
        })
        cy.request({ url: `${GW}/api/expense/vouchers?from=${TODAY()}&to=${TODAY()}&size=200`, headers: hdr(u) }).its('body.data.content').then((l) =>
          report(u, TODAY(), TODAY()).then((r) => {
            const own = l.filter((v) => v.status === 'POSTED' && v.postingStatus === 'POSTED_GL').reduce((s, v) => s + Number(v.total), 0)
            expect(r2(r.total), 'the user\'s total is their own expenses').to.eq(r2(own))
          }))
      })))
    token(OWNER).then((t) => report(t, TODAY(), TODAY(), 'member').its('groups').then((g) =>
      expect(g.map((x) => x.label), 'the member is named').to.include('User Education')))
  })

  it('⭐ 6 — E8: a branch cannot be stamped from the user path', () => {
    token(OWNER).then((t) => category(t, '6000').then((rent) =>
      record(t, { voucherDate: TODAY(), paidFrom: 'CASH', storeId: 999999, payeeName: `EX8A branch ${run}`, lines: [{ categoryId: rent.id, amount: 3 }] })
        .then((r) => {
          expect(r.body.success).to.eq(false)
          expect(r.body.message).to.contain('branch')
        })))
  })
})
