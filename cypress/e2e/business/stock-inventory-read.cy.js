/**
 * M3.1 (slice 62) — the Stock list shows INVENTORY on-hand (the saga's source of truth), not local Stock.
 * Register a product (master-sync projects the Item), stock it in inventory, then getUserStock reports that on-hand.
 * Run headed.
 */
describe('Stock screen reads inventory on-hand (M3.1)', () => {
  let productId
  const pname = 'StkRead_' + Date.now()

  beforeEach(() => cy.loginAsBusiness())

  it('getUserStock reflects inventory on-hand for a stocked product', () => {
    cy.request({ method: 'POST', url: '/addProduct', body: { name: pname, sku: 'SR' + Date.now(), sellingPrice: 9, taxRate: 0, unit: 'pcs' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => { expect(r.body.success, JSON.stringify(r.body)).to.eq(true); productId = r.body.data.id })

    cy.then(() => {
      cy.request({ method: 'POST', url: '/addProductStock', body: { productId, quantity: 7 }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      cy.request('/getUserStock').then((r) => {
        const items = r.body.object || r.body.collection || r.body.data || []
        const mine = items.find((i) => i.iname === pname)
        expect(mine, 'the projected item is listed').to.exist
        expect(mine.stock, 'has a stock block').to.exist
        expect(Number(mine.stock.stock), 'on-hand sourced from inventory').to.eq(7)
      })
    })
  })
})
