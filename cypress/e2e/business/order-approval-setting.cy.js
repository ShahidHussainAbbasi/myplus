/**
 * OMS O7 D1 — the approval gate becomes a per-tenant choice ({@code order.booking.requireApproval}).
 *
 * <h3>Why the gate had to become optional</h3>
 * It was hardcoded: every booked order stopped at PENDING_APPROVAL. That is right when two people are
 * involved, and it is the control the pre-sales model rests on. But a distributor whose back office is ONE
 * person has that person book, review and convert — the gate segregates nothing, it is the same human
 * clicking twice. A control that cannot fail is not a control; it is friction, and friction is what teaches
 * people to work around a system.
 *
 * <h3>What the spec has to prove, in both directions</h3>
 * Off is easy to get wrong in a way that looks fine: skip the gate but also skip the RECORD, and the org
 * loses its answer to "who booked this". So each case asserts the entry state <b>and</b> that the booker is
 * still stamped. Losing the second pair of eyes is the trade; losing the audit trail is not.
 *
 * <p>Leaves no server state behind: the setting is restored in {@code after()}, because a spec that leaves
 * approval off would silently disarm every later booking test.
 */
describe('OMS O7 D1 — a one-person back office can skip the review gate', () => {
  const GW = 'http://localhost:8765'
  const PW = 'Demo@2025!'
  const KEY = 'order.booking.requireApproval'
  const run = String(Date.now()).slice(-6)
  const PRICE = 25
  let productId
  let auth

  const login = (email) => cy.request({
    method: 'POST', url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' }, body: { email, password: PW }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `login as ${email}: ${JSON.stringify(r.body)}`).to.eq(200)
    const token = r.body.data && r.body.data.accessToken
    expect(token, 'no access token').to.be.a('string')
    return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
  })

  const setApproval = (on) => cy.request({
    method: 'POST', url: `${GW}/api/marketplace/settings?key=${KEY}&value=${on}`,
    headers: auth, failOnStatusCode: false,
  }).then((r) => {
    expect(r.body.success, `saving ${KEY}=${on} failed: ${JSON.stringify(r.body)}`).to.eq(true)
  })

  before(() => {
    login('owner.marketplace@myplus.com').then((h) => { auth = h })
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name: 'GateProd_' + run, sku: 'GT' + run, sellingPrice: PRICE, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId, quantity: 100 }, failOnStatusCode: false,
      })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
  })

  after(() => {
    // The default, restored. Leaving it off would quietly disarm order-approval.cy.js and order-review-ui.cy.js.
    login('owner.marketplace@myplus.com').then((h) => {
      auth = h
      setApproval(true)
    })
  })

  beforeEach(() => cy.loginAsOrderBooker())

  const book = (name) => cy.request({
    method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      customerName: name, customerContact: '0300' + run, shippingAddress: '3 Gate St',
      items: [{ productId, quantity: 2, price: PRICE, productName: 'GateProd_' + run }],
    },
  }).then((r) => {
    expect(r.body.success, 'booked: ' + JSON.stringify(r.body)).to.eq(true)
    return r.body.data
  })

  it('ON (the default): a booked order waits for review', () => {
    cy.then(() => setApproval(true))
    cy.loginAsOrderBooker()
    book('GateOutlet_on_' + run).then((o) => {
      expect(o.fulfilmentStatus, 'held at the gate').to.eq('PENDING_APPROVAL')
      expect(o.bookedByName, 'who booked it is recorded').to.be.a('string').and.not.be.empty
      expect(o.invoiceNo, 'no invoice at booking, either way').to.not.be.ok
    })
  })

  it('OFF: the same booking goes straight to picking — and still records who booked it', () => {
    cy.then(() => setApproval(false))
    cy.loginAsOrderBooker()
    book('GateOutlet_off_' + run).then((o) => {
      expect(o.fulfilmentStatus, 'no gate to wait at').to.eq('NEW')
      // The assertion that keeps this honest. Skipping the REVIEW must not skip the RECORD — otherwise a
      // one-person shop trades a pointless click for an unanswerable audit question.
      expect(o.bookedByName, 'the booker is still stamped').to.be.a('string').and.not.be.empty
      expect(o.invoiceNo, 'still no invoice until dispatch').to.not.be.ok
    })
  })

  it('OFF: the order is immediately actionable — NEW offers PACKED, not a review decision', () => {
    cy.then(() => setApproval(false))
    cy.loginAsOrderBooker()
    book('GateOutlet_flow_' + run).then((o) => {
      cy.request('/getOrder?id=' + o.id).then((r) => {
        const moves = r.body.data.allowedTransitions || []
        expect(moves, 'ready to pick').to.include('PACKED')
        expect(moves, 'nothing left to approve').to.not.include('REJECTED')
      })
    })
  })

  it('turning it back ON re-arms the gate for the next order', () => {
    // Proves the switch is read per booking, not cached from startup — the failure mode that would make an
    // owner think the setting had not saved.
    cy.then(() => setApproval(true))
    cy.loginAsOrderBooker()
    book('GateOutlet_rearm_' + run).then((o) => {
      expect(o.fulfilmentStatus).to.eq('PENDING_APPROVAL')
    })
  })
})
