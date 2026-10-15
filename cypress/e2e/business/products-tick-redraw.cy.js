/**
 * PG-TICK — on Register → Products a tick survives a redraw of its row, and the "selected" bar always counts what is
 * ticked on the page.
 *
 * Found 2026-10-10 (doc selling-price-per-purchase-analysis.md §12.7): the grid is server-paged, so every redraw
 * replaces the rows. Tick a product while its search is still settling and the next page drew it again UNticked,
 * while the bar still said "1 selected" — Delete then had nothing to confirm.
 *
 * What each case protects:
 *   K1  a redraw of the same page keeps the tick, and the bar says 1
 *   K2  a search that no longer shows the product drops it: nothing ticked, the bar is gone — Delete can never act
 *       on a row the operator cannot see
 *
 * Tenant: owner.business@ (POS). Saves one spec product (PRKTICK_*), deactivated in after().
 * Before the monolith is rebuilt: --env EVAL_SRC=1 loads catalog-products.js into the page and rebuilds the grid
 * (whole file: loadProductTable reads its closure).
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/products-tick-redraw.cy.js --headed --browser chrome
 */
const name = 'PRKTICK_' + Date.now()
let productId = null

const openProducts = () => {
  cy.visit('/businessDashboard')
  cy.window().should('have.property', 'showProducts')
  if (Cypress.env('EVAL_SRC')) {
    cy.readFile('src/main/resources/static/js/business/catalog-products.js').then((src) => cy.window().then((w) => w.eval(src)))
  }
  cy.window().then((w) => w.showProducts())
  cy.get('#tableProduct tbody tr', { timeout: 20000 }).should('have.length.greaterThan', 0)
}

/** Search and wait until the page for the FULL term has drawn (the search box fires while typing). */
const search = (term) => {
  cy.intercept({ method: 'GET', url: '**/getProductPage*', query: { q: term } }).as('full')
  cy.get('#ProductDiv input[type="search"]').first().clear().type(term)
  cy.wait('@full', { timeout: 15000 })
}

const box = () => cy.get(`#tableProduct input[type='checkbox'][value='${productId}']`)
const bar = () => cy.get('#bulkBarProduct')

describe('PG-TICK — a tick survives a redraw; the bar counts the page', () => {
  before(() => {
    cy.loginAsOwner()
    cy.seedProduct({ name, sellingPrice: 10 }).then((p) => { productId = p.productId })
  })
  beforeEach(() => cy.loginAsOwner())
  after(() => {
    cy.loginAsOwner()
    cy.then(() => productId && cy.request({ method: 'POST', url: '/deactivateProduct', headers: { 'Content-Type': 'application/json' },
      body: { checked: String(productId) }, failOnStatusCode: false }))
  })

  it('K1 a redraw of the same page keeps the tick, and the bar says 1', () => {
    openProducts()
    search(name)
    box().check({ force: true })
    bar().should('be.visible').find('.bulk-count').should('have.text', '1')

    // The same page drawn again — what a settling search, a reload after a save or a page-length change all do.
    cy.intercept('GET', '**/getProductPage*').as('redraw')
    cy.window().then((w) => w.datatable.ajax.reload(null, false))
    cy.wait('@redraw')
    cy.wait(500)
    box().should('be.checked')
    bar().should('be.visible').find('.bulk-count').should('have.text', '1')
  })

  it('K2 a search that hides the ticked product drops it: nothing ticked, no bar', () => {
    openProducts()
    search(name)
    box().check({ force: true })
    bar().find('.bulk-count').should('have.text', '1')
    search(name + '_NOPE')
    cy.get(`#tableProduct input[type='checkbox'][value='${productId}']`).should('not.exist')
    cy.get("#tableProduct input[type='checkbox']:checked").should('have.length', 0)
    bar().should('not.be.visible')
  })
})
