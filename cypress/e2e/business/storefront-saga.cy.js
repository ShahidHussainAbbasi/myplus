/**
 * E-commerce E7 (slice 49) — a storefront order reserves + confirms stock through the SAME inventory saga POS
 * uses, so an online sale decrements on-hand. We stock a fresh catalog product to 10, place a guest order for 2
 * (on-hand → 8), then prove an order beyond what's left is rejected (out of stock → no order). Run headed + slow.
 */
describe('E-commerce — storefront order decrements inventory via the saga', () => {
  let orgId, productId
  const pname = 'SagaShop_' + Date.now()

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => {
      const o = (r.body.collection || [])[0] || {}
      orgId = o.id || o.organizationId || o.orgId
    })
    // 1) a catalog product, 2) stock it to 10 (opening inventory the saga can draw down).
    cy.request({ method: 'POST', url: '/addProduct', body: { name: pname, sku: 'SAGA' + Date.now(), sellingPrice: 20, taxRate: 0, unit: 'pcs' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        productId = r.body.data.id
        return cy.request({ method: 'POST', url: '/addProductStock', body: { productId, quantity: 10 }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      })
      .then((r) => { expect(r.body.success, JSON.stringify(r.body)).to.eq(true) })
  })

  // testIsolation clears the session between tests; /productStock + /getOrders need auth, so re-login each test.
  beforeEach(() => cy.loginAsMarketplace())

  const stockLevel = () => cy.request('/productStock?productId=' + productId).then((r) => parseFloat(r.body.stock))
  const checkout = (qty, name) => cy.request({
    method: 'POST', url: '/storefront/checkout',
    body: { organizationId: orgId, customerName: name, customerContact: '0300SAGA', shippingAddress: '9 Saga St', total: 20 * qty, paymentMode: 'COD', items: [{ productId, quantity: qty, price: 20 }] },
    headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
  })

  it('the product starts with the opening stock of 10', () => {
    expect(productId, 'product created').to.exist
    stockLevel().then((s) => expect(s, 'opening on-hand').to.eq(10))
  })

  it('a storefront order reserves + confirms, dropping on-hand by the quantity sold', () => {
    checkout(2, 'SagaBuyer_' + Date.now()).then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.source).to.eq('STOREFRONT')
      expect(r.body.data.reservationId, 'order carries the inventory hold').to.be.a('string')
    })
    // confirm is inline (no relay), so on-hand should be 8 — poll briefly to be safe.
    const poll = (tries) => stockLevel().then((s) => {
      if (s <= 8 || tries <= 0) { expect(s, 'saga decremented on-hand').to.eq(8) }
      else { cy.wait(800); poll(tries - 1) }
    })
    poll(6)
  })

  it('an order beyond available stock is rejected — nothing is created or decremented', () => {
    const buyer = 'OverBuyer_' + Date.now()
    checkout(9999, buyer).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(false)   // out of stock blocks the order
    })
    // on-hand unchanged (still 8); and the blocked order is not in the back-office.
    stockLevel().then((s) => expect(s, 'no decrement on a rejected order').to.eq(8))
    cy.loginAsMarketplace()
    cy.request('/getOrders').then((r) => {
      const mine = (r.body.data || []).find((o) => o.customerName === buyer)
      expect(mine, 'rejected order must not exist').to.not.exist
    })
  })
})
