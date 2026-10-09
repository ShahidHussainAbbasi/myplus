/**
 * PG-LATE — a product page that arrives after its grid was destroyed is dropped, not drawn.
 *
 * Defect (doc selling-price-per-purchase-analysis.md §8.1, pre-existing): reopening Products while its grid was
 * still loading a page — or opening another section in that moment — destroyed the table the request belonged to.
 * The late reply was then handed to the destroyed table and DataTables threw
 * "Cannot read properties of undefined (reading 'style')" (catalog-products.js deliver).
 *
 * What each case protects:
 *   G1  Products rebuilt while a page is in flight: no error, and the grid shows the rebuilt build's rows
 *
 * Not a case: opening ANOTHER section while a product page is in flight also destroys the grid, but the old build
 * did not throw there (tried: green on the old build twice), so a case for it would test nothing. The same guard
 * covers it — it drops any reply whose table was destroyed, whoever destroyed it.
 *
 * The first /getProductPage after the trigger is HELD (delay) so the rebuild provably happens while it is in flight;
 * the case asserts the held reply really arrived after the rebuild, or it would test nothing.
 *
 * Tenant: owner.business@ (POS). Saves nothing.
 *
 * Before the monolith is rebuilt the browser still has the old catalog-products.js; run with
 *   --env EVAL_SRC=1   to evaluate the source file in the page first (cy.intercept cannot replace the hashed, cached JS).
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/product-grid-late-page.cy.js --headed --browser chrome
 */
const HOLD_MS = 2500

function openProducts() {
  cy.visit('/businessDashboard')
  cy.window().should('have.property', 'showProducts')
  if (Cypress.env('EVAL_SRC')) {
    cy.readFile('src/main/resources/static/js/business/catalog-products.js').then((src) => cy.window().then((w) => w.eval(src)))
  }
  cy.intercept('GET', '**/getProductPage*').as('page0')
  cy.window().then((w) => w.showProducts())
  cy.wait('@page0', { timeout: 20000 })
  cy.get('#tableProduct tbody tr', { timeout: 20000 }).should('have.length.greaterThan', 0)
}

/**
 * Hold the NEXT GRID page only; record when it was answered.
 *
 * ⚠ The "already registered" panel (refreshExistingRows) calls /getProductPage too, on a debounce timer, always with
 * includeInactive=true. Holding whichever request came first sometimes held the PANEL's, the grid's page came back
 * at once, and the case passed on the broken build (1 run in 3). The grid never sends includeInactive here.
 */
function holdNextPage(state) {
  let held = false
  state.gridPages = 0
  cy.intercept('GET', '**/getProductPage*', (req) => {
    if (String(req.query.includeInactive) === 'true') return   // the panel, not the grid
    state.gridPages++
    if (held) return
    held = true
    req.on('response', (res) => { res.setDelay(HOLD_MS); state.heldAnsweredAt = Date.now() + HOLD_MS })
  }).as('page')
}

describe('PG-LATE — a late product page is dropped, not drawn into a destroyed grid', () => {
  let errors
  beforeEach(() => {
    errors = []
    cy.on('uncaught:exception', (err) => { errors.push(String(err && err.stack).replace(/\s+/g, ' ').slice(0, 600)); return false })
    cy.loginAsOwner()
  })

  it('G1 Products rebuilt while a page is in flight: no error, the rebuilt grid draws', () => {
    openProducts()
    const state = {}
    holdNextPage(state)
    cy.window().then((w) => {
      w.loadProductTable()                       // build A — its page is HELD
      cy.wait(300).then(() => {
        state.rebuiltAt = Date.now()
        w.loadProductTable()                     // build B — destroys A while A's page is in flight
      })
    })
    cy.wait(HOLD_MS + 1500)
    cy.then(() => {
      expect(state.gridPages, 'both builds asked for a page').to.be.at.least(2)
      expect(state.rebuiltAt, 'the rebuild happened').to.be.a('number')
      expect(state.heldAnsweredAt, 'the held page was answered AFTER the rebuild').to.be.greaterThan(state.rebuiltAt)
      expect(errors, 'no uncaught error: ' + errors.join(' | ')).to.deep.eq([])
    })
    cy.get('#tableProduct tbody tr').should('have.length.greaterThan', 0)
    cy.get('#tableProduct tbody td.dataTables_empty').should('not.exist')
  })
})
