/**
 * QTY-RACE-1 — a late default never overwrites what the cashier typed.
 *
 * THE DEFECT: picking a product fires /productStock asynchronously, and its answer writes the tenant's default
 * quantity into an EMPTY box. Clear the box and start typing before that answer lands, and the default is
 * written in the gap: "1" then the cashier's "5" = 15. Found by a probe on a flaky gate that rang up "11".
 *
 * The race is made DETERMINISTIC here: /productStock is held back, the quantity is typed while it is in
 * flight, and only then is the answer released. A test that relies on real timing would pass on a fast
 * machine with the fix removed.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/sale-qty-default-race.cy.js --headed --browser chrome
 */
const uniq = () => `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 900 + 100)

describe('QTY-RACE-1 — the stock answer never overwrites a typed quantity', () => {
  beforeEach(() => cy.loginAsOwner())

  const open = (productId) => {
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.get('#sellType').select('sellDiv', { force: true })
  }

  /*
   * THE REAL RACE (corrected after a probe): the cashier is IN the empty box when the late default lands, and
   * their first keystroke went AFTER it — "1" then "5" = 15. An earlier version of this case cleared an EMPTY box
   * and expected it to stay empty; a probe showed that fires no input event (nothing was touched), so the default
   * correctly filled it and the case was asserting something that was never the defect. Fix under test: a default
   * written into the focused box arrives SELECTED, so typing replaces it.
   */
  it('⭐⭐ 1 — the cashier is in the box when the default lands: typing REPLACES it (5, not 15)', () => {
    cy.seedProduct({ name: `QR1_${uniq()}`, sellingPrice: 100, stock: 20 }).then(({ productId }) => {
      open(productId)
      let release
      const gate = new Promise((r) => { release = r })
      cy.intercept('GET', `**/productStock?productId=${productId}*`, () => gate).as('stock')

      cy.get('#sellItemDD', { timeout: 15000 }).select(String(productId), { force: true })
      cy.get('#sellQuantity').focus().should('have.value', '')   // in the empty box, about to type
      cy.then(() => release())                                  // the late default lands NOW
      cy.wait('@stock')
      cy.get('#sellQuantity').should('have.value', '1')          // the default is there…
      cy.get('#sellQuantity').type('5')                          // …and the cashier types over it
      cy.get('#sellQuantity').should('have.value', '5')          // the defect made this "15"
    })
  })

  it('⭐ 1b — a quantity typed BEFORE the answer lands is never overwritten', () => {
    cy.seedProduct({ name: `QR1b_${uniq()}`, sellingPrice: 100, stock: 20 }).then(({ productId }) => {
      open(productId)
      let release
      const gate = new Promise((r) => { release = r })
      cy.intercept('GET', `**/productStock?productId=${productId}*`, () => gate).as('stock')
      cy.get('#sellItemDD', { timeout: 15000 }).select(String(productId), { force: true })
      cy.get('#sellQuantity').type('7')                          // typed while the answer is held
      cy.get('#sellQuantity').clear()                            // …and then emptied, deliberately
      cy.then(() => release())
      cy.wait('@stock')
      cy.wait(500)
      cy.get('#sellQuantity').should('have.value', '')           // touched: the default stays out
    })
  })

  it('2 — an untouched box still gets the default (the feature itself is unchanged)', () => {
    cy.seedProduct({ name: `QR2_${uniq()}`, sellingPrice: 100, stock: 20 }).then(({ productId }) => {
      open(productId)
      cy.intercept('GET', `**/productStock?productId=${productId}*`).as('stock')
      cy.get('#sellQuantity').invoke('val', '')                 // empty, as after a reset — not typed in
      cy.get('#sellItemDD', { timeout: 15000 }).select(String(productId), { force: true })
      cy.wait('@stock')
      cy.get('#sellQuantity').should(($q) => expect(Number($q.val()), 'the tenant default').to.be.greaterThan(0))
    })
  })

  it('3 — the next product starts fresh: its default fills an emptied, untouched box', () => {
    cy.seedProduct({ name: `QR3_${uniq()}`, sellingPrice: 100, stock: 20 }).then(({ productId }) => {
      open(productId)
      // Typed on some EARLIER line, then the form reset after Add — the flag must not leak across products.
      cy.get('#sellQuantity').clear({ force: true }).type('7', { force: true })
      cy.get('#sellQuantity').invoke('val', '')
      cy.intercept('GET', `**/productStock?productId=${productId}*`).as('stock')
      cy.get('#sellItemDD', { timeout: 15000 }).select(String(productId), { force: true })
      cy.wait('@stock')
      cy.get('#sellQuantity').should(($q) => expect(Number($q.val()), 'default applied').to.be.greaterThan(0))
    })
  })
})
