/**
 * Slice 50 — back-office stock UI on the shared Product (Catalog Master) screen. An operator sees each product's
 * on-hand and adds stock from the dashboard (no API/curl) — the inventory the storefront/POS reservation saga then
 * draws down. Run headed.
 */
describe('Back-office product stock (slice 50)', () => {
  let productId
  const pname = 'StockUI_' + Date.now()

  before(() => {
    cy.loginAsMarketplace()
    cy.request({ method: 'POST', url: '/addProduct', body: { name: pname, sku: 'STK' + Date.now(), sellingPrice: 30, taxRate: 0, unit: 'pcs' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => { expect(r.body.success, JSON.stringify(r.body)).to.eq(true); productId = r.body.data.id })
  })

  beforeEach(() => cy.loginAsMarketplace())

  it('shows on-hand and adds stock from the Product screen', () => {
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showProducts')
    cy.window().then((w) => w.showProducts())

    // the new product's row renders with a lazy-loaded on-hand of 0
    cy.get('#stk_' + productId, { timeout: 10000 }).should('have.text', '0')

    // add 15 from the UI → on-hand reflects it
    cy.get('#addstk_' + productId).clear().type('15')
    cy.get('#addstkbtn_' + productId).click()
    cy.get('#stk_' + productId, { timeout: 10000 }).should('have.text', '15')
  })

  it('persists the added stock (visible to the storefront reservation path)', () => {
    cy.request('/productStock?productId=' + productId).then((r) => {
      expect(r.body.success).to.eq(true)
      expect(parseFloat(r.body.stock)).to.eq(15)
    })
  })
})
