/**
 * E-commerce (slice 68, E3) — persistent server-side cart. The cart is owned by marketplace-service: add/update/
 * remove are server ops, priced authoritatively from catalog, and the cart survives a page reload via an opaque
 * token kept in localStorage. Run headed.
 */
describe('E-commerce — persistent cart (slice 68, E3)', () => {
  let orgId, productId
  const tag = Date.now()
  const name = 'CartProd_' + tag

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    cy.request({ method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name, sku: 'C' + tag, sellingPrice: 4, taxRate: 0, unit: 'pcs' } })
    // Resolve the catalog productId via the public browse (robust across addProduct's response shape).
    cy.then(() => cy.request('/storefront/products?org=' + orgId).then((r) => {
      const p = (r.body.data || []).find((x) => x.name === name)
      productId = p && p.id
      expect(productId, 'product created + visible in store').to.be.ok
    }))
  })

  it('adds, persists, updates and removes via the cart API', () => {
    let token
    cy.then(() => cy.request({ method: 'POST', url: '/storefront/cart/add', headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, productId } }).then((r) => {
      expect(r.body.success).to.be.true
      token = r.body.data.cartToken
      expect(token, 'token minted').to.be.a('string').and.not.empty
      expect(r.body.data.items).to.have.length(1)
      expect(r.body.data.items[0].name).to.eq(name)           // authoritative name from catalog
      expect(Number(r.body.data.items[0].unitPrice)).to.eq(4) // authoritative price from catalog
      expect(r.body.data.count).to.eq(1)
    }))

    // add again → quantity increments on the same line
    cy.then(() => cy.request({ method: 'POST', url: '/storefront/cart/add', headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, cartToken: token, productId } }).then((r) => {
      expect(r.body.data.items[0].quantity).to.eq(2)
    }))

    // persists across a fresh read (server-owned, not client memory)
    cy.then(() => cy.request('/storefront/cart?org=' + orgId + '&cartToken=' + token).then((r) => {
      expect(r.body.data.count).to.eq(2)
    }))

    // update sets the quantity + recomputes the subtotal
    cy.then(() => cy.request({ method: 'POST', url: '/storefront/cart/update', headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, cartToken: token, productId, quantity: 5 } }).then((r) => {
      expect(r.body.data.items[0].quantity).to.eq(5)
      expect(Number(r.body.data.subtotal)).to.eq(20)
    }))

    // remove empties the cart
    cy.then(() => cy.request({ method: 'POST', url: '/storefront/cart/remove', headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, cartToken: token, productId } }).then((r) => {
      expect(r.body.data.items).to.have.length(0)
    }))
  })

  it('the store page cart persists across a reload', () => {
    cy.visit('/store?org=' + orgId)
    cy.contains('.card .name', name, { timeout: 10000 }).should('exist')
    cy.contains('.card', name).find('button.add').click()
    cy.get('#cartLines .line', { timeout: 10000 }).should('have.length', 1)
    cy.get('#cartLines').should('contain', name)

    // reload — the server-backed cart rehydrates from the localStorage token
    cy.reload()
    cy.get('#cartLines .line', { timeout: 10000 }).should('have.length', 1)
    cy.get('#cartLines').should('contain', name)
  })
})
