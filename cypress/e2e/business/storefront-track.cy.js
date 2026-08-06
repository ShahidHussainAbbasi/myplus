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
    // Slice 106: server cart (slice 68) — see cy.storefrontOrder.
    cy.storefrontOrder(orgId, { productId, quantity: 1 },
      { customerName: 'Tracker_' + Date.now(), customerContact: contact, shippingAddress: '1 Track St', paymentMode: 'COD' },
    ).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      // OMS O2 (OMS-8): the tracking reference is the order NUMBER, not the primary key. A raw auto-increment
      // id was guessable — the id space could be walked across tenants — and meaningless to quote on the phone.
      ref = r.body.data.orderNo
      expect(ref, 'order reference returned').to.match(/^SO-/)
    })

    cy.then(() => {
      cy.request('/storefront/track?ref=' + encodeURIComponent(ref) + '&contact=' + encodeURIComponent(contact)).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        expect(r.body.data.ref, 'the reference echoed back is the SO- number').to.eq(ref)
        expect(r.body.data.status).to.eq('NEW')
      })
      cy.request('/storefront/track?ref=' + encodeURIComponent(ref) + '&contact=wrongcontact').then((r) => {
        expect(r.body.success, 'wrong contact reveals nothing').to.eq(false)
      })
    })
  })

  it('the storefront track panel shows the order status', () => {
    cy.visit('/store?org=' + orgId)
    cy.get('#trkRef', { timeout: 10000 }).clear().type(String(ref))
    cy.get('#trkContact').clear().type(contact)
    cy.contains('button', 'Check status').click()
    // The number carries its own SO- prefix, so the panel no longer prints a redundant "#".
    cy.get('#trkStatus', { timeout: 10000 }).should('contain', 'NEW').and('contain', ref)
  })
})
