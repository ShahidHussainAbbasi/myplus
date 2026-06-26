/**
 * E-commerce E2a (slice 47) — public storefront: anonymous browse + guest checkout → order in the back-office.
 * The store is identified by orgId. We log in once only to resolve the demo org + seed a product; the storefront
 * paths themselves (/storefront/**) are public. Run headed.
 */
describe('E-commerce — public storefront', () => {
  let orgId, productId
  const pname = 'Shop_' + Date.now()

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => {
      const orgs = r.body.collection || []
      const o = orgs[0] || {}
      orgId = o.id || o.organizationId || o.orgId
    })
    // Seed a product in this store's catalog AND stock it — checkout reserves inventory now (slice 49).
    cy.request({ method: 'POST', url: '/addProduct', body: { name: pname, sku: 'SHOP' + Date.now(), sellingPrice: 12.5, taxRate: 0, unit: 'pcs' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => {
        productId = r.body.data.id
        return cy.request({ method: 'POST', url: '/addProductStock', body: { productId, quantity: 20 }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      })
  })

  it('the public products endpoint lists the store catalog with stock availability', () => {
    expect(orgId, 'resolved a store org').to.exist
    cy.request('/storefront/products?org=' + orgId).then((r) => {
      expect(r.body.success).to.eq(true)
      const mine = (r.body.data || []).find((p) => p.name === pname)
      expect(mine, 'product visible on the storefront').to.exist
      expect(mine).to.not.have.property('costPrice')   // public projection — no internal fields
      // availability merge (slice 49 follow-up): the stocked product reports its sellable qty so the UI can cap/badge.
      expect(mine).to.have.property('available')
      expect(Number(mine.available), 'stocked product is available').to.be.greaterThan(0)
    })
  })

  it('checkout of an out-of-stock item returns the real "out of stock" reason (not a generic retry)', () => {
    // A product with NO inventory — the reservation must reject it and the proxy must surface marketplace's message.
    const dry = 'Dry_' + Date.now()
    cy.request({ method: 'POST', url: '/addProduct', body: { name: dry, sku: 'DRY' + Date.now(), sellingPrice: 5, taxRate: 0, unit: 'pcs' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => {
        const dryId = r.body.data.id
        cy.request({
          method: 'POST', url: '/storefront/checkout',
          body: { organizationId: orgId, customerName: 'NoStock', customerContact: '0300DRY', shippingAddress: 'x', total: 5, items: [{ productId: dryId, quantity: 1, price: 5 }] },
          headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        }).then((res) => {
          expect(res.status).to.eq(200)
          expect(res.body.success, JSON.stringify(res.body)).to.eq(false)
          expect(String(res.body.message).toLowerCase(), 'message names the stock problem').to.contain('stock')
        })
      })
  })

  it('a guest checkout places an order that appears in the back-office', () => {
    const buyer = 'Guest_' + Date.now()
    cy.request({
      method: 'POST', url: '/storefront/checkout',
      body: { organizationId: orgId, customerName: buyer, customerContact: '0300SHOP', shippingAddress: '12 Test St', total: 25, items: [{ productId, quantity: 2, price: 12.5 }] },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    }).then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.source).to.eq('STOREFRONT')
      expect(r.body.data.fulfilmentStatus).to.eq('NEW')
    })

    cy.loginAsMarketplace()
    cy.request('/getOrders').then((r) => {
      const mine = (r.body.data || []).find((o) => o.customerName === buyer)
      expect(mine, 'guest order in the back-office').to.exist
      expect(mine.source).to.eq('STOREFRONT')
    })
  })

  it('the public store page renders the product grid', () => {
    cy.visit('/store?org=' + orgId)
    cy.get('#grid', { timeout: 10000 }).should('be.visible')
    cy.contains('.card .name', pname, { timeout: 10000 }).should('exist')
    cy.get('.cart').should('be.visible')
  })
})
