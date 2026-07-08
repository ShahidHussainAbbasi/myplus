/**
 * SF-11 — a sale return writes an audit / credit-note record (invoice, line, qty, reason, refund) so returns
 * leave a trail instead of silently mutating the invoice. We sell, return one line WITH a reason, and assert the
 * record surfaces in /getSaleReturns. Requires business-service + gateway up. Run headed.
 */
describe('SF-11 — sale return is captured in the credit-note audit log', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('a return with a reason appears in /getSaleReturns', () => {
    cy.seedProduct({ name: 'CN_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: 'CNC_' + Date.now(), contact: '0300CN', paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 100,
        }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        const invoiceNo = r.body.object
        cy.request('/getUserSell').then((gs) => {
          const line = (gs.body.collection || gs.body.data || []).find((s) => s.productId === productId)
          expect(line, 'sell line').to.exist
          const reason = 'damaged-CY-' + Date.now()
          cy.request({
            method: 'POST', url: '/saleReturn', form: true,
            body: { sellId: line.sellId, quantity: 1, reason }, failOnStatusCode: false,
          }).then((rr) => expect(rr.body.status, JSON.stringify(rr.body)).to.eq('SUCCESS'))

          cy.request('/getSaleReturns').then((sr) => {
            expect(sr.body.status, JSON.stringify(sr.body)).to.eq('SUCCESS')
            const rows = sr.body.collection || sr.body.data || []
            const mine = rows.find((x) => x.reason === reason)
            expect(mine, 'audit record for this return').to.exist
            expect(mine.sellId, 'links the returned line').to.eq(line.sellId)
            expect(Number(mine.quantity), 'returned qty').to.eq(1)
            expect(mine.invoiceNo, 'links the invoice').to.eq(invoiceNo)
          })
        })
      })
    })
  })
})
