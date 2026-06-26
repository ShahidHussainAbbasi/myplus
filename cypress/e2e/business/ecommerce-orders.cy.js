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

    cy.request('/getOrders').then((r) => {
      expect(r.body.success).to.eq(true)
      const mine = (r.body.data || []).find((o) => o.invoiceNo === invoiceNo)
      expect(mine, 'order appears in the list').to.exist
    })

    cy.then(() => {
      cy.request({ method: 'POST', url: '/updateOrderStatus', body: { id: orderId, status: 'SHIPPED' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false }).then((r) => {
        expect(r.body.success).to.eq(true)
        expect(r.body.data.fulfilmentStatus).to.eq('SHIPPED')
      })
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
