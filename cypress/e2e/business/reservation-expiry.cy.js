/**
 * OMS O5a — reservation expiry (fixes OMS-6).
 * Design: microservices/docs/slices/oms-O5a-reservation-expiry.md
 *
 * The defect: a stock hold that was never confirmed or released held its stock FOREVER. Availability is
 * (quantity - reservedQuantity), so a stranded hold did not delay a sale — it made the stock permanently
 * unsellable while it went on being counted in on-hand. `SagaSellService` even logged "held stock will
 * lapse/cleanup later"; nothing lapsed, because neither a deadline nor a sweeper existed.
 *
 * Holds are created here through inventory-service's real reservation API with a Bearer token — the same API
 * business-service calls during a sale. That is deliberate: no UI flow can strand a hold on purpose (stranding
 * one requires a failure), so the only honest way to test the repair is to act as the service that reserves.
 * The gateway-token pattern is the one party-master.cy.js and academic-year.cy.js already use.
 */
const GW = 'http://localhost:8765'

describe('OMS O5a — expired stock holds are returned (OMS-6)', () => {
  const PW = 'Demo@2025!'
  const OWNER = 'owner.business@myplus.com'
  const pname = 'HoldShop_' + Date.now()

  let auth, orgId, productId

  const login = (email) => cy.request({
    method: 'POST', url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' },
    body: { email, password: PW }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `login as ${email}: ${JSON.stringify(r.body)}`).to.eq(200)
    const token = r.body.data && r.body.data.accessToken
    expect(token, 'no access token').to.be.a('string')
    return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
  })

  /** The tenant's per-product stock split — {onHand, sellable, expired, held}. */
  const levels = () => cy.request({ url: `${GW}/api/inventory/stock/levels/detail`, headers: auth })
    .then((r) => r.body[String(productId)] || r.body[productId] || {})

  const setHoldMinutes = (minutes) => cy.request({
    method: 'POST', url: `${GW}/api/inventory/settings?key=inventory.reservation.holdMinutes&value=${minutes}`,
    headers: auth, failOnStatusCode: false,
  }).then((r) => {
    expect(r.body.success, `saving holdMinutes=${minutes} failed: ${JSON.stringify(r.body)}`).to.eq(true)
  })

  /** Reserve stock and DO NOT confirm — exactly the state a failed sale leaves behind. */
  const strand = (qty) => cy.request({
    method: 'POST', url: `${GW}/api/inventory/reservations`, headers: auth, failOnStatusCode: false,
    body: { idempotencyKey: 'o5a-' + Date.now() + '-' + Math.random(), lines: [{ itemId: productId, quantity: qty }] },
  }).then((r) => {
    expect(r.body.status, `reserve failed: ${JSON.stringify(r.body)}`).to.eq('RESERVED')
    return r.body.reservationId
  })

  const sweep = () => cy.request({
    method: 'POST', url: `${GW}/api/inventory/reservations/sweep`, headers: auth, failOnStatusCode: false,
  }).then((r) => {
    expect(r.body.success, `sweep failed: ${JSON.stringify(r.body)}`).to.eq(true)
    return r.body.data.released
  })

  before(() => {
    login(OWNER).then((h) => { auth = h })
    cy.then(() => cy.request({ url: `${GW}/api/auth/me`, headers: auth, failOnStatusCode: false }))
      .then((r) => { orgId = (r.body.data && (r.body.data.activeOrgId || r.body.data.organizationId)) || null })

    // A product with stock, created through the monolith as the same owner.
    cy.loginAsOwner()
    cy.request({
      method: 'POST', url: '/addProduct', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { name: pname, sku: 'HLD' + Date.now(), sellingPrice: 10, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, `addProduct failed: ${JSON.stringify(r.body)}`).to.eq(true)
      productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' }, body: { productId, quantity: 20 },
      })
    })
  })

  // Leave the tenant on the catalog default so a re-run — and every other spec — starts clean.
  after(() => {
    if (auth) setHoldMinutes(30)
  })

  it('the hold policy is configurable, and defaults to the pre-O5a-safe 30 minutes', () => {
    cy.request({ url: `${GW}/api/inventory/settings`, headers: auth }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const entry = (r.body.data || []).find((e) => e.key === 'inventory.reservation.holdMinutes')
      expect(entry, 'inventory must expose a stock-hold policy').to.exist
      expect(entry.type).to.eq('INT')
      expect(String(entry.defaultValue)).to.eq('30')
    })
  })

  it('an unconfirmed hold removes stock from sale — and is now VISIBLE as held', () => {
    setHoldMinutes(30)
    let before
    levels().then((l) => { before = Number(l.sellable) })
    strand(4)
    levels().then((l) => {
      expect(Number(l.sellable), 'the hold comes straight off sellable').to.eq(before - 4)
      // The operator-facing half of the fix. Before O5a `sellable` simply got smaller and nothing anywhere
      // explained why: on-hand 20, sellable 16, no expired batches, nowhere to look.
      expect(Number(l.held), 'held is published so the gap is explainable').to.be.at.least(4)
      expect(Number(l.onHand), 'the stock is still physically there').to.be.at.least(Number(l.sellable))
    })
  })

  it('a hold past its deadline is released, and the stock is sellable again', () => {
    // Measured as a DELTA against whatever is already held, not against zero: the previous test deliberately
    // leaves a live 30-minute hold, and the sweep is right to leave it alone. Asserting held===0 here would be
    // asserting that a healthy hold gets swept.
    let before, heldBefore
    levels().then((l) => { before = Number(l.sellable); heldBefore = Number(l.held) })

    // A zero-minute policy makes every new hold due immediately — the same state a 30-minute hold reaches
    // half an hour later, without the test sleeping for it.
    setHoldMinutes(1)
    cy.then(() => strand(5))
    levels().then((l) => expect(Number(l.sellable), 'held while the deadline stands').to.eq(before - 5))

    // Move the deadline into the past by shortening the policy? No — the deadline is STAMPED at reserve time,
    // so shortening the setting afterwards must NOT retroactively expire it. Wait the minute out instead.
    cy.wait(61000)
    sweep().then((released) => expect(released, 'the stranded hold was found').to.be.at.least(1))
    levels().then((l) => {
      expect(Number(l.sellable), 'stock is sellable again').to.eq(before)
      expect(Number(l.held), 'the expired hold is gone; live ones are untouched').to.eq(heldBefore)
    })
  })

  it('a hold that is still within its deadline is NOT swept', () => {
    setHoldMinutes(120)
    let before
    levels().then((l) => { before = Number(l.sellable) })
    cy.then(() => strand(3))
    sweep()
    levels().then((l) => {
      // Sweeping early would return stock a checkout in progress is relying on, and the shopper would lose
      // their order at the last click.
      expect(Number(l.sellable), 'a live hold survives the sweep').to.eq(before - 3)
      expect(Number(l.held)).to.be.at.least(3)
    })
  })

  it('0 means never expire, not expire immediately', () => {
    setHoldMinutes(0)
    let before
    levels().then((l) => { before = Number(l.sellable) })
    cy.then(() => strand(2))
    sweep()
    levels().then((l) => {
      // If 0 were read as "already due", typing it would release every hold on the platform at once.
      expect(Number(l.sellable), 'expiry is switched off for this tenant').to.eq(before - 2)
    })
    // Put it back and release the two holds this test and the previous one left behind.
    setHoldMinutes(1)
  })

  it('a confirmed hold is never swept — its stock stays sold', () => {
    setHoldMinutes(1)
    let onHandBefore
    levels().then((l) => { onHandBefore = Number(l.onHand) })

    let rid
    strand(2).then((id) => { rid = id })
    cy.then(() => cy.request({
      method: 'POST', url: `${GW}/api/inventory/reservations/${rid}/confirm`, headers: auth, failOnStatusCode: false,
    })).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('CONFIRMED'))

    cy.wait(61000)
    sweep()
    levels().then((l) => {
      // The oversell guard: returning a confirmed hold would put back stock that was sold.
      expect(Number(l.onHand), 'confirmed stock stays decremented').to.eq(onHandBefore - 2)
    })
  })

  it('confirming a hold that was already swept is refused, and says why', () => {
    setHoldMinutes(1)
    let rid
    strand(1).then((id) => { rid = id })
    cy.wait(61000)
    sweep()
    cy.then(() => cy.request({
      method: 'POST', url: `${GW}/api/inventory/reservations/${rid}/confirm`, headers: auth, failOnStatusCode: false,
    })).then((r) => {
      expect(r.status, 'the sale must not proceed on stock that was given back').to.not.eq(200)
      const msg = JSON.stringify(r.body)
      // "Cannot confirm reservation in state EXPIRED" tells a cashier nothing they can act on.
      expect(msg.toLowerCase()).to.contain('expired')
    })
  })

  it('the sweep is admin-gated', () => {
    // It moves stock back into sellable — the same class of action as a stock adjustment.
    //
    // user.business, NOT demo.business: DEMO_ROLE carries SUPER_PRIVILEGE (and therefore ADMIN), so a demo
    // account is correctly allowed through and would have proved nothing. Existence is not eligibility — read
    // what the endpoint actually refuses before picking the fixture.
    login('user.business@myplus.com').then((plainUser) =>
      cy.request({
        method: 'POST', url: `${GW}/api/inventory/reservations/sweep`,
        headers: plainUser, failOnStatusCode: false,
      }).then((r) => expect(r.status, 'a non-admin cannot release stock holds').to.be.oneOf([401, 403])))
  })
})
