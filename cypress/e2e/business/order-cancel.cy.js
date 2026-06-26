/**
 * E-commerce E7 cancel (slice 50.2) — cancelling an order returns its stock to inventory via the G2 inverse saga
 * (the reservation was confirmed at placement, so on cancel we return the order's lines). Re-cancel is idempotent.
 * Run headed.
 */
describe('E-commerce — order cancel returns stock', () => {
  let orgId, productId
  const pname = 'CancelShop_' + Date.now()

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    cy.request({ method: 'POST', url: '/addProduct', body: { name: pname, sku: 'CAN' + Date.now(), sellingPrice: 30, taxRate: 0, unit: 'pcs' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => {
        productId = r.body.data.id
        return cy.request({ method: 'POST', url: '/addProductStock', body: { productId, quantity: 10 }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      })
  })

  beforeEach(() => cy.loginAsMarketplace())

  const stockLevel = () => cy.request('/productStock?productId=' + productId).then((r) => parseFloat(r.body.stock))
  const place = (qty, name) => cy.request({
    method: 'POST', url: '/storefront/checkout',
    body: { organizationId: orgId, customerName: name, customerContact: '0300CAN', shippingAddress: '7 Cancel St', total: 30 * qty, paymentMode: 'COD', items: [{ productId, quantity: qty, price: 30 }] },
    headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
  })
  const cancel = (id) => cy.request({ method: 'POST', url: '/updateOrderStatus', body: { id, status: 'CANCELLED' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })

  it('cancelling a storefront order restores its stock and marks it CANCELLED', () => {
    let before, orderId
    stockLevel().then((s) => { before = s })
    place(3, 'CancelBuyer_' + Date.now()).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      orderId = r.body.data.id
    })
    stockLevel().then((s) => expect(s, 'decremented by the order').to.eq(before - 3))
    cy.then(() => cancel(orderId).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true)))
    stockLevel().then((s) => expect(s, 'restored by the cancel').to.eq(before))
    cy.request('/getOrders').then((r) => {
      const o = (r.body.data || []).find((x) => x.id === orderId)
      expect(o.fulfilmentStatus).to.eq('CANCELLED')
    })
  })

  it('re-cancelling does not return stock twice', () => {
    let before, orderId
    stockLevel().then((s) => { before = s })
    place(2, 'TwiceBuyer_' + Date.now()).then((r) => { orderId = r.body.data.id })
    cy.then(() => cancel(orderId))
    cy.then(() => cancel(orderId))   // already cancelled — must be a no-op for stock
    stockLevel().then((s) => expect(s, 'restored exactly once').to.eq(before))
  })

  it('the back-office Cancel button cancels and returns stock (UI)', () => {
    cy.on('window:confirm', () => true)
    cy.intercept('POST', '/updateOrderStatus').as('cancel')
    const buyer = 'UICancel_' + Date.now()
    let before
    stockLevel().then((s) => { before = s })
    place(1, buyer)
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showOrders')
    cy.window().then((w) => w.showOrders())
    cy.contains('#ordersBody tr', buyer, { timeout: 10000 }).find('button.btn-danger').click()
    cy.wait('@cancel').its('response.body.success').should('eq', true)
    cy.then(() => stockLevel().then((s) => expect(s, 'UI cancel restored stock').to.eq(before)))
  })
})
