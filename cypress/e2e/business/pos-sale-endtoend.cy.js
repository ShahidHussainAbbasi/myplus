/**
 * P1+P2+P3 — a WHOLE sale, start to finish, without a mouse.
 *
 * The other POS specs test the pieces. This one tests the journey, because the pieces passing is not
 * the same claim: the bridge from line entry to checkout was bound as a jQuery handler delegated from
 * `document`, and #sellScan's inline onkeydown calls event.stopPropagation() on every Enter — so the
 * event never reached it and "Enter on the empty scan box" did nothing at all. Every unit-level spec
 * stayed green while the end-to-end path was broken, and a user found it, not the suite.
 *
 * So this file asserts the SEAMS:
 *   lines → checkout → tender → completed invoice
 * and it drives them the way a cashier does — real keystrokes, no direct calls into app functions.
 *
 * Run headed.
 */

/** Open the sell screen with the keyboard packages on, asserting the modules actually loaded. */
function openTill(opts) {
  var o = opts || {}
  cy.visit('/businessDashboard')
  cy.get('#sellType').select('sellDiv', { force: true })
  cy.get('#sellDiv').should('be.visible')
  cy.window().should((w) => {
    expect(w.posGoToCheckout, 'pos-keyboard.js exposes the checkout bridge').to.be.a('function')
    expect(w.sellScanAdd, 'business.js exposes sellScanAdd (the bridge lives inside it)').to.be.a('function')
  })
  cy.window().then((w) => {
    w.posKeyboardEnabled = true
    w.posShortcutsEnabled = o.shortcuts !== false
    w.posQuickPickEnabled = o.quickPick === true
    w.applyPosKeyboard()
  })
  // Scanning ships OFF for every tenant now, so #sellScanRow is display:none and scan() below could
  // not reach its box. Pinned in the BROWSER alongside the keyboard flags above - see cy.enableScanBox.
  cy.enableScanBox()
}

/** Type into the scan box, tolerating the global AJAX overlay (see sell.cy.js for why the long timeout). */
function scan(entry) {
  cy.get('#sellScan', { timeout: 30000 }).should('be.visible').type(entry, { timeout: 30000 })
}

/*
 * The "Complete this sale?" dialog is answered by `cy.confirmSale()` (commands.js) — this file's local
 * helper is what became that command.
 *
 * Called with `{ optional: true }` at both sites below: this spec drives the KEYBOARD end to end and does
 * not pin pos.sale.confirmOnComplete, so if the dialog is off the sale has already posted and there is
 * nothing to answer.
 *
 * ⚠ The shared command also fixed a race the local helper had: it probed with jQuery `:visible`, but
 * confirm-dialog.js lifts the backdrop off `opacity: 0` inside a requestAnimationFrame — so a snapshot
 * taken right after the click could see nothing and skip a dialog that was already in the DOM.
 */

function pressKey(key, alt) {
  cy.document().then((doc) => {
    doc.dispatchEvent(new doc.defaultView.KeyboardEvent('keydown', {
      key: key, altKey: alt === true, bubbles: true, cancelable: true
    }))
  })
}

describe('End-to-end — a complete sale with no mouse', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  /**
   * THE REGRESSION THIS FILE EXISTS FOR. Enter on the empty scan box must leave line entry and cross to
   * the checkout. It did nothing at all in the shipped build.
   *
   * ⚠ IT LANDS ON THE PAYMENT METHOD, and this case used to expect the CUSTOMER.
   *
   * Both halves of that moved after it was written. Task #13 took the customer OUT of the checkout block
   * and put it at the head of the LINE chain — CHECKOUT's own comment says leaving it in both "would make
   * the cashier name the customer twice per sale". So "leave line entry" can no longer mean "go to the
   * customer": the customer is now BEHIND the cursor, not ahead of it.
   *
   * Then the owner ruled where the crossing lands: CHECKOUT_LANDING = ['sellPayMethod', 'sellRec',
   * 'sellTradeDiscount'], first usable wins. The rule is "after the goods comes how they pay".
   */
  it('Enter on the EMPTY scan box crosses from line entry to the checkout', () => {
    const stamp = Date.now()
    cy.seedProduct({ name: 'E2E_A_' + stamp, sku: 'E2EA' + stamp, sellingPrice: 50, stock: 20 })
      .then(({ sku }) => {
        openTill()
        scan(sku + '{enter}')
        cy.window().its('data').should('have.length', 1)

        // The bridge. Nothing typed — just Enter.
        scan('{enter}')

        // Focus is now in the checkout block, not the line form. Resolved through the shared helper
        // because sellPayMethod is a bootstrap-select and the plugin focuses a <button> with no id.
        cy.window().should((w) => {
          expect(Cypress.focusedPicker(w), 'the crossing lands on how they pay')
            .to.eq('sellPayMethod')
        })
      })
  })

  it('the bridge refuses on an EMPTY cart — payment for nothing strands the cashier', () => {
    openTill()
    scan('{enter}')
    // Still on the items: focus stayed at the entry point rather than jumping to a checkout that
    // cannot complete anything.
    cy.focused().should('have.id', 'sellScan')
    cy.window().its('data').should('have.length', 0)
  })

  it('Esc from the checkout returns to the items with the cart intact', () => {
    const stamp = Date.now()
    cy.seedProduct({ name: 'E2E_B_' + stamp, sku: 'E2EB' + stamp, sellingPrice: 40, stock: 20 })
      .then(({ sku }) => {
        openTill()
        scan('2*' + sku + '{enter}')
        cy.window().its('data.0.quantity').should('eq', 2)

        scan('{enter}')                                  // to checkout
        cy.get('#sellCN').should('exist')
        cy.get('body').type('{esc}')                     // changed my mind — more items
        cy.window().its('data').should('have.length', 1) // cart untouched
      })
  })

  /**
   * The whole journey: two scanned lines, a multiplier, a named customer, exact cash, completed —
   * every step a keystroke. This is the claim "a sale without a mouse" actually makes.
   */
  it('scans, tenders and completes an invoice using only the keyboard', () => {
    const stamp = Date.now()
    cy.intercept('POST', '**/addSell').as('sale')
    cy.seedProduct({ name: 'E2E_C_' + stamp, sku: 'E2EC' + stamp, sellingPrice: 25, stock: 50 })
      .then(({ sku }) => {
        openTill()

        // Two lines: one single, one x3 via the P2 multiplier. 25 + 75 = 100.
        scan(sku + '{enter}')
        cy.window().its('data.0.quantity').should('eq', 1)
        scan('3*' + sku + '{enter}')
        cy.window().its('data.0.quantity').should('eq', 4)
        cy.get('#sellTotal').should('contain', '100')

        // Bridge to checkout.
        scan('{enter}')

        // Name the customer. Manual mode is reached without a mouse only if the tenant defaults to it,
        // so switch explicitly here — the mode toggle itself is still mouse-driven (a known gap).
        cy.get('#btnModeManual').click({ timeout: 30000 })
        cy.get('#sellCN').should('be.visible').type('E2E Buyer ' + stamp)

        // P2: exact cash, then complete — both keys, no mouse.
        pressKey('F8')
        cy.get('#sellRec').should('have.value', '100.00')
        pressKey('F2')

        cy.confirmSale({ optional: true })
        cy.wait('@sale').its('response.statusCode').should('eq', 200)
        // A completed sale clears the till for the next customer.
        cy.window({ timeout: 20000 }).its('data').should('have.length', 0)
      })
  })

  it('Enter through the checkout completes the sale without F2', () => {
    const stamp = Date.now()
    cy.intercept('POST', '**/addSell').as('sale2')
    cy.seedProduct({ name: 'E2E_D_' + stamp, sku: 'E2ED' + stamp, sellingPrice: 60, stock: 20 })
      .then(({ sku }) => {
        openTill()
        scan(sku + '{enter}')
        cy.window().its('data').should('have.length', 1)

        scan('{enter}')
        cy.get('#btnModeManual').click({ timeout: 30000 })
        cy.get('#sellCN').should('be.visible').type('E2E Enter ' + stamp)
        pressKey('F8')                                   // exact cash so nothing is left owing
        cy.get('#sellRec').should('have.value', '60.00')

        /*
         * Walk the REST of the checkout with Enter; past the last field it completes.
         *
         * ⚠ One Enter is not the rest of the chain. CHECKOUT is
         * ['sellPayMethod','sellStoreCredit','sellRec','sellTradeDiscount','dueDateTemp'], and
         * `pos.invoice.tradeDiscountEnabled` defaults TRUE — so Enter on the till lands on the trade
         * discount, not on a completed sale. dueDateTemp is skipped because F8 paid this one exactly and
         * it only appears when a sale leaves a balance; past it, walk() returns null and completeSale()
         * runs.
         *
         * Each stop is asserted rather than blind-fired: "press Enter twice and hope" would still pass
         * if the chain silently lost a field, which is the regression this file is for.
         */
        cy.get('#sellRec').type('{enter}')
        cy.focused().should('have.id', 'sellTradeDiscount')

        cy.get('#sellTradeDiscount').type('{enter}')
        cy.confirmSale({ optional: true })
        cy.wait('@sale2', { timeout: 30000 }).its('response.statusCode').should('eq', 200)
      })
  })

  it('a quick-pick tile joins the same journey', () => {
    cy.intercept('GET', '**/topProducts*', {
      body: { status: 'SUCCESS', collection: [{ productId: 9001, name: 'Loose Tea /kg', sellingPrice: 45, units: 12 }] }
    }).as('tiles')
    openTill({ quickPick: true })
    cy.window().then((w) => w.renderQuickPick())
    cy.wait('@tiles')
    cy.get('.qp-tile').should('have.length', 1)

    pressKey('1', true)                                  // Alt+1
    cy.window().its('data').should('have.length', 1)
    // ...and the bridge still works with a tile-only cart.
    scan('{enter}')
    cy.focused().should('not.have.id', 'sellScan')
  })
})
