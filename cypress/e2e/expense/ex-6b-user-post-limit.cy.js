/**
 * EX-6b — how much a member may post directly. No limit by default (owner's ruling: nothing changes on deploy). With a
 * limit set, a member's expense above it is SAVED and WAITS (owner's ruling: not refused) until an owner or admin posts
 * it; up to it, it posts as before. Owners and admins are never limited.
 *
 * Design: microservices/docs/slices/ex-6b-user-post-limit.md §4. School tenant: owner, admin and user.education.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const MGMT = 'org.cap.expenseManagement', LIMIT = 'expense.voucher.userPostLimit'
const OWNER = 'owner.education@myplus.com', ADMIN = 'admin.education@myplus.com', USER = 'user.education@myplus.com'
const run = String(Date.now()).slice(-6)

const signIn = (email) => cy.loginAs(email, PW, '/getDashboardData', 'ex6b-' + Date.now())
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const key = () => `ex6b-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
const TODAY = () => localIsoDate(new Date())
const r2 = (n) => Math.round(Number(n) * 100) / 100
const pnl = (t) => cy.request({ url: `${GW}/api/finance/gl/pnl?from=${TODAY()}&to=${TODAY()}`, headers: hdr(t) }).its('body.totalExpense').then(Number)
const rentId = (t) => cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).its('body.data').then((l) => l.find((c) => c.accountCode === '6000' && c.active).id)
const record = (t, amount, payee) => rentId(t).then((cat) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`,
  headers: hdr(t, { 'Idempotency-Key': key() }), failOnStatusCode: false,
  body: { voucherDate: TODAY(), paidFrom: 'CASH', payeeName: payee, lines: [{ categoryId: cat, amount }] } }))
const setLimit = (t, value) => cy.request({ method: 'POST', url: `${GW}/api/expense/settings?key=${LIMIT}&value=${value}`, headers: hdr(t), failOnStatusCode: false })
const resetLimit = (t) => cy.request({ method: 'POST', url: `${GW}/api/expense/settings/reset?key=${LIMIT}`, headers: hdr(t), failOnStatusCode: false })
const byPayee = (t, payee) => cy.request({ url: `${GW}/api/expense/vouchers?size=200`, headers: hdr(t) }).its('body.data.content')
  .then((l) => l.find((v) => v.payeeName === payee))
const inBooks = (t, id, tries = 20) => cy.request({ url: `${GW}/api/expense/vouchers/${id}`, headers: hdr(t) }).its('body.data').then((v) => {
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
const fill = (amount, payee) => {
  cy.get('#expCategory option').contains('Rent').then(($o) => cy.get('#expCategory').select($o.val(), { force: true }))
  cy.get('#expAmount').clear().type(String(amount))
  cy.get('#expPaidFrom').select('CASH', { force: true })
  cy.get('#expPayee').clear().type(payee)
}
const row = (payee) => cy.contains('#tableExpense tbody tr.expense-row', payee, { timeout: 25000 })

describe('EX-6b — how much a member may post directly', () => {
  before(() => {
    token(OWNER).then((t) => {
      cy.request({ method: 'POST', url: `${GW}/api/auth/settings?key=${MGMT}&value=true`, headers: { Authorization: `Bearer ${t}` } })
        .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
      resetLimit(t)
    })
  })

  after(() => {
    token(OWNER).then((t) => {
      resetLimit(t)
      cy.request({ url: `${GW}/api/expense/vouchers?size=200`, headers: hdr(t) }).its('body.data.content').then((l) => {
        l.filter((v) => (v.payeeName || '').includes(run) && v.status === 'POSTED').forEach((v) =>
          cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${v.id}/void`, headers: hdr(t), body: { reason: 'EX-6b gate' }, failOnStatusCode: false }))
        l.filter((v) => (v.payeeName || '').includes(run) && v.status === 'DRAFT').forEach((v) =>
          cy.request({ method: 'DELETE', url: `${GW}/api/expense/vouchers/${v.id}`, headers: hdr(t), failOnStatusCode: false }))
      })
      cy.request({ method: 'POST', url: `${GW}/api/auth/settings/reset?key=${MGMT}`, headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false })
    })
  })

  it('⭐ 1 — no limit by default: a member\'s 500 posts directly', () => {
    token(USER).then((u) => record(u, 500, `EX6B free ${run}`).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.status).to.eq('POSTED')
      expect(r.body.data.voucherNo).to.match(/^EXP-/)
    }))
  })

  it('⭐ 2 — the owner sets 50 on screen; the member\'s 80 is saved and waits (not in the books), their 30 posts', () => {
    signIn(OWNER)
    openExpenses()
    cy.get('[data-cy=expense-settings-open]').click()
    cy.get('[data-cy=set-post-limit]').should('have.value', '').clear().type('50')
    cy.get('[data-cy=set-post-limit-save]').click()
    cy.get('#expSetMsg').should('contain', 'Setting saved')
    token(OWNER).then((t) => pnl(t).then((p0) => {
      signIn(USER)
      openExpenses()
      fill(80, `EX6B big ${run}`)
      cy.get('[data-cy=save-expense]').click()
      cy.get('#expMsg', { timeout: 20000 }).should('contain', 'waits for an owner or admin')
      row(`EX6B big ${run}`).find('[data-cy=expense-waiting]').should('contain', 'Waiting to be posted')
      row(`EX6B big ${run}`).find('[data-cy=post-draft]').should('not.exist')            // a member cannot post it
      token(USER).then((u) => {
        byPayee(u, `EX6B big ${run}`).then((v) => {
          expect(v.status).to.eq('DRAFT')
          expect(v.voucherNo, 'no number until it is posted').to.eq(null)
        })
        record(u, 30, `EX6B small ${run}`).then((r) => expect(r.body.data.status, 'up to the limit posts').to.eq('POSTED'))
      })
      cy.wait(3000)
      pnl(t).then((p1) => expect(r2(p1 - p0), 'only the 30 reached the books').to.eq(30))
    }))
  })

  it('⭐ 3 — the member cannot post the waiting one; the admin posts it on screen and it reaches the books', () => {
    token(USER).then((u) => byPayee(u, `EX6B big ${run}`).then((v) =>
      cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${v.id}/post`, headers: hdr(u), failOnStatusCode: false }).then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.message).to.contain('owner or admin')
      })))
    token(OWNER).then((t) => pnl(t).then((p0) => {
      signIn(ADMIN)
      openExpenses()
      row(`EX6B big ${run}`).find('[data-cy=post-draft]').click()
      row(`EX6B big ${run}`).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
      byPayee(t, `EX6B big ${run}`).then((v) => {
        expect(v.voucherNo).to.match(/^EXP-/)
        inBooks(t, v.id).its('postingStatus').should('eq', 'POSTED_GL')
      })
      pnl(t).then((p1) => expect(r2(p1 - p0), 'the 80 is in the books now').to.eq(80))
    }))
  })

  it('⭐ 4 — the member discards their own waiting expense; a waiting one is named in the duplicate warning', () => {
    token(USER).then((u) => {
      record(u, 70, `EX6B oops ${run}`).its('body.data.status').should('eq', 'DRAFT')
      cy.request({ url: `${GW}/api/expense/vouchers/duplicates?date=${TODAY()}&amount=70&payee=${encodeURIComponent(`EX6B oops ${run}`)}`, headers: hdr(u) })
        .its('body.data').should('deep.eq', ['an expense waiting to be posted'])
    })
    signIn(USER)
    openExpenses()
    row(`EX6B oops ${run}`).find('[data-cy=discard-draft]').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.contains('#tableExpense tbody tr.expense-row', `EX6B oops ${run}`).should('not.exist')
    // (a .then returning undefined passes the previous subject on, so the list is checked directly)
    token(USER).then((u) => cy.request({ url: `${GW}/api/expense/vouchers?size=200`, headers: hdr(u) }).its('body.data.content')
      .then((l) => expect(l.some((v) => v.payeeName === `EX6B oops ${run}`), 'gone from the server too').to.eq(false)))
  })

  it('5 — the setting\'s edges: 0 makes every member expense wait; negative refused; Reset is no limit again', () => {
    token(OWNER).then((t) => {
      setLimit(t, 0).its('body.success').should('eq', true)
      token(USER).then((u) => record(u, 1, `EX6B zero ${run}`).its('body.data.status').should('eq', 'DRAFT'))
      record(t, 900, `EX6B owner ${run}`).its('body.data.status').should('eq', 'POSTED')          // an owner is never limited
      setLimit(t, -5).then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.message).to.contain('cannot be negative')
      })
      resetLimit(t).its('body.success').should('eq', true)
      token(USER).then((u) => record(u, 600, `EX6B again ${run}`).its('body.data.status').should('eq', 'POSTED'))
    })
  })
})
