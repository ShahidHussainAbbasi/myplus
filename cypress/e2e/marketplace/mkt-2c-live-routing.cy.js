/**
 * MKT-2c — live routing: a seller's stock is asked for with a deadline, and a seller that keeps failing to answer is
 * not asked again for a while. Source §18.1, §18.3, §18.5. Run headed: --env '{"mkt":"2c"}'
 * Contract: microservices/docs/slices/mkt-2c-live-routing.md
 *
 * Needs marketplace-service started with the operator's test switch (MKT_ROUTING_TEST_SWITCH=true), which lets the
 * operator make one seller slow from Platform → Marketplace policies. Never on in production. The limits are the
 * defaults: 800 ms per seller, 2 s per checkout, 3 failures in a row open the circuit for 30 s.
 *
 * Every case starts from a clean state for Seller A (slowness off, circuit closed) and the gate leaves it that way.
 * The offers hold ONE unit each, so a hold that was never given back would refuse the next shopper ("the hold is
 * real", as in MKT-1e-04): that is how the gate sees the late hold released.
 */
const { gate, uniq, SELLER_A, SELLER_B, API, UI, ok, data, list, post, get, expectRefused, seedPolicies, publishOffer } =
  require('./mkt-helpers')

const ROUTING = {
  view: '/platform/mkt/routing',
  close: '/platform/mkt/routingClose',
  test: '/platform/mkt/routingTest',
}
/** What the shopper is told; the 2 s deadline plus the round trip through the monolith and the gateway. */
const SLOW_ONE = 'This seller did not answer in time. Please choose another offer.'
const ANSWER_WITHIN_MS = 2500

gate('2c')('MKT-2c — live routing: deadlines and sellers that do not answer', () => {
  const run = uniq()
  const o = {}
  let aOrg, aName, bName

  const newPhone = () => `0314${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`
  /** Seller A answers after `ms` (0 = normally), and its circuit is closed. */
  const slowA = (ms) => {
    cy.loginAsOperator()
    post(ROUTING.test, { sellerOrganizationId: ms ? aOrg : null, delayMs: ms }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    return post(ROUTING.close, { sellerOrganizationId: aOrg }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  }
  /** An anonymous COD checkout of one unit of each offer; yields {r, ms, phone}. */
  const buy = (...offers) => {
    const phone = newPhone()
    cy.clearCookies()
    cy.visit(UI.publicPage)
    let t0
    return cy.then(() => { t0 = Date.now() })                           // when the request is sent, not when queued
      .then(() => post(API.checkout, { lines: offers.map((x) => ({ offerId: x.offerId, quantity: 1, expectedPrice: x.price })),
        customerName: 'Ali', customerPhone: phone, address: '1 Clifton', city: 'Karachi',
        idempotencyKey: `m2c-${run}-${Math.random()}` }))
      .then((r) => ({ r, ms: Date.now() - t0, phone }))
  }
  /** Cleanup: the seller rejects what a case placed, so the unit is free for the next case. */
  const rejectAs = (email, orderNo) => {
    cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')
    return get(`${API.incomingOrders}?status=OFFERED&size=100`).then((r) => {
      const so = list(r.body).find((x) => x.orderNo === orderNo)
      expect(so, `${orderNo} waits for ${email}`).to.exist
      return post(API.rejectOrder, { id: so.id, version: so.version, reason: 'gate cleanup' })
    }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  }
  /** Long enough for Seller A's late hold (3 s) to land and be released. */
  const letTheLateHoldLand = () => cy.wait(3500)

  before(() => {
    seedPolicies(run).then((pol) => {
      publishOffer(SELLER_A, { run: `${run}a`, price: 52000, promiseHours: 4, qty: 1,
        warrantyPolicyId: pol.warranty, returnPolicyId: pol.returns }).then((x) => { o.a = Object.assign(x, { price: 52000 }) })
      cy.then(() => publishOffer(SELLER_B, { run: `${run}b`, price: 51500, promiseHours: 4, qty: 1,
        warrantyPolicyId: pol.warranty, returnPolicyId: pol.returns })).then((x) => { o.b = Object.assign(x, { price: 51500 }) })
    })
    cy.then(() => get(`${API.publicOffers(o.a.mktProductId)}?city=Karachi`)).then((r) => {
      aName = list(r.body).find((x) => x.offerId === o.a.offerId).sellerName
    })
    cy.then(() => get(`${API.publicOffers(o.b.mktProductId)}?city=Karachi`)).then((r) => {
      bName = list(r.body).find((x) => x.offerId === o.b.offerId).sellerName
    })
    cy.loginAsOperator()
    post(API.acceptWindow, { minutes: 5, multiSeller: true, reroute: false })
    cy.then(() => get(ROUTING.view)).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      const v = data(r.body)
      expect(v.testSwitch, 'marketplace-service must be started with MKT_ROUTING_TEST_SWITCH=true').to.eq(true)
      expect(v.holdTimeoutMs).to.eq(800)
      expect(v.deadlineMs).to.eq(2000)
      aOrg = v.sellers.find((s) => s.name === aName).sellerOrganizationId
    })
  })

  after(() => {
    slowA(0)
    cy.loginAsOperator()
    post(API.acceptWindow, { multiSeller: false })
  })

  it('MKT-2c-01 [MKT-R18.3] [MKT-R18.5] a seller that does not answer in time: the shopper is told within the deadline, never left waiting, nothing is confirmed', () => {
    slowA(3000)
    buy(o.a).then(({ r, ms }) => {
      expectRefused(r, SLOW_ONE)
      expect(ms, 'the 800 ms timeout, not the seller\'s 3 s').to.be.lessThan(ANSWER_WITHIN_MS)
    })
    letTheLateHoldLand()
  })

  it('MKT-2c-02 [MKT-R18.3] the hold that lands after the shopper was told no is given back: the only unit sells to the next shopper', () => {
    slowA(3000)
    buy(o.a).then(({ r }) => expectRefused(r, SLOW_ONE))
    letTheLateHoldLand()
    slowA(0)
    buy(o.a).then(({ r }) => {
      expect(ok(r.body), `the unit is free again: ${JSON.stringify(r.body)}`).to.eq(true)
      expect(data(r.body).status).to.eq('SUBMITTED')
      return rejectAs(SELLER_A, data(r.body).orderNo)                     // cleanup: the unit back for the next case
    })
  })

  it('MKT-2c-03 [MKT-R18.1] [MKT-R17.2] [MKT-R18.5] a basket from two sellers, one slow: refused within the deadline, the slow seller named, the other seller\'s unit given back', () => {
    slowA(3000)
    buy(o.a, o.b).then(({ r, ms }) => {
      expectRefused(r, `${aName} did not answer in time. Please remove its items and place the order again.`)
      expect(ms, 'both sellers asked at once: the slower one, not the sum').to.be.lessThan(ANSWER_WITHIN_MS)
    })
    letTheLateHoldLand()
    buy(o.b).then(({ r }) => {
      expect(ok(r.body), `Seller B's unit was released: ${JSON.stringify(r.body)}`).to.eq(true)
      return rejectAs(SELLER_B, data(r.body).orderNo)
    })
  })

  it('MKT-2c-04 [MKT-R18.3] after 3 calls in a row it did not answer, the seller is not asked: refused at once; the operator sees it and asks it again (real UI)', () => {
    slowA(3000)
    for (let i = 0; i < 3; i++) buy(o.a).then(({ r }) => expectRefused(r, SLOW_ONE))
    buy(o.a).then(({ r, ms }) => {
      expectRefused(r, SLOW_ONE)
      expect(ms, 'the circuit is open: no call, no wait').to.be.lessThan(800)
    })
    // the operator's screen
    cy.loginAsOperator()
    cy.visit('/platformDashboard')
    cy.get('#platMktPoliciesBtn').click()
    cy.get('#mktRoutingLimits').should('contain', '800 ms').and('contain', '2000 ms').and('contain', '3 times').and('contain', '30 seconds')
    cy.contains('#mktRoutingOpen .mkt-routing-row', aName).should('contain', 'is not answering: not asked until')
    // the seller says it is fixed: test slowness off on the same screen, then "Ask it again now"
    cy.get('#mktRoutingDelay').clear().type('0')
    cy.get('#mktRoutingTestSave').click()
    cy.get('#mktRoutingMsg').should('contain', 'Test slowness is off.')
    cy.contains('#mktRoutingOpen .mkt-routing-row', aName).find('.mkt-routing-close').click()
    cy.get('#mktRoutingMsg').should('contain', 'The seller will be asked again on its next order.')
    cy.get('#mktRoutingOpen').should('not.contain', aName).and('contain', 'Every seller is being asked.')
    letTheLateHoldLand()
    buy(o.a).then(({ r }) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      return rejectAs(SELLER_A, data(r.body).orderNo)
    })
  })

  it('MKT-2c-05 [MKT-R18.5] the shopper\'s screen: the order button answers in time with the way forward, never spins (real UI)', () => {
    slowA(3000)
    cy.clearCookies()
    cy.visit(`${UI.publicPage}?product=${o.a.mktProductId}&city=Karachi`)
    cy.get(`${UI.offerRow}[data-offer-id="${o.a.offerId}"] ${UI.chooseOffer}`).check()
    cy.get(UI.buyButton).click()
    cy.get('#mktCoName').type('Ali')
    cy.get('#mktCoPhone').type(newPhone())
    cy.get('#mktCoAddress').type('1 Clifton')
    cy.get('#mktCoPlace').click()
    cy.get('#mktCoError', { timeout: ANSWER_WITHIN_MS }).should('have.text', SLOW_ONE)
    cy.get('#mktCoPlace').should('not.be.disabled')
    letTheLateHoldLand()
  })

  it('MKT-2c-06 [MKT-R22.1] the routing screen is the operator\'s: a seller is refused, and cannot make another seller slow', () => {
    slowA(0)
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    get(ROUTING.view).then((r) => expect(r.status === 403 || !ok(r.body), `refused for a tenant: ${r.status}`).to.eq(true))
    post(ROUTING.test, { sellerOrganizationId: aOrg, delayMs: 3000 })
      .then((r) => expect(r.status === 403 || !ok(r.body), `refused for a tenant: ${r.status}`).to.eq(true))
    post(ROUTING.close, { sellerOrganizationId: aOrg })
      .then((r) => expect(r.status === 403 || !ok(r.body), `refused for a tenant: ${r.status}`).to.eq(true))
    cy.loginAsOperator()
    get(ROUTING.view).then((r) => expect(data(r.body).slowSellerOrganizationId, 'nothing changed').to.eq(null))
  })
})
