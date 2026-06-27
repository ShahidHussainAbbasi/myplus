/**
 * E-commerce (slice 56) — public order tracking. A guest order returns a reference; the customer looks up its
 * fulfilment status by ref + contact (no account). A wrong contact reveals nothing. Run headed.
 */
describe('E-commerce — public order tracking', () => {
  let orgId, productId, ref
  const pname = 'TrackShop_' + Date.now()
  const contact = '0300TRACK'

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    cy.request({ method: 'POST', url: '/addProduct', body: { name: pname, sku: 'TRK' + Date.now(), sellingPrice: 12, taxRate: 0, unit: 'pcs' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => {
        productId = r.body.data.id
        return cy.request({ method: 'POST', url: '/addProductStock', body: { productId, quantity: 10 }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      })
  })

  it('a placed order can be tracked by ref + contact, but not with a wrong contact', () => {
    cy.request({
      method: 'POST', url: '/storefront/checkout',
      body: { organizationId: orgId, customerName: 'Tracker_' + Date.now(), customerContact: contact, shippingAddress: '1 Track St', total: 12, paymentMode: 'COD', items: [{ productId, quantity: 1, price: 12 }] },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      ref = r.body.data.id
      expect(ref, 'order reference returned').to.exist
    })

    cy.then(() => {
      cy.request('/storefront/track?ref=' + ref + '&contact=' + encodeURIComponent(contact)).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        expect(r.body.data.ref).to.eq(ref)
        expect(r.body.data.status).to.eq('NEW')
      })
      cy.request('/storefront/track?ref=' + ref + '&contact=wrongcontact').then((r) => {
        expect(r.body.success, 'wrong contact reveals nothing').to.eq(false)
      })
    })
  })

  it('the storefront track panel shows the order status', () => {
    cy.visit('/store?org=' + orgId)
    cy.get('#trkRef', { timeout: 10000 }).clear().type(String(ref))
    cy.get('#trkContact').clear().type(contact)
    cy.contains('button', 'Check status').click()
    cy.get('#trkStatus', { timeout: 10000 }).should('contain', 'NEW').and('contain', '#' + ref)
  })
})
