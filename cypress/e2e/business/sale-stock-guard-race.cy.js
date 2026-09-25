/**
 * STOCK-RACE-1 — the quantity guard judges only THIS product's stock, and withdraws its refusal when it no
 * longer holds.
 *
 * REPORTED (org 15, pharmacy): "I selected BLK5 Rx 55482396316 and can see stock 4 [the DB and both endpoints say
 * 3], so why 'Quantity exceeds available stock'?" Probe: at +0.8 s the till showed stock 3, quantity 1 — AND the
 * refusal. Cause: loose-sell.js re-runs calculateNetSell() the moment /looseInfo answers (synchronously, from its
 * cache, for a product seen before), which is usually BEFORE /productStock — so the guard compared quantity 1 with
 * batchStock 0 (or the previous product's stock), refused, and nothing ever cleared the message once 3 arrived.
 *
 * The race is made deterministic: /productStock is HELD until /looseInfo has answered. A test that relied on
 * timing would pass on a fast machine with the fix removed.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/sale-stock-guard-race.cy.js --headed --browser chrome
 */
const uniq = () => `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 900 + 100)
const EXCEEDS = /Quantity exceeds available stock/

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value } })
    .its('body.success').should('eq', true)

describe('STOCK-RACE-1 — no refusal against stock that has not loaded', () => {
  let original   // the tenant's own value, restored in after()
  before(() => {
    cy.loginAsPharmaOwner()
    // Pin the guard ON for this file and put back whatever the tenant had (leave no server state).
    cy.request('/getBusinessConfig').then((r) => {
      const all = JSON.stringify(r.body)
      original = /"pos\.stock\.validateOnSelect"[^}]*"value"\s*:\s*"?(true|false)/.exec(all)
    })
    setConfig('pos.stock.validateOnSelect', 'true')
  })
  after(() => {
    cy.loginAsPharmaOwner()
    setConfig('pos.stock.validateOnSelect', original ? original[1] : 'true')
  })
  beforeEach(() => cy.loginAsPharmaOwner())

  const openTill = () => {
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().its('posValidateStockOnSelect', { timeout: 15000 }).should('eq', true)
    cy.get('#sellType').select('sellDiv', { force: true })
  }

  it('⭐⭐ 1 — stock 3, quantity 1: NO refusal, even when /looseInfo answers before /productStock', () => {
    // Two products: stock 3 (the reported case) and stock 0, picked first so batchStock holds a WRONG number
    // when the race runs — the guard must not judge product 1's quantity against product 0's stock.
    cy.seedProduct({ name: `SR0_${uniq()}`, sellingPrice: 50, stock: 0 }).then(({ productId: otherId }) =>
    cy.seedProduct({ name: `SR1_${uniq()}`, sellingPrice: 50, stock: 3 }).then(({ productId }) => {
      openTill()
      // First selection warms loose-sell's cache — the next one settles SYNCHRONOUSLY, the reported case.
      cy.get('#sellItemDD', { timeout: 15000 }).select(String(productId), { force: true })
      cy.get('#sellStock', { timeout: 15000 }).should('have.value', '3')
      // Move to ANOTHER product so re-picking the first really fires loadStock (re-selecting the same value does
      // not change the select — the earlier version never reached the held request).
      cy.get('#sellItemDD').select(String(otherId), { force: true })
      // No inventory → the sellable guard answers 0 (and may reset the picker, which is fine: the next pick is a
      // real change). What the race depends on is batchStock holding this WRONG product's 0.
      cy.window({ timeout: 15000 }).should((w) => expect(w.batchStock, 'batchStock holds the other product').to.eq(0))
      cy.window().then((w) => { if (w.clearFormError) w.clearFormError() })   // start the race from a clean slate

      let release
      const gate = new Promise((r) => { release = r })
      cy.intercept('GET', `**/productStock?productId=${productId}*`, () => gate).as('stock')
      cy.get('#sellItemDD').select(String(productId), { force: true })
      cy.wait(800)                                              // /looseInfo (cached) has settled by now
      cy.get('#globalError').should(($e) => expect($e.text(), 'no refusal while stock is unknown').not.to.match(EXCEEDS))
      cy.then(() => release())
      cy.wait('@stock')
      cy.get('#sellStock', { timeout: 10000 }).should('have.value', '3')
      cy.get('#sellQuantity').should('have.value', '1')
      cy.get('#globalError').should(($e) => expect($e.text(), '⭐ stock 3, qty 1 — nothing to refuse').not.to.match(EXCEEDS))
    }))
  })

  it('⭐ 2 — the guard still refuses a real excess, and withdraws it when the quantity is corrected', () => {
    cy.seedProduct({ name: `SR2_${uniq()}`, sellingPrice: 50, stock: 3 }).then(({ productId }) => {
      openTill()
      cy.get('#sellItemDD', { timeout: 15000 }).select(String(productId), { force: true })
      cy.get('#sellStock', { timeout: 15000 }).should('have.value', '3')
      cy.get('#sellQuantity').clear().type('4')
      cy.get('#globalError').should(($e) => expect($e.text(), '4 > 3 is refused').to.match(EXCEEDS))
      cy.get('#sellQuantity').should('have.class', 'alert-danger')
      cy.get('#sellQuantity').clear().type('2')
      cy.get('#globalError').should(($e) => expect($e.text(), '⭐ corrected — the refusal is withdrawn').not.to.match(EXCEEDS))
      cy.get('#sellQuantity').should('not.have.class', 'alert-danger')
    })
  })
})
