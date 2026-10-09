/**
 * MKT-2-06 — the time a seller has to accept depends on the order's value. Source R10.6 ("terms may vary by … order
 * value"), R10.5 (the default stays the configured minutes), R22.1 (operator only).
 * Contract: microservices/docs/slices/mkt-2-06-acceptance-by-value.md
 *
 * The rule is read when a part is offered to its seller; the deadline is stored on the part. So a part's window is
 * checked as acceptBy − createdAt on the part itself, and on the seller's countdown, and a rule changed later must not
 * move it. Cleanup: the rules are emptied, the window put back to 5 minutes, and every order placed here is rejected.
 */
const { gate, uniq, SELLER_A, API, UI, ok, data, list, post, get, expectRefused, seedPolicies, publishOffer } =
  require('./mkt-helpers')

const TIERS = '/platform/mkt/acceptTiers'   // GET → {baseMinutes, tiers:[{above, minutes}]}; POST {tiers}
const PRICE = 52000

gate('2-06')('MKT-2-06 — time to accept by order value', () => {
  const run = uniq()
  let offer
  const placed = []   // orderNo of every order this gate places, rejected in after()

  const newPhone = () => `0313${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`
  const asSeller = () => cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
  const tiers = () => {
    cy.loginAsOperator()
    return get(TIERS).then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body) })
  }
  const setTiers = (t) => {
    cy.loginAsOperator()
    return post(TIERS, { tiers: t })
  }
  /** An anonymous one-seller checkout of `qty` units; yields the checkout's data. */
  const order = (qty) => {
    cy.clearCookies()
    cy.visit(UI.publicPage)
    return post(API.checkout, { offerId: offer.offerId, quantity: qty, expectedPrice: PRICE, customerName: 'Ali',
      customerPhone: newPhone(), address: '1 Clifton', city: 'Karachi', idempotencyKey: `m206-${run}-${Math.random()}` })
      .then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); placed.push(data(r.body).orderNo); return data(r.body) })
  }
  /** The seller's own view of a waiting part. */
  const part = (orderNo) => {
    asSeller()
    return get(`${API.incomingOrders}?status=OFFERED&size=100`).then((r) => list(r.body).find((x) => x.orderNo === orderNo))
  }
  const windowMinutes = (p) => Math.round((new Date(p.acceptBy) - new Date(p.createdAt)) / 60000)
  /** The seller's countdown for that order, on the dashboard. */
  const countdown = (orderNo) => {
    asSeller()
    cy.visit('/businessDashboard')
    cy.get('#snavSell .snav-btn').click()
    cy.get(UI.sellerNav).click()
    return cy.contains(`${UI.incoming} tr`, orderNo).find(UI.countdown).invoke('text')
  }

  before(() => {
    let p
    seedPolicies(run).then((x) => { p = x })
    cy.loginAsOperator()
    post(API.acceptWindow, { minutes: 5 })
    setTiers([]).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => publishOffer(SELLER_A, { run, price: PRICE, promiseHours: 4, qty: 30, warrantyPolicyId: p.warranty,
      returnPolicyId: p.returns })).then((o) => { offer = o })
  })

  after(() => {
    setTiers([])
    post(API.acceptWindow, { minutes: 5 })
    asSeller()
    get(`${API.incomingOrders}?status=OFFERED&size=100`).then((r) => list(r.body)
      .filter((x) => placed.includes(x.orderNo))
      .forEach((x) => post(API.rejectOrder, { id: x.id, version: x.version, reason: 'gate cleanup' })))
  })

  it('MKT-2-06-01 [MKT-R10.6] [MKT-R10.5] no rules: every order gets the configured minutes, whatever its value', () => {
    tiers().then((v) => {
      expect(v.baseMinutes).to.eq(5)
      expect(v.tiers).to.deep.eq([])
    })
    order(3).then((o) => part(o.orderNo)).then((p) => expect(windowMinutes(p), 'Rs 156,000 with no rules').to.eq(5))
  })

  it('MKT-2-06-02 [MKT-R10.6] the operator adds two rules in Marketplace policies and saves them (real UI)', () => {
    cy.loginAsOperator()
    cy.visit('/platformDashboard')
    cy.get('#platMktPoliciesBtn').click()
    cy.get('#mktTiersList tbody').should('contain', 'No rules: every order gets the minutes above.')
    cy.get('#mktTiersAdd').click()
    cy.get('#mktTiersList .mkt-tier').last().within(() => {
      cy.get('.mkt-tier-above').type('150000')
      cy.get('.mkt-tier-minutes').type('30')
    })
    cy.get('#mktTiersAdd').click()
    cy.get('#mktTiersList .mkt-tier').last().within(() => {
      cy.get('.mkt-tier-above').type('100000')
      cy.get('.mkt-tier-minutes').type('15')
    })
    cy.get('#mktTiersSave').click()
    cy.get('#mktTiersMsg').should('contain', 'Acceptance rules saved. They apply to orders placed from now on.')
    // shown back sorted by amount, as stored
    cy.get('#mktTiersList .mkt-tier .mkt-tier-above').then(($i) => expect([...$i].map((x) => x.value)).to.deep.eq(['100000', '150000']))
    tiers().then((v) => expect(v.tiers.map((t) => [Number(t.above), t.minutes])).to.deep.eq([[100000, 15], [150000, 30]]))
  })

  it('MKT-2-06-03 [MKT-R10.6] [MKT-R10.5] a part gets the minutes of the highest amount it is ABOVE; at or under every amount, the configured minutes', () => {
    setTiers([{ above: 100000, minutes: 15 }, { above: 150000, minutes: 30 }])
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    order(3).then((o) => part(o.orderNo)).then((p) => expect(windowMinutes(p), 'Rs 156,000: above both').to.eq(30))
    order(2).then((o) => part(o.orderNo)).then((p) => expect(windowMinutes(p), 'Rs 104,000: above 100,000 only').to.eq(15))
    order(1).then((o) => part(o.orderNo)).then((p) => expect(windowMinutes(p), 'Rs 52,000: above none').to.eq(5))
  })

  it('MKT-2-06-04 [MKT-R10.6] the seller\'s countdown starts from the longer window (real UI)', () => {
    setTiers([{ above: 100000, minutes: 15 }]).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    order(3).then((o) => countdown(o.orderNo).should('match', /^(15:00|14:[0-5]\d)$/))
    order(1).then((o) => countdown(o.orderNo).should('match', /^[0-5]:[0-5]\d$/))
  })

  it('MKT-2-06-05 [MKT-R10.6] changing the rules never moves an order already waiting', () => {
    let before
    setTiers([{ above: 100000, minutes: 15 }])
    order(3).then((o) => part(o.orderNo)).then((p) => { before = p; expect(windowMinutes(p)).to.eq(15) })
    setTiers([{ above: 100000, minutes: 40 }]).then((r) => expect(ok(r.body)).to.eq(true))
    cy.then(() => part(before.orderNo)).then((p) => expect(p.acceptBy, 'its deadline is its own').to.eq(before.acceptBy))
    setTiers([]).then((r) => expect(ok(r.body)).to.eq(true))
    cy.then(() => part(before.orderNo)).then((p) => expect(p.acceptBy).to.eq(before.acceptBy))
  })

  it('MKT-2-06-06 [MKT-R10.6] bad rules are refused in words and nothing changes (real UI)', () => {
    setTiers([{ above: 100000, minutes: 15 }])
    cy.loginAsOperator()
    post(TIERS, { tiers: [{ above: 100000, minutes: 61 }] }).then((r) => expectRefused(r, 'Each rule\'s minutes are 1 to 60.'))
    post(TIERS, { tiers: [{ above: 100000, minutes: 0 }] }).then((r) => expectRefused(r, 'Each rule\'s minutes are 1 to 60.'))
    post(TIERS, { tiers: [{ above: 0, minutes: 10 }] }).then((r) => expectRefused(r, 'Each rule needs an amount above Rs 0'))
    post(TIERS, { tiers: [{ above: 100000, minutes: 10 }, { above: 100000, minutes: 20 }] })
      .then((r) => expectRefused(r, 'Two rules have the same amount: Rs 100,000.'))
    post(TIERS, { tiers: [1, 2, 3, 4, 5, 6].map((n) => ({ above: n * 10000, minutes: n })) })
      .then((r) => expectRefused(r, 'At most 5 rules.'))
    post(TIERS, { tiers: [{ above: 100000 }] }).then((r) => expectRefused(r, 'Each rule\'s minutes are 1 to 60.'))
    // on the screen: the operator's sentence, and the saved rule is still there after a reload
    cy.visit('/platformDashboard')
    cy.get('#platMktPoliciesBtn').click()
    cy.get('#mktTiersList .mkt-tier .mkt-tier-minutes').should('have.value', '15').clear().type('61')
    cy.get('#mktTiersSave').click()
    cy.get('#mktTiersMsg').should('contain', 'Each rule\'s minutes are 1 to 60.')
    tiers().then((v) => expect(v.tiers.map((t) => [Number(t.above), t.minutes])).to.deep.eq([[100000, 15]]))
  })

  it('MKT-2-06-07 [MKT-R22.1] a seller can neither read nor change the rules', () => {
    asSeller()
    get(TIERS).then((r) => expectRefused(r))
    post(TIERS, { tiers: [] }).then((r) => expectRefused(r))
    tiers().then((v) => expect(v.tiers).to.have.length(1))
  })
})
