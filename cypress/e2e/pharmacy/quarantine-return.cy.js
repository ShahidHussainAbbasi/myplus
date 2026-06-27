/**
 * Pharmacy P11 (slice 55) — a returned medicine is quarantined, not put back on the shelf. After a saga dispense,
 * a quarantine return must NOT raise sellable on-hand (the returned units can't be re-dispensed). Run headed.
 */
describe('Pharmacy — quarantine returns (P11)', () => {
  let productId, itemId
  const pname = 'QMed_' + Date.now()
  const cust = 'QCust_' + Date.now()

  beforeEach(() => cy.loginAsPharma())

  const onHand = () => cy.request('/productStock?productId=' + productId).then((r) => parseFloat(r.body.stock))

  it('a quarantined return keeps the stock off the sellable shelf', () => {
    cy.request({ method: 'POST', url: '/addProduct', body: { name: pname, sku: 'QM' + Date.now(), sellingPrice: 10, taxRate: 0, unit: 'tablet' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => { expect(r.body.success, JSON.stringify(r.body)).to.eq(true); productId = r.body.data.id })

    cy.request('/getUserItem').then((r) => {
      const items = r.body.collection || r.body.object || r.body.data || []
      const mine = items.find((i) => i.iname === pname)
      expect(mine, 'projected Item (master-sync)').to.exist
      itemId = mine.id
    })

    cy.then(() => {
      cy.request({ method: 'POST', url: '/addProductStock', body: { productId, quantity: 20, batchNo: 'QB' + Date.now(), expiryDate: '2030-12-31' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      onHand().then((s) => expect(s, 'opening on-hand').to.eq(20))

      // saga dispense of 5 → on-hand 15
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: { customer: { name: cust, contact: '0300QQ', paidAmount: 50, dueAmount: 0 }, sales: [{ itemId, quantity: 5, sellRate: 10, totalAmount: 50, netAmount: 50 }] },
        failOnStatusCode: false,
      }).then((r) => expect(r.body.status, JSON.stringify(r.body).substring(0, 200)).to.eq('SUCCESS'))

      const pollSold = (t) => onHand().then((s) => { if (s <= 15 || t <= 0) expect(s, 'sale decremented').to.eq(15); else { cy.wait(800); pollSold(t - 1) } })
      pollSold(6)

      // find the sell line to return
      cy.request('/getUserSell').then((r) => {
        const sells = r.body.collection || r.body.data || r.body.object || []
        const line = sells.find((s) => s.customer && s.customer.name === cust)
        expect(line, 'the dispense sell line').to.exist
        const stockId = (line.stock && line.stock.stockId != null) ? line.stock.stockId : ''
        cy.request({
          method: 'POST', url: '/saleReturn', form: true,
          body: { sellId: line.sellId, sellSId: stockId, quantity: 3, reason: 'patient return', quarantine: true },
          failOnStatusCode: false,
        }).then((rr) => expect(rr.body.status, JSON.stringify(rr.body)).to.eq('SUCCESS'))
      })

      // on-hand stays 15 — the 3 returned units are quarantined, NOT restocked (would be 18 if restocked)
      onHand().then((s) => expect(s, 'quarantined return does not raise sellable on-hand').to.eq(15))
    })
  })
})
