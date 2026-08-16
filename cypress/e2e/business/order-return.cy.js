/**
 * E-commerce (slice 71, E10) — returns / RMA. A delivered card order: shopper requests a return (by ref + contact),
 * back-office processes it → RETURNED, stock goes back to inventory (G2 inverse saga) and the card is refunded (E6).
 * Run headed.
 */
describe('E-commerce — returns / RMA (slice 71, E10)', () => {
  let orgId, productId
  const tag = Date.now()
  const name = 'ReturnProd_' + tag

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    cy.request({ method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name, sku: 'RT' + tag, sellingPrice: 10, taxRate: 0, unit: 'pcs' } })
    cy.then(() => cy.request('/storefront/products?org=' + orgId).then((r) => {
      const p = (r.body.data || []).find((x) => x.name === name)
      productId = p && p.id
      expect(productId, 'product created').to.be.ok
    }))
    cy.then(() => cy.request({ method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { productId, quantity: 10 } }))
  })

  beforeEach(() => cy.loginAsMarketplace())   // admin endpoints + /productStock need auth

  const cartFor = (qty) => cy.request({ method: 'POST', url: '/storefront/cart/add', headers: { 'Content-Type': 'application/json' },
    body: { organizationId: orgId, productId, quantity: qty } }).then((r) => r.body.data.cartToken)

  it('request a return, then process it: stock back + card refunded', () => {
    let token, orderId, before
    cy.then(() => cy.request('/productStock?productId=' + productId).then((r) => { before = parseFloat(r.body.stock) }))
    cy.then(() => cartFor(2).then((t) => { token = t }))
    cy.then(() => cy.request({ method: 'POST', url: '/storefront/checkout', headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, cartToken: token, shippingMethod: 'PICKUP', customerName: 'RMA Buyer', customerContact: '0300RT', paymentMode: 'CARD', cardToken: 'tok_sandbox' } }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.be.true
      expect(r.body.data.paymentStatus).to.eq('PAID')
      orderId = r.body.data.id
    }))
    // Deliver it (back-office) — via the LEGAL route.
    //
    // This used to jump straight NEW → DELIVERED, which O2's transition whitelist refuses
    // (`FulfilmentStatus.ALLOWED`: NEW → {PACKED, CANCELLED}) and O5b made impossible a second way:
    // SHIPPED is DERIVED from parcels, never typed, so DELIVERED is only reachable from SHIPPED or
    // PARTIALLY_SHIPPED. The spec predates both and had been asserting a jump the server now rejects.
    //
    // The old assertion also hid the reason: `expect(r.body.success).to.not.eq(false)` reported
    // "expected false to not equal false" and dumped nothing, so the refusal message never surfaced.
    // Every step below prints the body on failure.
    cy.then(() => cy.request({ method: 'POST', url: '/updateOrderStatus', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false, body: { id: orderId, status: 'PACKED' } })
      .then((r) => { expect(r.body.success, `NEW→PACKED: ${JSON.stringify(r.body)}`).to.eq(true) }))

    // Record the parcel. The header becomes SHIPPED because everything ordered has now gone — a status
    // the packer cannot type, which is the property O5b exists to guarantee.
    cy.then(() => cy.request('/getOrder?id=' + orderId).then((r) => {
      const line = (r.body.data.items || [])[0]
      expect(line && line.id, `the order has a line to ship: ${JSON.stringify(r.body)}`).to.exist
      return cy.request({ method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' },
        failOnStatusCode: false,
        body: { id: orderId, lines: [{ orderItemId: line.id, quantity: line.quantity }],
                carrier: 'DHL', trackingNumber: 'TRK-RMA-' + tag } })
        .then((s) => { expect(s.body.success, `shipOrder: ${JSON.stringify(s.body)}`).to.eq(true) })
    }))

    cy.then(() => cy.request({ method: 'POST', url: '/updateOrderStatus', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false, body: { id: orderId, status: 'DELIVERED' } })
      .then((r) => { expect(r.body.success, `SHIPPED→DELIVERED: ${JSON.stringify(r.body)}`).to.eq(true) }))
    // shopper requests the return (public, by ref + contact)
    cy.then(() => cy.request({ method: 'POST', url: '/storefront/return', headers: { 'Content-Type': 'application/json' },
      body: { ref: orderId, contact: '0300RT', reason: 'changed my mind' } }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.be.true
    }))
    // back-office processes it
    cy.then(() => cy.request({ method: 'POST', url: '/processReturn', headers: { 'Content-Type': 'application/json' },
      body: { id: orderId } }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.be.true
      expect(r.body.data.fulfilmentStatus).to.eq('RETURNED')
      expect(r.body.data.paymentStatus).to.eq('REFUNDED')
    }))
    // stock returned to inventory (poll for the saga)
    cy.then(() => {
      const poll = (n) => cy.request('/productStock?productId=' + productId).then((r) => {
        const s = parseFloat(r.body.stock)
        if (s === before || n <= 0) { expect(s, 'stock returned to baseline').to.eq(before); return }
        cy.wait(1000); poll(n - 1)
      })
      poll(8)
    })
  })
})
