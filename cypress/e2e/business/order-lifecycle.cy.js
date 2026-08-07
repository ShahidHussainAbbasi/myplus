/**
 * OMS O2 — order lifecycle, authority and safety.
 * Design: microservices/docs/slices/oms-O2-lifecycle-authority-safety.md
 *
 * Four defects, one aggregate's integrity:
 *   OMS-2  no state machine, no authority on status change → any user could mark an order DELIVERED, and a
 *          CANCELLED order could be SHIPPED (goods dispatched after money and stock were reversed);
 *   OMS-3  the ORDER row was not idempotent — O1 made the SALE idempotent, so a double-submit replayed one
 *          invoice but inserted TWO orders: picked and shipped twice;
 *   OMS-4  no optimistic locking — two people touching one order, last write silently won;
 *   OMS-8  public tracking resolved a raw auto-increment id, UNSCOPED — enumerable, and useless to quote.
 *
 * Run headed.
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

describe('OMS O2 — order lifecycle, authority and safety', () => {
  let orgId, productId

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    cy.request({
      method: 'POST', url: '/addProduct', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { name: 'LifeProd_' + uniq(), sku: 'LFC' + uniq(), sellingPrice: 40, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      productId = r.body.data.id
      expect(productId, 'seeded product').to.exist
      return cy.request({
        method: 'POST', url: '/addProductStock', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' },
        body: { productId, quantity: 300 },
      })
    })
  })

  beforeEach(() => cy.loginAsMarketplace())

  const place = (qty, name) => cy.storefrontOrder(orgId, { productId, quantity: qty },
    { customerName: name, customerContact: '0300LFC', shippingAddress: '5 Cycle St', paymentMode: 'COD' })

  const setStatus = (id, status) => cy.request({
    method: 'POST', url: '/updateOrderStatus', failOnStatusCode: false,
    headers: { 'Content-Type': 'application/json' },
    body: { id, status },
  })

  /**
   * OMS O5b: an order reaches SHIPPED by RECORDING A PARCEL, not by being marked — the status is derived from
   * line quantities. `setStatus(id, 'SHIPPED')` is now refused on purpose, so anything that needs a shipped
   * order ships everything outstanding through the real dispatch path.
   */
  const shipAll = (id) => cy.request('/getOrder?id=' + id).then((r) => {
    const lines = (r.body.data.items || [])
      .map((l) => ({ orderItemId: l.id, quantity: (l.quantity || 0) - (l.quantityShipped || 0) }))
      .filter((l) => l.quantity > 0)
    expect(lines.length, 'something must be outstanding to ship').to.be.greaterThan(0)
    return cy.request({
      method: 'POST', url: '/shipOrder', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { id, lines, carrier: 'TestVan' },
    })
  })

  // ── OMS-8: identity ───────────────────────────────────────────────────────────────────────────────

  it('an order gets a merchant-facing SO- number, and tracking resolves by it', () => {
    const buyer = 'TrackBuyer_' + uniq()
    place(1, buyer).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const orderNo = r.body.data.orderNo
      expect(orderNo, 'the order carries its own number').to.match(/^SO-/)

      // Tracking by the NUMBER + contact works…
      cy.request({ url: `/storefront/track?ref=${encodeURIComponent(orderNo)}&contact=0300LFC`,
                   failOnStatusCode: false }).then((t) => {
        expect(t.body.success, `track by number: ${JSON.stringify(t.body)}`).to.eq(true)
        expect(t.body.data.ref, 'the reference echoed back is the NUMBER, not a raw id').to.eq(orderNo)
      })

      // …and a wrong contact does not, even with a correct number.
      cy.request({ url: `/storefront/track?ref=${encodeURIComponent(orderNo)}&contact=0000WRONG`,
                   failOnStatusCode: false }).then((t) => {
        expect(t.body.success, 'the contact check still guards it').to.not.eq(true)
      })
    })
  })

  it('a made-up order number is not found — the id space is no longer walkable', () => {
    cy.request({ url: '/storefront/track?ref=SO-999999&contact=0300LFC', failOnStatusCode: false })
      .then((t) => expect(t.body.success, 'a guessed number reveals nothing').to.not.eq(true))
  })

  // ── OMS-3: idempotent placement ───────────────────────────────────────────────────────────────────

  it('the same cart submitted twice yields ONE order, not just one invoice', () => {
    // The O1 gate proves one INVOICE. This proves one ORDER — before O2 the second submit replayed the invoice
    // but still inserted a second order row, which would be picked and shipped twice.
    const buyer = 'DupeOrder_' + uniq()
    let cartToken, firstOrderNo

    cy.request({
      method: 'POST', url: '/storefront/cart/add', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, productId, quantity: 1 },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      cartToken = r.body.data.cartToken
    })

    const checkout = () => cy.request({
      method: 'POST', url: '/storefront/checkout', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, cartToken, customerName: buyer, customerContact: '0300DUP',
              shippingAddress: '6 Dupe St', paymentMode: 'COD' },
    })

    cy.then(() => checkout().then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      firstOrderNo = r.body.data.orderNo
      expect(firstOrderNo).to.match(/^SO-/)
    }))

    cy.then(() => checkout().then((r) => {
      if (r.body.success && r.body.data && r.body.data.orderNo) {
        expect(r.body.data.orderNo, 'a replay, not a second order').to.eq(firstOrderNo)
      }
    }))

    // And only ONE order row exists for this buyer.
    // OMS O4: paginated + filterable. Filtering by the buyer is what makes "exactly one" exact — an unfiltered
    // page could hold one of two duplicates and still look correct.
    cy.then(() => cy.request('/getOrders?q=' + encodeURIComponent(buyer)).then((r) => {
      const mine = ((r.body.data && r.body.data.content) || []).filter((o) => o.customerName === buyer)
      expect(mine.length, 'exactly one order for one checkout').to.eq(1)
    }))
  })

  // ── OMS-2: the state machine ──────────────────────────────────────────────────────────────────────

  it('the normal fulfilment path is allowed end to end', () => {
    place(1, 'FlowBuyer_' + uniq()).then((r) => {
      const id = r.body.data.id
      setStatus(id, 'PACKED').then((s) => expect(s.body.success, JSON.stringify(s.body)).to.eq(true))
      // O5b: dispatching the goods is what makes it SHIPPED.
      shipAll(id).then((s) => expect(s.body.success, JSON.stringify(s.body)).to.eq(true))
      setStatus(id, 'DELIVERED').then((s) => expect(s.body.success, JSON.stringify(s.body)).to.eq(true))
    })
  })

  it('an order cannot skip straight to DELIVERED — it must be reached, not declared', () => {
    place(1, 'SkipBuyer_' + uniq()).then((r) => {
      setStatus(r.body.data.id, 'DELIVERED').then((s) => {
        expect(s.body.success, `NEW -> DELIVERED must be refused: ${JSON.stringify(s.body)}`).to.not.eq(true)
        expect(String(s.body.message || '')).to.match(/cannot become/i)
      })
    })
  })

  it('a CANCELLED order can never be shipped — its money and stock are already reversed', () => {
    place(1, 'CancelShip_' + uniq()).then((r) => {
      const id = r.body.data.id
      setStatus(id, 'CANCELLED').then((s) => expect(s.body.success, JSON.stringify(s.body)).to.eq(true))

      // O5b: the same defect, at the endpoint that can now actually cause it — dispatching goods against an
      // order whose money and stock have already been reversed.
      shipAll(id).then((s) => {
        expect(s.body.success, `CANCELLED -> shipping must be refused: ${JSON.stringify(s.body)}`).to.not.eq(true)
      })

      // And the refusal left the order alone — a rejected transition must not half-apply.
      cy.request('/getOrders').then((list) => {   // OMS O4: paginated — the order is the newest, so page 1
        const o = ((list.body.data && list.body.data.content) || []).find((x) => x.id === id)
        expect(o.fulfilmentStatus, 'still cancelled').to.eq('CANCELLED')
      })
    })
  })

  it('a shipped order cannot be cancelled — the goods are on a van, not on the shelf', () => {
    place(1, 'ShippedCancel_' + uniq()).then((r) => {
      const id = r.body.data.id
      setStatus(id, 'PACKED')
      shipAll(id).then((s) => expect(s.body.success, JSON.stringify(s.body)).to.eq(true))

      setStatus(id, 'CANCELLED').then((s) => {
        // Cancelling would return stock that has not physically come back, inflating on-hand.
        expect(s.body.success, `SHIPPED -> CANCELLED must be refused: ${JSON.stringify(s.body)}`).to.not.eq(true)
      })
    })
  })
})
