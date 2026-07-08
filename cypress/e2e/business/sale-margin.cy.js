/**
 * SF-10 — the sell line snapshots unit COST (from the product's latest purchase rate) so the Sale Detail Report
 * can show true per-line margin (= net − cost×qty). We purchase a product at a known cost, sell it, and assert the
 * captured costPrice + the derived margin on the receipt line (the report reads the same field).
 * Requires business-service + gateway up. Run headed.
 */
describe('SF-10 — sell line captures cost; margin = net − cost×qty', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('a purchased-then-sold product carries costPrice, and margin derives from it', () => {
    cy.seedProduct({ name: 'MGN_' + Date.now(), sellingPrice: 100, stock: 10 }).then(({ productId }) => {
      // record a purchase so the product has a known unit cost (bpurchaseRate = 60) — the SF-10 cost source
      cy.request({
        method: 'POST', url: '/addPurchase', form: true,
        body: {
          productId, quantity: 10,
          'stock.bpurchaseRate': 60, 'stock.bsellRate': 100,
          totalAmount: 600, netAmount: 600, purchaseInvoiceNo: 'PINV-MGN-' + Date.now(),
        }, failOnStatusCode: false,
      }).then((p) => expect(p.body.status, JSON.stringify(p.body)).to.eq('SUCCESS'))

      // sell 2 units @ 100, net 200 (ex-tax)
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: 'MGNC_' + Date.now(), contact: '0300MGN', paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 2, sellRate: 100, totalAmount: 200, netAmount: 200 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 200,
        }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(r.body.object)).then((rc) => {
          const inv = rc.body.object || rc.body.data
          const line = (inv.sales || []).find((s) => s.productId === productId)
          expect(line, 'sell line on receipt').to.exist
          expect(Number(line.costPrice), 'unit cost snapshot from the purchase (bpurchaseRate)').to.eq(60)
          // margin = net − cost×qty = 200 − 60×2 = 80 (tax-agnostic: netAmount is ex-tax)
          const margin = Number(line.netAmount) - Number(line.costPrice) * Number(line.quantity)
          expect(margin, 'per-line margin').to.eq(80)
        })
      })
    })
  })
})
