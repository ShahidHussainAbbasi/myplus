/**
 * EX-7a — paying a member back for an approved claim. finance records it as a disbursement to an EMPLOYEE party:
 * Dr 2300 Employee Reimbursements Payable / Cr cash·bank — Accounts Payable (2000) is never touched (F6).
 * Only an owner or admin pays back, never their own claim; a paid-back claim is voided only after its payment is reversed.
 *
 * Design: microservices/docs/slices/ex-7a-claim-payback.md §4. School tenant (owner/admin/user.education).
 * Expense claims is not in FREE: the plan is lifted for the run and restored. Money is measured on the trial balance.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const MGMT = 'org.cap.expenseManagement', CLAIMS = 'org.cap.expenseClaims'
const OWNER = 'owner.education@myplus.com', ADMIN = 'admin.education@myplus.com', USER = 'user.education@myplus.com'
const run = String(Date.now()).slice(-6)

const signIn = (email) => cy.loginAs(email, PW, '/getDashboardData', 'ex7a-' + Date.now())
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const key = () => `ex7a-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
const today = () => { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0') }

const netByCode = (t) => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: hdr(t) }).then((r) => {
  expect(r.body.balanced, 'trial balance is balanced').to.eq(true)
  const m = {}
  ;(r.body.rows || []).forEach((row) => { m[row.code] = Number(row.debit || 0) - Number(row.credit || 0) })
  return m
})
const delta = (b, a, code) => Math.round(((a[code] || 0) - (b[code] || 0)) * 100) / 100
const voucher = (t, id) => cy.request({ url: `${GW}/api/expense/vouchers/${id}`, headers: hdr(t) }).its('body.data')
const inBooks = (t, id, tries = 20) => voucher(t, id).then((v) => {
  if (v.postingStatus === 'POSTED_GL' || tries <= 0) return v
  cy.wait(1000)
  return inBooks(t, id, tries - 1)
})
/** A claim by `claimant`, approved by the owner, in the books. Yields its id. */
const approvedClaim = (claimant, amount, payee) => token(claimant).then((c) =>
  cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(c) }).its('body.data').then((cats) => {
    const cat = cats.find((x) => x.accountCode === '6000' && x.active).id
    return cy.request({ method: 'POST', url: `${GW}/api/expense/claims`, headers: hdr(c, { 'Idempotency-Key': key() }),
      body: { voucherDate: today(), paidFrom: 'EMPLOYEE', payeeName: payee, lines: [{ categoryId: cat, amount }] } }).its('body.data.id')
  }).then((id) => token(OWNER).then((o) => cy.request({ method: 'POST', url: `${GW}/api/expense/claims/${id}/approve`, headers: hdr(o), body: {} })
    .its('body.success').should('eq', true).then(() => inBooks(o, id)).then((v) => {
      expect(v.postingStatus, 'the approved claim is in the books').to.eq('POSTED_GL')
      return id
    }))))
const pay = (t, id, amount, method) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/pay`,
  headers: hdr(t, { 'Idempotency-Key': key() }), body: { amount, method }, failOnStatusCode: false })
const openExpenses = () => {
  cy.visit('/educationDashboard'); cy.waitForAppReady()
  cy.get('#snavFee').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavFee .snav-btn').click() })
  cy.get('#navExpenses').should('be.visible').click()
  cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
}
const row = (payee) => cy.contains('#tableExpense tbody tr.expense-row', payee, { timeout: 25000 })

describe('EX-7a — paying a claim back', () => {
  let planWas = null

  before(() => {
    cy.loginAsOperator()
    cy.planOf(OWNER).then((p) => { planWas = p; if (p.plan === 'FREE') cy.setPlan(p.id, 'PRO') })
    signIn(OWNER)
    ;[MGMT, CLAIMS].forEach((k) => cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: k, enabled: 'true' } })
      .then((r) => expect(r.body.success, `switch ${k} on: ${JSON.stringify(r.body)}`).to.eq(true)))
  })

  after(() => {
    // the plan FIRST: it needs only the operator, so a later sign-in that fails cannot leave this school on PRO
    cy.then(() => { if (planWas && planWas.plan === 'FREE') { cy.loginAsOperator(); cy.setPlan(planWas.id, 'FREE') } })
    // through auth's API with the owner's token (the call /resetModuleSwitch makes): a teardown must not depend on a
    // browser sign-in, which has failed intermittently at this point with no refusal logged (ex-7a slice doc §5)
    token(OWNER).then((t) => [CLAIMS, MGMT].forEach((k) => cy.request({ method: 'POST', url: `${GW}/api/auth/settings/reset?key=${k}`,
      headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false }).its('status').should('eq', 200)))
  })

  it('⭐ 1 — the owner pays a claim back in two parts: 2300 clears, cash and bank go down, Accounts Payable never moves', () => {
    const payee = `EX7 fuel ${run}`
    approvedClaim(USER, 40, payee).then(() => token(OWNER).then((t) => netByCode(t).then((b) => {
      signIn(OWNER)
      openExpenses()
      row(payee).find('[data-cy=expense-bill-owes]').should('contain', 'Owed to the member').and('contain', '40.00')
      row(payee).find('[data-cy=void-expense]').should('exist')                // nothing paid yet: still voidable
      row(payee).find('[data-cy=pay-claim]').click()
      cy.get('#expPayTitle').should('contain', 'Pay back claim')
      cy.get('#expPayAmount').clear().type('15')
      cy.get('#expPayMethod').select('CASH', { force: true })
      cy.get('[data-cy=expense-pay-go]').click()
      cy.get('#expMsg').should('contain', 'Payment recorded').and('contain', 'PV-')
      row(payee).find('[data-cy=expense-bill-owes]').should('contain', '25.00')
      row(payee).find('[data-cy=void-expense]').should('not.exist')            // paid in part: not voidable
      netByCode(t).then((a) => {
        expect(delta(b, a, '2300'), 'owed to the member goes down by 15 (Dr 2300)').to.eq(15)
        expect(delta(b, a, '1000'), 'cash paid out').to.eq(-15)
        expect(delta(b, a, '2000'), 'Accounts Payable untouched').to.eq(0)
      })
      row(payee).find('[data-cy=pay-claim]').click()
      cy.get('#expPayAmount').should('have.value', '25.00')
      cy.get('#expPayMethod').select('BANK', { force: true })
      cy.get('[data-cy=expense-pay-go]').click()
      row(payee).find('[data-cy=expense-bill-paid]').should('contain', 'Paid back')
      netByCode(t).then((a) => {
        expect(delta(b, a, '2300'), 'the claim no longer owed: 2300 back by the full 40').to.eq(40)
        expect(delta(b, a, '1010'), 'bank paid out').to.eq(-25)
        expect(delta(b, a, '2000'), 'Accounts Payable untouched').to.eq(0)
      })
    })))
  })

  it('⭐ 2 — a paid-back claim is voided only after its payment is reversed; then the books are as before the claim', () => {
    const payee = `EX7 void ${run}`
    token(OWNER).then((t) => netByCode(t).then((b) => approvedClaim(USER, 10, payee).then((id) => {
      pay(t, id, 10, 'CASH').its('body.success').should('eq', true)
      cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/void`, headers: hdr(t), body: { reason: 'EX-7a' }, failOnStatusCode: false })
        .its('body.message').should('contain', 'paid back. Reverse the payment first')
      signIn(OWNER)
      openExpenses()
      row(payee).find('[data-cy=bill-payments]').click()
      cy.get('[data-cy=reverse-payment]').first().click()
      cy.get('.uiC-input').type('Paid to the wrong person')
      cy.get('[data-ui-confirm="ok"]').click()
      row(payee).find('[data-cy=expense-bill-owes]', { timeout: 20000 }).should('contain', '10.00')
      row(payee).find('[data-cy=void-expense]').click()
      cy.get('.uiC-input').type('EX-7a gate')
      cy.get('[data-ui-confirm="ok"]').click()
      row(payee).find('.exp-chip').should('contain', 'Void')
      cy.wait(3000)
      netByCode(t).then((a) => ['2300', '6000', '1000', '1010', '2000'].forEach((c) =>
        expect(delta(b, a, c), `${c} back where it was`).to.eq(0)))
    })))
  })

  it('⭐ 3 — only an owner or admin pays back, never their own claim; the member sees what is owed, without a Pay button', () => {
    const own = `EX7 own ${run}`, mine = `EX7 mine ${run}`
    approvedClaim(ADMIN, 12, own).then((id) => {
      token(ADMIN).then((a) => pay(a, id, 12, 'CASH').its('body.message').should('contain', 'cannot pay your own claim back'))
      token(USER).then((u) => pay(u, id, 12, 'CASH').its('status').should('be.oneOf', [403, 404]))
      token(OWNER).then((o) => pay(o, id, 12, 'CASH').its('body.success').should('eq', true))
    })
    approvedClaim(USER, 6, mine).then((id) => {
      token(USER).then((u) => pay(u, id, 6, 'CASH').its('status').should('eq', 403))
      signIn(USER)
      openExpenses()
      row(mine).find('[data-cy=expense-bill-owes]').should('contain', 'Owed to the member').and('contain', '6.00')
      row(mine).find('[data-cy=pay-claim]').should('not.exist')
      token(OWNER).then((o) => pay(o, id, 6, 'BANK').its('body.success').should('eq', true))
    })
  })

  it('4 — a claim still waiting cannot be paid, and nothing more than is owed', () => {
    token(USER).then((u) => cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(u) }).its('body.data').then((cats) => {
      const cat = cats.find((x) => x.accountCode === '6000' && x.active).id
      cy.request({ method: 'POST', url: `${GW}/api/expense/claims`, headers: hdr(u, { 'Idempotency-Key': key() }),
        body: { voucherDate: today(), paidFrom: 'EMPLOYEE', payeeName: `EX7 waiting ${run}`, lines: [{ categoryId: cat, amount: 5 }] } })
        .its('body.data.id').then((id) => token(OWNER).then((o) => {
          pay(o, id, 5, 'CASH').its('body.message').should('contain', 'Only an approved claim can be paid back')
          cy.request({ method: 'POST', url: `${GW}/api/expense/claims/${id}/withdraw`, headers: hdr(o), body: {} })
        }))
    }))
    approvedClaim(USER, 8, `EX7 over ${run}`).then((id) => token(OWNER).then((o) => {
      pay(o, id, 9, 'CASH').its('body.message').should('contain', 'more than is owed on this claim')
      pay(o, id, 8, 'CASH').its('body.success').should('eq', true)
    }))
  })
})
