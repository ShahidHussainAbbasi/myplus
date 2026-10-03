/**
 * FP-5b — one Pay Supplier settles purchases AND expense bills, oldest first, as ONE payment in the books.
 *
 * Design: microservices/docs/slices/fp-5-one-settlement-path.md §5 (rulings 1–4).
 *
 * <h3>Tenant</h3>
 * owner.payables@ — seeded for exactly this (ruling 2026-10-04): a mixed payment pins a tenant to finance figures for
 * good (ruling 4), so it must not be a tenant other specs switch back. Every run makes a NEW supplier, so earlier runs
 * cannot change what this one measures.
 *
 * <h3>Oldest first, any kind</h3>
 * The bill is dated YESTERDAY and the purchase TODAY, so a 250 payment must go to the bill first — the order a
 * purchases-first implementation would get wrong.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const PAYABLES = 'owner.payables@myplus.com'
const CAP = 'expenseManagement'

const signIn = (fresh) => cy.loginAs(PAYABLES, PW, '/getBusinessDashboardStats', fresh ? 'fp5b-' + Date.now() : undefined)
const token = () => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email: PAYABLES, password: PW } })
  .its('body.data.accessToken')
const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const list = (b) => b.collection || b.data || b.object || []
const netByCode = (t) => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: hdr(t) }).then((r) => {
  expect(r.body.balanced, 'trial balance is balanced').to.eq(true)
  const m = {}
  ;(r.body.rows || []).forEach((row) => { m[row.code] = Number(row.debit || 0) - Number(row.credit || 0) })
  return m
})
const delta = (b, a, code) => Math.round(((a[code] || 0) - (b[code] || 0)) * 100) / 100
const day = (offset) => { const d = new Date(); d.setDate(d.getDate() + offset); return d.toISOString().slice(0, 10) }
const flip = (orgId, source, reason) =>
  cy.request({ method: 'POST', url: '/platform/payablesSource', form: true, failOnStatusCode: false, body: { organizationId: orgId, source, reason } }).its('body')

describe('FP-5b — one Pay Supplier for purchases and expense bills', () => {
  const stamp = Date.now()
  let org = null
  let t = null
  let vendorId = null
  let billId = null
  let voucher = null

  /** The bill as expense-service sees it now (paid / open), polled — its share arrives by outbox after commit. */
  const bill = (want, n = 25) => cy.request({ url: `${GW}/api/expense/vouchers/${billId}`, headers: hdr(t) }).then((r) => {
    const v = r.body.data
    if (want === undefined || Number(v.paidAmount) === want || n <= 0) return v
    cy.wait(1000)
    return bill(want, n - 1)
  })
  const vendorDue = () => cy.request('/getUserVender').then((r) => Number((list(r.body).find((v) => v.id === vendorId) || {}).dueAmount || 0))

  before(() => {
    cy.loginAsOperator()
    cy.orgOf(PAYABLES).then((o) => { org = o.id })
    cy.then(() => flip(org, 'FINANCE', 'FP-5b gate')).its('status').should('eq', 'SUCCESS')
    signIn()
    cy.setCapability(CAP, true)
    signIn(true)
    token().then((x) => { t = x })
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'FP5bCo_' + stamp, email: `fp5b${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const company = list(cr.body).find((c) => c.name === 'FP5bCo_' + stamp)
      cy.request({ method: 'POST', url: '/addVender', form: true,
        body: { name: 'FP5bVEN_' + stamp, companyId: company.id, mobile: '0307' + String(stamp).slice(-7), email: `fp5bv${stamp}@t.com` } })
      cy.request('/getUserVender').then((lr) => { vendorId = list(lr.body).find((x) => x.name === 'FP5bVEN_' + stamp).id })
    })
    // the BILL (300), dated yesterday — the older document
    cy.then(() => cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) })).then((cr) => {
      const cat = (cr.body.data || []).find((c) => c.active !== false)
      cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, headers: hdr(t, { 'Idempotency-Key': 'fp5b-bill-' + stamp }),
        body: { voucherDate: day(-1), paidFrom: 'AP', supplierId: vendorId, lines: [{ categoryId: cat.id, amount: 300 }] } })
        .then((r) => { expect(r.body.success, JSON.stringify(r.body)).to.eq(true); billId = r.body.data.id })
    })
    const inBooks = (n = 25) => cy.request({ url: `${GW}/api/expense/vouchers/${billId}`, headers: hdr(t) }).then((r) =>
      (r.body.data.postingStatus === 'POSTED_GL' || n <= 0) ? r.body.data : (cy.wait(1000), inBooks(n - 1)))
    cy.then(() => inBooks()).its('postingStatus').should('eq', 'POSTED_GL')
    // the PURCHASE (100) on credit, today — the newer document
    cy.seedProduct({ name: 'FP5bP_' + stamp, sellingPrice: 101, stock: 1 }).then(({ productId }) =>
      cy.request({ method: 'POST', url: '/addPurchase', form: true, body: { productId, quantity: 1, venderId: vendorId, paidAmount: 0,
        'stock.bpurchaseRate': 100, 'stock.bsellRate': 101, totalAmount: 100, netAmount: 100, purchaseInvoiceNo: 'FP5bINV-' + stamp } })
        .its('body.status').should('eq', 'SUCCESS'))
  })

  after(() => {
    signIn()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } })
  })

  it('1 — pay 250: the OLDER bill takes it all (oldest first, any kind); ONE voucher; 2000 and Cash move by exactly 250', () => {
    signIn()
    netByCode(t).then((before) => {
      cy.request({ method: 'POST', url: '/payVendor', form: true,
        body: { venderId: vendorId, amount: 250, method: 'CASH', idempotencyKey: 'fp5b-pay1-' + stamp } }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        voucher = r.body.object.voucherNo
        expect(voucher).to.match(/^PV-\d+/)
        expect(Number(r.body.object.appliedToBills), 'the bill is older, so it takes the whole 250').to.eq(250)
      })
      netByCode(t).then((after) => {
        expect(delta(before, after, '2000'), '2000 debited once').to.eq(250)
        expect(delta(before, after, '1000'), 'Cash').to.eq(-250)
      })
    })
    bill(250).then((v) => {
      expect(Number(v.paidAmount), 'bill paid 250').to.eq(250)
      expect(Number(v.openAmount), 'bill still owes 50').to.eq(50)
    })
    vendorDue().should('eq', 100)   // the newer purchase is untouched
  })

  it('2 — the same Pay Supplier again: the same voucher, the books do not move, the bill is not paid twice', () => {
    signIn()
    netByCode(t).then((before) => {
      cy.request({ method: 'POST', url: '/payVendor', form: true,
        body: { venderId: vendorId, amount: 250, method: 'CASH', idempotencyKey: 'fp5b-pay1-' + stamp } })
        .its('body.object.voucherNo').should('eq', voucher)
      netByCode(t).then((after) => expect(delta(before, after, '2000')).to.eq(0))
    })
    cy.wait(3000)
    bill().its('paidAmount').then((p) => expect(Number(p)).to.eq(250))
  })

  it('3 — pay 150: the bill’s last 50, then the purchase’s 100; the bill reads Paid, the supplier owes nothing', () => {
    signIn()
    cy.request({ method: 'POST', url: '/payVendor', form: true,
      body: { venderId: vendorId, amount: 150, method: 'CASH', idempotencyKey: 'fp5b-pay2-' + stamp } }).then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      expect(Number(r.body.object.appliedToBills)).to.eq(50)
    })
    bill(300).then((v) => expect(Number(v.openAmount)).to.eq(0))
    vendorDue().should('eq', 0)
    // finance's statement: the two payments, each once
    cy.request({ url: `${GW}/api/finance/payables/statement?partyType=VENDOR&partyId=${vendorId}`, headers: hdr(t) }).then((r) => {
      const pays = r.body.filter((l) => l.type === 'PAYMENT')
      expect(pays, 'two payments, no duplicates').to.have.length(2)
      expect(Math.round(Number(r.body[r.body.length - 1].balance) * 100) / 100, 'nothing owed').to.eq(0)
    })
  })

  it('4 — ruling 4: switching this business back to BUSINESS figures is refused, and the console says why', () => {
    cy.loginAsOperator()
    cy.then(() => flip(org, 'BUSINESS', 'should be refused')).then((b) => {
      expect(b.status).to.eq('ERROR')
      expect(b.message).to.match(/supplier payment share/i)
    })
    cy.request(`/platform/payablesSource?organizationId=${org}`).its('body.object').then((d) => {
      expect(d.source).to.eq('FINANCE')
      expect(Number(d.mixedPayments)).to.be.greaterThan(0)
    })
  })

  it('5 — a business on BUSINESS figures pays exactly as before: purchases only', () => {
    cy.loginAsOwner()
    const s = Date.now()
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'FP5bB_' + s, email: `fp5bb${s}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const company = list(cr.body).find((c) => c.name === 'FP5bB_' + s)
      cy.request({ method: 'POST', url: '/addVender', form: true, body: { name: 'FP5bBV_' + s, companyId: company.id, mobile: '0308' + String(s).slice(-7), email: `fp5bbv${s}@t.com` } })
      cy.request('/getUserVender').then((lr) => {
        const vid = list(lr.body).find((x) => x.name === 'FP5bBV_' + s).id
        cy.seedProduct({ name: 'FP5bBP_' + s, sellingPrice: 51, stock: 1 }).then(({ productId }) => {
          cy.request({ method: 'POST', url: '/addPurchase', form: true, body: { productId, quantity: 1, venderId: vid, paidAmount: 0,
            'stock.bpurchaseRate': 50, 'stock.bsellRate': 51, totalAmount: 50, netAmount: 50, purchaseInvoiceNo: 'FP5bBINV-' + s } })
          cy.request({ method: 'POST', url: '/payVendor', form: true, body: { venderId: vid, amount: 50, method: 'CASH', idempotencyKey: 'fp5b-b-' + s } })
            .then((r) => {
              expect(r.body.status).to.eq('SUCCESS')
              expect(Number(r.body.object.appliedToBills || 0), 'no bills on BUSINESS').to.eq(0)
            })
        })
      })
    })
  })
})
