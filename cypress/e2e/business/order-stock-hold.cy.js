/**
 * O7 D1c — a confirmed order's stock is actually set aside.
 *
 * <h3>The gap</h3>
 * §8.1 departure #1, recorded honestly at the time: *"two orders confirmed for the last carton will both
 * confirm, and the second will fail or backorder at dispatch."* A confirmed order is a promise to a named
 * shopkeeper, and until now nothing backed it until the van was being loaded — the worst possible moment to
 * find out, with the rep gone and the customer already told.
 *
 * <h3>What is measured, and why it is not the response body</h3>
 * A hold does NOT decrement on-hand; it raises `reserved`. So "did the promise get made" is only visible in the
 * SELLABLE figure, read back from `/productStockLevels` (inventory's `{onHand, sellable, held}` per product).
 * A test asserting the confirm returned 200 would pass on a build that held nothing at all.
 *
 * <h3>What is deliberately NOT here</h3>
 * That an ORDER hold outlives a CHECKOUT hold — three days against thirty minutes — is the single most
 * important property of this slice, and it is asserted in `OrderHoldExpiryTest` instead. Nothing observable at
 * confirm time distinguishes the two; a spec could only tell them apart by waiting out the clock.
 */
describe('O7 D1c — confirming an order sets its stock aside', () => {
  const run = String(Date.now()).slice(-6)
  const ctx = {}
  const PRODUCT = 'HoldProd_' + run
  const STOCK = 20

  before(() => {
    cy.loginAsMarketplaceOwner()
    cy.seedProduct({ name: PRODUCT, sellingPrice: 100, stock: STOCK })
      .then((p) => { ctx.productId = p.productId })

    const name = 'HoldOutlet_' + run
    cy.then(() => cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name, contact: '0309' + run, creditLimit: 1000000 },
    })).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    cy.then(() => cy.request('/getUserCustomer')).then((r) => {
      const row = (r.body.collection || r.body.data || []).filter((c) => c.name === name)[0]
      expect(row, 'the outlet exists').to.exist
      ctx.outletId = row.customerId || row.id
      ctx.outletName = name
    })
  })

  beforeEach(() => cy.loginAsMarketplaceOwner())

  /** What inventory says is genuinely sellable for our product right now. */
  const sellable = () =>
    cy.request('/productStockLevels').then((r) => {
      expect(r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)
      const lvl = (r.body.levels || {})[String(ctx.productId)]
      expect(lvl, 'inventory reports a level for the seeded product').to.exist
      return Number(lvl.sellable != null ? lvl.sellable : lvl.onHand)
    })

  const onHand = () =>
    cy.request('/productStockLevels').then((r) => {
      const lvl = (r.body.levels || {})[String(ctx.productId)]
      return Number(lvl.onHand)
    })

  /*
   * Every order this spec creates, so nothing is left holding stock.
   *
   * The per-test cancels below are not enough on their own: a case that FAILS never reaches its own cleanup,
   * and one that did exactly that turned a single failure into two — an order left confirmed held four units,
   * and the next case measured the leak as a double-hold bug that did not exist. Cleanup that only runs on the
   * happy path is cleanup that vanishes precisely when it is needed.
   */
  const created = []

  after(() => {
    cy.loginAsMarketplaceOwner()
    created.forEach((id) => cy.request({
      method: 'POST', url: '/updateOrderStatus', headers: { 'Content-Type': 'application/json' },
      body: { id, status: 'CANCELLED' }, failOnStatusCode: false,
    }))
  })

  /** Book an order and leave it PENDING_APPROVAL. */
  const book = (qty) => {
    let orderId, lineId
    return cy.request({
      method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: {
        customerId: ctx.outletId, customerName: ctx.outletName, customerContact: '0309' + run,
        items: [{ productId: ctx.productId, quantity: qty, price: 100, productName: PRODUCT }],
      },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      orderId = r.body.data.id
      created.push(orderId)
      return cy.request('/getOrder?id=' + orderId)
    }).then((r) => {
      lineId = r.body.data.items[0].id
      return cy.wrap({ orderId, lineId })
    })
  }

  const confirm = (o) => cy.request({
    method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
    body: { id: o.orderId }, failOnStatusCode: false,
  })

  const setStatus = (o, status) => cy.request({
    method: 'POST', url: '/updateOrderStatus', headers: { 'Content-Type': 'application/json' },
    body: { id: o.orderId, status }, failOnStatusCode: false,
  })

  const ship = (o, qty) => cy.request({
    method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: { id: o.orderId, lines: [{ orderItemId: o.lineId, quantity: qty }], carrier: 'D1c' },
  })

  // ── the point of the slice ────────────────────────────────────────────────────────────────────

  it('THE CASE — confirming reduces what is sellable, by exactly the ordered quantity', () => {
    let before
    cy.then(sellable).then((s) => { before = s })

    book(5).then((o) => {
      // POSITIVE CONTROL: a BOOKED order holds nothing. Without this, "sellable went down" could be
      // measuring the booking rather than the confirm, and the slice would look done while doing nothing.
      cy.then(sellable).then((afterBooking) => {
        expect(afterBooking, 'a booked order holds no stock — it is not a promise yet').to.eq(before)
      })

      confirm(o).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

      cy.then(sellable).then((afterConfirm) => {
        expect(afterConfirm, 'the confirmed order has its goods set aside').to.eq(before - 5)
      })
      // ...and the goods have NOT left the building. A hold raises `reserved`; it does not decrement on-hand.
      cy.then(onHand).then((oh) => {
        expect(oh, 'a hold is not a sale — nothing has shipped').to.be.greaterThan(0)
      })
      cy.then(() => setStatus(o, 'CANCELLED'))          // leave no promise behind
    })
  })

  it('a CONFIRMED order cannot be rejected — cancel is how it is undone', () => {
    /*
     * This case was written the obvious way — "reject a confirmed order and the stock comes back" — and the
     * gate answered "Only an order awaiting review can be rejected. This one is NEW."
     *
     * It is the product that is right. `requirePending` allows a rejection only from PENDING_APPROVAL, and
     * stock is held at CONFIRM, so an order that can be rejected has never held any. The release that D1c
     * originally put in `reject` was therefore unreachable and has been removed.
     *
     * Asserted so nobody re-adds it: if this ever starts succeeding, the lifecycle has changed and the hold
     * needs releasing there after all.
     */
    let before
    cy.then(sellable).then((s) => { before = s })
    book(4).then((o) => {
      confirm(o).then((r) => expect(r.body.success).to.eq(true))
      cy.then(sellable).then((held) => expect(held, 'held while confirmed').to.eq(before - 4))

      cy.then(() => cy.request({
        method: 'POST', url: '/rejectOrder', headers: { 'Content-Type': 'application/json' },
        body: { id: o.orderId, reason: 'D1c gate' }, failOnStatusCode: false,
      })).then((r) => {
        expect(r.body.success, 'a confirmed order is past the point of rejection').to.eq(false)
        expect(r.body.message).to.match(/awaiting review/i)
      })

      // Undo it the way the lifecycle actually allows, and the stock comes back.
      cy.then(() => setStatus(o, 'CANCELLED'))
      cy.then(sellable).then((after) => expect(after, 'cancel releases it').to.eq(before))
    })
  })

  it('cancelling a confirmed order puts the stock back', () => {
    /*
     * Deliberately its own case. The cancel path's existing reversal is guarded on "is there an INVOICE or a
     * pre-O1 reservation to undo" — a confirmed-but-never-dispatched order has neither, so it fails that test
     * while still holding stock. The release therefore sits OUTSIDE that guard, and this is what proves it.
     */
    let before
    cy.then(sellable).then((s) => { before = s })
    book(3).then((o) => {
      confirm(o).then((r) => expect(r.body.success).to.eq(true))
      cy.then(sellable).then((held) => expect(held, 'held while confirmed').to.eq(before - 3))

      cy.then(() => setStatus(o, 'CANCELLED'))
      cy.then(sellable).then((after) => {
        expect(after, 'a cancelled order promises nothing').to.eq(before)
      })
    })
  })

  // ── the handover at dispatch ──────────────────────────────────────────────────────────────────

  it('dispatch does NOT hold the same goods twice', () => {
    /*
     * The sale takes its own hold through the normal sell path. If the order's confirm-time hold were still
     * standing, the same cartons would be held twice — and the second reserve could refuse against stock the
     * order itself was holding. On-hand must fall by exactly what shipped, no more.
     */
    let before
    cy.then(onHand).then((s) => { before = s })
    book(4).then((o) => {
      confirm(o).then((r) => expect(r.body.success).to.eq(true))
      cy.then(() => ship(o, 4)).then((r) =>
        expect(r.body.success, JSON.stringify(r.body).slice(0, 300)).to.eq(true))

      cy.then(onHand).then((after) => {
        expect(after, 'exactly the dispatched quantity left the building').to.eq(before - 4)
      })
      /*
       * A fully dispatched order owes nothing, so it must hold nothing — and the honest way to say that is
       * SELLABLE === ON-HAND, since a hold is precisely the gap between the two.
       *
       * A first draft compared sellable against a helper that returned its own argument, which asserts
       * `s === s` and cannot fail. Same shape this programme has been caught by repeatedly: the assertion
       * looked specific and tested nothing.
       */
      cy.then(() => cy.request('/productStockLevels')).then((r) => {
        const lvl = (r.body.levels || {})[String(ctx.productId)]
        const sell = Number(lvl.sellable != null ? lvl.sellable : lvl.onHand)
        expect(sell, 'nothing is still promised on a completed order').to.eq(Number(lvl.onHand))
      })
    })
  })

  it('after a PARTIAL dispatch the remainder is still promised', () => {
    /*
     * The case that decides whether the handover is right. A first parcel releases the order hold so the sale
     * can take its own; if the remainder were not re-held, the goods the customer is still owed would silently
     * become available to another order — and partial dispatch is a distributor's normal week, not an edge.
     */
    let before
    cy.then(sellable).then((s) => { before = s })
    book(6).then((o) => {
      confirm(o).then((r) => expect(r.body.success).to.eq(true))
      cy.then(sellable).then((s) => expect(s, 'all six promised').to.eq(before - 6))

      cy.then(() => ship(o, 2)).then((r) =>
        expect(r.body.success, JSON.stringify(r.body).slice(0, 300)).to.eq(true))

      // 2 shipped (gone from on-hand AND from sellable), 4 still owed and still held.
      cy.then(sellable).then((after) => {
        expect(after, 'the undispatched four are still set aside').to.eq(before - 6)
      })
      cy.then(() => setStatus(o, 'CANCELLED'))
    })
  })

  // ── it must never make things worse ───────────────────────────────────────────────────────────

  it('a confirm always succeeds, and reports rather than refuses when stock is short', () => {
    /*
     * Out of stock is an answer the admin is entitled to act on — a distributor expecting a delivery tomorrow
     * may confirm against it knowingly. Refusing would also make an inventory outage read as a business
     * refusal. Confirming without a hold is exactly the pre-D1c behaviour, so the floor is "no worse than
     * before"; what must never happen is the reverse, claiming a hold that was not taken.
     */
    book(100000).then((o) => {          // far more than was ever seeded
      confirm(o).then((r) => {
        expect(r.body.success, 'the order still confirms').to.eq(true)
        const w = r.body.data.policyWarnings || []
        expect(w.join(' '), 'and the admin is told the stock is not set aside: ' + JSON.stringify(w))
          .to.match(/stock|sellable|hold/i)
      })
      cy.then(() => setStatus(o, 'CANCELLED'))
    })
  })
})
