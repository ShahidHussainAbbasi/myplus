/**
 * E-commerce (slice 69, E5) — server-authoritative checkout. Totals (subtotal + EXCLUSIVE tax + shipping) are
 * computed server-side from the persistent cart; placing an order sources items/prices from the cart (not the
 * client) and decrements stock via the existing saga. Run headed.
 */
describe('E-commerce — checkout (slice 69, E5)', () => {
  let orgId, productId
  const tag = Date.now()
  const name = 'CheckoutProd_' + tag

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    // product @10.00 with 10% tax, stocked to 10 so the checkout reservation succeeds.
    cy.request({ method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name, sku: 'CO' + tag, sellingPrice: 10, taxRate: 10, unit: 'pcs' } })
    cy.then(() => cy.request('/storefront/products?org=' + orgId).then((r) => {
      const p = (r.body.data || []).find((x) => x.name === name)
      productId = p && p.id
      expect(productId, 'product created').to.be.ok
    }))
    cy.then(() => cy.request({ method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { productId, quantity: 10 } }))
  })

  beforeEach(() => cy.loginAsMarketplace())   // /productStock needs auth; testIsolation clears the session

  const addToCart = (qty) => cy.request({ method: 'POST', url: '/storefront/cart/add', headers: { 'Content-Type': 'application/json' },
    body: { organizationId: orgId, productId, quantity: qty } }).then((r) => r.body.data.cartToken)

  it('quotes subtotal + tax + shipping server-side', () => {
    let token
    cy.then(() => addToCart(2).then((t) => { token = t }))
    cy.then(() => cy.request('/storefront/checkout/quote?org=' + orgId + '&cartToken=' + token + '&shippingMethod=STANDARD').then((r) => {
      const q = r.body.data
      expect(Number(q.subtotal)).to.eq(20)      // 10.00 × 2
      expect(Number(q.taxTotal)).to.eq(2)       // 10% of 20
      expect(Number(q.shippingFee)).to.eq(5)    // STANDARD
      expect(Number(q.total)).to.eq(27)
    }))
    cy.then(() => cy.request('/storefront/checkout/quote?org=' + orgId + '&cartToken=' + token + '&shippingMethod=PICKUP').then((r) => {
      const q = r.body.data
      expect(Number(q.shippingFee)).to.eq(0)    // pickup is free
      expect(Number(q.total)).to.eq(22)
    }))
  })

  it('places the order at the server total and decrements stock', () => {
    let token, before
    cy.then(() => cy.request('/productStock?productId=' + productId).then((r) => { before = parseFloat(r.body.stock) }))
    cy.then(() => addToCart(2).then((t) => { token = t }))
    cy.then(() => cy.request({ method: 'POST', url: '/storefront/checkout', headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, cartToken: token, shippingMethod: 'PICKUP', customerName: 'Checkout Buyer', customerContact: '0300CO', paymentMode: 'COD' } }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.be.true
      expect(Number(r.body.data.total), 'server grand total').to.eq(22)   // 20 + 2 tax + 0 shipping
      expect(Number(r.body.data.taxTotal)).to.eq(2)
      expect(r.body.data.shippingMethod).to.eq('PICKUP')
    }))
    // stock decremented by 2 (saga confirm — poll)
    cy.then(() => {
      const poll = (n) => cy.request('/productStock?productId=' + productId).then((r) => {
        const s = parseFloat(r.body.stock)
        if (s === before - 2 || n <= 0) { expect(s, 'on-hand dropped by 2').to.eq(before - 2); return }
        cy.wait(1000); poll(n - 1)
      })
      poll(8)
    })
  })
})
