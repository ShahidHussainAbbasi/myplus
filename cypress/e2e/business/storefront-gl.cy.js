/**
 * A storefront order must land in the GENERAL LEDGER correctly — not merely produce a correct-looking invoice.
 *
 * <h3>Why this spec exists</h3>
 * `storefront-coupon` and `storefront-checkout` both went green while the books were wrong. They asserted the
 * INVOICE: grand total 18, concession 2, delivery 5 — all true, all stored. But the GL outbox is a persisted
 * table that copies its payload field by field, and it had no column for either figure, so both were dropped
 * in silence on the way to finance. `4200 Sales Discount` was empty in every tenant since D-4 shipped, and a
 * sale with delivery posted NO journal at all (the event was short by the fee, so the journal would not
 * balance and was rejected).
 *
 * Every assertion here is therefore about the LEDGER. An invoice is what the shop shows the customer; the
 * ledger is what the shop actually knows. The two are different systems and this is the seam between them.
 */
describe('E-commerce — a storefront order posts a correct, balanced journal', () => {
  let orgId, productId
  const tag = Date.now()
  const name = 'GlShopProd_' + tag
  const code = 'GLSAVE' + tag

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const tb = () => cy.request('/gl/trialBalance').then((r) => {
    const t = parse(r.body)
    expect(t, 'trial balance readable: ' + JSON.stringify(r.body).slice(0, 300)).to.have.property('rows')
    return t
  })
  const acct = (rows, c) => (rows || []).find((x) => x.code === c) || { debit: 0, credit: 0 }
  /** Signed net (Dr − Cr). The trial balance nets each account to one side, and this org is shared with other
   *  specs, so only the MOVEMENT between two reads is meaningful — never an absolute balance. */
  const net = (rows, c) => { const a = acct(rows, c); return Number(a.debit) - Number(a.credit) }

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    // Tax ON so the journal has a tax leg too — the fee must stay OUT of it.
    cy.request({ method: 'POST', url: '/saveTaxSetting', form: true, failOnStatusCode: false,
      body: { enabled: true, taxMode: 'EXCLUSIVE', defaultRate: 0 } })
    cy.request({ method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name, sku: 'GL' + tag, sellingPrice: 10, taxRate: 10, unit: 'pcs' } })
    cy.then(() => cy.request('/storefront/products?org=' + orgId).then((r) => {
      const p = (r.body.data || []).find((x) => x.name === name)
      productId = p && p.id
      expect(productId, 'product created').to.be.ok
    }))
    cy.then(() => cy.request({ method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { productId, quantity: 20 } }))
    cy.then(() => cy.request({ method: 'POST', url: '/addCoupon', headers: { 'Content-Type': 'application/json' },
      body: { code, type: 'PERCENT', value: 10 } }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.be.true))
    cy.wait(16000)   // marketplace caches the tax policy for app.tax-policy.cache-ttl-ms (15s)
  })

  beforeEach(() => cy.loginAsMarketplace())   // testIsolation clears the session

  const order = (shipping, coupon) => cy.request({ method: 'POST', url: '/storefront/cart/add',
    headers: { 'Content-Type': 'application/json' },
    body: { organizationId: orgId, productId, quantity: 2 } })
    .then((r) => r.body.data.cartToken)
    .then((token) => cy.request({ method: 'POST', url: '/storefront/checkout',
      headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, cartToken: token, shippingMethod: shipping,
              shippingAddress: '7 Ledger Lane', customerName: 'GlBuyer_' + Date.now(),
              customerContact: '0300GL', paymentMode: 'COD', couponCode: coupon } })
      .then((r) => { expect(r.body.success, JSON.stringify(r.body)).to.be.true; return r.body.data }))

  it('the delivery fee is credited to 4300 Delivery Income, not to Sales, and the journal balances', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    tb().then((before) => {
      const sales0 = net(before.rows, '4000'), delivery0 = net(before.rows, '4300'), tax0 = net(before.rows, '2100')

      // 2 × 10.00 goods @10% tax + 5.00 STANDARD delivery = 27.00 owed.
      order('STANDARD', null).then((o) => {
        expect(o.invoiceNo, 'the order produced a trade sale').to.be.a('string')

        tb().then((after) => {
          // Before the fix this was the LOUD failure: the event was short by the fee, GlService.validate
          // rejected the journal, and the sale posted nothing at all.
          expect(after.balanced, 'GL balanced after a delivered sale').to.eq(true)
          expect(Number(after.totalDebit)).to.eq(Number(after.totalCredit))

          expect(net(after.rows, '4300') - delivery0, 'delivery credited to its own income account')
            .to.eq(-5)                                   // a credit moves the signed net DOWN
          expect(net(after.rows, '4000') - sales0, 'goods revenue, delivery excluded').to.eq(-20)
          expect(net(after.rows, '2100') - tax0, 'delivery is not taxed').to.eq(-2)
        })
      })
    })
  })

  it('a coupon debits 4200 Sales Discount and leaves Sales at the list value', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    tb().then((before) => {
      const sales0 = net(before.rows, '4000'), disc0 = net(before.rows, '4200')

      // 2 × 10.00 = 20.00 list, 10% coupon = 2.00 off, PICKUP so no delivery.
      order('PICKUP', code).then(() => {
        tb().then((after) => {
          expect(after.balanced, 'GL balanced after a discounted sale').to.eq(true)

          // The whole point of contra-revenue: Sales reads what the goods LIST at, and the concession is its
          // own number. Netting the discount into Sales would make a shop that discounted heavily
          // indistinguishable from one that simply sold less.
          expect(net(after.rows, '4200') - disc0, 'the concession is debited to 4200').to.eq(2)
          expect(net(after.rows, '4000') - sales0, 'Sales at list value, NOT netted down to 18').to.eq(-20)
        })
      })
    })
  })
})
