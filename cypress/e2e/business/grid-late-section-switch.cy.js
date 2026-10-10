/**
 * GRID-LATE — a grid reply that arrives after its section was left is dropped, not drawn into the next section's grid.
 *
 * Found 2026-10-10 while gating TP-1 (doc selling-price-per-purchase-analysis.md §12.7): loadDataTable()'s reply was
 * drawn with the GLOBALS read when it ARRIVED — getAll picks the row builder, datatable is the table it fills. Leave
 * Sale while its list is loading and the late sale reply was drawn as purchases into the Purchase grid:
 * "Cannot read properties of null (reading 'bpurchaseRate')".
 *
 * What each case protects:
 *   L1  Sale → Purchase while the sale list is in flight: no error, and the Purchase grid holds only purchase rows
 *
 * The sale list's reply is HELD (2.5 s) so the switch provably happens while it is in flight; the case asserts the
 * held reply came after the switch, or it would test nothing.
 *
 * Tenant: owner.business@ (POS). Saves nothing.
 * Before the monolith is rebuilt: --env EVAL_SRC=1 loads loadDataTable() from the source into the page (cut out by
 * braces; the content-hashed cached JS cannot be replaced by cy.intercept). The held request is issued AFTER that, so
 * it is the new function's.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/grid-late-section-switch.cy.js --headed --browser chrome
 */
const HOLD_MS = 2500

const loadFix = () => {
  if (!Cypress.env('EVAL_SRC')) return
  cy.readFile('src/main/resources/static/js/business/business.js').then((s) => {
    const i = s.indexOf('function loadDataTable(')
    let d = 0, j = s.indexOf('{', i)
    for (; j < s.length; j++) { if (s[j] === '{') d++; else if (s[j] === '}' && --d === 0) break }
    cy.window().then((w) => w.eval(s.slice(i, j + 1)))
  })
}

describe('GRID-LATE — a late grid reply is dropped when its section was left', () => {
  let errors
  beforeEach(() => {
    errors = []
    cy.on('uncaught:exception', (err) => { errors.push(String(err && err.stack).replace(/\s+/g, ' ').slice(0, 900)); return false })
    cy.loginAsOwner()
  })

  it('L1 Sale → Purchase while the sale list is loading: no error, the Purchase grid holds only purchases', () => {
    const state = { armed: false }
    cy.intercept('GET', '**/getUserSell*', (req) => {
      if (!state.armed || state.held) return
      state.held = true
      req.on('response', (res) => { res.setDelay(HOLD_MS); state.heldAnsweredAt = Date.now() + HOLD_MS })
    }).as('saleGrid')
    cy.intercept('GET', '**/getUserPurchase*').as('purchaseGrid')

    cy.visitSaleScreen()
    cy.wait('@saleGrid', { timeout: 30000 })           // the page's own first load, not held
    // Opening the page can send more than one sale load; let them all land before the fix is loaded, or a slow one
    // (sent by the OLD loader) arrives after the switch and throws in a --env EVAL_SRC run — not the code under test.
    cy.get('#tableSell tbody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)
    cy.wait(3000)
    loadFix()
    cy.then(() => { state.armed = true })
    cy.window().then((w) => {
      w.$('#sellType').val('sellDiv').trigger('change')       // a sale list load — HELD
      w.$('#purchaseType').val('purchaseDiv').trigger('change')   // leave Sale while it is in flight
      state.switchedAt = Date.now()
    })
    cy.get('#purchaseDiv').should('be.visible')
    cy.wait('@purchaseGrid', { timeout: 30000 })
    cy.wait(HOLD_MS + 1000)
    cy.then(() => {
      expect(state.held, 'a sale list load was held').to.eq(true)
      expect(state.heldAnsweredAt, 'the held sale reply came AFTER leaving Sale').to.be.greaterThan(state.switchedAt)
      expect(errors, 'no uncaught error: ' + errors.join(' | ')).to.deep.eq([])
    })
    cy.get('#tablePurchase tbody tr').should('have.length.greaterThan', 0).each(($tr) => {
      expect($tr.find('#purchaseInvoiceNo').length, 'a purchase row, not a sale row: ' + $tr.text().slice(0, 80)).to.eq(1)
    })
  })
})
