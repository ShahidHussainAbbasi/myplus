/**
 * Pharmacy P11 register (slice 58) — quarantined lots are listed and can be disposed. Create + stock + saga-sell a
 * product, quarantine-return some, then the lot shows in the register and Dispose removes it. Run headed.
 */
describe('Pharmacy — quarantine register (P11)', () => {
  let productId, itemId
  const pname = 'QRMed_' + Date.now()
  const cust = 'QRCust_' + Date.now()
  const batch = 'QRB' + Date.now()

  beforeEach(() => cy.loginAsPharma())

  it('a quarantined lot appears in the register and can be disposed', () => {
    cy.request({ method: 'POST', url: '/addProduct', body: { name: pname, sku: 'QR' + Date.now(), sellingPrice: 8, taxRate: 0, unit: 'tablet' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => { expect(r.body.success, JSON.stringify(r.body)).to.eq(true); productId = r.body.data.id })

    cy.request('/getUserItem').then((r) => {
      const items = r.body.collection || r.body.object || r.body.data || []
      itemId = (items.find((i) => i.iname === pname) || {}).id
      expect(itemId, 'projected Item').to.exist
    })

    cy.then(() => {
      cy.request({ method: 'POST', url: '/addProductStock', body: { productId, quantity: 20, batchNo: batch, expiryDate: '2030-11-30' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      // saga dispense of 5
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: { customer: { name: cust, contact: '0300QR', paidAmount: 40, dueAmount: 0 }, sales: [{ itemId, quantity: 5, sellRate: 8, totalAmount: 40, netAmount: 40 }] },
        failOnStatusCode: false,
      }).then((r) => expect(r.body.status, JSON.stringify(r.body).substring(0, 200)).to.eq('SUCCESS'))

      // quarantine-return 2
      cy.request('/getUserSell').then((r) => {
        const line = (r.body.collection || r.body.data || []).find((s) => s.customer && s.customer.name === cust)
        const stockId = (line.stock && line.stock.stockId != null) ? line.stock.stockId : ''
        cy.request({ method: 'POST', url: '/saleReturn', form: true, body: { sellId: line.sellId, sellSId: stockId, quantity: 2, reason: 'expired', quarantine: true }, failOnStatusCode: false })
          .then((rr) => expect(rr.body.status).to.eq('SUCCESS'))
      })

      // the lot is in the quarantine register
      let lotId
      cy.request('/quarantineList').then((r) => {
        const mine = (r.body.items || []).find((q) => q.productId === productId && q.batchNo === batch)
        expect(mine, 'quarantined lot listed').to.exist
        expect(Number(mine.quantity)).to.eq(2)
        lotId = mine.id
      })

      // dispose it → gone
      cy.then(() => cy.request({ method: 'POST', url: '/disposeQuarantine', body: { id: lotId }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
        .then((r) => expect(r.body.success).to.eq(true)))
      cy.request('/quarantineList').then((r) => {
        const still = (r.body.items || []).find((q) => q.id === lotId)
        expect(still, 'disposed lot removed').to.not.exist
      })
    })
  })
})
