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
  // Scanning ships OFF for every tenant now, so #sellScanRow is display:none and scan() below could
  // not reach its box. Pinned in the BROWSER alongside the keyboard flags above - see cy.enableScanBox.
  cy.enableScanBox()
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
        // The picker fills from PagedFetch across every page of the catalogue, so a product seeded
        // moments ago may not be in the <select> yet. Wait for the option, or cy.select() fails with
        // "could not find a single <option>" — a race that reads like a missing product.
        cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 20000 }).should('exist')
        cy.get('#sellItemDD').select(String(productId), { force: true })
        cy.get('#sellSellRate', { timeout: 10000 }).should('not.have.value', '')

        // Bonus off: this case is about the PRICE stop, and #sellBonus now sits between Qty and
        // Price in the chain (it always did on screen). Pinned rather than inherited -
        // pos.entry.showBonus defaults TRUE, so this passed only on tenants carrying an explicit
        // false. The bonus link itself is asserted in pos-keyboard.cy.js and by
        // keyboard-chain-order.cy.js case 7.
        // No pinning: the walk follows whatever this tenant has on screen, and asserts each stop.
        // Choosing a product reflows the strip (loadStock writes the stock and sellable badges from
        // two chained round trips), so typing straight away races the layout and Cypress reports the
        // field as un-actionable. Wait for the row to stop moving - see cy.settled.
        cy.settled('#sellQuantity')
        cy.get('#sellQuantity').clear().type('2{enter}')
        cy.window().should((w) => {
          expect(Cypress.focusedPicker(w), 'Enter on Qty follows the screen')
            .to.eq(nextOnScreen(w, 'sellQuantity'))
        })

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
        /*
         * ⚠ THE WRAPPER, not the <select>. #sellDiscountTypeDD carries class="selectpicker", so
         * bootstrap-select hides the native element PERMANENTLY and renders a button in a sibling
         * .bootstrap-select div. Asserting not.be.visible on the select itself therefore passed whether the
         * chooser was switched off or left on screen — a coincidental pass that could never fail, guarding
         * the very thing this case exists to prove.
         *
         * Found 2026-09-22 by grepping the suite after myplus-f9 hit the same trap from the other side in
         * Slice D (be.visible on a selectpicker, which can never pass). returns-parity.cy.js:125 already
         * records the rule; this line predates it.
         */
        cy.get('#sellDiscountTypeDD').next('.bootstrap-select').should('not.be.visible')

        // The picker fills from PagedFetch across every page of the catalogue, so a product seeded
        // moments ago may not be in the <select> yet. Wait for the option, or cy.select() fails with
        // "could not find a single <option>" — a race that reads like a missing product.
        cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 20000 }).should('exist')
        cy.get('#sellItemDD').select(String(productId), { force: true })
        cy.get('#sellSellRate', { timeout: 10000 }).should('not.have.value', '')
        cy.settled('#sellQuantity')          // the strip is still reflowing after the pick - see above
        cy.get('#sellQuantity').clear().type('1{enter}')
        cy.get('#sellSellRate').type('{enter}')
        /*
         * Straight past the hidden chooser. Read off the screen rather than named: with the TYPE picker
         * hidden it is not in the screen's field list at all, so "the next field on screen" IS the
         * discount amount - which is RULE 2 stated as an assertion instead of as a constant.
         */
        cy.window().should((w) => {
          expect(Cypress.focusedPicker(w), 'the hidden chooser is skipped, not stranded on')
            .to.eq(nextOnScreen(w, 'sellSellRate'))
        })
      })
  })
})

/**
 * The stop the SCREEN says comes after `fromId` on the sale line.
 *
 * The line strip only - #sellScan is excluded because it is deliberately not in CHAIN (focusGoodsEntry
 * targets it directly, and pos-keyboard.js says so).
 *
 * ⚠ Every stop in this file used to be a hardcoded id, and that is what made it tenant-dependent: the
 * D-23 case demanded #sellSerials, which is correct only where serial tracking is ON. On the demo
 * tenant the capability is off, the serial cell is hidden, the chain correctly skips to #sellQuantity -
 * and the spec failed against a product doing exactly what RULE 2 says it must.
 */
const nextOnScreen = (w, fromId) => {
  const ids = Cypress.screenFields(w, '#sellDiv').filter((id) => id !== 'sellScan')
  const i = ids.indexOf(fromId)
  expect(i, `${fromId} is a usable field on the sale line`).to.be.greaterThan(-1)
  return i + 1 < ids.length ? ids[i + 1] : null
}

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
        // The picker fills from PagedFetch across every page of the catalogue, so a product seeded
        // moments ago may not be in the <select> yet. Wait for the option, or cy.select() fails with
        // "could not find a single <option>" — a race that reads like a missing product.
        cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 20000 }).should('exist')
        cy.get('#sellItemDD').select(String(productId), { force: true })
        /*
         * No Enter pressed at all — selection alone advanced the chain.
         *
         * ⚠ To the SERIAL box, not Qty: SER-3c put #sellSerials between the item picker and the
         * quantity, and RULE 1 keeps the chain in screen order. What this case proves is that a
         * SELECTION moves the cursor at all — which field is next is keyboard-chain-order.cy.js's job.
         */
        cy.window({ timeout: 10000 }).should((w) => {
          expect(Cypress.focusedPicker(w), 'a SELECTION advances to the next field on screen')
            .to.eq(nextOnScreen(w, 'sellItemDD'))
        })
      })
  })

  /**
   * ⭐⭐ REPORTED FROM THE COUNTER: choosing a product left the cursor where it was.
   *
   * The case above passes and could not have caught this, which is the finding worth keeping. It selects
   * with `cy.get('#sellItemDD').select(...)` - a programmatic change on the hidden <select>. bootstrap-
   * select fires `changed.bs.select` either way, so the chain advanced and the gate went green.
   *
   * A REAL choice is different: the plugin also closes its menu and RETURNS FOCUS TO ITS OWN BUTTON,
   * and it does that AFTER telling us the value changed. So the handler focused the next field and the
   * plugin pulled the cursor straight back - the operator pressed Enter, chose a product, and sat
   * exactly where they started. Only clicking a real menu row reproduces it.
   *
   * The fix defers the advance by a tick. This case clicks the row a cashier clicks.
   */
  it('⭐⭐ choosing a product by CLICKING the menu advances the cursor, and it STAYS advanced', () => {
    /*
     * ⚠ THE FULL NAME, NOT THE PREFIX. The first cut clicked `.contains('P5Click_')`, which matched a
     * LEFTOVER product from an earlier run - the picker selected id 5140 while this case had just seeded
     * 5168, and the failure read as a broken chain when the click had simply chosen the wrong row.
     * Every seeded name here is unique; the assertion has to use all of it.
     */
    const name = 'P5Click_' + Date.now()
    cy.seedProduct({ name, sellingPrice: 30, stock: 20 })
      .then(({ productId }) => {
        openTill()
        cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 20000 }).should('exist')

        // Open the picker and click the row, exactly as a cashier does. Scoped to the option that
        // carries THIS product's id, so a same-prefix leftover cannot be picked instead.
        cy.get('#sellItemDD').next('.bootstrap-select').find('button').first().click({ force: true })
        cy.get('#sellItemDD').next('.bootstrap-select')
          .find(`.dropdown-menu li a:contains("${name}")`).first().click({ force: true })

        cy.get('#sellItemDD').should('have.value', String(productId))

        /*
         * ⭐⭐ CHOOSING A PRODUCT MUST NOT ADD IT TO THE CART.
         *
         * Reported from the counter. The selection handler committed the line whenever walk() answered
         * null - and choosing an item fires loadStock(), which re-renders the strip, so for a few frames
         * every field behind the picker reads as not-yet-usable and null comes back for a MOMENT. The
         * product landed in the cart with no quantity, no price and no keystroke.
         *
         * Asserted BEFORE the focus check because it is the more serious failure: a cursor in the wrong
         * place is an annoyance, a line in the cart nobody rang up is money.
         */
        cy.window().its('data').should('have.length', 0)

        /*
         * The assertion the old case could not make: where the cursor is AFTER the plugin has finished.
         * Retried through cy.window().should so it outlasts the plugin's own focus restore - with the
         * advance still synchronous the cursor settles back on the picker and this fails.
         */
        cy.window({ timeout: 10000 }).should((w) => {
          const ids = Cypress.screenFields(w, '#sellDiv').filter((id) => id !== 'sellScan')
          const expected = ids[ids.indexOf('sellItemDD') + 1]
          const landed = Cypress.focusedPicker(w)
          expect(landed, 'the cursor left the item picker and stayed away').to.not.eq('sellItemDD')
          expect(landed, `and it landed on the next field the screen offers (${expected})`)
            .to.eq(expected)
        })
      })
  })

  it('a PROGRAMMATIC value change does not move the cursor', () => {
    cy.seedProduct({ name: 'P5Prog_' + Date.now(), sellingPrice: 30, stock: 20 })
      .then(({ productId }) => {
        openTill()

        /*
         * ⚠ WAIT FOR THE TILL TO PLACE ITS OWN CURSOR FIRST.
         *
         * businessDashboard.html calls posFocusEntryPoint() on a 150ms timer when the sale screen
         * opens, which lands on the CUSTOMER (task #13). Focusing #sellScan before that timer fires
         * means the till moves the cursor a moment later, and this case then reports "expected
         * <button.selectpicker> to have id sellScan" - a race, read as a broken rule.
         *
         * pos-checkout-chain.cy.js has a settled() helper for exactly this; here one assertion is
         * enough because there is only the one case.
         */
        cy.window().should((w) => {
          expect(Cypress.focusedPicker(w), 'the till has placed its own initial cursor')
            .to.eq('sellCustomerDD')
        })

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
        // F2 runs completeSale(), the same path the button takes - so the till asks "Complete this
        // sale?" before it posts. Without this the wait below reports "No request ever occurred".
        cy.confirmSale()

        cy.wait('@paid', { timeout: 30000 }).its('response.statusCode').should('eq', 200)
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
