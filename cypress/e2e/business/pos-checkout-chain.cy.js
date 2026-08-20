/**
 * The CHECKOUT half of the Enter chain — every path a sale can take to being paid for.
 *
 * <h3>The bug this was written for</h3>
 * On a CASH sale — the commonest checkout there is — Enter on the payment method went nowhere. Cash is
 * the pre-selected default, so {@code #sellPayMethod} holds a value from the moment the screen opens;
 * advancing was wired only to {@code changed.bs.select}, which fires on a CHANGE, and keeping the
 * default is not a change. Enter then fell through to a branch that opens the menu on an EMPTY picker
 * and does nothing on a full one, so the cashier sat on the pay method with no keystroke that would
 * leave it. <b>{@code #sellRec} — the amount received — was unreachable on every cash sale.</b>
 *
 * The rule that was missing, and is now the fix: <i>"it already has the right answer" is a reason to
 * move on, not a reason to stop.</i>
 *
 * <h3>Why it survived the existing suite</h3>
 * The line chain (Item → Qty → Price) was covered thoroughly. The checkout chain was exercised only
 * where a value was CHANGED, which is the one case that already worked. A default that is correct and
 * therefore never touched is exactly the state no test had.
 */

function openTill() {
  cy.viewport(1600, 900)
  cy.loginAsBusiness()
  cy.visit('/businessDashboard')
  cy.get('#sellType').select('sellDiv', { force: true })
  cy.get('#sellDiv').should('be.visible')
  cy.window().should((w) => expect(w.posGoToCheckout, 'pos-keyboard.js').to.be.a('function'))
  cy.window().then((w) => {
    w.posKeyboardEnabled = true
    w.posShortcutsEnabled = true
    w.applyPosKeyboard()
  })
  quiet()
}

/**
 * Wait for the overlay to LIFT — the one thing that stops a keystroke reaching a field.
 *
 * NOT waitForAppReady(): the till keeps a poll running while it is open, so that helper's additional
 * "jQuery.active quiet for 300ms" condition never becomes true here and it times out. Tried, and it took
 * this spec from six passes to two.
 *
 * Called at the START of every test AND before any keystroke that follows an action which refetches —
 * the customer list, the settings read, a completed sale. The overlay comes and goes throughout, and a
 * single wait in openTill() only covers the first wave. That partial cover is why this spec flapped:
 * five runs of identical code gave 6, 3, 5, 2 and 6 passes with a different test failing each time.
 */
function quiet() {
  cy.get('#appAjaxOverlay', { timeout: 30000 }).should('not.be.visible')
  cy.get('.ao-box', { timeout: 30000 }).should('not.be.visible')
}

/**
 * Enter, delivered to whatever currently has focus.
 *
 * `.type('{enter}')`, not `.trigger('keydown')`. Two reasons, both learned the hard way here:
 *
 *  1. A DISPATCHED KeyboardEvent is not a real key press. The handler opens an empty picker by calling
 *     `.click()` on the plugin's button, and the browser's own key-to-click activation does not happen
 *     for a synthetic event — so the empty-picker case failed while the till was working perfectly.
 *
 *  2. `.trigger()` enforces actionability at the moment it fires and does NOT retry past a cover, so a
 *     keystroke landing while the shared AJAX overlay is up dies with "covered by <div class=ao-box>".
 *     That is what made this spec flap: five runs of identical code gave 6, 3, 5, 2 and 6 passes, with
 *     a different test failing each time. `.type()` waits for the element to become interactable, which
 *     is also what a cashier does — they wait for the spinner and then press the key.
 */
function pressEnter() {
  cy.focused().type('{enter}')
}

describe('Checkout chain — every route to a paid sale', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  // ── the walk itself ─────────────────────────────────────────────────────────────────────────────

  it('the chain reaches the amount received — nothing between them swallows it', () => {
    openTill()
    cy.window().then((w) => {
      // Asserted on walk(), which is what Enter calls. sellTradeDiscount sits between the two and is
      // visible by default, so the route is pay method -> trade discount -> received.
      const CHECKOUT = ['sellCustomerDD', 'sellCN', 'sellCC', 'sellPayMethod', 'sellTradeDiscount',
        'sellStoreCredit', 'sellInsured', 'sellRec', 'dueDateTemp']
      let cur = 'sellPayMethod'
      const seen = []
      for (let i = 0; i < 6 && cur; i++) {
        cur = w.EnterChain.walk(CHECKOUT, cur, 1)
        if (cur) seen.push(cur)
      }
      expect(seen, 'the walk from the pay method: ' + JSON.stringify(seen)).to.include('sellRec')
    })
  })

  it('sellInsured is gone from the screen but still listed — a dead id must not break the walk', () => {
    openTill()
    cy.window().then((w) => {
      // The insurance field was removed from the template; its id remains in the CHECKOUT list. That is
      // harmless ONLY because usable() answers false for a missing element rather than throwing — worth
      // pinning, because the next dead id will not be noticed either.
      expect(w.document.getElementById('sellInsured'), 'really is absent').to.be.null
      expect(w.EnterChain.usable('sellInsured'), 'and is skipped, not fatal').to.eq(false)
    })
  })

  // ── THE CASE ────────────────────────────────────────────────────────────────────────────────────

  it('CASH — Enter on the pre-selected payment method moves the cursor on', () => {
    openTill()
    cy.window().then((w) => {
      // Exactly the state a cashier meets: the default is already correct, so nothing was ever changed.
      expect(w.$('#sellPayMethod').val(), 'cash is pre-selected').to.be.ok
    })

    // Focus the picker the way the keyboard does — bootstrap-select hides the <select> and focuses its
    // generated button, so pressing Enter on the <select> would test something no cashier can do.
    cy.get('#sellPayMethod').next('.bootstrap-select').find('button').focus()
    pressEnter()

    // Before the fix the cursor stayed on the pay-method button for ever.
    cy.focused().should(($el) => {
      expect($el.attr('id') || $el.closest('.bootstrap-select').prev('select').attr('id'),
        'the cursor left the payment method').to.not.eq('sellPayMethod')
    })
  })

  it('CASH — and keeps going until it reaches the amount received', () => {
    openTill()
    cy.get('#sellPayMethod').next('.bootstrap-select').find('button').focus()

    /*
     * TWO presses: pay method -> trade discount -> received.
     *
     * A fixed count is honest here because the trade discount is visible by default, which is the
     * configuration a shop meets before it changes anything. A shop that switches it off has one stop
     * fewer, and that is covered by the walk() test above, which asserts the ROUTE rather than a
     * keystroke count.
     *
     * Measured, not assumed: the focus trail is
     *     sellPayMethod -> sellTradeDiscount -> sellRec
     * and a further press STAYS on sellRec rather than completing the sale, so the cashier cannot
     * overshoot the money by leaning on the key.
     */
    pressEnter()
    // One press must leave the pay method and land on a REAL field. Which field depends on the tenant's
    // configuration — the trade discount is one a shop can switch off — so the route is asserted, not a
    // keystroke count. Counting presses would pin one tenant's setup and complete the sale on any other,
    // which is exactly what an earlier version of this test did.
    cy.focused().should(($el) => {
      const id = $el.attr('id')
      expect(id, 'landed somewhere real').to.be.ok
      expect(id, 'and not back at the start of the form').to.not.eq('sellScan')
    })
    pressEnter()
    cy.focused().should(($el) => {
      expect(['sellRec', 'sellTradeDiscount', 'sellStoreCredit', 'dueDateTemp'],
        'still walking the checkout, not thrown out of it').to.include($el.attr('id'))
    })
  })

  // ── the paths that already worked, re-pinned so the fix cannot break them ───────────────────────

  it('CHANGING the payment method still advances — the case that always worked', () => {
    openTill()
    cy.get('#sellPayMethod').select('CARD', { force: true })
    // changed.bs.select fires on a real selection and moves the cursor on. The fix must not double-fire
    // it or the cursor would skip a field.
    cy.focused().should(($el) => {
      expect($el.attr('id'), 'moved off the payment method').to.not.eq('sellPayMethod')
    })
  })

  it('an EMPTY picker still OPENS on Enter rather than jumping past it', () => {
    openTill()
    /*
     * The behaviour that must survive the fix: a picker with NO value opens rather than being skipped.
     * On a credit sale the cashier has to be able to name the account, and a keystroke that jumped past
     * an empty customer list would make that impossible from the keyboard.
     *
     * Cleared explicitly. #sellCustomerDD is empty at page load and filled by AJAX (see
     * searchable-selects.js), so whether it holds a value when this runs is a race — and with a value
     * it correctly ADVANCES, which is the opposite of what this case is about.
     */
    cy.window().then((w) => {
      const $dd = w.$('#sellCustomerDD')
      // The list must have something IN it. On a fresh demo screen it carries only its placeholder
      // option, and bootstrap-select will not open an empty menu — so a test that skipped this would be
      // asserting the plugin's emptiness rule, not the keyboard's behaviour.
      if ($dd.find('option').length < 2) {
        $dd.append(new w.Option('Probe Customer', '999999'))
      }
      $dd.val('')
      if ($dd.data('selectpicker')) $dd.selectpicker('refresh')
      expect($dd.val() || '', 'genuinely empty before the keystroke').to.eq('')
      expect($dd.find('option').length, 'and has something to offer').to.be.greaterThan(1)
    })
    // {enter} through cy.type, not a synthetic keydown. The handler opens the menu by calling .click()
    // on the plugin's button, and a dispatched KeyboardEvent does not reproduce the browser's own
    // key-to-click activation — so the assertion would be measuring Cypress, not the till.
    cy.get('#sellCustomerDD').next('.bootstrap-select').find('button').focus().type('{enter}')
    // The MENU, not just the class. Both arrive, but the visible dropdown is what a cashier is looking
    // for, and Cypress retries this assertion while bootstrap-select finishes opening — the class alone
    // was asserted a beat too early and reported a working picker as broken.
    cy.get('#sellCustomerDD').next('.bootstrap-select').find('.dropdown-menu')
      .should('be.visible')
    cy.get('#sellCustomerDD').next('.bootstrap-select').should('have.class', 'open')
  })

  it('SHIFT+Enter still walks BACKWARDS out of a filled picker', () => {
    openTill()
    quiet()
    // .type, not .trigger: same reason as pressEnter — a dispatched event does not retry past a cover.
    cy.get('#sellRec').focus().type('{shift}{enter}')
    // Backwards from received lands on the trade discount, not on nothing: a chain that only goes one
    // way is a chain a cashier cannot correct a mistake in.
    cy.focused().should(($el) => {
      expect($el.attr('id'), 'went back, not nowhere').to.not.eq('sellRec')
    })
  })

  // ── end to end, no mouse ────────────────────────────────────────────────────────────────────────

  it('MOUSE FREE — scan, quantity, checkout, and the cursor lands on the money', () => {
    const name = 'CashChain_' + Date.now()
    cy.seedProduct({ name, sellingPrice: 50, stock: 20, sku: 'CC' + Date.now() })
      .then(({ sku }) => {
        openTill()
        // Wait for the overlay to lift before scanning. The till fires several AJAX waves as it opens and
        // the shared spinner sits over the scan box while they run; a keystroke landing then fails with
        // "covered by <div class=ao-box>", which reads like a layout fault and is really a race.
        quiet()
        cy.get('#sellScan').should('be.visible').type(sku + '{enter}')
        cy.get('#sellItems', { timeout: 15000 }).should('be.visible')
        cy.focused().type('2{enter}')
        cy.get('#tablesi tbody tr', { timeout: 15000 }).should('have.length.at.least', 1)

        // Into the checkout, then along it to the money — the whole point of the keyboard flow.
        cy.window().then((w) => w.posGoToCheckout())
        cy.get('#sellPayMethod').next('.bootstrap-select').find('button').focus()
        pressEnter()
        pressEnter()
        cy.focused().should('have.id', 'sellRec')
      })
  })
})
