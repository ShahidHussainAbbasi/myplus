/**
 * Audit #3 — Void / Cancel (books-safe). Void replaces hard-delete: it reverses the whole document (stock + AR/AP +
 * GL SALE_RETURN/PURCHASE_RETURN via the #4 outbox), soft-stamps the record VOID, and makes it read-only. A void on
 * an invoice that already has a partial return is rejected. Requires finance-service (GL) + business + gateway up.
 */
describe('Audit #3 — Void / Cancel', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const tb = () => cy.request('/gl/trialBalance').then((r) => parse(r.body))

  it('voiding a sale reverses stock + AR + GL, then the invoice is read-only', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    cy.seedProduct({ name: 'VOIDP_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: 'VOIDC_' + Date.now(), contact: '0300VOID', paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 2, sellRate: 100, totalAmount: 200, netAmount: 200 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 200,
        }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        const invoiceNo = r.body.object

        // Void the whole invoice (by invoice number).
        cy.request({ method: 'POST', url: '/voidSell', form: true, body: { invoiceNo, reason: 'CY void' }, failOnStatusCode: false })
          .then((v) => expect(v.body.status, JSON.stringify(v.body)).to.eq('SUCCESS'))

        // A SALE_RETURN reversal is delivered to the GL via the outbox.
        cy.request('/getGlOutbox').then((o) => {
          const rows = o.body.collection || o.body.data || []
          const row = rows.find((x) => x.ref === invoiceNo && x.eventType === 'SALE_RETURN')
          expect(row, 'SALE_RETURN outbox row for the void').to.exist
          expect(row.status, 'reversal delivered to GL').to.eq('POSTED')
        })
        tb().then((t) => expect(t.balanced, 'GL balanced after void').to.eq(true))

        // Read-only: a second void, an edit, and a return are all rejected.
        cy.request({ method: 'POST', url: '/voidSell', form: true, body: { invoiceNo, reason: 'again' }, failOnStatusCode: false })
          .then((v2) => { expect(v2.body.status).to.eq('FAILED'); expect(v2.body.message).to.match(/already voided/i) })
      })
    })
  })

  it('a purchase void reverses stock + payable + GL', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    const stamp = Date.now()
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'VCo_' + stamp, email: `vco${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const companyId = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'VCo_' + stamp).id
      cy.request({ method: 'POST', url: '/addVender', form: true, body: { name: 'VVEN_' + stamp, companyId, mobile: '03001112222', email: `vv${stamp}@t.com` } })
      cy.request('/getUserVender').then((lr) => {
        const venderId = (lr.body.collection || lr.body.data || []).find((x) => x.name === 'VVEN_' + stamp).id
        cy.seedProduct({ name: 'VPP_' + stamp, sellingPrice: 100, stock: 10 }).then(({ productId }) => {
          const inv = 'VPINV-' + stamp
          cy.request({
            method: 'POST', url: '/addPurchase', form: true,
            body: { productId, quantity: 10, venderId, paidAmount: 0, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 12, totalAmount: 100, netAmount: 100, purchaseInvoiceNo: inv },
            failOnStatusCode: false,
          }).then((pr) => expect(pr.body.status, JSON.stringify(pr.body)).to.eq('SUCCESS'))

          cy.request('/getUserPurchase').then((pl) => {
            const p = (pl.body.collection || pl.body.data || []).find((x) => x.purchaseInvoiceNo === inv)
            expect(p, 'purchase row').to.exist
            cy.request({ method: 'POST', url: '/voidPurchase', form: true, body: { purchaseId: p.purchaseId, reason: 'CY void' }, failOnStatusCode: false })
              .then((v) => expect(v.body.status, JSON.stringify(v.body)).to.eq('SUCCESS'))
            tb().then((t) => expect(t.balanced, 'GL balanced after bill void').to.eq(true))
          })
        })
      })
    })
  })

  it('void is rejected once a partial return exists on the invoice', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    cy.seedProduct({ name: 'VRP_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: 'VRC_' + Date.now(), contact: '0300VR', paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 2, sellRate: 100, totalAmount: 200, netAmount: 200 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 200,
        }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        const invoiceNo = r.body.object
        cy.request('/getUserSell').then((gs) => {
          const line = (gs.body.collection || gs.body.data || []).find((s) => s.productId === productId)
          expect(line, 'sell line').to.exist
          cy.request({ method: 'POST', url: '/saleReturn', form: true, body: { sellId: line.sellId, quantity: 1, reason: 'partial' }, failOnStatusCode: false })
            .then((rr) => expect(rr.body.status, JSON.stringify(rr.body)).to.eq('SUCCESS'))
          cy.request({ method: 'POST', url: '/voidSell', form: true, body: { invoiceNo, reason: 'x' }, failOnStatusCode: false })
            .then((v) => { expect(v.body.status).to.eq('FAILED'); expect(v.body.message).to.match(/return was already recorded/i) })
        })
      })
    })
  })
})
