/**
 * E-commerce (slice 57) — order status timeline. Each fulfilment transition records a notification event; the
 * public tracking shows the timeline. Place → NEW; back-office advance → NEW then PACKED. Run headed.
 */
describe('E-commerce — order status timeline', () => {
  let orgId, productId, ref
  const pname = 'TLShop_' + Date.now()
  const contact = '0300TL'

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    cy.request({ method: 'POST', url: '/addProduct', body: { name: pname, sku: 'TL' + Date.now(), sellingPrice: 9, taxRate: 0, unit: 'pcs' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => {
        productId = r.body.data.id
        return cy.request({ method: 'POST', url: '/addProductStock', body: { productId, quantity: 10 }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      })
  })

  beforeEach(() => cy.loginAsMarketplace())

  it('placing then advancing an order builds the tracking timeline', () => {
    cy.request({
      method: 'POST', url: '/storefront/checkout',
      body: { organizationId: orgId, customerName: 'TLBuyer_' + Date.now(), customerContact: contact, shippingAddress: '2 TL St', total: 9, paymentMode: 'COD', items: [{ productId, quantity: 1, price: 9 }] },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    }).then((r) => { expect(r.body.success, JSON.stringify(r.body)).to.eq(true); ref = r.body.data.id })

    // wrap in cy.then so `ref` is read at execution time (it's set inside the checkout .then above)
    cy.then(() => {
      // freshly placed → timeline has NEW
      cy.request('/storefront/track?ref=' + ref + '&contact=' + encodeURIComponent(contact)).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        expect((r.body.data.events || []).map((e) => e.status)).to.deep.eq(['NEW'])
      })
      // back-office advances to PACKED → timeline appends it
      cy.request({ method: 'POST', url: '/updateOrderStatus', body: { id: ref, status: 'PACKED' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      cy.request('/storefront/track?ref=' + ref + '&contact=' + encodeURIComponent(contact)).then((r) => {
        expect((r.body.data.events || []).map((e) => e.status)).to.deep.eq(['NEW', 'PACKED'])
        expect(r.body.data.status).to.eq('PACKED')
      })
    })
  })

  it('the storefront track panel renders the timeline', () => {
    cy.visit('/store?org=' + orgId)
    cy.get('#trkRef', { timeout: 10000 }).clear().type(String(ref))
    cy.get('#trkContact').clear().type(contact)
    cy.contains('button', 'Check status').click()
    cy.get('#trkStatus', { timeout: 10000 }).should('contain', 'NEW').and('contain', 'PACKED')
  })
})
