/**
 * CART-TOP — on the sale screen the cart (the lines of the sale) sits ABOVE the item entry, after the customer;
 * payment stays below. Asked by the owner 2026-10-10; decided: the cart is capped (about six lines) and scrolls inside
 * itself, so the entry row never moves however long the bill gets.
 *
 * What each case protects:
 *   C1  the order on screen: customer → cart → item entry → payment
 *   C2  8 lines: the entry row does not move; the cart stops at its cap and scrolls inside; the NEWEST line and the
 *       Totals row are both visible inside the cart
 *
 * Tenant: owner.business@ (POS). Saves nothing: the cart is cleared at the end of each case.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/sale-cart-top.cy.js --headed --browser chrome
 */
const uniq = () => `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 900 + 100)

const openTill = () => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.get('#sellType').select('sellDiv', { force: true })
  cy.get('#sellDiv').should('be.visible')
}
const addLine = (productId, qty) => {
  cy.get('#sellItemDD', { timeout: 15000 }).select(String(productId), { force: true })
  cy.get('#sellQuantity', { timeout: 15000 }).should(($q) => expect($q.val(), 'loadStock answered').not.to.eq(''))
  cy.get('#sellQuantity').clear().type(String(qty)).should('have.value', String(qty))
  cy.get('#addInviceItem').click({ force: true })
}
const top = ($el) => Math.round($el[0].getBoundingClientRect().top)
const clearCart = () => cy.get('#resetSellItem').click({ force: true })

describe('CART-TOP — the cart above the item entry', () => {
  beforeEach(() => cy.loginAsOwner())

  it('C1 the order on screen: customer → cart → item entry → payment', () => {
    openTill()
    cy.get('#sellCustomerDD').closest('.form-group, div').then(($cust) => {
      cy.get('#sellCartTop').should('be.visible').then(($cart) => {
        cy.get('#Sell').then(($form) => {
          cy.get('#iDiv').then(($pay) => {
            expect(top($cart), 'the cart is below the customer').to.be.greaterThan(top($cust))
            expect(top($form), 'the item entry is below the cart').to.be.greaterThan(top($cart))
            expect(top($pay), 'payment is below the item entry').to.be.greaterThan(top($form))
          })
        })
      })
    })
    cy.get('#sellCartTop #sellCartScroll #tablesi').should('exist')
    cy.get('#iDiv #tablesi').should('not.exist')
  })

  it('C2 eight lines: the entry row stays put; the cart scrolls inside; the newest line and Totals stay visible', () => {
    const ids = []
    for (let i = 0; i < 8; i++) cy.seedProduct({ name: `CT_${uniq()}`, sellingPrice: 10 + i, stock: 5 }).then((p) => ids.push(p.productId))
    openTill()
    let entryTop
    cy.get('#sellItemDD').closest('.form-group').then(($e) => { entryTop = top($e) })
    cy.then(() => ids.forEach((id) => addLine(id, 1)))
    cy.window().its('data').should('have.length', 8)
    cy.get('#sellItemDD').closest('.form-group').should(($e) => expect(top($e), 'the entry row did not move').to.eq(entryTop))
    cy.get('#sellCartScroll').should(($b) => {
      const b = $b[0]
      expect(b.scrollHeight, 'more lines than the cap').to.be.greaterThan(b.clientHeight)
      expect(b.clientHeight, 'capped').to.be.at.most(330)
    })
    // the newest line (the last added) is visible inside the box, and not under the pinned Totals
    cy.window().then((w) => {
      const pid = String(ids[ids.length - 1])
      const box = w.document.getElementById('sellCartScroll').getBoundingClientRect()
      const foot = w.document.querySelector('#sellCartScroll tfoot').getBoundingClientRect()
      let row = null
      w.tablesi.rows().every(function () { if (String(this.data()[0]).replace(/<[^>]*>/g, '').trim() === pid) row = this.node() })
      expect(row, 'the newest line is in the grid').to.exist
      const r = row.getBoundingClientRect()
      expect(r.top, 'newest line top inside the box').to.be.at.least(box.top - 1)
      expect(r.bottom, 'newest line above the pinned Totals').to.be.at.most(foot.top + 1)
      expect(foot.bottom, 'Totals pinned inside the box').to.be.at.most(box.bottom + 1)
    })
    cy.get('#sellTotal').should('be.visible')
    clearCart()
    cy.window().its('data').should('have.length', 0)
  })
})
