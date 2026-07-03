/**
 * M3.1 (slice 62; M4e.d productId-native) — the stock readout shows INVENTORY on-hand (the saga's source of
 * truth). Register a catalog Product, stock it in inventory, then /productStock reports that on-hand by productId.
 * The legacy /getUserStock (Item projection) is retired. Run headed.
 */
describe('Stock screen reads inventory on-hand (M3.1)', () => {
  let productId
  const pname = 'StkRead_' + Date.now()

  beforeEach(() => cy.loginAsBusiness())

  it('productStock reflects inventory on-hand for a stocked product', () => {
    cy.request({ method: 'POST', url: '/addProduct', body: { name: pname, sku: 'SR' + Date.now(), sellingPrice: 9, taxRate: 0, unit: 'pcs' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => { expect(r.body.success, JSON.stringify(r.body)).to.eq(true); productId = r.body.data.id })

    cy.then(() => {
      cy.request({ method: 'POST', url: '/addProductStock', body: { productId, quantity: 7 }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      cy.request('/productStock?productId=' + productId).then((r) => {
        expect(Number(r.body.stock), 'on-hand sourced from inventory').to.eq(7)
      })
    })
  })
})
