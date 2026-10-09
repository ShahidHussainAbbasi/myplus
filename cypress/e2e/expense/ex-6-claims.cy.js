/**
 * EX-6 — expense claims: money a member paid from their own pocket. Submitted by anyone, decided by an owner or admin
 * who is not the claimant, and only an APPROVED claim reaches the books (Dr the category / Cr 2300 Employee
 * Reimbursements Payable). A waiting, rejected or withdrawn claim is never money spent.
 *
 * Design: microservices/docs/slices/ex-6-claims.md §4. School tenant (owner/admin/user.education).
 * Expense claims is NOT in the FREE plan (a shop can sell without it), so the plan is lifted for the run and restored.
 * Money is measured on the trial balance, never on the voucher.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const MGMT = 'org.cap.expenseManagement', CLAIMS = 'org.cap.expenseClaims'
const OWNER = 'owner.education@myplus.com', ADMIN = 'admin.education@myplus.com', USER = 'user.education@myplus.com'
const run = String(Date.now()).slice(-6)

const signIn = (email) => cy.loginAs(email, PW, '/getDashboardData', 'ex6-' + Date.now())
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' })
const key = () => `ex6-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
const today = () => { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0') }

const netByCode = (t) => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: hdr(t) }).then((r) => {
  expect(r.body.balanced, 'trial balance is balanced').to.eq(true)
  const m = {}
  ;(r.body.rows || []).forEach((row) => { m[row.code] = Number(row.debit || 0) - Number(row.credit || 0) })
  return m
})
const delta = (b, a, code) => Math.round(((a[code] || 0) - (b[code] || 0)) * 100) / 100
const rentId = (t) => cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).its('body.data')
  .then((cats) => cats.find((c) => c.accountCode === '6000' && c.active).id)
/** A claim submitted straight to the service (the screen's own request), by whoever `t` belongs to. */
const submit = (t, amount, payee) => rentId(t).then((cat) => cy.request({ method: 'POST', url: `${GW}/api/expense/claims`,
  headers: { ...hdr(t), 'Idempotency-Key': key() }, failOnStatusCode: false,
  body: { voucherDate: today(), paidFrom: 'EMPLOYEE', payeeName: payee, lines: [{ categoryId: cat, amount }] } }))
const decide = (t, id, action, body) => cy.request({ method: 'POST', url: `${GW}/api/expense/claims/${id}/${action}`, headers: hdr(t),
  body: body || {}, failOnStatusCode: false })
const voucher = (t, id) => cy.request({ url: `${GW}/api/expense/vouchers/${id}`, headers: hdr(t) }).its('body.data')
const openExpenses = () => {
  cy.visit('/educationDashboard'); cy.waitForAppReady()
  cy.get('#snavFee').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavFee .snav-btn').click() })
  cy.get('#navExpenses').should('be.visible').click()
  cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
}
const row = (payee) => cy.contains('#tableExpense tbody tr.expense-row', payee, { timeout: 25000 })
/** Wait until the books have answered for an approved claim. */
const inBooks = (t, id, tries = 20) => voucher(t, id).then((v) => {
  if (v.postingStatus === 'POSTED_GL' || tries <= 0) return v
  cy.wait(1000)
  return inBooks(t, id, tries - 1)
})

describe('EX-6 — expense claims', () => {
  const approved = []                       // voided in after()
  let planWas = null

  before(() => {
    cy.loginAsOperator()
    cy.planOf(OWNER).then((p) => { planWas = p; if (p.plan === 'FREE') cy.setPlan(p.id, 'PRO') })
    signIn(OWNER)
    ;[MGMT, CLAIMS].forEach((k) => cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: k, enabled: 'true' } })
      .its('body.success').should('eq', true))
    // a run that stopped half-way leaves claims waiting: withdraw them, so "N waiting" is about this run only
    token(OWNER).then((t) => cy.request({ url: `${GW}/api/expense/vouchers?claim=SUBMITTED&size=200`, headers: hdr(t) })
      .its('body.data.content').then((l) => l.forEach((v) => decide(t, v.id, 'withdraw'))))
  })

  after(() => {
    // the plan FIRST: it needs only the operator, so a later sign-in that fails cannot leave this school on PRO
    cy.then(() => { if (planWas && planWas.plan === 'FREE') { cy.loginAsOperator(); cy.setPlan(planWas.id, 'FREE') } })
    token(OWNER).then((t) => approved.forEach((id) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/void`,
      headers: hdr(t), body: { reason: 'EX-6 gate' }, failOnStatusCode: false })))
    signIn(OWNER)
    ;[CLAIMS, MGMT].forEach((k) => cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: k }, failOnStatusCode: false }))
  })

  it('⭐ 1 — a user claims what they paid themselves: sent for approval, waiting, and not money spent yet', () => {
    const payee = `EX6 fuel ${run}`
    let before
    token(OWNER).then((t) => cy.request({ url: `${GW}/api/expense/vouchers/totals`, headers: hdr(t) }).its('body.data.total').then((x) => { before = Number(x) }))
    signIn(USER)
    openExpenses()
    cy.get('#expPaidFrom option[value="EMPLOYEE"]').should('exist')
    cy.get('#expCategory option').contains('Rent').then(($o) => cy.get('#expCategory').select($o.val(), { force: true }))
    cy.get('#expAmount').clear().type('35')
    cy.get('#expPaidFrom').select('EMPLOYEE', { force: true })
    cy.get('[data-cy=save-expense]').should('contain', 'Send for approval')
    cy.get('[data-cy=claim-hint]').should('be.visible')
    cy.get('#expPayee').clear().type(payee)
    cy.get('[data-cy=save-expense]').click()
    cy.get('#expMsg').should('contain', 'Claim sent for approval')
    row(payee).within(() => {
      cy.get('[data-cy=claim-waiting]').should('contain', 'Waiting for approval')
      cy.root().should('contain', 'Claim · ' + USER)
      cy.get('[data-cy=claim-withdraw]').should('exist')
      cy.get('td').first().should('have.text', '')                       // no expense number until it is approved
    })
    token(OWNER).then((t) => cy.request({ url: `${GW}/api/expense/vouchers/totals`, headers: hdr(t) }).its('body.data.total')
      .then((x) => expect(Number(x), 'a waiting claim is not money spent').to.eq(before)))
  })

  it('⭐ 2 — the owner sees it waiting, approves it, and the books now owe the claimant (Cr 2300)', () => {
    const payee = `EX6 fuel ${run}`
    token(OWNER).then((t) => netByCode(t).then((b) => {
      signIn(OWNER)
      openExpenses()
      cy.get('[data-cy=claims-waiting]').should('be.visible').and('contain', '1 claim is waiting for approval')
      cy.get('[data-cy=claims-show]').click()
      cy.get('#tableExpense tbody tr.expense-row').should('have.length', 1)
      row(payee).find('[data-cy=claim-approve]').click()
      cy.get('#expMsg').should('contain', 'Claim approved')
      cy.get('[data-cy=claims-show]').should('contain', 'Show every expense').click()
      row(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
      row(payee).find('td').first().invoke('text').should('match', /^EXP-\d{6}$/)
      cy.request(`/expense/vouchers?size=50`).its('body.data.content').then((l) => {
        const v = l.find((x) => x.payeeName === payee)
        approved.push(v.id)
        expect(v.claimStatus).to.eq('APPROVED')
        inBooks(t, v.id).then(() => netByCode(t).then((a) => {
          expect(delta(b, a, '2300'), 'owed to the claimant: Cr 2300').to.eq(-35)
          expect(delta(b, a, '6000'), 'the expense: Dr Rent').to.eq(35)
          expect(delta(b, a, '1000'), 'no cash moved').to.eq(0)
          expect(delta(b, a, '1010'), 'no bank moved').to.eq(0)
        }))
      })
    }))
  })

  it('⭐ 3 — nobody decides their own claim, and a user decides nobody\'s', () => {
    token(ADMIN).then((a) => submit(a, 12, `EX6 own ${run}`).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const id = r.body.data.id
      decide(a, id, 'approve').then((x) => {
        expect(x.body.success).to.eq(false)
        expect(x.body.message).to.contain('cannot approve your own claim')
      })
      decide(a, id, 'reject', { reason: 'mine' }).its('body.message').should('contain', 'cannot decide your own claim')
      token(USER).then((u) => {
        decide(u, id, 'approve').its('status').should('be.oneOf', [403, 404])
        decide(u, id, 'reject', { reason: 'x' }).its('status').should('be.oneOf', [403, 404])
      })
      token(OWNER).then((o) => decide(o, id, 'approve').then((x) => {
        expect(x.body.success, 'another owner/admin may approve it').to.eq(true)
        approved.push(id)
      }))
    }))
  })

  it('⭐ 4 — the owner rejects with a reason; the claimant reads it; nothing reaches the books', () => {
    const payee = `EX6 taxi ${run}`
    token(USER).then((u) => submit(u, 18, payee).its('body.success').should('eq', true))
    token(OWNER).then((t) => netByCode(t).then((b) => {
      signIn(OWNER)
      openExpenses()
      row(payee).find('[data-cy=claim-reject]').click()
      cy.get('.uiC-card').should('contain', 'Reject this claim?')
      cy.get('.uiC-input').type('No receipt for the taxi')
      cy.get('[data-ui-confirm="ok"]').click()
      row(payee).find('[data-cy=claim-rejected]').should('contain', 'Rejected')
      netByCode(t).then((a) => {
        expect(delta(b, a, '2300'), 'nothing owed').to.eq(0)
        expect(delta(b, a, '6000'), 'nothing spent').to.eq(0)
      })
    }))
    signIn(USER)
    openExpenses()
    row(payee).find('[data-cy=claim-reason]').should('contain', 'No receipt for the taxi')
    row(payee).find('[data-cy=claim-withdraw]').should('not.exist')
    row(payee).find('[data-cy=expense-receipts]').should('not.exist')     // a finished claim takes no new receipts
    cy.request(`/expense/vouchers?size=50`).its('body.data.content').then((l) => {
      const id = l.find((x) => x.payeeName === payee).id
      cy.fixture('receipt-invoice.pdf', 'binary').then((bin) => {
        const fd = new FormData()
        fd.append('file', new Blob([Uint8Array.from(bin, (c) => c.charCodeAt(0))], { type: 'application/pdf' }), 'late.pdf')
        token(USER).then((u) => cy.request({ method: 'POST', url: `${GW}/api/expense/receipts?voucherId=${id}`, headers: { Authorization: `Bearer ${u}` },
          body: fd, failOnStatusCode: false }).then((r) => expect(JSON.parse(new TextDecoder().decode(r.body)).message).to.contain('rejected claim takes no new receipts')))
      })
    })
  })

  it('5 — the claimant withdraws a waiting claim; it cannot then be approved', () => {
    const payee = `EX6 lunch ${run}`
    token(USER).then((u) => submit(u, 9, payee).its('body.data.id').then((id) => {
      signIn(USER)
      openExpenses()
      row(payee).find('[data-cy=claim-withdraw]').click()
      row(payee).find('[data-cy=claim-withdrawn]').should('contain', 'Withdrawn')
      token(OWNER).then((o) => decide(o, id, 'approve').its('body.message').should('contain', 'already decided'))
    }))
  })

  it('⭐ 6 — the other doors are shut: no claim recorded as an ordinary expense, posted straight in, or paid as a bill', () => {
    token(USER).then((u) => rentId(u).then((cat) => {
      cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, headers: { ...hdr(u), 'Idempotency-Key': key() }, failOnStatusCode: false,
        body: { voucherDate: today(), paidFrom: 'EMPLOYEE', payeeName: `EX6 sneak ${run}`, lines: [{ categoryId: cat, amount: 5 }] } })
        .its('body.message').should('contain', 'claim')
      submit(u, 4, `EX6 door ${run}`).its('body.data.id').then((id) => {
        token(OWNER).then((o) => {
          cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/post`, headers: hdr(o), failOnStatusCode: false })
            .its('body.message').should('contain', 'approves it')
          cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/pay`, headers: { ...hdr(o), 'Idempotency-Key': key() },
            body: { amount: 4, method: 'CASH' }, failOnStatusCode: false }).its('body.success').should('eq', false)
          decide(o, id, 'withdraw').its('body.success').should('eq', true)
        })
      })
    }))
  })

  it('⭐ 8 — the same receipt on a claim still waiting is warned about; once the claim is withdrawn it is not', () => {
    const up = (t, voucherId) => cy.fixture('receipt-invoice.pdf', 'binary').then((bin) => {
      const fd = new FormData()
      fd.append('file', new Blob([Uint8Array.from(bin, (c) => c.charCodeAt(0))], { type: 'application/pdf' }), 'bill.pdf')
      return cy.request({ method: 'POST', url: `${GW}/api/expense/receipts${voucherId ? '?voucherId=' + voucherId : ''}`,
        headers: { Authorization: `Bearer ${t}` }, body: fd }).then((r) => JSON.parse(new TextDecoder().decode(r.body)).data)
    })
    token(USER).then((u) => up(u).then((rc) => rentId(u).then((cat) => cy.request({ method: 'POST', url: `${GW}/api/expense/claims`,
      headers: { ...hdr(u), 'Idempotency-Key': key() },
      body: { voucherDate: today(), paidFrom: 'EMPLOYEE', payeeName: `EX6 dup ${run}`, receiptIds: [rc.id], lines: [{ categoryId: cat, amount: 7 }] } })
      .its('body.data.id').then((claimId) => {
        token(OWNER).then((o) => {
          up(o).its('alsoOn').should('include', 'a claim waiting for approval')
          decide(o, claimId, 'withdraw').its('body.success').should('eq', true)
          up(o).its('alsoOn').should('not.include', 'a claim waiting for approval')
        })
      }))))
  })

  it('7 — with Expense claims switched off, the choice is gone and the server refuses a claim', () => {
    signIn(OWNER)
    cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: CLAIMS } })
    signIn(USER)
    openExpenses()
    cy.get('#expPaidFrom option[value="EMPLOYEE"]').should('not.exist')
    token(USER).then((u) => submit(u, 3, `EX6 off ${run}`).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.contain('Expense claims are not switched on')
    }))
    signIn(OWNER)
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: CLAIMS, enabled: 'true' } }).its('body.success').should('eq', true)
  })
})
