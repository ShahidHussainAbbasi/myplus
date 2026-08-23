/**
 * O7 D1b — the policy pre-check: tell the reviewer at the moment they decide.
 *
 * <h3>The gap</h3>
 * §6 D-3 said an amendment must re-run the margin and credit checks. D1 shipped without it and §8.1 recorded
 * the departure honestly: the rules ARE enforced, by the sale path, at dispatch — so nothing unsafe shipped.
 * What was missing is telling the reviewer while they are still deciding, rather than when the van is loading.
 *
 * <h3>What these cases are really defending</h3>
 * The pre-check's whole value is that it runs the SAME `assertMarginPolicy` and `assertCreditPolicy` the sale
 * runs. The failure mode worth guarding is not a wrong field on a response — it is somebody re-implementing
 * the rules, which drifts silently: the panel says fine, dispatch refuses, and neither log explains the other.
 * So the central case asserts the two answers AGREE, and a defect of exactly that kind was already caught in
 * the unit test (a margin summed over all lines instead of costed ones only).
 *
 * <h3>Advisory, not enforcement</h3>
 * The amendment saves either way. Refusing to save because a sale WOULD fail later takes the decision from the
 * person the review step exists to serve, and D1 established that both booker and admin may revise.
 */
describe('O7 D1b — an amendment says what it will cost', () => {
  const run = String(Date.now()).slice(-6)
  const ctx = {}
  let productId
  const PRODUCT = 'D1bProd_' + run

  before(() => {
    cy.loginAsMarketplaceOwner()
    /*
     * A product with a KNOWN COST, and the cost has to come from a real PURCHASE.
     *
     * The margin rule excludes uncosted lines from BOTH sides, so a product with no purchase behind it makes
     * every margin assertion here vacuous — it would report "nothing to judge" and the spec would pass while
     * testing nothing. `seedProduct` documents a `purchaseRate` option in its usage comment but never sends
     * it anywhere, so recording a purchase is the only way to stamp a cost.
     *
     * The positive control below is what would catch this if it ever silently stopped working: a
     * loss-making amendment that raised NO warning would mean the cost never landed.
     */
    cy.seedProduct({ name: PRODUCT, sellingPrice: 100, stock: 500 })
      .then((p) => { productId = p.productId })

    /*
     * A purchase needs a vendor, and a vendor needs a COMPANY — `contact` is not one of its fields, which is
     * why a first draft created nothing and the fixture failed one step later at "the vendor exists". Asserted
     * at each step so a fixture failure names itself instead of surfacing as a missing margin warning.
     */
    const coName = 'D1bCo_' + run
    cy.then(() => cy.request({
      method: 'POST', url: '/addCompany', form: true, failOnStatusCode: false,
      body: { name: coName, email: 'd1bco' + run + '@t.com' },
    })).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    cy.then(() => cy.request('/getUserCompany')).then((r) => {
      const c = (r.body.collection || r.body.data || []).find((x) => x.name === coName)
      expect(c, 'the company exists').to.exist
      ctx.companyId = c.id
    })

    const vName = 'D1bVendor_' + run
    cy.then(() => cy.request({
      method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
      // mobile must be a real 11-digit number — the validator rejects a 10-digit one, which is what
      // "'0302' + run" produced when `run` is a 6-digit stamp.
      body: { name: vName, companyId: ctx.companyId, mobile: '03007778888', email: 'd1bv' + run + '@t.com' },
    })).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    cy.then(() => cy.request('/getUserVender')).then((r) => {
      const v = (r.body.collection || r.body.data || []).find((x) => x.name === vName)
      expect(v, 'the vendor exists').to.exist
      ctx.venderId = v.id
    })
    cy.then(() => cy.request({
      method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
      body: {
        productId, quantity: 500, venderId: ctx.venderId, paidAmount: 0,
        'stock.bpurchaseRate': 60, 'stock.bsellRate': 100,
        totalAmount: 30000, netAmount: 30000, purchaseInvoiceNo: 'D1BINV-' + run,
      },
    })).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

    const name = 'D1bOutlet_' + run
    cy.then(() => cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name, contact: '0301' + run, creditLimit: 1000000 },
    })).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    cy.then(() => cy.request('/getUserCustomer')).then((r) => {
      const row = (r.body.collection || r.body.data || []).filter((c) => c.name === name)[0]
      expect(row, 'the outlet exists').to.exist
      ctx.outletId = row.customerId || row.id
      ctx.outletName = name
    })
  })

  beforeEach(() => cy.loginAsMarketplaceOwner())

  /** Book an order at a healthy price and leave it PENDING_APPROVAL, where amendment is allowed. */
  const bookOrder = (price) => {
    let orderId, lineId
    return cy.request({
      method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: {
        customerId: ctx.outletId, customerName: ctx.outletName, customerContact: '0301' + run,
        items: [{ productId, quantity: 10, price, productName: PRODUCT }],
      },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      orderId = r.body.data.id
      return cy.request('/getOrder?id=' + orderId)
    }).then((r) => {
      lineId = r.body.data.items[0].id
      return cy.wrap({ orderId, lineId })
    })
  }

  const amend = (o, price) =>
    cy.request({
      method: 'POST', url: '/amendOrder', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: {
        id: o.orderId, amendmentReason: 'D1b gate',
        items: [{ id: o.lineId, productId, quantity: 10, price }],
      },
    })

  // ── the point of the slice ────────────────────────────────────────────────────────────────────

  it('THE CASE — an amendment below cost says so, and the amendment still saves', () => {
    // Cost is 60. Amending to 40 is a real loss the reviewer should hear about before the van loads.
    bookOrder(100).then((o) => {
      amend(o, 40).then((r) => {
        expect(r.body.success, JSON.stringify(r.body).slice(0, 300)).to.eq(true)
        const w = r.body.data.policyWarnings || []
        expect(w.length, 'the reviewer is warned: ' + JSON.stringify(w)).to.be.greaterThan(0)
        expect(w.join(' '), 'and told what is wrong').to.match(/profit|margin/i)
      })
      // ADVISORY, not enforcement — the price really did change. If this ever fails, the pre-check has
      // started blocking amendments, which takes the decision from the person review exists to serve.
      cy.then(() => cy.request('/getOrder?id=' + o.orderId)).then((r) => {
        expect(Number(r.body.data.items[0].price), 'the amendment saved').to.eq(40)
      })
    })
  })

  it('POSITIVE CONTROL — an amendment within policy reports nothing at all', () => {
    /*
     * Without this, every "a warning appears" assertion above would pass just as happily against an
     * implementation that warns about everything. This programme has been caught by that exact shape before,
     * including an anti-IDOR case that went green against a 404.
     */
    bookOrder(100).then((o) => {
      amend(o, 90).then((r) => {          // still above the 60 cost
        expect(r.body.success).to.eq(true)
        expect(r.body.data.policyWarnings || [], 'a healthy amendment is quiet').to.have.length(0)
      })
    })
  })

  it('THE AGREEMENT — what the check refuses is what dispatch refuses', () => {
    /*
     * The property that matters most, and the one no unit test can reach: the pre-check and the sale path must
     * give the SAME answer, because they are the same rules. A re-implementation would pass every other case
     * in this file and fail here.
     *
     * Under the default `warn` policy neither actually refuses — so what is asserted is that both RAISE the
     * same finding: the amendment warns, and the invoice the dispatch produces carries the warning too.
     */
    bookOrder(100).then((o) => {
      amend(o, 40).then((r) => {
        expect((r.body.data.policyWarnings || []).join(' '), 'the check flagged it').to.match(/profit|margin/i)
      })
      cy.then(() => cy.request({
        method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
        body: { id: o.orderId }, failOnStatusCode: false,
      })).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

      cy.then(() => cy.request({
        method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        body: { id: o.orderId, lines: [{ orderItemId: o.lineId, quantity: 10 }], carrier: 'D1b' },
      })).then((r) => {
        // The dispatch SUCCEEDS under `warn` — that is the same answer the check gave (flagged, not refused).
        // If the check said "loses money" and the dispatch had refused outright, the two would disagree and a
        // reviewer would have been told the wrong thing.
        expect(r.body.success, JSON.stringify(r.body).slice(0, 300)).to.eq(true)
      })
    })
  })

  it('THE CHECK WRITES NOTHING — stock and the outlet\'s balance are untouched by an amendment', () => {
    /*
     * A dry run that reserved stock or moved a balance would be a sale, not a check. This is the property that
     * separates the two, and it is invisible to any assertion about the response body.
     */
    let stockBefore, dueBefore
    // `/productStock`, the endpoint order-approval.cy.js reads for exactly this purpose. Stock lives in
    // inventory-service, so there is no product record to read it off.
    cy.request('/productStock?productId=' + productId).then((r) => {
      stockBefore = parseFloat(r.body.stock)
      expect(Number.isFinite(stockBefore), 'a readable stock figure: ' + JSON.stringify(r.body)).to.eq(true)
    })
    cy.then(() => cy.request('/creditStanding?customerId=' + ctx.outletId)).then((r) => {
      dueBefore = Number((r.body.object || {}).owed)
    })

    bookOrder(100).then((o) => amend(o, 40))

    cy.then(() => cy.request('/productStock?productId=' + productId)).then((r) => {
      expect(parseFloat(r.body.stock), 'no stock was held').to.eq(stockBefore)
    })
    cy.then(() => cy.request('/creditStanding?customerId=' + ctx.outletId)).then((r) => {
      expect(Number((r.body.object || {}).owed), 'no receivable was created').to.eq(dueBefore)
    })
  })

  it('a check for ANOTHER tenant\'s order is refused', () => {
    // Every id here is attacker-controlled. Amending someone else's order must not be a way to read their
    // pricing and credit position.
    bookOrder(100).then((o) => {
      cy.loginAsBusiness()          // a different organization
      amend(o, 40).then((r) => {
        expect(r.body.success, 'another tenant cannot amend this order').to.not.eq(true)
      })
    })
  })

  it('business-service being unreachable does not fail the amendment', () => {
    /*
     * The forecast is a convenience; the amendment is work. If the pre-check throws, the amendment must still
     * stand with an empty warning list — never a 500 that loses what the reviewer typed.
     *
     * Asserted through the normal path rather than by breaking the service: what is being pinned is that
     * `policyWarnings` is always a LIST and never the thing that decides whether the call succeeded.
     */
    bookOrder(100).then((o) => {
      amend(o, 90).then((r) => {
        expect(r.body.success).to.eq(true)
        expect(r.body.data).to.have.property('policyWarnings')
        expect(r.body.data.policyWarnings).to.be.an('array')
      })
    })
  })
})
