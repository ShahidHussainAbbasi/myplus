/**
 * E-commerce (slice 72, E13) — coupons. A store creates a promo code; applying it at checkout reduces the
 * server-computed total, and placing the order records the coupon + discount. Run headed.
 */
describe('E-commerce — coupons (slice 72, E13)', () => {
  let orgId, productId
  const tag = Date.now()
  const name = 'CouponProd_' + tag
  const code = 'SAVE' + tag   // unique per run so re-runs don't clash on the unique (org,code)

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    cy.request({ method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name, sku: 'CP' + tag, sellingPrice: 10, taxRate: 0, unit: 'pcs' } })
    cy.then(() => cy.request('/storefront/products?org=' + orgId).then((r) => {
      const p = (r.body.data || []).find((x) => x.name === name)
      productId = p && p.id
      expect(productId, 'product created').to.be.ok
    }))
    cy.then(() => cy.request({ method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { productId, quantity: 10 } }))
    // a 10%-off promo code for this store
    cy.then(() => cy.request({ method: 'POST', url: '/addCoupon', headers: { 'Content-Type': 'application/json' },
      body: { code, type: 'PERCENT', value: 10 } }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.be.true
    }))
  })

  beforeEach(() => cy.loginAsMarketplace())   // /addCoupon needs auth; testIsolation clears the session

  const cartFor = (qty) => cy.request({ method: 'POST', url: '/storefront/cart/add', headers: { 'Content-Type': 'application/json' },
    body: { organizationId: orgId, productId, quantity: qty } }).then((r) => r.body.data.cartToken)

  it('a coupon reduces the checkout quote', () => {
    let token
    cy.then(() => cartFor(2).then((t) => { token = t }))
    cy.then(() => cy.request('/storefront/checkout/quote?org=' + orgId + '&cartToken=' + token + '&shippingMethod=PICKUP&couponCode=' + code).then((r) => {
      const q = r.body.data
      expect(Number(q.subtotal)).to.eq(20)
      expect(Number(q.discount)).to.eq(2)        // 10% of 20
      expect(q.couponCode).to.eq(code)
      expect(Number(q.total)).to.eq(18)          // 20 - 2 + 0 tax + 0 pickup
    }))
  })

  it('placing with a coupon records the discount on the order', () => {
    let token
    cy.then(() => cartFor(2).then((t) => { token = t }))
    cy.then(() => cy.request({ method: 'POST', url: '/storefront/checkout', headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, cartToken: token, shippingMethod: 'PICKUP', customerName: 'Coupon Buyer', customerContact: '0300CP', paymentMode: 'COD', couponCode: code } }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.be.true
      expect(r.body.data.couponCode).to.eq(code)
      expect(Number(r.body.data.discountAmount)).to.eq(2)
      expect(Number(r.body.data.total)).to.eq(18)
    }))
  })

  /**
   * The half the order row could not prove.
   *
   * `order.total` is copied from the INVOICE's grand total, so the assertion above only says the two agree —
   * it cannot say they agree on the RIGHT figure. Before the contract carried a discount field they agreed on
   * 20: the shopper paid 18, the books recorded 20, and the coupon existed nowhere in the accounts. This reads
   * the invoice itself and pins both the amount and the concession.
   */
  it('the coupon reaches the BOOKS — the invoice is raised at the discounted figure', () => {
    let token
    cy.then(() => cartFor(2).then((t) => { token = t }))
    cy.then(() => cy.request({ method: 'POST', url: '/storefront/checkout', headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, cartToken: token, shippingMethod: 'PICKUP', customerName: 'CouponBooks_' + tag, customerContact: '0300CP', paymentMode: 'COD', couponCode: code } })
      .then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.be.true
        const invoiceNo = r.body.data.invoiceNo
        expect(invoiceNo, 'the order produced a trade sale').to.be.a('string')
        // GenericResponse carries a single payload on `object`, never on `data`.
        return cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo)).then((rr) => {
          expect(rr.body.status, 'invoice readable: ' + JSON.stringify(rr.body)).to.eq('SUCCESS')
          const inv = rr.body.object
          expect(inv, 'receipt payload: ' + JSON.stringify(rr.body)).to.be.an('object')
          expect(Number(inv.grandTotal), 'invoiced at the discounted figure, not the list price').to.eq(18)
          expect(Number(inv.tradeDiscount), 'the concession is recorded on the invoice').to.eq(2)
        })
      }))
  })
})
