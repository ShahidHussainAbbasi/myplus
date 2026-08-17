/**
 * OMS O7 D1 — the review decision, DRIVEN THROUGH THE SCREEN.
 *
 * <h3>Why this exists alongside order-approval.cy.js</h3>
 * That spec proves confirm and reject work. It proves it with `cy.request` — and everything it asserts stayed
 * green while the reviewer had **no button to press**. `actionsFor()` rendered every allowed transition as a
 * generic "Mark X", so a PENDING_APPROVAL order showed *Mark NEW* and *Mark REJECTED*, both of which the
 * server refuses ("a review decision, not a status change"), and the two buttons that would have worked were
 * never drawn at all. A user following the documented steps could not approve an order.
 *
 * So the rule this spec encodes is narrow and worth keeping: **an endpoint is not reachable until a person
 * can reach it.** Every assertion below goes through the grid a human uses, and the negative assertions —
 * that the always-failing buttons are gone — matter as much as the positive ones.
 */
describe('OMS O7 D1 — a reviewer can actually release a booked order', () => {
  const run = String(Date.now()).slice(-6)
  const PRICE = 40
  let productId
  const outlet = 'ReviewOutlet_' + run

  // Everything happens in the ORDER BOOKER's org (owner.marketplace's), because that is where the booker
  // fixture lives — booking into any other tenant would make the refusal below prove org scoping rather than
  // the approval gate.
  before(() => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name: 'RevProd_' + run, sku: 'RV' + run, sellingPrice: PRICE, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId, quantity: 100 }, failOnStatusCode: false,
      })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
  })

  /** Book as the rep. The API is fine HERE — booking is not what this spec is testing. */
  const book = (name) => cy.request({
    method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      customerName: name, customerContact: '0300' + run, shippingAddress: '9 Review Rd',
      items: [{ productId, quantity: 2, price: PRICE, productName: 'RevProd_' + run }],
    },
  }).then((r) => {
    expect(r.body.success, 'booked: ' + JSON.stringify(r.body)).to.eq(true)
    return r.body.data
  })

  /** Open Orders and filter to one order, so the row under test is the only one on screen. */
  const openOrders = (orderNo) => {
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => w.showOrders())
    cy.get('#ordFilterQ').clear().type(orderNo)
    cy.window().then((w) => w.applyOrderFilters())
    cy.get('#ordersBody tr', { timeout: 15000 }).should('have.length.at.least', 1)
  }

  const act = (orderId, name) => cy.get('[data-act="' + name + '"][data-order="' + orderId + '"]')

  /** The order's state, read from the server rather than from the row we are asserting about. */
  const statusOf = (id) => cy.request('/getOrder?id=' + id).then((r) => (r.body.data || {}).fulfilmentStatus)

  it('the booker sees the order but is offered no way to release it', () => {
    cy.loginAsOrderBooker()
    book(outlet + '_A').then((o) => {
      expect(o.fulfilmentStatus).to.eq('PENDING_APPROVAL')
      openOrders(o.orderNo)

      // The gate, as the rep experiences it.
      act(o.id, 'confirm').should('not.exist')
      act(o.id, 'reject').should('not.exist')
      // THE DEFECT. These were drawn for everyone and refused for everyone — a button whose only possible
      // outcome is an error message. Their absence is the fix.
      act(o.id, 'mark-NEW').should('not.exist')
      act(o.id, 'mark-REJECTED').should('not.exist')
      // …but the row is not blank: a booker must still be able to see where their order has got to.
      cy.get('#ordersBody tr').first().should('contain.text', 'PENDING_APPROVAL')
    })
  })

  it('the reviewer gets Confirm and Reject, and rejecting records the reason the booker needs', () => {
    let order
    cy.loginAsOrderBooker()
    book(outlet + '_B').then((o) => { order = o })

    cy.then(() => {
      cy.loginAsMarketplaceOwner()
      openOrders(order.orderNo)

      act(order.id, 'confirm').should('exist')
      act(order.id, 'mark-NEW').should('not.exist')   // still never the generic form

      act(order.id, 'reject').click()
      // uiPromptConfirm, not window.prompt — the platform confirm contract.
      cy.get('#uiC-input').type('Outlet over credit limit')
      cy.get('[data-ui-confirm="ok"]').click()
    })

    cy.then(() => statusOf(order.id).should('eq', 'REJECTED'))
    // The reason is the point of the named endpoint: without it the rep has nothing to revise.
    cy.then(() => cy.request('/getOrder?id=' + order.id).then((r) => {
      expect(r.body.data.rejectionReason, 'the booker is told why').to.eq('Outlet over credit limit')
    }))
  })

  it('a rejected order goes back for review on the booker\'s own Resubmit, never straight to NEW', () => {
    let order
    cy.loginAsOrderBooker()
    book(outlet + '_C').then((o) => { order = o })

    cy.then(() => {
      cy.loginAsMarketplaceOwner()
      openOrders(order.orderNo)
      act(order.id, 'reject').click()
      cy.get('#uiC-input').type('Wrong pack size')
      cy.get('[data-ui-confirm="ok"]').click()
    })
    cy.then(() => statusOf(order.id).should('eq', 'REJECTED'))

    // Resubmit is the REP's move and is deliberately not admin-gated — gating it would leave the rejection
    // with nobody able to answer it.
    cy.then(() => {
      cy.loginAsOrderBooker()
      openOrders(order.orderNo)
      act(order.id, 'mark-PENDING_APPROVAL').should('not.exist')   // the generic form is refused by the server
      act(order.id, 'resubmit').click()
    })
    cy.then(() => statusOf(order.id).should('eq', 'PENDING_APPROVAL'))
  })

  it('confirming from the grid releases the order to the floor', () => {
    let order
    cy.loginAsOrderBooker()
    book(outlet + '_D').then((o) => { order = o })

    cy.then(() => {
      cy.loginAsMarketplaceOwner()
      openOrders(order.orderNo)
      act(order.id, 'confirm').click()
    })
    cy.then(() => statusOf(order.id).should('eq', 'NEW'))
    // O7 D1's founding rule, re-asserted here because this is the moment it could break: releasing an order
    // is not dispatching it, so there is still no invoice.
    cy.then(() => cy.request('/getOrder?id=' + order.id).then((r) => {
      expect(r.body.data.invoiceNo, 'no invoice until the goods leave').to.not.be.ok
    }))
  })
})
