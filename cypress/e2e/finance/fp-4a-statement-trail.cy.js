/**
 * FP-4a — finance's payables subledger holds the supplier STATEMENT trail, line for line with business's.
 *
 * Design: microservices/docs/slices/fp-4-payables-reads-switch.md §3.
 *
 * <h3>What "the same" means here</h3>
 * finance's statement with {@code sources=PURCHASE} is business's view (purchases and their payments). Every line —
 * date, number, type, debit, credit, running balance — must equal business's {@code /vendorStatement}. That is the
 * property FP-4b relies on when a tenant's supplier screens start reading finance instead.
 *
 * <h3>Delivery is asynchronous</h3>
 * Business reports a purchase to finance after its commit (outbox). Each comparison polls (bounded) until the two
 * agree, and the failure message shows the first line that differs.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const OWNER = 'owner.business@myplus.com'
const CAP = 'expenseManagement'

const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const token = () => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email: OWNER, password: PW } })
  .its('body.data.accessToken')
const today = () => localIsoDate()
const key = () => `fp4a-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

/** A statement line in one comparable shape (dates may arrive as "yyyy-MM-dd" or [y, m, d]). */
const norm = (l) => {
  const d = Array.isArray(l.date) ? l.date.map((x, i) => (i ? String(x).padStart(2, '0') : x)).join('-') : (l.date || '')
  const n = (v) => (v === null || v === undefined ? null : Math.round(Number(v) * 100) / 100)
  return `${d}|${l.docNo || ''}|${l.type}|${n(l.debit)}|${n(l.credit)}|${n(l.balance)}`
}
const businessLines = (vid) => cy.request('/vendorStatement?venderId=' + vid).then((r) => {
  expect(r.body.status, `business statement ${vid}`).to.eq('SUCCESS')
  return (r.body.collection || r.body.data || []).map(norm)
})
const financeLines = (t, vid, purchasesOnly = true) =>
  cy.request({ url: `${GW}/api/finance/payables/statement?partyType=VENDOR&partyId=${vid}${purchasesOnly ? '&sources=PURCHASE' : ''}`,
    headers: hdr(t) }).then((r) => r.body.map(norm))

/** Poll until both statements agree (the outbox delivers after commit); then assert, showing the first difference. */
const agree = (t, vid, tries = 20) => businessLines(vid).then((b) => financeLines(t, vid).then((f) => {
  const same = b.length === f.length && b.every((x, i) => x === f[i])
  if (same || tries <= 0) {
    const i = b.findIndex((x, k) => x !== f[k])
    expect(same, `supplier ${vid}: line ${i} business «${b[i]}» vs finance «${f[i]}» (${b.length} vs ${f.length} lines)`).to.eq(true)
    return b
  }
  cy.wait(1000)
  return agree(t, vid, tries - 1)
}))

describe('FP-4a — the supplier statement trail lives in finance', () => {
  let t = null
  let vendorId = null
  let purchaseId = null

  before(() => {
    cy.loginAsOwner()
    token().then((x) => { t = x })
  })

  after(() => {
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } })
  })

  it('1 — every supplier of this business: finance’s statement = business’s, line for line', () => {
    cy.loginAsOwner()
    cy.request('/getUserVender').then((r) => {
      const vendors = (r.body.collection || r.body.data || []).map((v) => v.id)
      expect(vendors.length, 'the tenant has suppliers to compare').to.be.greaterThan(0)
      cy.wrap(vendors).each((vid) => agree(t, vid, 3))
    })
  })

  it('2 — a credit purchase, a return and a payment: the same BILL, DEBIT_NOTE and PAYMENT lines on both', () => {
    cy.loginAsOwner()
    const stamp = Date.now()
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'FP4Co_' + stamp, email: `fp4${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const company = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'FP4Co_' + stamp)
      cy.request({ method: 'POST', url: '/addVender', form: true,
        body: { name: 'FP4VEN_' + stamp, companyId: company.id, mobile: '0302' + String(stamp).slice(-7), email: `fp4v${stamp}@t.com` } })
      cy.request('/getUserVender').then((lr) => {
        vendorId = (lr.body.collection || lr.body.data || []).find((x) => x.name === 'FP4VEN_' + stamp).id
        cy.seedProduct({ name: 'FP4P_' + stamp, sellingPrice: 100, stock: 1 }).then(({ productId }) => {
          cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
            body: { productId, quantity: 10, venderId: vendorId, paidAmount: 0, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 12,
              totalAmount: 100, netAmount: 100, purchaseInvoiceNo: 'FP4INV-' + stamp } })
            .its('body.status').should('eq', 'SUCCESS')
          cy.request('/getUserPurchase').then((pr) => {
            const rows = pr.body.collection || pr.body.data || pr.body.object || []
            purchaseId = (rows.find((p) => p.purchaseInvoiceNo === 'FP4INV-' + stamp) || {}).purchaseId
            expect(purchaseId, 'the purchase').to.exist
            cy.request({ method: 'POST', url: '/purchaseReturn', form: true, body: { purchaseId, quantity: 3, reason: 'FP-4a gate' } })
              .its('body.status').should('eq', 'SUCCESS')
            cy.request({ method: 'POST', url: '/payVendor', form: true, body: { venderId: vendorId, amount: 20, method: 'CASH' } })
              .its('body.status').should('eq', 'SUCCESS')
            agree(t, vendorId).then((lines) => {
              const types = lines.map((l) => l.split('|')[2])
              expect(types, 'the trail').to.include.members(['BILL', 'DEBIT_NOTE', 'PAYMENT'])
              expect(lines[0].split('|')[3], 'the bill AS ISSUED, not what is left after the return').to.eq('100')
            })
          })
        })
      })
    })
  })

  it('3 — voiding the purchase: a debit note for the rest on both, and the trail still agrees', () => {
    cy.loginAsOwner()
    expect(purchaseId, 'the purchase from case 2').to.exist
    cy.request({ method: 'POST', url: '/voidPurchase', form: true, failOnStatusCode: false, body: { purchaseId, reason: 'FP-4a gate' } })
      .its('body.status').should('eq', 'SUCCESS')
    agree(t, vendorId).then((lines) => {
      expect(lines.filter((l) => l.includes('|DEBIT_NOTE|')).length, 'the return and the void').to.eq(2)
    })
  })

  it('4 — an expense bill and its payment are on finance’s full statement, and not on business’s', () => {
    cy.loginAsOwner()
    cy.setCapability(CAP, true)
    token().then((t2) => {
      cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t2) }).then((cr) => {
        const cat = (cr.body.data || []).find((c) => c.active !== false) || cr.body.data[0]
        cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, headers: hdr(t2, { 'Idempotency-Key': key() }),
          body: { voucherDate: today(), paidFrom: 'AP', supplierId: vendorId, dueDate: today(), lines: [{ categoryId: cat.id, amount: 45 }] } })
          .then((r) => {
            expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
            const billNo = r.body.data.voucherNo
            const id = r.body.data.id
            const inBooks = (n = 20) => cy.request({ url: `${GW}/api/expense/vouchers/${id}`, headers: hdr(t2) }).then((v) =>
              (v.body.data.postingStatus === 'POSTED_GL' || n <= 0) ? v.body.data : (cy.wait(1000), inBooks(n - 1)))
            inBooks().its('postingStatus').should('eq', 'POSTED_GL')
            cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/pay`, headers: hdr(t2, { 'Idempotency-Key': key() }),
              body: { amount: 45, method: 'CASH' } }).then((p) => {
              expect(p.body.success, JSON.stringify(p.body)).to.eq(true)
              const pv = p.body.data.receiptNo
              const full = (n = 20) => financeLines(t2, vendorId, false).then((f) =>
                (f.some((l) => l.includes(`|${billNo}|BILL|45|`)) || n <= 0) ? f : (cy.wait(1000), full(n - 1)))
              full().then((f) => {
                expect(f.some((l) => l.includes(`|${billNo}|BILL|45|`)), `finance shows ${billNo} as a 45 bill`).to.eq(true)
                expect(f.some((l) => l.includes(`|${pv}|PAYMENT|`)), `finance shows its payment ${pv}`).to.eq(true)
              })
              businessLines(vendorId).then((b) => {
                expect(b.some((l) => l.includes(billNo) || l.includes(pv)), 'business’s purchase statement is unchanged').to.eq(false)
              })
              agree(t2, vendorId)   // and finance's purchase view still equals business's
            })
          })
      })
    })
  })
})
