/**
 * MKT-4c — a slow or failing supplier never hangs checkout, and a retried step is done once. Source R22.5 ("every
 * external supplier call has timeout + circuit breaker; every webhook/event retryable and idempotent").
 * Contract: microservices/docs/slices/mkt-4c-supplier-resilience.md
 *
 * Ruling 2026-10-15: a supplier is a MyPlus business, so its stock is held through the same live routing as every
 * seller's (MKT-2c: 800 ms per seller, 2 s per checkout, 3 failures in a row open its circuit for 30 s). This gate
 * proves that on a marked supplier, and that the steps a client may retry (checkout, Accept, a shipment) take effect
 * once. Needs marketplace-service started with MKT_ROUTING_TEST_SWITCH=true, as MKT-2c.
 *
 * Leaves the supplier unmarked, not slow, with its circuit closed; every order it placed is rejected or delivered.
 */
const { gate, uniq, API, UI, ok, data, list, post, get, expectRefused, seedPolicies, publishOffer } = require('./mkt-helpers')

const SUPPLIER = 'owner.supplier@myplus.com'
const NAME = 'Phone Supplier'
const ROUTING = { view: '/platform/mkt/routing', close: '/platform/mkt/routingClose', test: '/platform/mkt/routingTest' }
const SLOW_ONE = 'This seller did not answer in time. Please choose another offer.'
const ANSWER_WITHIN_MS = 2500
const PRICE = 46000

gate('4c')('MKT-4c — a slow supplier never hangs checkout; a retried step is done once', () => {
  const run = uniq()
  const S = {}

  const asSupplier = () => cy.loginAs(SUPPLIER, 'Demo@2025!', '/getBusinessDashboardStats')
  const newPhone = () => `0317${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`
  /** The supplier answers after `ms` (0 = normally), and its circuit is closed. */
  const slow = (ms) => {
    cy.loginAsOperator()
    post(ROUTING.test, { sellerOrganizationId: ms ? S.org : null, delayMs: ms }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    return post(ROUTING.close, { sellerOrganizationId: S.org }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  }
  const buy = (offer, key) => {
    const phone = newPhone()
    cy.clearCookies()
    cy.visit(UI.publicPage)
    let t0
    const body = { offerId: offer.offerId, quantity: 1, expectedPrice: PRICE, customerName: 'Ali', customerPhone: phone,
      address: '1 Clifton', city: 'Karachi', idempotencyKey: key || `m4c-${run}-${Math.random()}` }
    return cy.then(() => { t0 = Date.now() }).then(() => post(API.checkout, body)).then((r) => ({ r, ms: Date.now() - t0, body }))
  }
  const incoming = (status, orderNo) => {
    asSupplier()
    return get(`${API.incomingOrders}?status=${status}&size=100`).then((r) => list(r.body).filter((x) => x.orderNo === orderNo))
  }
  const reject = (orderNo) => incoming('OFFERED', orderNo).then((xs) => {
    expect(xs, `${orderNo} waits for the supplier`).to.have.length(1)
    return post(API.rejectOrder, { id: xs[0].id, version: xs[0].version, reason: 'gate cleanup' })
  }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  const letTheLateHoldLand = () => cy.wait(3500)

  before(() => {
    cy.loginAsOperator()
    cy.orgOf(SUPPLIER).then((o) => { S.org = o.id })
    cy.then(() => get(ROUTING.view)).then((r) => {
      expect(data(r.body).testSwitch, 'marketplace-service must be started with MKT_ROUTING_TEST_SWITCH=true').to.eq(true)
    })
    seedPolicies(run).then((p) => {
      // one unit: a hold that was never given back would refuse the next shopper (as MKT-2c)
      cy.then(() => publishOffer(SUPPLIER, { run: `${run}a`, price: PRICE, qty: 1, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))
        .then((o) => { S.one = o })
      cy.then(() => publishOffer(SUPPLIER, { run: `${run}b`, price: PRICE, qty: 3, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))
        .then((o) => { S.more = o })
    })
    cy.loginAsOperator()
    cy.then(() => post('/platform/mkt/sellerSupplier', { organizationId: S.org, supplier: true }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => get(`${API.publicOffers(S.one.mktProductId)}?city=Karachi`))
      .then((r) => expect(list(r.body).find((x) => x.offerId === S.one.offerId).dropship, 'supplier stock').to.eq(true))
  })

  after(() => {
    slow(0)
    cy.loginAsOperator()
    post('/platform/mkt/sellerSupplier', { organizationId: S.org, supplier: false })
  })

  it('MKT-4c-01 [MKT-R22.5] a supplier that does not answer in time: the shopper is told within the deadline, and the late hold is given back', () => {
    slow(3000)
    buy(S.one).then(({ r, ms }) => {
      expectRefused(r, SLOW_ONE)
      expect(ms, 'the 800 ms timeout, not the supplier\'s 3 s').to.be.lessThan(ANSWER_WITHIN_MS)
    })
    letTheLateHoldLand()
    slow(0)
    buy(S.one).then(({ r }) => {
      expect(ok(r.body), `the only unit is free again: ${JSON.stringify(r.body)}`).to.eq(true)
      return reject(data(r.body).orderNo)
    })
  })

  it('MKT-4c-02 [MKT-R22.5] after 3 calls in a row it did not answer, the supplier is not asked: refused at once; the operator sees it and asks it again (real UI)', () => {
    slow(3000)
    for (let i = 0; i < 3; i++) buy(S.one).then(({ r }) => expectRefused(r, SLOW_ONE))
    buy(S.one).then(({ r, ms }) => {
      expectRefused(r, SLOW_ONE)
      expect(ms, 'the circuit is open: no call, no wait').to.be.lessThan(800)
    })
    cy.loginAsOperator()
    cy.visit('/platformDashboard')
    cy.get('#platMktPoliciesBtn').click()
    cy.contains('#mktRoutingOpen .mkt-routing-row', NAME).should('contain', 'is not answering: not asked until')
    cy.get('#mktRoutingDelay').clear().type('0')
    cy.get('#mktRoutingTestSave').click()
    cy.get('#mktRoutingMsg').should('contain', 'Test slowness is off.')
    cy.contains('#mktRoutingOpen .mkt-routing-row', NAME).find('.mkt-routing-close').click()
    cy.get('#mktRoutingMsg').should('contain', 'The seller will be asked again on its next order.')
    cy.get('#mktRoutingOpen').should('not.contain', NAME)
    letTheLateHoldLand()
    buy(S.one).then(({ r }) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      return reject(data(r.body).orderNo)
    })
  })

  it('MKT-4c-03 [MKT-R22.5] retried steps take effect once: the same checkout twice is one order, Accept twice is one sale, a shipment sent again is refused', () => {
    slow(0)
    buy(S.more).then(({ r, body }) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      S.o = Object.assign(data(r.body), { phone: body.customerPhone })
      // the client did not see the answer and sends the same request again
      return post(API.checkout, body).then((again) => {
        expect(ok(again.body), JSON.stringify(again.body)).to.eq(true)
        expect(data(again.body).orderNo, 'the same order, not a second one').to.eq(S.o.orderNo)
      })
    })
    cy.then(() => incoming('OFFERED', S.o.orderNo)).then((xs) => expect(xs, 'one part waits for the supplier').to.have.length(1))
    // Accept, then the same Accept again (a double click, a retried request)
    asSupplier()
    cy.then(() => post(API.acceptOrder, { id: S.o.sellerOrderId, version: S.o.sellerOrderVersion }))
      .then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); S.invoice = data(r.body).invoiceNo })
    cy.then(() => post(API.acceptOrder, { id: S.o.sellerOrderId, version: S.o.sellerOrderVersion })).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      expect(data(r.body).invoiceNo, 'the same sale').to.eq(S.invoice)
    })
    cy.then(() => incoming('ACCEPTED', S.o.orderNo)).then((xs) => {
      expect(xs, 'one accepted part').to.have.length(1)
      S.store = xs[0].storeOrderId
    })
    // the parcel, then the same parcel again
    cy.then(() => get(`/getOrder?id=${S.store}`)).then((r) => {
      const l = data(r.body).items[0]
      const ship = { id: S.store, lines: [{ orderItemId: l.id, quantity: l.quantity }], carrier: 'TCS', trackingNumber: `TC-${run}` }
      return post('/shipOrder', ship).then((a) => {
        expect(ok(a.body), JSON.stringify(a.body)).to.eq(true)
        return post('/shipOrder', ship)
      }).then((b) => expect(ok(b.body), 'nothing is left to send').to.eq(false))
    })
    cy.clearCookies()
    cy.then(() => get(API.trackOrder(S.o.orderNo, S.o.phone))).then((r) => {
      expect(data(r.body).sellerOrders[0].parcels, 'one parcel').to.have.length(1)
    })
    // cleanup: delivered, so nothing is left waiting
    asSupplier()
    cy.then(() => post('/updateOrderStatus', { id: S.store, status: 'DELIVERED' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  })
})
