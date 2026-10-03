/**
 * FP-5a — every settlement's payment reaches the books EXACTLY ONCE: Pay Supplier, Receive Payment, school fees.
 *
 * Design: microservices/docs/slices/fp-5-one-settlement-path.md §4.
 *
 * <h3>Money is measured in the TRIAL BALANCE</h3>
 * A response saying "PV-000123" is what the caller believes. Each case measures the accounts the payment must move
 * (2000 AP or 1100 AR, and 1000 Cash) by exactly its amount, and the replay case proves they do not move again.
 * The finance-down path (settlement commits, request waits, lands once) is proven on real MySQL by
 * LedgerOutboxIntegrationTest — stopping finance here would need a restart, which is the user's to make.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const OWNER = 'owner.business@myplus.com'

const hdr = (t) => ({ Authorization: `Bearer ${t}` })
const token = () => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email: OWNER, password: PW } })
  .its('body.data.accessToken')
const netByCode = (t) => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: hdr(t) }).then((r) => {
  expect(r.body.balanced, 'trial balance is balanced').to.eq(true)
  const m = {}
  ;(r.body.rows || []).forEach((row) => { m[row.code] = Number(row.debit || 0) - Number(row.credit || 0) })
  return m
})
const delta = (b, a, code) => Math.round(((a[code] || 0) - (b[code] || 0)) * 100) / 100
const list = (body) => body.collection || body.data || body.object || []

describe('FP-5a — a payment reaches the books exactly once', () => {
  const stamp = Date.now()
  let t = null
  let vendorId = null
  let customerId = null
  let voucher = null
  let receipt = null

  before(() => {
    cy.loginAsOwner()
    token().then((x) => { t = x })
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'FP5Co_' + stamp, email: `fp5${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const company = list(cr.body).find((c) => c.name === 'FP5Co_' + stamp)
      cy.request({ method: 'POST', url: '/addVender', form: true,
        body: { name: 'FP5VEN_' + stamp, companyId: company.id, mobile: '0305' + String(stamp).slice(-7), email: `fp5v${stamp}@t.com` } })
      cy.request('/getUserVender').then((lr) => { vendorId = list(lr.body).find((x) => x.name === 'FP5VEN_' + stamp).id })
    })
    cy.seedProduct({ name: 'FP5P_' + stamp, sellingPrice: 100, stock: 1 }).then(({ productId }) => {
      cy.then(() => cy.request({ method: 'POST', url: '/addPurchase', form: true,
        body: { productId, quantity: 10, venderId: vendorId, paidAmount: 0, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 12,
          totalAmount: 100, netAmount: 100, purchaseInvoiceNo: 'FP5INV-' + stamp } }).its('body.status').should('eq', 'SUCCESS'))
    })
    cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name: 'FP5CUS_' + stamp, contact: 'C' + stamp } })
    cy.request('/getUserCustomer').then((r) => { customerId = list(r.body).find((c) => c.name === 'FP5CUS_' + stamp).customerId })
  })

  it('1 — Pay Supplier 40: a PV number at once; 2000 debited and Cash down by exactly 40', () => {
    cy.loginAsOwner()
    netByCode(t).then((before) => {
      cy.request({ method: 'POST', url: '/payVendor', form: true,
        body: { venderId: vendorId, amount: 40, method: 'CASH', idempotencyKey: 'fp5a-pv-' + stamp } }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        voucher = r.body.object.voucherNo
        expect(voucher, 'finance answered after commit, so the screen gets its number').to.match(/^PV-\d+/)
        expect(r.body.object.voucherPending, 'not pending when finance is up').to.not.eq(true)
      })
      netByCode(t).then((after) => {
        expect(delta(before, after, '2000'), '2000 Accounts Payable (debit)').to.eq(40)
        expect(delta(before, after, '1000'), '1000 Cash').to.eq(-40)
      })
    })
  })

  it('2 — the same Pay Supplier again (same key): the SAME voucher, and the books do not move', () => {
    cy.loginAsOwner()
    netByCode(t).then((before) => {
      cy.request({ method: 'POST', url: '/payVendor', form: true,
        body: { venderId: vendorId, amount: 40, method: 'CASH', idempotencyKey: 'fp5a-pv-' + stamp } }).then((r) => {
        expect(r.body.status).to.eq('SUCCESS')
        expect(r.body.object.voucherNo, 'the first voucher, answered again').to.eq(voucher)
      })
      netByCode(t).then((after) => {
        expect(delta(before, after, '2000')).to.eq(0)
        expect(delta(before, after, '1000')).to.eq(0)
      })
      cy.request({ url: `${GW}/api/finance/payments?partyType=VENDOR&partyId=${vendorId}`, headers: hdr(t) }).then((r) => {
        const pays = Array.isArray(r.body) ? r.body : (r.body.data || [])
        expect(pays.filter((p) => p.receiptNo === voucher), 'one payment in the ledger').to.have.length(1)
      })
    })
  })

  it('3 — Receive Payment 5: an RCPT number; Cash up and 1100 Receivables credited by exactly 5; a replay changes nothing', () => {
    cy.loginAsOwner()
    netByCode(t).then((before) => {
      cy.request({ method: 'POST', url: '/receivePayment', form: true,
        body: { customerId, amount: 5, method: 'CASH', idempotencyKey: 'fp5a-rc-' + stamp } }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        receipt = r.body.object.receiptNo
        expect(receipt).to.match(/^RCPT-\d+/)
      })
      netByCode(t).then((after) => {
        expect(delta(before, after, '1000'), '1000 Cash').to.eq(5)
        expect(delta(before, after, '1100'), '1100 Accounts Receivable (credit)').to.eq(-5)
      })
    })
    netByCode(t).then((before) => {
      cy.request({ method: 'POST', url: '/receivePayment', form: true,
        body: { customerId, amount: 5, method: 'CASH', idempotencyKey: 'fp5a-rc-' + stamp } })
        .its('body.object.receiptNo').should((no) => expect(no).to.eq(receipt))
      netByCode(t).then((after) => expect(delta(before, after, '1000'), 'replay: no second receipt').to.eq(0))
    })
  })

  it('4 — a school fee payment reaches the books once (education)', () => {
    cy.loginAsEduOwner()
    const en = 'FP5E' + stamp
    cy.request({ method: 'POST', url: '/addStudent', form: true, body: { name: 'FP5 ' + en, enrollNo: en, status: 'ACTIVE' } })
      .its('body').should((b) => expect(JSON.stringify(b)).to.match(/SUCCESS/))
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    cy.request('/gl/trialBalance').then((tb) => {
      const b = typeof tb.body === 'string' ? JSON.parse(tb.body) : tb.body
      const cash = (b.rows || []).find((x) => x.code === '1000') || { debit: 0, credit: 0 }
      const before = Number(cash.debit) - Number(cash.credit)
      cy.request({ method: 'POST', url: '/addFc', form: true, body: { enrollNo: en, fee: 700, dueAmount: 700, feePaid: 700, receivedIn: 'Cash' } })
        .its('body').should((r) => expect(JSON.stringify(r)).to.match(/SUCCESS/))
      const settled = (n = 15) => cy.request('/gl/trialBalance').then((tb2) => {
        const a = typeof tb2.body === 'string' ? JSON.parse(tb2.body) : tb2.body
        const c2 = (a.rows || []).find((x) => x.code === '1000') || { debit: 0, credit: 0 }
        const moved = Math.round((Number(c2.debit) - Number(c2.credit) - before) * 100) / 100
        if (moved === 700 || n <= 0) return moved
        cy.wait(1000)
        return settled(n - 1)
      })
      settled().should('eq', 700)
    })
  })
})
