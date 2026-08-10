/**
 * P5 — the complete Enter chain, and the rules that go with it.
 *
 * Design: microservices/docs/slices/uiux-P5-enter-chain-and-row.md
 *
 * The three things this pins, all of which came from driving a real till:
 *   D-23  a dropdown advances on SELECTION, not on a keystroke — so choosing a value costs ONE
 *         action, not the two the bootstrap-select button used to force
 *   D-24  a customer is required only when the sale leaves a BALANCE; a fully-paid sale needs none
 *   #1    sellDiscountTypeDD is in the chain, so amount-vs-percent no longer needs the mouse
 *
 * Run headed.
 */

function openTill() {
  cy.visit('/businessDashboard')
  cy.get('#sellType').select('sellDiv', { force: true })
  cy.get('#sellDiv').should('be.visible')
  cy.window().should((w) => {
    expect(w.posGoToCheckout, 'pos-keyboard.js loaded').to.be.a('function')
  })
  cy.window().then((w) => {
    w.posKeyboardEnabled = true
    w.posShortcutsEnabled = true
    w.applyPosKeyboard()
  })
}

function scan(entry) {
  cy.get('#sellScan', { timeout: 30000 }).should('be.visible').type(entry, { timeout: 30000 })
}

function pressKey(key) {
  cy.document().then((doc) => {
    doc.dispatchEvent(new doc.defaultView.KeyboardEvent('keydown',
      { key: key, bubbles: true, cancelable: true }))
  })
}

describe('P5 — the line chain includes the discount TYPE', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('Enter walks price → discount type → discount, one press per field', () => {
    cy.seedProduct({ name: 'P5Chain_' + Date.now(), sellingPrice: 25, stock: 20 })
      .then(({ productId }) => {
        openTill()
        cy.get('#sellItemDD').select(String(productId), { force: true })
        cy.get('#sellSellRate', { timeout: 10000 }).should('not.have.value', '')

        cy.get('#sellItems').clear().type('2{enter}')
        cy.focused().should('have.id', 'sellSellRate')

        // The stop that did not exist before: amount-vs-percent, reachable without the mouse.
        cy.get('#sellSellRate').type('{enter}')
        cy.focused().then(($f) => {
          const id = $f.attr('id') || $f.closest('.bootstrap-select').prev('select').attr('id') || ''
          expect(id, 'the discount TYPE is a stop in the chain').to.eq('sellDiscountTypeDD')
        })
      })
  })

  it('a discount type hidden by configuration is skipped, not stranded', () => {
    cy.seedProduct({ name: 'P5Skip_' + Date.now(), sellingPrice: 25, stock: 20 })
      .then(({ productId }) => {
        openTill()
        // Exactly what the Configuration screen does when the shop turns the chooser off.
        cy.window().then((w) => {
          w.posFields = { discountType: false }
          w.applyPosFieldVisibility()
        })
        cy.get('#sellDiscountTypeDD').should('not.be.visible')

        cy.get('#sellItemDD').select(String(productId), { force: true })
        cy.get('#sellSellRate', { timeout: 10000 }).should('not.have.value', '')
        cy.get('#sellItems').clear().type('1{enter}')
        cy.get('#sellSellRate').type('{enter}')
        // Straight past the hidden chooser to the discount amount.
        cy.focused().should('have.id', 'sellDiscount')
      })
  })
})

describe('P5 — D-23: a dropdown advances on SELECTION', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  /**
   * The regression this rule exists for. Enter on a bootstrap-select BUTTON activates it and opens the
   * menu, so the cursor never moved on the first press. Selecting a value must advance by itself.
   */
  it('choosing an item moves the cursor on without a second key', () => {
    cy.seedProduct({ name: 'P5Sel_' + Date.now(), sellingPrice: 30, stock: 20 })
      .then(({ productId }) => {
        openTill()
        // .select() on the underlying <select> fires the same changed.bs.select a real click does.
        cy.get('#sellItemDD').select(String(productId), { force: true })
        // No Enter pressed at all — selection alone advanced the chain.
        cy.focused({ timeout: 10000 }).should('have.id', 'sellItems')
      })
  })

  it('a PROGRAMMATIC value change does not move the cursor', () => {
    cy.seedProduct({ name: 'P5Prog_' + Date.now(), sellingPrice: 30, stock: 20 })
      .then(({ productId }) => {
        openTill()
        cy.get('#sellScan').focus()
        // loadStock()/loadCategories set values this way constantly. If that advanced the chain, the
        // cursor would be flung across the form every time a product loaded.
        cy.window().then((w) => {
          w.$('#sellItemDD').val(String(productId))
          if (w.$('#sellItemDD').data('selectpicker')) w.$('#sellItemDD').selectpicker('refresh')
        })
        cy.wait(400)
        cy.focused().should('have.id', 'sellScan')      // never moved
      })
  })
})

describe('P5 — D-24: a customer is needed only when money is owed', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('a FULLY PAID sale completes with no customer named', () => {
    const stamp = Date.now()
    cy.intercept('POST', '**/addSell').as('paid')
    cy.seedProduct({ name: 'P5Cash_' + stamp, sku: 'P5C' + stamp, sellingPrice: 40, stock: 20 })
      .then(({ sku }) => {
        openTill()
        scan(sku + '{enter}')
        cy.window().its('data').should('have.length', 1)

        pressKey('F8')                                   // exact cash — nothing left owing
        cy.get('#sellRec').should('have.value', '40.00')
        cy.get('#sellCN').should('have.value', '')       // nobody named, deliberately
        pressKey('F2')

        cy.wait('@paid').its('response.statusCode').should('eq', 200)
        cy.window({ timeout: 20000 }).its('data').should('have.length', 0)
      })
  })

  /**
   * The carve-out that stays. A receivable against nobody cannot be chased, aged or collected — the
   * one field whose absence makes the money unrecoverable rather than merely inconvenient.
   */
  it('a sale that leaves a BALANCE still demands a customer', () => {
    const stamp = Date.now()
    cy.seedProduct({ name: 'P5Due_' + stamp, sku: 'P5D' + stamp, sellingPrice: 100, stock: 20 })
      .then(({ sku }) => {
        openTill()
        scan(sku + '{enter}')
        cy.window().its('data').should('have.length', 1)

        cy.get('#sellRec').clear().type('30')            // part payment — 70 still owed
        cy.get('#addSell').click({ timeout: 30000 })

        // Refused, and it says why.
        cy.get('#formErrorToast, #globalError', { timeout: 15000 })
          .should('be.visible').and('contain', 'balance')
        cy.window().its('data').should('have.length', 1) // nothing was submitted
      })
  })
})
