/**
 * Slice 74 — one creation path. The Register menu's primary entry is the Product (Catalog Master) form; the legacy
 * Item screen is demoted to "Legacy Items (edit)". Saving via the master form lists it as a catalog product. Run headed.
 */
describe('Registration — Product master is the creation path (slice 74)', () => {
  beforeEach(() => cy.loginAsBusiness())

  it('the Register menu opens the Product master form (primary) and the Item screen is edit-only', () => {
    cy.visit('/businessDashboard')

    // primary entry → Product master form
    cy.contains('.snav-btn', 'Register').click({ force: true })
    cy.contains('.snav-menu a', 'Product').click({ force: true })
    cy.get('#ProductDiv', { timeout: 10000 }).should('be.visible')
    cy.get('#prodName').should('be.visible')          // the master form (captures price)
    cy.get('#prodPrice').should('be.visible')

    // legacy Item screen is still reachable for editing — but demoted
    cy.contains('.snav-btn', 'Register').click({ force: true })
    cy.contains('.snav-menu a', 'Legacy Items').click({ force: true })
    cy.get('#itemDiv', { timeout: 10000 }).should('be.visible')
  })

  it('a product registered via the master form lists as a catalog product', () => {
    const name = 'OnePath_' + Date.now()
    let productId
    cy.request({ method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name, sku: 'OP' + Date.now(), sellingPrice: 12, taxRate: 0, unit: 'pcs', categoryName: 'General' } })
      .then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        productId = r.body.data.id
      })
    cy.then(() => cy.request('/catalogProducts?size=500').then((r) => {
      const content = (r.body.data && r.body.data.content) ? r.body.data.content : []
      expect(content.find((p) => p.id === productId), 'product in the catalog master').to.exist
    }))
  })
})
