/**
 * MKT-Xa — who answers for an order is always visible to MaxTheService.
 * Source R23.1 ("who answers for what is always visible"), R8.1 (MaxTheService owns the customer relationship, support,
 * complaints and return coordination), R3.1 / R3.2 (each line names its seller, stock owner, custodian and fulfiller).
 * Contract: microservices/docs/slices/mkt-Xa-parties.md
 *
 * The operator opens Platform → Marketplace orders and presses "Who answers for it" on an order: under it, MaxTheService's
 * part (customer relationship, support), then each seller's part (seller, carrier) and each line (where the stock comes
 * from, its owner, custodian and fulfiller by name, the warranty and the returns as snapshotted at checkout).
 *
 * Each run places its own order for a fresh offer of owner.business@ (Shahzad Mobile Shop). The consigned case reads the
 * newest order with a CONSIGNMENT line (task latestOrderOfSource): run it with 5c (`--env '{"mkt":"5c,Xa"}'`) so that
 * order is this run's own; the case asserts that the line's owner is not its seller before it relies on it.
 */
const { gate, uniq, SELLER_A, SELLER_B, API, UI, ok, data, msg, post, get, list, seedPolicies, publishOffer } = require('./mkt-helpers')

const PARTIES = '/platform/mkt/orderParties'

gate('Xa')('MKT-Xa — who answers for an order', () => {
  const run = uniq()
  const S = {}

  const orders = (status) => {
    cy.loginAsOperator()
    cy.visit(UI.operatorPage)
    cy.get('#platMktOrdersBtn').should('be.visible').click()
    cy.get(`#platMktOrderStatus button[data-status="${status}"]`).click()
    return cy.get('#mktOrderList tbody tr').should('have.length.greaterThan', 0)
  }
  const row = (no) => `#mktOrderList tr.mkt-order-row[data-order-no="${no}"]`
  const panel = (no) => `#mktOrderList tr.mkt-parties[data-order-no="${no}"]`

  before(() => {
    seedPolicies(run).then((p) => { S.policies = p })
    cy.then(() => publishOffer(SELLER_A, { run, price: 52000, warrantyPolicyId: S.policies.warranty, returnPolicyId: S.policies.returns }))
      .then((o) => { S.offer = o.offerId })
    cy.then(() => {
      cy.clearCookies()
      cy.visit(UI.publicPage)                                  // the page hands out the security token the POST needs
      return post(API.checkout, { offerId: S.offer, quantity: 1, expectedPrice: 52000, customerName: 'Hina',
        customerPhone: `0316${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`, address: '4 Clifton', city: 'Karachi',
        idempotencyKey: `xa-${run}` })
    }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      S.order = data(r.body)
    })
  })

  it('MKT-Xa-01 [MKT-R23.1] [MKT-R8.1] [MKT-R3.1] the operator opens an order\'s parties: MaxTheService for the customer and support, the seller and every line\'s parties by name', () => {
    orders('SUBMITTED')
    cy.then(() => {
      cy.get(`${row(S.order.orderNo)} .mkt-parties-btn`).should('have.attr', 'aria-expanded', 'false')
        .and('contain', 'Who answers for it').click()
      cy.get(`${row(S.order.orderNo)} .mkt-parties-btn`).should('have.attr', 'aria-expanded', 'true')
      const p = panel(S.order.orderNo)
      cy.get(`${p} .mkt-party-customer`).should('contain', 'Customer relationship').and('contain', 'MaxTheService')
      cy.get(`${p} .mkt-party-support`).should('contain', 'MaxTheService: support, complaints and returns')
      cy.get(`${p} .mkt-party-part`).should('have.length', 1)
      cy.get(`${p} .mkt-party-seller`).should('contain', 'Shahzad Mobile Shop')
      cy.get(`${p} .mkt-party-carrier`).should('contain', 'None yet: no seller has accepted')
      cy.get(`${p} .mkt-party-line`).should('have.length', 1)
      cy.get(`${p} .mkt-party-source`).should('contain', 'The seller\'s own stock')
      for (const c of ['owner', 'custodian', 'fulfiller']) cy.get(`${p} .mkt-party-${c}`).should('contain', 'Shahzad Mobile Shop')
      cy.get(`${p} .mkt-party-warranty`).should('contain', 'Samsung Pakistan, 12 months')
      cy.get(`${p} .mkt-party-returns`).should('contain', '7 days, coordinated by MaxTheService')
      // pressed again, it closes
      cy.get(`${row(S.order.orderNo)} .mkt-parties-btn`).click()
      cy.get(p).should('not.exist')
      cy.get(`${row(S.order.orderNo)} .mkt-parties-btn`).should('have.attr', 'aria-expanded', 'false')
    })
  })

  it('MKT-Xa-02 [MKT-R3.1] [MKT-R3.2] [MKT-R23.1] a consigned line names its owner apart from its seller, and the warehouse that holds it', () => {
    cy.task('latestOrderOfSource', { source: 'CONSIGNMENT' }).then((c) => {
      expect(c, 'an order with a consigned line exists (run with 5c)').to.not.eq(null)
      expect(c.owner, 'the consigned line\'s owner is not its seller').to.not.eq(c.seller)
      S.consigned = c
    })
    cy.loginAsOperator()
    cy.then(() => get('/platform/mkt/warehouse')).then((r) => { S.warehouse = data(r.body).organizationId })
    cy.orgOf(SELLER_B).then((o) => { S.mobile = o.id })
    cy.then(() => {
      const c = S.consigned
      orders(['SUBMITTED', 'CONFIRMED', 'CANCELLED'].includes(c.status) ? c.status : '')
      cy.get(`${row(c.orderNo)} .mkt-parties-btn`).click()
      const p = panel(c.orderNo)
      const wh = (org) => (org === S.warehouse ? 'MaxTheService warehouse' : null)
      cy.get(`${p} .mkt-party-source`).first().should('contain', 'Consigned stock')
      if (wh(c.seller)) cy.get(`${p} .mkt-party-seller`).first().should('contain', wh(c.seller))
      if (wh(c.custodian)) cy.get(`${p} .mkt-party-custodian`).first().should('contain', wh(c.custodian))
      if (wh(c.fulfiller)) cy.get(`${p} .mkt-party-fulfiller`).first().should('contain', wh(c.fulfiller))
      if (c.owner === S.mobile) cy.get(`${p} .mkt-party-owner`).first().should('contain', 'Mobile Distributor')
      cy.get(`${p} .mkt-party-owner`).first().invoke('text').then((owner) => {
        cy.get(`${p} .mkt-party-seller`).first().invoke('text').then((seller) => {
          expect(owner.replace(/^.*?:\s*/, '')).to.not.eq(seller.replace(/^.*?:\s*/, ''))
        })
      })
      // a part that went ahead names a carrier or that none is booked yet; never "no seller has accepted"
      if (c.status === 'CONFIRMED') cy.get(`${p} .mkt-party-carrier`).first().should('not.contain', 'None yet')
      cy.get(`${p} .mkt-party-line b`).each(($b) => expect($b.text().trim(), 'no party is blank').to.not.eq(''))
    })
  })

  it('MKT-Xa-03 [MKT-R23.1] only the operator sees it: a shop is refused, and a bad or unknown number is "No such order."', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    cy.then(() => get(`${PARTIES}?orderNo=${S.order.orderNo}`)).then((r) => {
      expect(r.status === 403 || !ok(r.body), `a shop: ${r.status}`).to.eq(true)
      expect(JSON.stringify(r.body || '')).to.not.contain('Shahzad Mobile Shop')
    })
    cy.loginAsOperator()
    for (const bad of ["x' or 1=1", 'MKT-999999', '']) {
      get(`${PARTIES}?orderNo=${encodeURIComponent(bad)}`).then((r) => {
        expect(ok(r.body), JSON.stringify(r.body)).to.eq(false)
        expect(msg(r.body)).to.eq('No such order.')
      })
    }
  })

  after(() => {                                                 // cleanup: the order no longer waits on the seller
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    cy.then(() => S.order && get(`${API.incomingOrders}?status=OFFERED&size=100`)).then((r) => {
      const so = r && list(r.body).find((x) => x.orderNo === S.order.orderNo)
      if (so) post(API.rejectOrder, { id: so.id, version: so.version, reason: 'gate Xa cleanup' })
    })
  })
})
