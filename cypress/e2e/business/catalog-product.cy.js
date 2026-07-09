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
    // The Product form lives in a modal now — open it before asserting its fields render.
    cy.window().then((w) => w.newProduct())
    cy.get('#ProductModal').should('have.class', 'open')
    cy.get('#prodName').should('be.visible')
  })

  // The duplicate-SKU 409 from catalog-service must reach the user. Previously the message was written to
  // #globalError, which sits behind the fixed modal overlay → invisible. Now it surfaces as a toast (and the
  // client pre-check blocks it before submit). Either way the user sees "already …" and the modal stays open.
  it('duplicate SKU is rejected with a visible error and the modal stays open', () => {
    const sku = 'DUP' + Date.now()

    // The proxy relays the catalog 409 as a friendly {success:false, message} body (HTTP 200), not a 5xx.
    cy.request({
      method: 'POST', url: '/addProduct',
      body: { name: 'Seed_' + Date.now(), sku: sku, sellingPrice: 1, taxRate: 0, unit: 'pcs', categoryName: 'General' },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    }).its('body.success').should('eq', true)

    cy.request({
      method: 'POST', url: '/addProduct',
      body: { name: 'Dupe_' + Date.now(), sku: sku, sellingPrice: 1, taxRate: 0, unit: 'pcs', categoryName: 'General' },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    }).then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.success).to.eq(false)
      expect(String(r.body.message).toLowerCase()).to.contain('already')
    })

    // UI: entering the same SKU in the modal shows a visible error toast and does NOT close the modal.
    cy.visit('/businessDashboard')
    cy.window().then((w) => w.showProducts())
    cy.get('#ProductDiv').should('be.visible')
    cy.window().then((w) => w.newProduct())
    cy.get('#ProductModal').should('have.class', 'open')
    cy.get('#prodName').type('Another_' + Date.now())
    cy.get('#prodSku').type(sku).blur()
    cy.get('#addProduct').click()
    cy.get('#formErrorToast', { timeout: 10000 }).should('be.visible')
      .invoke('text').then((t) => expect(t.toLowerCase()).to.contain('already'))
    cy.get('#ProductModal').should('have.class', 'open')   // save was blocked, form not lost
  })
})
