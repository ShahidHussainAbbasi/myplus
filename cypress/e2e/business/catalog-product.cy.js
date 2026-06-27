/**
 * M1 (slice 42) — catalog Product master CRUD from the monolith (Item→Product convergence, strangler step).
 * Additive: the Item screen still works; this proves the single product master is registrable + listable via the
 * existing catalog-service through the monolith. Run headed.
 */
describe('Catalog Product master (M1)', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('registers a catalog Product and lists it', () => {
    const name = 'Prod_' + Date.now()
    let productId

    cy.request({
      method: 'POST', url: '/addProduct',
      body: { name: name, sku: 'SKU' + Date.now(), sellingPrice: 9.5, taxRate: 17, unit: 'pcs', categoryName: 'General' },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    }).then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data).to.have.property('id')
      productId = r.body.data.id
    })

    cy.request('/catalogProducts?size=500').then((r) => {
      expect(r.body.success).to.eq(true)
      const content = (r.body.data && r.body.data.content) ? r.body.data.content : []
      const mine = content.find((p) => p.id === productId)
      expect(mine, 'product appears in the catalog list').to.exist
      expect(mine.name).to.eq(name)
    })
  })

  it('Product master screen renders', () => {
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showProducts')
    cy.window().then((w) => w.showProducts())
    cy.get('#ProductDiv').should('be.visible')
    cy.get('#prodName').should('be.visible')
  })
})
