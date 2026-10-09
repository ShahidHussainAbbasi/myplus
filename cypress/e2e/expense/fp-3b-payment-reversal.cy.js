/**
 * FP-3b (E10) — a bill payment can be REVERSED, so a paid bill can then be voided.
 *
 * Design: microservices/docs/slices/fp-3b-payment-reversal.md §4.
 * Money is measured in the live trial balance (2000 Accounts Payable, 1000 Cash) and in finance's payables subledger
 * — never in the bill's own figures alone. Same tenant and supplier set-up as FP-3 (a fresh supplier per run).
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const OWNER = 'owner.business@myplus.com'
const CAP = 'expenseManagement'

const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const key = () => `fp3b-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
const r2 = (n) => Math.round(n * 100) / 100
const netByCode = (t) => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: hdr(t) }).then((r) => {
  expect(r.body.balanced, 'trial balance is balanced').to.eq(true)
  const m = {}
  ;(r.body.rows || []).forEach((row) => { m[row.code] = Number(row.debit || 0) - Number(row.credit || 0) })
  return m
})
const delta = (b, a, code) => r2((a[code] || 0) - (b[code] || 0))
const openInFinance = (t, supplierId) => cy.request({ url: `${GW}/api/finance/payables/summary`, headers: hdr(t) })
  .then((r) => { const row = (r.body.bySupplier || []).find((s) => Number(s.partyId) === Number(supplierId)); return row ? Number(row.open) : 0 })
const untilOpen = (t, sid, want, n = 20) => openInFinance(t, sid).then((o) => (Math.abs(o - want) < 0.005 || n <= 0) ? o : (cy.wait(1000), untilOpen(t, sid, want, n - 1)))
const untilInBooks = (t, id, n = 20) => cy.request({ url: `${GW}/api/expense/vouchers/${id}`, headers: hdr(t) }).then((r) => {
  const v = r.body.data
  return (v.postingStatus === 'POSTED_GL' || n <= 0) ? v : (cy.wait(1000), untilInBooks(t, id, n - 1))
})
const recordBill = (t, sid, catId, amount) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`,
  headers: hdr(t, { 'Idempotency-Key': key() }),
  body: { voucherDate: localIsoDate(), paidFrom: 'AP', supplierId: sid, lines: [{ categoryId: catId, amount }] }, failOnStatusCode: false })
  .its('body').then((b) => { expect(b.success, 'bill recorded: ' + b.message).to.eq(true); return b.data })
const pay = (t, id, amount) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/pay`, headers: hdr(t, { 'Idempotency-Key': key() }),
  body: { amount, method: 'CASH' } }).its('body')
const reverse = (t, id, pid, reason) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/payments/${pid}/reverse`,
  headers: hdr(t), body: { reason }, failOnStatusCode: false })
const voidBill = (t, id) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/void`, headers: hdr(t),
  body: { reason: 'FP-3b gate' }, failOnStatusCode: false }).its('body')
const lock = (t, through) => cy.request({ method: 'POST', url: `${GW}/api/finance/gl/period-lock${through ? `?lockedThrough=${through}` : ''}`, headers: hdr(t) })

describe('FP-3b — reversing a bill payment', () => {
  let t, supplierId, category, billId, paymentId, receiptNo

  before(() => {
    cy.loginAsOwner()
    cy.setCapability(CAP, true)
    const stamp = Date.now()
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'FP3BCo_' + stamp, email: `fp3b${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const company = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'FP3BCo_' + stamp)
      cy.request({ method: 'POST', url: '/addVender', form: true,
        body: { name: 'FP3BVEN_' + stamp, companyId: company.id, mobile: '0302' + String(stamp).slice(-7), email: `fp3bv${stamp}@t.com` } })
      cy.request('/getUserVender').then((lr) => { supplierId = (lr.body.collection || lr.body.data || []).find((x) => x.name === 'FP3BVEN_' + stamp).id })
    })
    token(OWNER).then((x) => {
      t = x
      lock(t, null)
      cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).then((r) => { category = r.body.data.find((c) => c.active && c.accountCode === '6300') })
    })
  })

  after(() => {
    token(OWNER).then((x) => lock(x, null))
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } })
  })

  it('⭐ 1 — reverse a 120 cash payment: 2000 credited back 120, 1000 Cash +120, finance owes 300 again, a PV-…-R number', () => {
    recordBill(t, supplierId, category.id, 300).then((b) => { billId = b.id })
    cy.then(() => untilInBooks(t, billId))
    cy.then(() => pay(t, billId, 120)).then((b) => {
      expect(b.success, b.message).to.eq(true)
      paymentId = b.data.id
      receiptNo = b.data.receiptNo
      expect(receiptNo).to.match(/^PV-/)
    })
    cy.then(() => untilOpen(t, supplierId, 180))
    netByCode(t).then((before) => {
      reverse(t, billId, paymentId, 'Paid the wrong supplier').then((r) => {
        expect(r.body.success, r.body.message).to.eq(true)
        expect(r.body.data.status).to.eq('REVERSED')
        expect(r.body.data.reversalReceiptNo).to.eq(receiptNo + '-R')
      })
      netByCode(t).then((after) => {
        expect(delta(before, after, '2000'), '2000 credited back').to.eq(-120)
        expect(delta(before, after, '1000'), 'cash back').to.eq(120)
      })
    })
    untilOpen(t, supplierId, 300).should('eq', 300)
    cy.then(() => cy.request({ url: `${GW}/api/expense/vouchers/${billId}`, headers: hdr(t) })).its('body.data').should((v) => {
      expect(Number(v.paidAmount)).to.eq(0)
      expect(Number(v.openAmount)).to.eq(300)
    })
  })

  it('2 — reversing the same payment again is the same reversal: the ledger does not move', () => {
    netByCode(t).then((before) => {
      reverse(t, billId, paymentId, 'again').then((r) => {
        expect(r.body.success, r.body.message).to.eq(true)
        expect(r.body.data.reversalReceiptNo).to.eq(receiptNo + '-R')
      })
      netByCode(t).then((after) => {
        expect(delta(before, after, '2000')).to.eq(0)
        expect(delta(before, after, '1000')).to.eq(0)
      })
    })
  })

  it('⭐ 3 — with its payments reversed the bill voids, and 2000 and the subledger return to where they started', () => {
    netByCode(t).then((before) => {
      voidBill(t, billId).then((b) => expect(b.success, b.message).to.eq(true))
      cy.wait(3000)
      netByCode(t).then((after) => expect(delta(before, after, '2000'), 'the bill reversed out of 2000').to.eq(300))
    })
    untilOpen(t, supplierId, 0).should('eq', 0)
  })

  it('4 — a reason is required; a bill with one payment still standing cannot be voided', () => {
    let b2, p1, p2
    recordBill(t, supplierId, category.id, 200).then((b) => { b2 = b.id })
    cy.then(() => untilInBooks(t, b2))
    cy.then(() => pay(t, b2, 50)).then((b) => { p1 = b.data.id })
    cy.then(() => pay(t, b2, 70)).then((b) => { p2 = b.data.id })
    cy.then(() => reverse(t, b2, p1, '  ')).its('body').should((b) => { expect(b.success).to.eq(false); expect(b.message).to.match(/why/i) })
    cy.then(() => reverse(t, b2, p1, 'duplicate')).its('body.success').should('eq', true)
    cy.then(() => voidBill(t, b2)).should((b) => { expect(b.success).to.eq(false); expect(b.message).to.contain('Reverse the payments first') })
    cy.then(() => reverse(t, b2, p2, 'cancelled job')).its('body.success').should('eq', true)
    cy.then(() => voidBill(t, b2)).its('success').should('eq', true)
  })

  it('⭐ 5 — a closed period refuses the reversal in words, and nothing moves', () => {
    let b3, p3
    recordBill(t, supplierId, category.id, 40).then((b) => { b3 = b.id })
    cy.then(() => untilInBooks(t, b3))
    cy.then(() => pay(t, b3, 40)).then((b) => { p3 = b.data.id })
    cy.then(() => lock(t, localIsoDate()))
    netByCode(t).then((before) => {
      cy.then(() => reverse(t, b3, p3, 'closed test')).its('body').should((b) => {
        expect(b.success).to.eq(false)
        expect(b.message).to.match(/period is closed/i)
      })
      netByCode(t).then((after) => expect(delta(before, after, '2000')).to.eq(0))
    })
    cy.then(() => cy.request({ url: `${GW}/api/expense/vouchers/${b3}/payments`, headers: hdr(t) }))
      .its('body.data.0.status').should('eq', 'RECORDED')
    cy.then(() => lock(t, null))
    cy.then(() => reverse(t, b3, p3, 'reopened')).its('body.success').should('eq', true)
    cy.then(() => voidBill(t, b3)).its('success').should('eq', true)
  })

  it('⭐ 6 — security: a user cannot reverse; another business cannot see the payment', () => {
    let b4, p4
    recordBill(t, supplierId, category.id, 30).then((b) => { b4 = b.id })
    cy.then(() => untilInBooks(t, b4))
    cy.then(() => pay(t, b4, 30)).then((b) => { p4 = b.data.id })
    token('user.business@myplus.com').then((u) => cy.then(() => reverse(u, b4, p4, 'not mine')).its('status').should('be.oneOf', [403, 404]))
    token('owner.lifecycle@myplus.com').then((o) => cy.then(() => reverse(o, b4, p4, 'not mine')).its('status').should('be.oneOf', [400, 404]))
    cy.then(() => cy.request({ url: `${GW}/api/expense/vouchers/${b4}/payments`, headers: hdr(t) })).its('body.data.0.status').should('eq', 'RECORDED')
    cy.then(() => reverse(t, b4, p4, 'clean-up')).its('body.success').should('eq', true)
    cy.then(() => voidBill(t, b4))
  })

  it('7 — on screen: a paid bill shows its payments; Reverse asks why; the bill owes again and can be voided', () => {
    let b5
    recordBill(t, supplierId, category.id, 25).then((b) => { b5 = b.id })
    cy.then(() => untilInBooks(t, b5))
    cy.then(() => pay(t, b5, 25))
    cy.loginAsOwner(undefined, undefined, 'fp3b-ui-' + Date.now())
    cy.visit('/businessDashboard'); cy.waitForAppReady()
    cy.get('#snavTill').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavTill .snav-btn').click() })
    cy.get('#navExpenses').click()
    cy.then(() => {
      const row = `#tableExpense tbody tr[data-id="${b5}"]`
      cy.get(`${row} [data-cy=expense-bill-paid]`, { timeout: 20000 }).should('be.visible')
      cy.get(`${row} [data-cy=void-expense]`).should('not.exist')
      cy.get(`${row} [data-cy=bill-payments]`).click()
      cy.get('[data-cy=bill-payment-row]').should('have.length', 1).and('contain', 'PV-')
      cy.get('[data-cy=reverse-payment]').click()
      cy.get('.uiC-input').type('Wrong supplier')
      cy.get('[data-ui-confirm="ok"]').click()
      cy.get('[data-cy=bill-payment-row]', { timeout: 20000 }).should('contain', 'Reversed').and('contain', '-R')
      cy.get(`${row} [data-cy=expense-bill-owes]`, { timeout: 20000 }).should('contain', '25.00')
      cy.get(`${row} [data-cy=void-expense]`).should('be.visible')
    })
    cy.then(() => voidBill(t, b5))
  })
})
