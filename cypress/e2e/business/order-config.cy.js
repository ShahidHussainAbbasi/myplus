/**
 * OMS O3 — per-org order configuration.
 * Design: microservices/docs/slices/oms-O3-order-config.md
 *
 * Before this slice every store on the platform shared one hardcoded fee table baked into the ShippingOption
 * enum: 5.00 standard, 15.00 express, no free-delivery threshold, cash-on-delivery always on. A shop could not
 * price its own delivery.
 *
 * What this gate proves is the part that is invisible until it is wrong:
 *   - the policies are MONEY entries, not tick boxes (a fee rendered as a checkbox saves "true")
 *   - a configured fee actually reaches the SHOPPER'S QUOTE, not just the settings table
 *   - the free-delivery threshold includes its own boundary ("free over 5000" must include 5000)
 *   - a zero threshold means OFF, not "everything ships free"
 *   - switching cash-on-delivery off is enforced BY THE SERVER, not merely hidden in the storefront —
 *     hiding a tab stops nobody who posts the checkout directly
 *
 * The quote endpoint is public (a shopper is not signed in); saving a setting is owner-gated, so the spec
 * logs in as the marketplace OWNER — the same org the storefront reads.
 */
describe('OMS O3 — order configuration (per-org delivery & payment policy)', () => {
  const K = {
    standard: 'order.shipping.standardFee',
    express:  'order.shipping.expressFee',
    freeOver: 'order.shipping.freeOverAmount',
    cod:      'order.payment.codEnabled',
  }

  let orgId, productId
  const pname = 'CfgShop_' + Date.now()

  // One unit at 100.00, no tax → a subtotal of exactly 100.00, which makes the threshold boundary exact.
  const UNIT = 100

  before(() => {
    cy.loginAsMarketplaceOwner()
    cy.request('/getMyOrganizations').then((r) => {
      const o = (r.body.collection || [])[0] || {}
      orgId = o.id || o.organizationId || o.orgId
      expect(orgId, 'marketplace owner must have an organization').to.exist
    })
    cy.request({
      method: 'POST', url: '/addProduct', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { name: pname, sku: 'CFG' + Date.now(), sellingPrice: UNIT, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, `addProduct failed: ${JSON.stringify(r.body)}`).to.eq(true)
      productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' },
        body: { productId, quantity: 50 },
      })
    })
  })

  // testIsolation clears the session between tests, so re-establish it for the authed saves.
  beforeEach(() => cy.loginAsMarketplaceOwner())

  /** Save one override and assert the SERVER accepted it — a silent failure here would fake every later pass. */
  const setCfg = (key, value) =>
    cy.request({ method: 'POST', url: '/saveOrderConfig', form: true, body: { key, value: String(value) }, failOnStatusCode: false })
      .then((r) => {
        expect(r.body.success, `saveOrderConfig ${key}=${value} failed: ${JSON.stringify(r.body)}`).to.eq(true)
      })

  /** A fresh public cart holding one unit, then the shopper's live quote for a shipping method. */
  const quoteFor = (method) =>
    cy.request({
      method: 'POST', url: '/storefront/cart/add', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, productId, quantity: 1 },
    }).then((r) => {
      expect(r.body.success, `cart/add failed: ${JSON.stringify(r.body)}`).to.eq(true)
      const token = r.body.data.cartToken
      expect(token, 'cart/add minted no token').to.be.a('string')
      return cy.request({
        url: `/storefront/checkout/quote?org=${orgId}&cartToken=${encodeURIComponent(token)}&shippingMethod=${method}`,
        failOnStatusCode: false,
      })
    }).then((r) => {
      expect(r.body.success, `quote failed: ${JSON.stringify(r.body)}`).to.eq(true)
      return r.body.data
    })

  // Leave the org on the catalog defaults so a re-run — and every other storefront spec — starts clean.
  after(() => {
    cy.loginAsMarketplaceOwner()
    setCfg(K.standard, '5.00')
    setCfg(K.express, '15.00')
    setCfg(K.freeOver, '0')
    setCfg(K.cod, 'true')
  })

  it('the order policies are in the catalog, and a fee is MONEY — not a tick box', () => {
    cy.request('/getOrderConfig').then((r) => {
      const items = r.body.data || r.body.collection || []
      expect(items, `no settings catalog returned: ${JSON.stringify(r.body)}`).to.be.an('array').and.not.be.empty
      const byKey = Object.fromEntries(items.map((i) => [i.key, i]))

      Object.values(K).forEach((key) => expect(byKey[key], `catalog is missing ${key}`).to.exist)
      // A MONEY entry renders as a decimal input. If it were typed BOOL the screen would show a checkbox and
      // save "true" as the delivery fee — the exact silent corruption this assertion exists to prevent.
      expect(byKey[K.standard].type, 'a delivery fee must be MONEY').to.eq('MONEY')
      expect(byKey[K.express].type, 'a delivery fee must be MONEY').to.eq('MONEY')
      expect(byKey[K.cod].type, 'cash-on-delivery is a switch').to.eq('BOOL')
    })
  })

  it('a store sets its own delivery fee and the shopper is charged it', () => {
    setCfg(K.freeOver, '0')          // threshold off, so the fee is what we are measuring
    setCfg(K.standard, '250')
    quoteFor('STANDARD').then((q) => {
      expect(Number(q.shippingFee), 'the configured fee must reach the quote').to.eq(250)
      expect(Number(q.subtotal)).to.eq(UNIT)
      expect(Number(q.total), 'goods + the store\'s own delivery charge').to.eq(UNIT + 250)
    })
  })

  it('express is priced independently of standard', () => {
    setCfg(K.express, '600')
    quoteFor('EXPRESS').then((q) => expect(Number(q.shippingFee)).to.eq(600))
    // …and setting express did not disturb standard.
    quoteFor('STANDARD').then((q) => expect(Number(q.shippingFee)).to.eq(250))
  })

  it('collection is free however the fees are set', () => {
    quoteFor('PICKUP').then((q) => {
      expect(Number(q.shippingFee)).to.eq(0)
      expect(Number(q.total)).to.eq(UNIT)
      expect(q.addressRequired, 'collection needs no delivery address').to.eq(false)
    })
  })

  it('free delivery over a threshold — and the boundary counts as over', () => {
    setCfg(K.freeOver, String(UNIT))     // threshold set to EXACTLY this order's subtotal
    quoteFor('STANDARD').then((q) => {
      expect(Number(q.shippingFee), '"free over 100" must include an order of exactly 100').to.eq(0)
      expect(Number(q.total)).to.eq(UNIT)
    })
    // The threshold is not method-specific: free delivery means free, whichever method the shopper picks.
    quoteFor('EXPRESS').then((q) => expect(Number(q.shippingFee)).to.eq(0))
  })

  it('a threshold set just above the order still charges', () => {
    setCfg(K.freeOver, String(UNIT + 0.01))
    quoteFor('STANDARD').then((q) => expect(Number(q.shippingFee)).to.eq(250))
  })

  it('a zero threshold means OFF, not "everything ships free"', () => {
    setCfg(K.freeOver, '0')
    quoteFor('STANDARD').then((q) => {
      expect(Number(q.shippingFee), '0 disables the threshold').to.eq(250)
    })
  })

  it('switching cash-on-delivery off is enforced by the SERVER, not just hidden in the storefront', () => {
    setCfg(K.cod, 'false')
    // Posted straight at the checkout, exactly as someone bypassing the storefront UI would.
    cy.storefrontOrder(orgId, { productId, quantity: 1 }, {
      customerName: 'CodOff_' + Date.now(), customerContact: '0300CFG',
      shippingAddress: '9 Config Street', paymentMode: 'COD',
    }).then((r) => {
      expect(r.body.success, 'a COD order must be refused while COD is switched off').to.eq(false)
      expect(String(r.body.message || ''), 'the shopper must be told why').to.match(/cash on delivery|not available|unavailable/i)
    })
  })

  it('switching it back on lets the same order through', () => {
    setCfg(K.cod, 'true')
    cy.storefrontOrder(orgId, { productId, quantity: 1 }, {
      customerName: 'CodOn_' + Date.now(), customerContact: '0300CFG',
      shippingAddress: '9 Config Street', paymentMode: 'COD',
    }).then((r) => {
      expect(r.body.success, `COD order should succeed once re-enabled: ${JSON.stringify(r.body)}`).to.eq(true)
      expect(r.body.data.paymentMode).to.eq('COD')
    })
  })
})
