/**
 * E-commerce E7 saga hardening (slice 52) — storefront orders now track their reservation's confirm state
 * (PENDING→CONFIRMED) so a recovery relay can re-drive a confirm that failed at placement. Happy path: a placed
 * order ends up CONFIRMED end-to-end (UI→service→DB→back-office). The relay's failure-recovery is unit-tested
 * (OrderServiceTest, needs fault injection). Run headed.
 */
describe('E-commerce — order reservation is confirmed (saga relay tracking)', () => {
  let orgId, productId
  const pname = 'RelayShop_' + Date.now()

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    cy.request({ method: 'POST', url: '/addProduct', body: { name: pname, sku: 'RLY' + Date.now(), sellingPrice: 25, taxRate: 0, unit: 'pcs' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => {
        productId = r.body.data.id
        return cy.request({ method: 'POST', url: '/addProductStock', body: { productId, quantity: 10 }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      })
  })

  beforeEach(() => cy.loginAsMarketplace())

  it('a placed storefront order reports its reservation CONFIRMED', () => {
    const buyer = 'RelayBuyer_' + Date.now()
    let orderId
    cy.request({
      method: 'POST', url: '/storefront/checkout',
      body: { organizationId: orgId, customerName: buyer, customerContact: '0300RLY', shippingAddress: '3 Relay St', total: 25, paymentMode: 'COD', items: [{ productId, quantity: 1, price: 25 }] },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.reservationStatus, 'confirmed inline at placement').to.eq('CONFIRMED')
      orderId = r.body.data.id
    })

    // and the back-office sees the same confirmed state
    cy.request('/getOrders').then((r) => {
      const o = (r.body.data || []).find((x) => x.id === orderId)
      expect(o, 'order in back-office').to.exist
      expect(o.reservationStatus).to.eq('CONFIRMED')
    })
  })
})
