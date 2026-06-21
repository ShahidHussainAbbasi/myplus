/**
 * Sell↔stock saga — UI-driven smoke (slice 33, U3/U4) using the real sell-form flow:
 *   fill #Sell → #addInviceItem (cart #iDiv) → pick customer (#btnModeSelect/#sellCustomerDD) → #addSell.
 *
 * Run HEADED with `trade.saga.enabled=true` + migration done. This is a first draft: bootstrap `selectpicker`
 * dropdowns + AJAX timing usually need one headed pass to settle selectors — adjust as needed. The reliable
 * regression check is the request-based saga-sell.cy.js; this verifies the saga through the actual screen.
 */
describe('Sell↔stock saga — through the sell form (UI)', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.intercept('GET', '**/getStock*').as('getStock')
    cy.intercept('POST', '**/addSell').as('addSell')
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('sellDiv', { force: true })   // open the Sell section (off-screen select)
    cy.get('#sellDiv').should('be.visible')
  })

  it('sells a stocked item end-to-end and addSell returns SUCCESS', () => {
    // 1. Pick the first real item; getStock fills #sellStock (inventory on-hand) + #sellSellRate (catalog price).
    cy.get('#sellItemDD option').then(($opts) => {
      const opt = [...$opts].find((o) => o.value && o.value !== '')
      if (!opt) return cy.log('No items in picker — skipping')
      cy.get('#sellItemDD').select(opt.value, { force: true })
    })
    cy.wait('@getStock')

    // 2. Only proceed if this item has inventory stock (else the saga correctly rejects OUT_OF_STOCK).
    cy.get('#sellStock').invoke('val').then((stockStr) => {
      const onHand = parseFloat(stockStr || '0')
      if (!(onHand > 0)) return cy.log(`Picked item has on-hand=${onHand} — pick a stocked item; skipping`)

      // 3. Fill QTY (1). Sell rate auto-fills from catalog; default it if empty.
      cy.get('#sellItems').clear().type('1')
      cy.get('#sellSellRate').invoke('val').then((r) => { if (!r) cy.get('#sellSellRate').clear().type('1') })

      // 4. Add the line to the cart.
      cy.get('#addInviceItem').click()
      cy.get('#iDiv').should('exist')

      // 5. Customer — select mode (default), pick the first existing customer.
      cy.get('#sellCustomerDD option').then(($c) => {
        const c = [...$c].find((o) => o.value && o.value !== '')
        if (c) cy.get('#sellCustomerDD').select(c.value, { force: true })
      })
      cy.get('#sellRec').clear({ force: true }).type('1', { force: true })

      // 6. Submit; saga reserves+confirms inventory.
      cy.get('#addSell').click()
      cy.wait('@addSell').then(({ response }) => {
        cy.log(`addSell: ${JSON.stringify(response.body).substring(0, 200)}`)
        expect(response.statusCode).to.eq(200)
        expect(response.body.status).to.eq('SUCCESS')
      })
    })
  })
})
