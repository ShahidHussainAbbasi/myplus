/**
 * E-commerce E1 (slice 46) — orders back-office. As demo.marketplace (Store vertical): record an order, list it,
 * advance fulfilment status; the Store nav + Orders panel render on the shared dashboard. Run headed.
 */
describe('E-commerce — orders back-office', () => {
  beforeEach(() => { cy.loginAsMarketplace() })

  it('records an order, lists it, and advances fulfilment status', () => {
    const invoiceNo = 'ORD-' + Date.now()
    let orderId

    cy.request({
      method: 'POST', url: '/recordOrder',
      body: { invoiceNo: invoiceNo, customerName: 'Buyer', total: 49.99 },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    }).then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.fulfilmentStatus).to.eq('NEW')
      orderId = r.body.data.id
    })

    // OMS O4: paginated + filterable; q searches the invoice number among other identifiers.
    cy.request('/getOrders?q=' + encodeURIComponent(invoiceNo)).then((r) => {
      expect(r.body.success).to.eq(true)
      const mine = ((r.body.data && r.body.data.content) || []).find((o) => o.invoiceNo === invoiceNo)
      expect(mine, 'order appears in the list').to.exist
    })

    // Fulfilment advances one legal step at a time. This spec used to post NEW -> SHIPPED directly and expect
    // it to SUCCEED — i.e. it asserted the very defect OMS O2 fixed, and had been failing since O2 shipped.
    // An order cannot be dispatched before it is packed, so the whitelist refuses the jump.
    cy.then(() => {
      cy.request({ method: 'POST', url: '/updateOrderStatus', body: { id: orderId, status: 'PACKED' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false }).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        expect(r.body.data.fulfilmentStatus).to.eq('PACKED')
      })
    })

    // OMS O5b: SHIPPED is now DERIVED from dispatched line quantities, so it cannot be typed at all — and this
    // order, recorded through /recordOrder with only {invoiceNo, customerName, total}, has NO LINES. There is
    // literally nothing to dispatch, so it can be packed and no further. That is the honest outcome of the
    // derived model meeting OMS-5 (a POS-recorded order never persists its items), which is still open.
    cy.then(() => {
      cy.request({ method: 'POST', url: '/updateOrderStatus', body: { id: orderId, status: 'SHIPPED' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false }).then((r) => {
        expect(r.body.success, 'an order is shipped by recording a parcel, not by being marked').to.eq(false)
        expect(String(r.body.message || '').toLowerCase()).to.contain('shipment')
      })
    })

    cy.then(() => {
      cy.request({ method: 'POST', url: '/shipOrder', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' },
        body: { id: orderId, lines: [{ orderItemId: 1, quantity: 1 }] } }).then((r) => {
        expect(r.body.success, 'a line-less POS order has nothing to dispatch').to.eq(false)
      })
    })
  })

  it('the illegal jump this spec used to assert is refused (OMS O2)', () => {
    const invoiceNo = 'ORDJUMP-' + Date.now()
    cy.request({
      method: 'POST', url: '/recordOrder',
      body: { invoiceNo: invoiceNo, customerName: 'JumpBuyer', total: 10 },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const id = r.body.data.id
      // Skipping PACKED would mean goods dispatched that nobody picked. The server refuses and says why.
      return cy.request({ method: 'POST', url: '/updateOrderStatus', body: { id, status: 'SHIPPED' },
        headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
    }).then((r) => {
      expect(r.body.success, 'NEW -> SHIPPED must be refused').to.eq(false)
      expect(String(r.body.message || ''), 'and the operator is told why').to.not.be.empty
    })
  })

  it('Store (MARKETPLACE) dashboard shows the Store nav + Orders panel', () => {
    cy.visit('/businessDashboard')
    cy.window().its('MODULE').should('eq', 'MARKETPLACE')
    cy.window().its('VERTICAL_PROFILE.brand').should('contain', 'Store')
    cy.get('#snavStore').should('be.visible')
    cy.window().then((w) => w.showOrders())
    cy.get('#OrdersDiv').should('be.visible')
    cy.get('#tableOrders').should('exist')
  })
})
