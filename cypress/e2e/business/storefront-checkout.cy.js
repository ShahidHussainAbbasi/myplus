/**
 * E-commerce (slice 69, E5) — server-authoritative checkout. Totals (subtotal + tax + shipping) are computed
 * server-side from the persistent cart; placing an order sources items/prices from the cart (not the client)
 * and decrements stock via the existing saga. Run headed.
 *
 * SF/tax fix: the last two tests are the ones that matter. Checkout used to run its OWN tax engine
 * (`net × product.taxRate / 100`) with no tenant switch, no org default rate and no INCLUSIVE handling, while
 * business-service gates every sale line on `tax_setting.enabled` — which is FALSE for a tenant that never
 * configured tax. So a shop with tax off was QUOTED 22 and INVOICED 20. Both halves were locally correct; only
 * the disagreement between them was wrong, and no test could see it because every test checked one half.
 * These read the figure back OFF THE INVOICE and compare, which is the property that was actually broken.
 */
describe('E-commerce — checkout (slice 69, E5)', () => {
  let orgId, productId
  const tag = Date.now()
  const name = 'CheckoutProd_' + tag

  // The tenant's tax policy is SERVER-WIDE state. Set it explicitly rather than inheriting whatever the last
  // spec left behind, and put it back in after() — a spec that leaves tax off reddens every later money spec.
  const setTax = (enabled, mode, defaultRate) => cy.request({
    method: 'POST', url: '/saveTaxSetting', form: true, failOnStatusCode: false,
    body: { enabled, taxMode: mode || 'EXCLUSIVE', defaultRate: defaultRate == null ? 0 : defaultRate },
  }).then((r) => expect(r.body.status || r.body.success, 'tax setting saved: ' + JSON.stringify(r.body))
    .to.satisfy((v) => v === 'SUCCESS' || v === true))

  /**
   * Set the policy AND wait for the storefront to see it.
   *
   * Marketplace caches the tenant's tax policy for `app.tax-policy.cache-ttl-ms` (15s) because the quote is a
   * hot path and this is month-end configuration, not transaction data. So a switch flipped here is genuinely
   * not visible to a quote for up to that long. Waiting it out is the honest thing to assert against — polling
   * the quote until it changes would hide a cache that never expired.
   */
  const setTaxAndLetItPropagate = (enabled, mode, defaultRate) => {
    setTax(enabled, mode, defaultRate)
    cy.wait(16000)
  }

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    // The product carries its own 10%, so no org default is needed. Propagating variant: another spec may
    // have left tax OFF within the cache window, and this spec's first two tests expect it ON.
    setTaxAndLetItPropagate(true, 'EXCLUSIVE', 0)
    // product @10.00 with 10% tax, stocked to 30 so every reservation below succeeds.
    cy.request({ method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name, sku: 'CO' + tag, sellingPrice: 10, taxRate: 10, unit: 'pcs' } })
    cy.then(() => cy.request('/storefront/products?org=' + orgId).then((r) => {
      const p = (r.body.data || []).find((x) => x.name === name)
      productId = p && p.id
      expect(productId, 'product created').to.be.ok
    }))
    cy.then(() => cy.request({ method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { productId, quantity: 30 } }))
  })

  after(() => {
    // Leave no server state behind: the last test switches tax OFF for this tenant.
    cy.loginAsMarketplace()
    setTax(true, 'EXCLUSIVE', 0)
  })

  beforeEach(() => cy.loginAsMarketplace())   // /productStock needs auth; testIsolation clears the session

  const addToCart = (qty) => cy.request({ method: 'POST', url: '/storefront/cart/add', headers: { 'Content-Type': 'application/json' },
    body: { organizationId: orgId, productId, quantity: qty } }).then((r) => r.body.data.cartToken)

  const quote = (token, method) => cy.request(
    '/storefront/checkout/quote?org=' + orgId + '&cartToken=' + token + '&shippingMethod=' + method)
    .then((r) => r.body.data)

  const place = (token, buyer) => cy.request({ method: 'POST', url: '/storefront/checkout',
    headers: { 'Content-Type': 'application/json' },
    body: { organizationId: orgId, cartToken: token, shippingMethod: 'PICKUP',
            customerName: buyer, customerContact: '0300CO', paymentMode: 'COD' } })
    .then((r) => { expect(r.body.success, JSON.stringify(r.body)).to.be.true; return r.body.data })

  /**
   * What the BOOKS say the sale came to — the other half of the disagreement.
   *
   * The payload is on `object`, NOT `data`: /getReceipt answers with GenericResponse, which carries a single
   * payload in `object` and lists in `collection`. Reading `data` here yielded `undefined`, and a `.then()`
   * that returns undefined hands back the PREVIOUS subject — so the assertions silently compared the order
   * against itself and failed as `expected NaN to equal 22` rather than saying the field was missing. Hence
   * the shape check below: a helper that cannot find its payload must say so.
   */
  const invoice = (invoiceNo) => cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo))
    .then((r) => {
      expect(r.body.status, 'invoice ' + invoiceNo + ' readable: ' + JSON.stringify(r.body)).to.eq('SUCCESS')
      const inv = r.body.object
      expect(inv, 'receipt payload on .object: ' + JSON.stringify(r.body)).to.be.an('object')
      expect(inv.grandTotal, 'receipt carries grandTotal: ' + JSON.stringify(inv)).to.not.be.undefined
      expect(inv.taxTotal, 'receipt carries taxTotal: ' + JSON.stringify(inv)).to.not.be.undefined
      return inv
    })

  it('quotes subtotal + tax + shipping server-side', () => {
    let token
    cy.then(() => addToCart(2).then((t) => { token = t }))
    cy.then(() => quote(token, 'STANDARD').then((q) => {
      expect(Number(q.subtotal)).to.eq(20)      // 10.00 × 2
      expect(Number(q.taxTotal)).to.eq(2)       // 10% of 20
      expect(Number(q.shippingFee)).to.eq(5)    // STANDARD
      expect(Number(q.total)).to.eq(27)
    }))
    cy.then(() => quote(token, 'PICKUP').then((q) => {
      expect(Number(q.shippingFee)).to.eq(0)    // pickup is free
      expect(Number(q.total)).to.eq(22)
    }))
  })

  it('places the order at the server total and decrements stock', () => {
    let token, before
    cy.then(() => cy.request('/productStock?productId=' + productId).then((r) => { before = parseFloat(r.body.stock) }))
    cy.then(() => addToCart(2).then((t) => { token = t }))
    cy.then(() => place(token, 'Checkout Buyer').then((o) => {
      expect(Number(o.total), 'server grand total').to.eq(22)   // 20 + 2 tax + 0 shipping
      expect(Number(o.taxTotal)).to.eq(2)
      expect(o.shippingMethod).to.eq('PICKUP')
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

  it('the quoted total is the total the books actually invoice — tax ON', () => {
    let token, quoted
    cy.then(() => addToCart(2).then((t) => { token = t }))
    cy.then(() => quote(token, 'PICKUP').then((q) => { quoted = q }))
    cy.then(() => place(token, 'TaxOnBuyer_' + Date.now())).then((o) => {
      expect(o.invoiceNo, 'the order produced a trade sale').to.be.a('string')
      cy.then(() => invoice(o.invoiceNo)).then((inv) => {
        // The whole defect in one line: shown price vs charged-and-recorded price.
        expect(Number(inv.grandTotal), 'invoice agrees with the quote').to.eq(Number(quoted.total))
        expect(Number(inv.taxTotal), 'invoice tax agrees with the quoted tax').to.eq(Number(quoted.taxTotal))
        expect(Number(inv.taxTotal), 'tax was charged').to.eq(2)
      })
    })
  })

  /**
   * Delivery has to reach the books too, and land on its OWN income line.
   *
   * The order row has carried a shippingFee since O3, but the sale contract had nowhere to put it — so the
   * shopper was charged for delivery and the invoice never mentioned it. Delivery income was absent from the
   * P&L entirely. STANDARD shipping, so the fee is non-zero and the invoice must include it.
   */
  it('the delivery fee reaches the books — invoiced, and on top of the taxed goods', () => {
    let token, quoted
    cy.then(() => addToCart(2).then((t) => { token = t }))
    cy.then(() => quote(token, 'STANDARD').then((q) => {
      quoted = q
      expect(Number(q.shippingFee), 'STANDARD is charged').to.eq(5)
      expect(Number(q.total)).to.eq(27)         // 20 goods + 2 tax + 5 delivery
    }))
    cy.then(() => cy.request({ method: 'POST', url: '/storefront/checkout',
      headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, cartToken: token, shippingMethod: 'STANDARD',
              shippingAddress: '4 Delivery Road', customerName: 'ShipBuyer_' + Date.now(),
              customerContact: '0300CO', paymentMode: 'COD' } })
      .then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.be.true
        return cy.then(() => invoice(r.body.data.invoiceNo))
      })
      .then((inv) => {
        expect(Number(inv.shippingFee), 'the invoice records the delivery charge').to.eq(5)
        expect(Number(inv.taxTotal), 'delivery is NOT taxed — the quote does not tax it either').to.eq(2)
        expect(Number(inv.grandTotal), 'invoice agrees with the quote').to.eq(Number(quoted.total))
      }))
  })

  it('the quoted total is the total the books actually invoice — tax OFF (the case that was broken)', () => {
    let token, quoted
    // Turn the tenant's tax switch off. The product still carries a 10% rate, and the OLD checkout would have
    // gone on quoting 22 off that rate alone while business-service invoiced 20 — the shopper charged one
    // figure, the books recording another.
    cy.then(() => setTaxAndLetItPropagate(false, 'EXCLUSIVE', 0))
    cy.then(() => addToCart(2).then((t) => { token = t }))
    cy.then(() => quote(token, 'PICKUP').then((q) => {
      quoted = q
      expect(Number(q.taxTotal), 'a tenant with tax off is quoted no tax').to.eq(0)
      expect(Number(q.total), 'quoted total').to.eq(20)
    }))
    cy.then(() => place(token, 'TaxOffBuyer_' + Date.now())).then((o) => {
      expect(o.invoiceNo, 'the order produced a trade sale').to.be.a('string')
      cy.then(() => invoice(o.invoiceNo)).then((inv) => {
        expect(Number(inv.taxTotal), 'the books charged no tax either').to.eq(0)
        expect(Number(inv.grandTotal), 'invoice agrees with the quote').to.eq(Number(quoted.total))
      })
    })
  })
})
