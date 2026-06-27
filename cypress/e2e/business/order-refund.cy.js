/**
 * E-commerce (slice 70, E6) — refunds. A card-paid storefront order can be refunded from the back-office; the order
 * flips to REFUNDED with the refunded amount. A COD order (settled in cash) cannot be refunded. Run headed.
 */
describe('E-commerce — refunds (slice 70, E6)', () => {
  let orgId, productId
  const tag = Date.now()
  const name = 'RefundProd_' + tag

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    cy.request({ method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name, sku: 'RF' + tag, sellingPrice: 10, taxRate: 0, unit: 'pcs' } })
    cy.then(() => cy.request('/storefront/products?org=' + orgId).then((r) => {
      const p = (r.body.data || []).find((x) => x.name === name)
      productId = p && p.id
      expect(productId, 'product created').to.be.ok
    }))
    cy.then(() => cy.request({ method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { productId, quantity: 10 } }))
  })

  beforeEach(() => cy.loginAsMarketplace())   // /refundOrder needs auth; testIsolation clears the session

  const cartFor = (qty) => cy.request({ method: 'POST', url: '/storefront/cart/add', headers: { 'Content-Type': 'application/json' },
    body: { organizationId: orgId, productId, quantity: qty } }).then((r) => r.body.data.cartToken)

  it('fully refunds a card-paid order', () => {
    let token, orderId, total
    cy.then(() => cartFor(2).then((t) => { token = t }))
    cy.then(() => cy.request({ method: 'POST', url: '/storefront/checkout', headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, cartToken: token, shippingMethod: 'PICKUP', customerName: 'Refund Buyer', customerContact: '0300RF', paymentMode: 'CARD', cardToken: 'tok_sandbox' } }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.be.true
      expect(r.body.data.paymentStatus).to.eq('PAID')
      orderId = r.body.data.id
      total = Number(r.body.data.total)
    }))
    cy.then(() => cy.request({ method: 'POST', url: '/refundOrder', headers: { 'Content-Type': 'application/json' },
      body: { id: orderId } }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.be.true
      expect(r.body.data.paymentStatus).to.eq('REFUNDED')
      expect(Number(r.body.data.refundedAmount)).to.eq(total)
    }))
  })

  it('rejects refunding a COD order', () => {
    let token, orderId
    cy.then(() => cartFor(1).then((t) => { token = t }))
    cy.then(() => cy.request({ method: 'POST', url: '/storefront/checkout', headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, cartToken: token, shippingMethod: 'PICKUP', customerName: 'COD Buyer', customerContact: '0300CD', paymentMode: 'COD' } }).then((r) => {
      orderId = r.body.data.id
    }))
    cy.then(() => cy.request({ method: 'POST', url: '/refundOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { id: orderId } }).then((r) => {
      expect(r.body.success).to.not.eq(true)   // COD is settled in cash → no card refund
    }))
  })
})
