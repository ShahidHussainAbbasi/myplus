/**
 * MKT-4b — a supplier's dispatch deadline: an order it accepts must leave within 24 hours. Source R20.5 (supplier
 * stock: acceptance, tracking, SLA), R8.2 (the shopper deals with MaxTheService, which chases the supplier), R12.4 (a
 * late record is a note on the scorecard, never a sanction). Contract: microservices/docs/slices/mkt-4b-supplier-dispatch.md
 *
 * TWO PHASES, because "late" needs a day to pass and nothing in the product moves the clock (as MKT-2d):
 *   1. --env '{"mkt":"4b"}'              cases 4b-01..03 on today's stack. Leaves order A accepted and not sent, and
 *                                        order B sent; their numbers are kept in cypress/walk/state-mkt-4b.json.
 *   2. restart the WHOLE stack under a clock two days later (/tmp/claude-0/stack/up.sh +3d when today's is +1d), then
 *      --env '{"mkt":"4b","later":1}'   cases 4b-04..06: A is late, B was on time.
 *   Then restart the stack on its usual clock.
 *
 * The supplier is its own seeded tenant (owner.supplier@, "Phone Supplier"), marked for this gate and unmarked after.
 */
const { gate, uniq, SELLER_A, API, UI, ok, data, list, post, get, seedPolicies, publishOffer } = require('./mkt-helpers')

const SUPPLIER = 'owner.supplier@myplus.com'
const MARK = '/platform/mkt/sellerSupplier'
const PERF = '/platform/mkt/sellerPerformance'
const STATE = 'cypress/walk/state-mkt-4b.json'   // not downloads: Cypress empties that folder before each run
const PRICE = 47000
const LATER = !!Cypress.env('later')
const now = LATER ? it.skip : it
const later = LATER ? it : it.skip

gate('4b')('MKT-4b — a supplier sends what it accepts within 24 hours, or the shopper is told it is late', () => {
  const run = uniq()
  const S = {}

  const asSupplier = () => cy.loginAs(SUPPLIER, 'Demo@2025!', '/getBusinessDashboardStats')
  const newPhone = () => `0316${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`
  const order = () => {
    const phone = newPhone()
    cy.clearCookies()
    cy.visit(UI.publicPage)
    return post(API.checkout, { offerId: S.offer.offerId, quantity: 1, expectedPrice: PRICE, customerName: 'Ali',
      customerPhone: phone, address: '1 Clifton', city: 'Karachi', idempotencyKey: `m4b-${run}-${Math.random()}` })
      .then((r) => {
        expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
        return Object.assign(data(r.body), { phone })
      })
  }
  const part = (orderNo) => {
    asSupplier()
    return get(`${API.incomingOrders}?status=ACCEPTED&size=100`).then((r) => list(r.body).find((x) => x.orderNo === orderNo))
  }
  const accept = (o) => {
    asSupplier()
    cy.then(() => post(API.acceptOrder, { id: o.sellerOrderId, version: o.sellerOrderVersion }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    return cy.then(() => part(o.orderNo))
  }
  const ship = (p, tracking) => {
    asSupplier()
    return cy.then(() => get(`/getOrder?id=${p.storeOrderId}`)).then((r) => {
      const line = data(r.body).items[0]
      return post('/shipOrder', { id: p.storeOrderId, lines: [{ orderItemId: line.id, quantity: line.quantity }],
        carrier: 'Leopards', trackingNumber: tracking }).then((x) => expect(ok(x.body), JSON.stringify(x.body)).to.eq(true))
    })
  }
  const tracked = (o) => {
    cy.clearCookies()
    return get(API.trackOrder(o.orderNo, o.phone)).then((r) => data(r.body).sellerOrders[0])
  }
  const page = (o) => {
    cy.clearCookies()
    // leave the dashboard first: its last requests, now without a session, would send this tab to the sign-in page
    cy.visit(UI.publicPage)
    cy.visit(`${UI.publicPage}?order=${encodeURIComponent(o.orderNo)}&phone=${o.phone}`)
    cy.location('pathname').should('eq', UI.publicPage)
  }
  /** The supplier's Accepted orders on Sale → Marketplace, and the row for this order. */
  const supplierRow = (orderNo) => {
    asSupplier()
    cy.visit('/businessDashboard')
    cy.get('#snavSell .snav-btn').click()
    cy.get(UI.sellerNav).should('be.visible').click()
    cy.intercept('GET', '**/mkt/incomingOrders*status=ACCEPTED*').as('accepted')
    cy.get('#mktIncomingStatus').select('ACCEPTED', { force: true })
    cy.wait('@accepted')
    return cy.contains(`${UI.incoming} tbody tr`, orderNo)
  }
  const mark = (on) => {
    cy.loginAsOperator()
    return cy.then(() => post(MARK, { organizationId: S.org, supplier: on }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  }

  before(() => {
    cy.loginAsOperator()
    cy.orgOf(SUPPLIER).then((o) => { S.org = o.id })
    if (LATER) {
      cy.readFile(STATE).then((st) => Object.assign(S, st))
      return
    }
    seedPolicies(run).then((p) => {
      cy.then(() => publishOffer(SUPPLIER, { run, price: PRICE, qty: 5, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))
        .then((o) => { S.offer = o })
    })
    mark(true)
  })

  after(() => {
    mark(false)
  })

  // ── phase 1: today ───────────────────────────────────────────────────────────────────────────────────

  now('MKT-4b-01 [MKT-R20.5] the supplier accepts: it has 24 hours to send it, on its own orders and on the shopper\'s page (real UI)', () => {
    order().then((o) => { S.A = { orderNo: o.orderNo, phone: o.phone, sellerOrderId: o.sellerOrderId, sellerOrderVersion: o.sellerOrderVersion } })
    cy.then(() => accept(S.A)).then((p) => {
      expect(p.secondsToShip, 'a day to send it, from Accept').to.be.within(23.9 * 3600, 24 * 3600)
      expect(p.dispatchLate).to.eq(false)
    })
    cy.then(() => supplierRow(S.A.orderNo)).find('.mkt-ship-by').invoke('text').should('match', /^Send within (24:00|23:[5]\d)$/)
    cy.then(() => page(S.A))
    cy.get(UI.checkoutStatus).should('have.text', 'Phone Supplier sends it within 24 hours')
    cy.get('#mktOrderDetail').should('have.text', 'Pay Rs. 47,000 in cash when it arrives.')
  })

  now('MKT-4b-02 [MKT-R20.5] once a parcel leaves, the deadline is gone: the shopper sees the carrier and tracking (real UI)', () => {
    order().then((o) => { S.B = { orderNo: o.orderNo, phone: o.phone, sellerOrderId: o.sellerOrderId, sellerOrderVersion: o.sellerOrderVersion } })
    cy.then(() => accept(S.B)).then((p) => ship(p, `LP-${run}`))
    cy.then(() => part(S.B.orderNo)).then((p) => {
      expect(p.secondsToShip, 'sent: no clock').to.eq(null)
      expect(p.dispatchLate).to.eq(false)
    })
    cy.then(() => tracked(S.B)).then((p) => {
      expect(p.secondsToShip).to.eq(null)
      expect(p.parcels.map((x) => x.trackingNumber)).to.include(`LP-${run}`)
    })
    cy.then(() => page(S.B))
    cy.get(UI.checkoutStatus).should('have.text', 'On its way from Phone Supplier')
    cy.get('#mktOrderDetail').should('contain', `Leopards · tracking LP-${run}.`)
    cy.then(() => supplierRow(S.B.orderNo)).find('.mkt-ship-by, .mkt-ship-late').should('not.exist')
  })

  now('MKT-4b-03 [MKT-R20.5] a shop\'s accepted orders have no dispatch deadline; the two supplier orders are kept for phase 2', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    cy.then(() => get(`${API.incomingOrders}?status=ACCEPTED&size=100`)).then((r) => {
      list(r.body).forEach((x) => {
        expect(x.secondsToShip, x.orderNo).to.eq(null)
        expect(x.dispatchLate, x.orderNo).to.eq(false)
      })
    })
    cy.then(() => cy.writeFile(STATE, { A: S.A, B: S.B }))
  })

  // ── phase 2: the stack restarted two days later ──────────────────────────────────────────────────────

  /** The sweeper settles a deadline within its 30-second pass; wait for it rather than assume it ran. */
  const settled = (orderNo, tries = 12) => part(orderNo).then((p) => {
    if (p.dispatchLate || tries <= 0) return p
    cy.wait(5000)
    return settled(orderNo, tries - 1)
  })

  later('MKT-4b-04 [MKT-R20.5] [MKT-R8.2] a day later the unsent order is late: the supplier is told to send it now, the shopper that MaxTheService is chasing it (real UI)', () => {
    cy.then(() => settled(S.A.orderNo)).then((p) => {
      expect(p.dispatchLate, 'not sent within 24 hours').to.eq(true)
      expect(p.secondsToShip).to.eq(0)
    })
    cy.then(() => supplierRow(S.A.orderNo)).find('.mkt-ship-late')
      .should('have.text', 'Late: it was due to leave within 24 hours of accepting. Send it now.')
    cy.then(() => page(S.A))
    cy.get(UI.checkoutStatus).should('have.text', 'Phone Supplier is late sending this. MaxTheService is chasing it.')
      .and('have.class', 'wait')
    cy.then(() => tracked(S.B)).then((p) => expect(p.dispatchLate, 'B left in time').to.eq(false))
  })

  later('MKT-4b-05 [MKT-R20.5] [MKT-R12.4] the operator\'s scorecard counts what Phone Supplier sent within 24 hours (real UI)', () => {
    cy.loginAsOperator()
    cy.then(() => get(`${PERF}?days=7`)).then((r) => {
      const row = data(r.body).sellers.find((x) => x.organizationId === S.org)
      expect(row, 'Phone Supplier has orders this week').to.exist
      expect(row.shipDue, 'A and B are settled').to.be.at.least(2)
      expect(row.shippedOnTime, 'B at least').to.be.at.least(1)
      expect(row.shippedOnTime).to.be.lessThan(row.shipDue)
      S.row = row
    })
    cy.visit('/platformDashboard')
    cy.intercept('GET', '**/platform/mkt/sellerPerformance*').as('perf')
    cy.get('#platMktPerformanceBtn').should('be.visible').click()
    cy.wait('@perf')
    cy.then(() => cy.get(`#mktPerfList .mkt-perf-row[data-org="${S.org}"] .mkt-perf-ship`))
      .invoke('text').should('match', /^Sent within 24 hours: (Not enough orders yet|\d+% \(\d+ of \d+\))$/)
  })

  later('MKT-4b-06 [MKT-R20.5] sent late, the shopper sees it on its way; the record stays late', () => {
    cy.then(() => part(S.A.orderNo)).then((p) => ship(p, `LP-late-${run}`))
    cy.then(() => page(S.A))
    cy.get(UI.checkoutStatus).should('have.text', 'On its way from Phone Supplier')
    cy.then(() => part(S.A.orderNo)).then((p) => {
      expect(p.dispatchLate, 'gone from the screen once sent').to.eq(false)
      expect(p.secondsToShip).to.eq(null)
    })
    cy.loginAsOperator()
    cy.then(() => get(`${PERF}?days=7`)).then((r) => {
      const row = data(r.body).sellers.find((x) => x.organizationId === S.org)
      expect(row.shipDue - row.shippedOnTime, 'still counted as late').to.eq(S.row.shipDue - S.row.shippedOnTime)
    })
    // cleanup: both orders delivered, so nothing is left waiting on the supplier
    ;[S.A, S.B].forEach((o) => {
      cy.then(() => part(o.orderNo)).then((p) => post('/updateOrderStatus', { id: p.storeOrderId, status: 'DELIVERED' }))
    })
  })
})
