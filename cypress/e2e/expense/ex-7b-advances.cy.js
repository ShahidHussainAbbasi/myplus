/**
 * EX-7b — advances to staff. Given: Dr 1300 / Cr cash·bank. A claim settled from it: Dr 2300 / Cr 1300 (no money moves).
 * Taken back: Dr cash·bank / Cr 1300. Only an owner or admin, never for themselves, only to staff (never a guardian or a
 * student), never more than the member holds.
 *
 * Design: microservices/docs/slices/ex-7b-advances.md §4. School tenant (owner/admin/user.education). Expense claims is
 * not in FREE: the plan is lifted for the run and restored FIRST in teardown. Money is measured on the trial balance.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const MGMT = 'org.cap.expenseManagement', CLAIMS = 'org.cap.expenseClaims'
const OWNER = 'owner.education@myplus.com', USER = 'user.education@myplus.com'
const run = String(Date.now()).slice(-6)

const signIn = (email) => cy.loginAs(email, PW, '/getDashboardData', 'ex7b-' + Date.now())
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const key = () => `ex7b-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
const today = () => { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0') }

const netByCode = (t) => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: hdr(t) }).then((r) => {
  expect(r.body.balanced, 'trial balance is balanced').to.eq(true)
  const m = {}
  ;(r.body.rows || []).forEach((row) => { m[row.code] = Number(row.debit || 0) - Number(row.credit || 0) })
  return m
})
const delta = (b, a, code) => Math.round(((a[code] || 0) - (b[code] || 0)) * 100) / 100
const members = (t) => cy.request({ url: `${GW}/api/auth/org/users`, headers: hdr(t) }).its('body.data')
const userIdOf = (t, email) => members(t).then((l) => l.find((m) => m.email === email).userId)
const holds = (t, userId) => cy.request({ url: `${GW}/api/expense/advances`, headers: hdr(t), failOnStatusCode: false })
  .then((r) => { const b = ((r.body && r.body.data) || []).find((x) => x.userId === userId); return b ? Number(b.balance) : 0 })
const move = (t, action, body) => cy.request({ method: 'POST', url: `${GW}/api/expense/advances/${action}`,
  headers: hdr(t, { 'Idempotency-Key': key() }), body, failOnStatusCode: false })
const voucher = (t, id) => cy.request({ url: `${GW}/api/expense/vouchers/${id}`, headers: hdr(t) }).its('body.data')
const inBooks = (t, id, tries = 20) => voucher(t, id).then((v) => {
  if (v.postingStatus === 'POSTED_GL' || tries <= 0) return v
  cy.wait(1000)
  return inBooks(t, id, tries - 1)
})
const approvedClaim = (amount, payee) => token(USER).then((u) =>
  cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(u) }).its('body.data').then((cats) =>
    cy.request({ method: 'POST', url: `${GW}/api/expense/claims`, headers: hdr(u, { 'Idempotency-Key': key() }),
      body: { voucherDate: today(), paidFrom: 'EMPLOYEE', payeeName: payee, lines: [{ categoryId: cats.find((x) => x.accountCode === '6000' && x.active).id, amount }] } })
      .its('body.data.id')).then((id) => token(OWNER).then((o) => cy.request({ method: 'POST', url: `${GW}/api/expense/claims/${id}/approve`, headers: hdr(o), body: {} })
    .then(() => inBooks(o, id)).then(() => id))))
/** Whatever user.education still holds from an earlier run is taken back, so each run starts from nothing held. */
const sweep = () => token(OWNER).then((o) => userIdOf(o, USER).then((uid) => holds(o, uid).then((h) => {
  if (h > 0) move(o, 'take-back', { userId: uid, amount: h, method: 'CASH' })
})))
const openExpenses = () => {
  cy.visit('/educationDashboard'); cy.waitForAppReady()
  cy.get('#snavFee').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavFee .snav-btn').click() })
  cy.get('#navExpenses').should('be.visible').click()
  cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
}
const row = (payee) => cy.contains('#tableExpense tbody tr.expense-row', payee, { timeout: 25000 })

describe('EX-7b — advances to staff', () => {
  let planWas = null, uid = null

  before(() => {
    cy.loginAsOperator()
    cy.planOf(OWNER).then((p) => { planWas = p; if (p.plan === 'FREE') cy.setPlan(p.id, 'PRO') })
    token(OWNER).then((t) => [MGMT, CLAIMS].forEach((k) => cy.request({ method: 'POST', url: `${GW}/api/auth/settings?key=${k}&value=true`,
      headers: { Authorization: `Bearer ${t}` } }).then((r) => expect(r.body.success, `switch ${k} on: ${JSON.stringify(r.body)}`).to.eq(true))))
    token(OWNER).then((o) => userIdOf(o, USER).then((id) => { uid = id }))
    sweep()
  })

  after(() => {
    // the plan FIRST (operator only), then API-only clean-up: no teardown step depends on a browser sign-in
    cy.then(() => { if (planWas && planWas.plan === 'FREE') { cy.loginAsOperator(); cy.setPlan(planWas.id, 'FREE') } })
    sweep()
    token(OWNER).then((t) => [CLAIMS, MGMT].forEach((k) => cy.request({ method: 'POST', url: `${GW}/api/auth/settings/reset?key=${k}`,
      headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false })))
  })

  it('⭐ 1 — the owner gives a member 50 in cash: 1300 up, cash down, nothing owed or payable; the member sees what they hold', () => {
    token(OWNER).then((t) => netByCode(t).then((b) => {
      signIn(OWNER)
      openExpenses()
      cy.get('[data-cy=expense-advances-open]').should('be.visible').click()
      cy.get('[data-cy=adv-member] option').should('contain', USER)
      cy.get('[data-cy=adv-member] option').should('not.contain', 'guardian.education').and('not.contain', 'student.education')
      cy.get('[data-cy=adv-member] option').contains(USER).then(($o) => cy.get('[data-cy=adv-member]').select($o.val(), { force: true }))
      cy.get('[data-cy=adv-amount]').type('50')
      cy.get('[data-cy=adv-method]').select('CASH', { force: true })
      cy.get('[data-cy=adv-give]').click()
      cy.get('#expAdvMsg').should('contain', 'Advance given').and('contain', 'PV-')
      cy.contains('[data-cy=adv-row]', 'User').find('[data-cy=adv-holds]').should('contain', '50.00')
      netByCode(t).then((a) => {
        expect(delta(b, a, '1300'), 'the member owes it back (Dr 1300)').to.eq(50)
        expect(delta(b, a, '1000'), 'cash out').to.eq(-50)
        expect(delta(b, a, '2300'), 'not a claim').to.eq(0)
        expect(delta(b, a, '2000'), 'not a supplier').to.eq(0)
      })
    }))
    signIn(USER)
    openExpenses()
    cy.get('[data-cy=my-advance]').should('be.visible').and('contain', '50.00')
    cy.get('[data-cy=expense-advances-open]').should('not.exist')
  })

  it('⭐ 2 — a claim of 30 settled from the advance: 2300 cleared, 1300 down by 30, no cash or bank moved; 20 still held', () => {
    const payee = `EX7B taxi ${run}`
    approvedClaim(30, payee).then(() => token(OWNER).then((t) => netByCode(t).then((b) => {
      signIn(OWNER)
      openExpenses()
      row(payee).find('[data-cy=pay-claim]').click()
      cy.get('#expPayMethod option[value=ADVANCE]').should('contain', 'From their advance').and('contain', '50.00')
      cy.get('#expPayMethod').select('ADVANCE', { force: true })
      cy.get('[data-cy=expense-pay-go]').click()
      row(payee).find('[data-cy=expense-bill-paid]').should('contain', 'Paid back')
      netByCode(t).then((a) => {
        expect(delta(b, a, '2300'), 'the claim no longer owed').to.eq(30)
        expect(delta(b, a, '1300'), 'spent from the advance').to.eq(-30)
        expect(delta(b, a, '1000'), 'no cash').to.eq(0)
        expect(delta(b, a, '1010'), 'no bank').to.eq(0)
      })
      holds(t, uid).should('eq', 20)
    })))
  })

  it('⭐ 3 — the 20 left is taken back by bank: 1300 back where it began, bank up', () => {
    token(OWNER).then((t) => netByCode(t).then((b) => {
      signIn(OWNER)
      openExpenses()
      cy.get('[data-cy=expense-advances-open]').click()
      cy.get('[data-cy=adv-method]').select('BANK', { force: true })
      cy.contains('[data-cy=adv-row]', 'User').find('[data-cy=adv-take-back]').click()
      cy.get('.uiC-input').should('have.value', '20.00')
      cy.get('[data-ui-confirm="ok"]').click()
      cy.get('#expAdvMsg').should('contain', 'Advance taken back').and('contain', 'RCPT-')
      cy.get('#expAdvTable tbody').should('contain', 'Nobody holds an advance')
      netByCode(t).then((a) => {
        expect(delta(b, a, '1300'), 'nothing held any more').to.eq(-20)
        expect(delta(b, a, '1010'), 'bank in').to.eq(20)
      })
    }))
  })

  it('⭐ 4 — refused: to yourself, to a guardian or student, by a user, more than is held (taken back or spent)', () => {
    token(OWNER).then((o) => members(o).then((l) => {
      const me = l.find((m) => m.email === OWNER).userId
      const notStaff = l.find((m) => m.role === 'GUARDIAN' || m.role === 'STUDENT').userId
      move(o, 'give', { userId: me, amount: 5, method: 'CASH' }).its('body.message').should('contain', 'cannot give yourself an advance')
      move(o, 'give', { userId: notStaff, amount: 5, method: 'CASH' }).its('body.message').should('contain', 'not staff of this business')
      move(o, 'take-back', { userId: uid, amount: 1, method: 'CASH' }).its('body.success').should('eq', false)
      token(USER).then((u) => move(u, 'give', { userId: uid, amount: 5, method: 'CASH' }).its('status').should('eq', 403))
      move(o, 'give', { userId: uid, amount: 10, method: 'CASH' }).its('body.success').should('eq', true)
      approvedClaim(15, `EX7B over ${run}`).then((id) => {
        cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/pay`, headers: hdr(o, { 'Idempotency-Key': key() }),
          body: { amount: 15, method: 'ADVANCE' }, failOnStatusCode: false }).its('body.message').should('contain', 'more than this member holds')
        cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/pay`, headers: hdr(o, { 'Idempotency-Key': key() }),
          body: { amount: 10, method: 'ADVANCE' } }).its('body.success').should('eq', true)
        cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/pay`, headers: hdr(o, { 'Idempotency-Key': key() }),
          body: { amount: 5, method: 'CASH' } }).its('body.success').should('eq', true)
      })
      holds(o, uid).should('eq', 0)
    }))
  })

  it('⭐ 5 — a settlement from an advance reversed: the member holds it again, and the books mirror it exactly', () => {
    const payee = `EX7B undo ${run}`
    token(OWNER).then((o) => move(o, 'give', { userId: uid, amount: 12, method: 'CASH' }).its('body.success').should('eq', true))
    approvedClaim(12, payee).then((id) => token(OWNER).then((o) => {
      cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/pay`, headers: hdr(o, { 'Idempotency-Key': key() }),
        body: { amount: 12, method: 'ADVANCE' } }).its('body.data.id').then((pid) => netByCode(o).then((b) => {
        holds(o, uid).should('eq', 0)
        cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/payments/${pid}/reverse`, headers: hdr(o),
          body: { reason: 'Settled from the wrong advance' } }).its('body.success').should('eq', true)
        holds(o, uid).should('eq', 12)
        netByCode(o).then((a) => {
          expect(delta(b, a, '1300'), 'held again (Dr 1300)').to.eq(12)
          expect(delta(b, a, '2300'), 'the claim owed again (Cr 2300)').to.eq(-12)
          expect(delta(b, a, '1000'), 'no cash').to.eq(0)
        })
      }))
    }))
  })
})
