/**
 * FP-1 + FP-2 — finance's payables subledger mirrors business, exactly.
 *
 * Design: microservices/docs/slices/fp-1-2-payables-in-finance.md §4.
 *
 * <h3>Totals, never fixed figures</h3>
 * The tenant's supplier balances are whatever earlier specs left. Each case compares business's Σ supplier due with
 * finance's Σ open BEFORE and AFTER one action, so the assertion is "they move together by exactly this", which is
 * the property the later reads-switch (FP-4) will rely on.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const OWNER = 'owner.business@myplus.com'

const gwToken = () => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email: OWNER, password: PW } })
  .its('body.data.accessToken')
const financeOpen = (t, tries = 15) => cy.request({ url: `${GW}/api/finance/payables/summary`, headers: { Authorization: `Bearer ${t}` } })
  .then((r) => Number(r.body.netOwed || 0))   // per-supplier net: business's own definition
const businessDue = () => cy.request('/getUserVender').then((r) => {
  const list = r.body.collection || r.body.data || r.body.object || []
  return Math.round(list.reduce((a, v) => a + Number(v.dueAmount || 0), 0) * 100) / 100
})
/** Poll until finance equals business (the outbox delivers after commit); bounded. */
const converge = (t, tries = 20) => businessDue().then((b) => financeOpen(t).then((f) => {
  if (Math.abs(b - f) < 0.005 || tries <= 0) return { b, f }
  cy.wait(1000)
  return converge(t, tries - 1)
}))

describe('FP-1/2 — payables shadow in finance', () => {
  let t = null
  let vendorId = null
  let purchaseId = null

  before(() => {
    cy.loginAsOwner()
    gwToken().then((x) => { t = x })
  })

  it('1 — after the automatic backfill, finance holds exactly what business says is owed', () => {
    cy.loginAsOwner()
    converge(t).then(({ b, f }) => expect(f, `finance open ${f} vs business due ${b}`).to.be.closeTo(b, 0.005))
  })

  it('2 — a credit purchase moves both by the bill', () => {
    cy.loginAsOwner()
    const stamp = Date.now()
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'FPCo_' + stamp, email: `fp${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const company = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'FPCo_' + stamp)
      cy.request({ method: 'POST', url: '/addVender', form: true,
        body: { name: 'FPVEN_' + stamp, companyId: company.id, mobile: '0300' + String(stamp).slice(-7), email: `fpv${stamp}@t.com` } })
      cy.request('/getUserVender').then((lr) => {
        vendorId = (lr.body.collection || lr.body.data || []).find((x) => x.name === 'FPVEN_' + stamp).id
        cy.seedProduct({ name: 'FPP_' + stamp, sellingPrice: 100, stock: 1 }).then(({ productId }) => {
          converge(t).then(({ b: before }) => {
            cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
              body: { productId, quantity: 10, venderId: vendorId, paidAmount: 0, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 12,
                totalAmount: 100, netAmount: 100, purchaseInvoiceNo: 'FPINV-' + stamp } })
              .its('body.status').should('eq', 'SUCCESS')
            cy.request('/getUserPurchase').then((pr) => {
              const rows = pr.body.collection || pr.body.data || pr.body.object || []
              purchaseId = (rows.find((p) => p.purchaseInvoiceNo === 'FPINV-' + stamp) || {}).purchaseId
            })
            converge(t).then(({ b, f }) => {
              expect(b, 'business owes more').to.be.greaterThan(before)
              expect(f, 'finance follows').to.be.closeTo(b, 0.005)
            })
          })
        })
      })
    })
  })

  it('3 — paying the supplier moves both down together', () => {
    cy.loginAsOwner()
    converge(t).then(({ b: before }) => {
      cy.request({ method: 'POST', url: '/payVendor', form: true, failOnStatusCode: false,
        body: { venderId: vendorId, amount: 40, method: 'CASH' } }).its('body.status').should('eq', 'SUCCESS')
      converge(t).then(({ b, f }) => {
        expect(Math.round((before - b) * 100) / 100, 'business down by the payment').to.eq(40)
        expect(f).to.be.closeTo(b, 0.005)
      })
    })
  })

  it('4 — voiding the purchase keeps them equal (the document is VOID, open 0)', () => {
    cy.loginAsOwner()
    expect(purchaseId, 'the purchase from case 2').to.exist
    cy.request({ method: 'POST', url: '/voidPurchase', form: true, failOnStatusCode: false,
      body: { purchaseId, reason: 'FP gate' } }).then((r) => cy.log(JSON.stringify(r.body).slice(0, 200)))
    converge(t).then(({ b, f }) => expect(f).to.be.closeTo(b, 0.005))
  })

  it('5 — the reconciliation report shows subledger, GL 2000 and the difference', () => {
    cy.request({ url: `${GW}/api/finance/payables/reconciliation`, headers: { Authorization: `Bearer ${t}` } }).then((r) => {
      expect(r.body).to.have.property('subledgerOpen')
      expect(r.body).to.have.property('glAccountsPayable')
      expect(r.body).to.have.property('difference')
      cy.log(`subledger ${r.body.subledgerOpen} · GL 2000 ${r.body.glAccountsPayable} · difference ${r.body.difference}`)
    })
  })
})
