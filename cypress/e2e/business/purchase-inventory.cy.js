/**
 * M3.2 (slice 63) — a purchase makes inventory authoritative even for a legacy (unmapped) item: the purchase
 * auto-maps the item to catalog and stocks inventory, so the item becomes sellable through the saga. Run headed.
 */
describe('Purchase stock-in is authoritative in inventory (M3.2)', () => {
  let itemId
  const iname = 'PurInv_' + Date.now()

  beforeEach(() => cy.loginAsBusiness())

  it('purchasing a legacy item stocks inventory and makes it saga-sellable', () => {
    // a brand-new Item via the legacy screen — NOT catalog-mapped yet
    cy.request({ method: 'POST', url: '/addItem', form: true, body: { icode: 'PI' + Date.now(), iname, unit: 'pcs' }, failOnStatusCode: false })
    cy.request('/getUserItem').then((r) => {
      const items = r.body.collection || r.body.object || r.body.data || []
      itemId = (items.find((i) => i.iname === iname) || {}).id
      expect(itemId, 'item created').to.exist
    })

    cy.then(() => {
      // purchase 6 → auto-maps + stocks inventory. The purchase form binds NESTED stock.* fields (PurchaseDTO.stock).
      cy.request({ method: 'POST', url: '/addPurchase', form: true, body: { itemId, quantity: 6, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 15, totalAmount: 60, netAmount: 60, purchaseInvoiceNo: 'PINV-' + Date.now() }, failOnStatusCode: false })
        .then((r) => expect(r.body.status, JSON.stringify(r.body).substring(0, 200)).to.eq('SUCCESS'))

      // inventory on-hand reflects the purchase (item is now catalog-mapped)
      const poll = (t, want, label) => cy.request('/getStock?itemId=' + itemId).then((r) => {
        const s = (r.body || {}).stock
        if (Number(s) === want || t <= 0) expect(Number(s), label).to.eq(want)
        else { cy.wait(700); poll(t - 1, want, label) }
      })
      poll(6, 6, 'inventory on-hand after purchase')

      // it now sells through the saga (which REQUIRES catalog mapping + inventory stock) → on-hand drops to 4
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: { customer: { name: 'PiCust_' + Date.now(), contact: '0300PI', paidAmount: 30, dueAmount: 0 }, sales: [{ itemId, quantity: 2, sellRate: 15, totalAmount: 30, netAmount: 30 }] },
        failOnStatusCode: false,
      }).then((r) => expect(r.body.status, JSON.stringify(r.body).substring(0, 200)).to.eq('SUCCESS'))

      poll(6, 4, 'inventory on-hand after a saga sale')
    })
  })
})
