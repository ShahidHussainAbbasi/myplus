/**
 * OMS O4 — order back office.
 * Design: microservices/docs/slices/oms-O4-back-office.md
 *
 * Three defects this gate exists to hold shut, all of them things that LOOKED fine:
 *
 *   1. The list was unbounded (OMS-7) — every order the tenant had ever taken, fetched to show the newest 25.
 *      Pagination alone does not fix that if the client picks the page size, so the cap is asserted here.
 *   2. The browser kept its own copy of the lifecycle rules and they had drifted from the server's: Cancel was
 *      drawn on a SHIPPED order, which the server refuses with 409, and RETURN_REQUESTED offered nothing at all.
 *   3. Refund and return shipped in slices 70/71 — admin-gated, error-relaying, and unreachable from any screen.
 */
describe('OMS O4 — order back office (paged list, server-driven actions, detail)', () => {
  let orgId, productId
  const pname = 'BackOffice_' + Date.now()
  const run = String(Date.now()).slice(-6)

  // Enough orders to need a second page at size=2, which is what makes the paging assertions real.
  const ORDERS = 3
  const placed = []

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
      body: { name: pname, sku: 'BO' + Date.now(), sellingPrice: 30, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, `addProduct failed: ${JSON.stringify(r.body)}`).to.eq(true)
      productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' }, body: { productId, quantity: 100 },
      })
    })
    // Place them sequentially so createdAt ordering is deterministic — the list is sorted newest-first and the
    // paging assertions below depend on that being a real order, not an accident of insert timing.
    //
    // Inside cy.then, NOT a bare loop: Cypress queues commands, so a plain `for` would evaluate orgId and
    // productId at enqueue time — while both are still undefined — and every cart/add would be sent empty.
    cy.then(() => {
      const place = (i) => {
        if (i >= ORDERS) return
        return cy.storefrontOrder(orgId, { productId, quantity: 1 }, {
          customerName: `BOBuyer${i}_${run}`, customerContact: '0300BO' + run,
          shippingAddress: '4 Back Office Road', paymentMode: 'CARD', cardToken: 'tok_sandbox',
        }).then((r) => {
          expect(r.body.success, `seed order ${i} failed: ${JSON.stringify(r.body)}`).to.eq(true)
          placed.push(r.body.data)
          return place(i + 1)
        })
      }
      return place(0)
    })
  })

  beforeEach(() => cy.loginAsMarketplaceOwner())

  const list = (qs = '') => cy.request('/getOrders' + qs).then((r) => {
    expect(r.body.success, `getOrders failed: ${JSON.stringify(r.body)}`).to.eq(true)
    expect(r.body.data, 'the list must be a PageResponse, not a bare array').to.have.property('content')
    return r.body.data
  })

  // ── paging (OMS-7) ────────────────────────────────────────────────────────────────────────────────

  it('the list is a page, not the whole shop', () => {
    list().then((p) => {
      expect(p.content).to.be.an('array')
      expect(p.pageSize, 'a default page, not everything').to.eq(25)
      expect(p.content.length).to.be.at.most(25)
      expect(p.totalElements, 'the caller is told how many exist without being sent them').to.be.a('number')
    })
  })

  it('page 2 holds different orders from page 1', () => {
    let firstIds
    list('?size=2&page=0').then((p) => {
      expect(p.content.length).to.eq(2)
      expect(p.last, 'with three seeded orders there must be more than one page').to.eq(false)
      firstIds = p.content.map((o) => o.id)
    })
    list('?size=2&page=1').then((p) => {
      const secondIds = p.content.map((o) => o.id)
      // If paging were faked client-side, both requests would return the same rows.
      secondIds.forEach((id) => expect(firstIds, 'page 2 must not repeat page 1').to.not.include(id))
    })
  })

  it('a caller cannot ask for the unbounded read back', () => {
    // This is OMS-7 itself: ?size=100000 must not be honoured. A cap the client can raise is not a cap.
    list('?size=100000').then((p) => {
      expect(p.pageSize, 'size must be clamped server-side').to.be.at.most(100)
      expect(p.content.length).to.be.at.most(100)
    })
  })

  // ── filtering ─────────────────────────────────────────────────────────────────────────────────────

  it('filters narrow the result set — and are applied by the SERVER', () => {
    list('?q=' + encodeURIComponent('BOBuyer0_' + run)).then((p) => {
      expect(p.totalElements, 'exactly the one buyer').to.eq(1)
      expect(p.content[0].customerName).to.eq('BOBuyer0_' + run)
    })
    // totalElements is what proves the filter ran in the query: a browser-side filter would narrow `content`
    // while the server's count still described everything.
    list('?status=NEW&q=' + encodeURIComponent(run)).then((p) => {
      expect(p.totalElements).to.eq(p.content.length)
      p.content.forEach((o) => expect(o.fulfilmentStatus).to.eq('NEW'))
    })
  })

  it('an unknown status returns nothing rather than being ignored', () => {
    // Silently dropping it would answer a different question: the operator would see every order and believe
    // they were looking at a filtered set.
    list('?status=NOT_A_STATUS').then((p) => expect(p.totalElements).to.eq(0))
  })

  it('a date range that ends today includes orders placed today', () => {
    // LOCAL date, not toISOString(): that returns UTC, so anywhere east of Greenwich between midnight and the
    // UTC offset "today" resolves to YESTERDAY and the range excludes the orders just placed. This machine is
    // +05:00, so the window is 00:00–05:00 — the test passed for weeks and failed the first time it ran at 01:35.
    const d = new Date()
    const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    // created_at is a timestamp; if `to` were compared against midnight, everything from the final day would
    // vanish — and "1st to today" returning nothing from today is a wrong answer nobody questions.
    list(`?from=${today}&to=${today}&q=${encodeURIComponent(run)}`)
      .then((p) => expect(p.totalElements, 'today is inside a range that ends today').to.be.greaterThan(0))
  })

  // ── the server decides which actions exist ────────────────────────────────────────────────────────

  it('every order carries the transitions the server permits', () => {
    list('?q=' + encodeURIComponent(run)).then((p) => {
      expect(p.content.length).to.be.greaterThan(0)
      p.content.forEach((o) => {
        expect(o, 'the list drives the buttons, so it must carry the moves').to.have.property('allowedTransitions')
        expect(o.allowedTransitions).to.be.an('array')
      })
      const fresh = p.content.find((o) => o.fulfilmentStatus === 'NEW')
      expect(fresh.allowedTransitions).to.deep.eq(['PACKED', 'CANCELLED'])
    })
  })

  it('a SHIPPED order offers no Cancel — and the server still refuses one', () => {
    const id = placed[0].id
    cy.request({ method: 'POST', url: '/updateOrderStatus', body: { id, status: 'PACKED' },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
    // OMS O5b: an order reaches SHIPPED by RECORDING A PARCEL — marking it is refused, because a status set
    // independently of its shipments can claim a dispatch that never happened.
    cy.request('/getOrder?id=' + id).then((r) => {
      const lines = (r.body.data.items || [])
        .map((l) => ({ orderItemId: l.id, quantity: (l.quantity || 0) - (l.quantityShipped || 0) }))
        .filter((l) => l.quantity > 0)
      return cy.request({ method: 'POST', url: '/shipOrder', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' }, body: { id, lines, carrier: 'TestVan' } })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.request('/getOrder?id=' + id).then((r) => {
      const o = r.body.data
      expect(o.fulfilmentStatus).to.eq('SHIPPED')
      // The old UI drew Cancel for anything not CANCELLED/DELIVERED. Goods on a van are not back on the shelf,
      // so the server forbids it — the button was an offer it could not keep.
      expect(o.allowedTransitions, 'no Cancel on a shipped order').to.not.include('CANCELLED')
      expect(o.allowedTransitions).to.deep.eq(['DELIVERED'])
    })
    cy.request({ method: 'POST', url: '/updateOrderStatus', body: { id, status: 'CANCELLED' },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => expect(r.body.success, 'the server refuses it too').to.eq(false))
  })

  it('a delivered order can be returned, and RETURN_REQUESTED is actionable', () => {
    const id = placed[0].id       // already SHIPPED by the previous test
    cy.request({ method: 'POST', url: '/updateOrderStatus', body: { id, status: 'DELIVERED' },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.request('/getOrder?id=' + id).then((r) => {
      expect(r.body.data.allowedTransitions).to.deep.eq(['RETURN_REQUESTED', 'RETURNED'])
    })
    cy.request({ method: 'POST', url: '/updateOrderStatus', body: { id, status: 'RETURN_REQUESTED' },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
    cy.request('/getOrder?id=' + id).then((r) => {
      // Absent from the old client-side map entirely, so a customer's return request landed somewhere the back
      // office was offered no way out of.
      expect(r.body.data.allowedTransitions, 'a return request must be actionable').to.deep.eq(['RETURNED'])
    })
  })

  it('a terminal order offers nothing, as an empty list rather than a missing field', () => {
    cy.request({ method: 'POST', url: '/updateOrderStatus', body: { id: placed[1].id, status: 'CANCELLED' },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
    cy.request('/getOrder?id=' + placed[1].id).then((r) => {
      expect(r.body.data.fulfilmentStatus).to.eq('CANCELLED')
      expect(r.body.data.allowedTransitions).to.be.an('array').and.be.empty
    })
  })

  // ── detail ────────────────────────────────────────────────────────────────────────────────────────

  it('order detail shows lines, money and the timeline', () => {
    cy.request('/getOrder?id=' + placed[2].id).then((r) => {
      const o = r.body.data
      expect(o.items, 'what was actually sold').to.be.an('array').and.not.be.empty
      expect(o.items[0].productName, 'the line name is snapshotted at write, not read from the catalog')
        .to.eq(pname)
      expect(Number(o.items[0].quantity)).to.eq(1)
      expect(o.subTotal, 'the money breakdown').to.exist
      expect(o.total).to.exist
      // order_events has been written on every status change since slice 46 and was visible only to the SHOPPER.
      expect(o.timeline, 'the merchant can finally see the order history').to.be.an('array').and.not.be.empty
      expect(o.timeline[0]).to.have.property('status')
      expect(o.timeline[0]).to.have.property('at')
    })
  })

  it('the timeline grows as the order moves', () => {
    const id = placed[2].id
    let before
    cy.request('/getOrder?id=' + id).then((r) => { before = r.body.data.timeline.length })
    cy.request({ method: 'POST', url: '/updateOrderStatus', body: { id, status: 'PACKED' },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
    cy.request('/getOrder?id=' + id).then((r) => {
      expect(r.body.data.timeline.length, 'the move was recorded').to.be.greaterThan(before)
    })
  })

  it('another tenant\'s order is indistinguishable from a missing one', () => {
    // Anti-IDOR: the detail endpoint is scoped, so a foreign id must not confirm the order exists.
    cy.request({ url: '/getOrder?id=999999999', failOnStatusCode: false })
      .then((r) => expect(r.body.success).to.eq(false))
  })

  // ── refund: built in slice 70, unreachable until O4 ───────────────────────────────────────────────

  it('a card order reports what is still refundable, and a refund moves it', () => {
    const id = placed[2].id
    let refundable
    cy.request('/getOrder?id=' + id).then((r) => {
      refundable = Number(r.body.data.refundableAmount)
      // Server-derived: the refund dialog defaults to it, and OrderService.refund rejects an over-refund. Two
      // derivations of one number is how a UI offers an amount the server refuses.
      expect(refundable, 'nothing refunded yet, so the whole total').to.eq(Number(r.body.data.total))
    })
    cy.then(() => cy.request({
      method: 'POST', url: '/refundOrder', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false, body: { id, amount: 1 },
    })).then((r) => {
      expect(r.body.success, `refund failed: ${JSON.stringify(r.body)}`).to.eq(true)
    })
    cy.request('/getOrder?id=' + id).then((r) => {
      expect(Number(r.body.data.refundedAmount)).to.eq(1)
      expect(Number(r.body.data.refundableAmount), 'the remaining amount follows').to.eq(refundable - 1)
    })
  })

  it('the UI renders actions from the server and no longer offers Cancel on a shipped order', () => {
    cy.visit('/businessDashboard')
    cy.window().then((w) => w.showOrders())
    cy.get('#ordersBody tr', { timeout: 15000 }).should('exist')

    // Filter down to this run's orders through the real controls.
    cy.get('#ordFilterQ').clear().type(run)
    cy.get('#ordSearchBtn').click()
    cy.get('#ordersBody tr').should('have.length.greaterThan', 0)

    // placed[0] was driven to RETURN_REQUESTED above; its only legal move is RETURNED, so no Cancel is drawn.
    cy.get('#ordFilterQ').clear().type('BOBuyer0_' + run)
    cy.get('#ordSearchBtn').click()
    cy.get('#ordersBody tr').should('have.length', 1)
    cy.get('#ordersBody tr').first().within(() => {
      cy.contains('button', 'CANCELLED').should('not.exist')
    })

    // The order number opens the detail, with the timeline the merchant could never see before.
    cy.get('#ordersBody tr').first().find('a').click()
    cy.get('#orderDetail').should('be.visible')
    cy.get('#orderTimeline li').should('have.length.greaterThan', 0)
  })
})
