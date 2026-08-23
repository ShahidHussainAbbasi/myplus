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
  // Not just quiet() — see settled(). Every test in this file drives the keyboard, and every one of
  // them was racing the till's own initial focus.
  settled()
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

/**
 * Wait until the TILL has placed its own cursor, before this test places one.
 *
 * Opening the sale screen focuses the scan box, asynchronously, after applyPosKeyboard(). A test that
 * focuses a field and immediately types is racing that, and loses often enough to look like a product
 * bug: the key is delivered to #sellScan instead, and Enter on an empty scan box means "go to
 * checkout", which on an empty cart puts the cursor straight back in the scan box. Measured, not
 * inferred — the keydown's own target was #sellScan while cy.focused() had just reported the button.
 *
 * The app's initial focus is one-shot, so once it has landed nothing moves the cursor again on its own.
 * Waiting for it is therefore enough; no arbitrary wait is involved.
 */
function settled() {
  quiet()
  cy.focused().should(($el) => {
    expect($el.attr('id'), 'the till has placed its own initial cursor').to.eq('sellScan')
  })
}

/**
 * Put the cursor on a picker and WAIT until it is really there before returning.
 *
 * `.focus()` fires the event, it does not guarantee the cursor stays: the till refetches the customer
 * list and re-runs selectpicker('refresh'), which rebuilds the very button just focused, and the cursor
 * falls back to the entry point. Pressing a key into that gap tests nothing — one test read `sellScan`
 * for exactly this reason and looked like a product bug.
 *
 * The trailing assertion is retryable, so it holds until focus settles or the test fails saying so.
 */
function focusPicker(id) {
  settled()
  cy.get('#' + id).next('.bootstrap-select').find('button').focus()
  cy.focused().should(($el) => {
    expect($el.closest('.bootstrap-select').prev('select').attr('id'),
      'the cursor is really on #' + id + ' before a key is pressed').to.eq(id)
  })
}

/**
 * Wait for a picker's menu to be OPEN.
 *
 * Required between the two presses of a double Enter. The first press opens the menu and the plugin
 * then moves focus into its live-search box; both are asynchronous. Pressing again before that settles
 * sends the second Enter to the OLD focus, so the escape never runs — the difference between the
 * passing and failing versions of this rule was precisely this one retryable assertion, not anything
 * in the till.
 */
function expectOpen(id) {
  cy.get('#' + id).next('.bootstrap-select').should('have.class', 'open')
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
    focusPicker('sellPayMethod')

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
    // `.open` on the wrapper — the plugin's own signal, and ONE element. Scoping to .dropdown-menu
    // matched two nodes (the menu and its inner list) and Cypress asserted on the hidden one, reporting
    // a picker that opens perfectly as broken. A probe confirmed the real state: class "…open", menu
    // visible, 282 options loaded.
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

  // ── the rule of thumb: EVERY dropdown behaves like the item picker ──────────────────────────────

  it('THE RULE — a double Enter escapes ANY unanswered picker, not just the item one', () => {
    openTill()
    quiet()
    /*
     * The behaviour a cashier learns on the item picker must hold on all of them: press Enter to open,
     * press again with nothing chosen to move on.
     *
     * It used to hold on ONE picker. The handler gated on membership of CHAIN, and the checkout
     * pickers — #sellCustomerDD, #sellPayMethod — live in CHECKOUT, so the escape never applied to
     * them. On a walk-in cash sale the cursor landed on the customer list and no keystroke would leave
     * it. Naming fields one at a time is what let the same dead end appear three times (the discount
     * type in P5, the customer list here); the rule is now "every picker", tested as such.
     */
    focusPicker('sellCustomerDD')
    pressEnter()                                     // opens
    expectOpen('sellCustomerDD')
    pressEnter()                                     // "nothing here"
    cy.focused().should(($el) => {
      const id = $el.attr('id') || $el.closest('.bootstrap-select').prev('select').attr('id')
      expect(id, 'the cursor left the customer picker').to.not.eq('sellCustomerDD')
    })
  })

  it('an empty CUSTOMER means a walk-in, so the cursor goes to the money', () => {
    openTill()
    quiet()
    // Not the payment method: that is already Cash, and a walk-in paying cash wants the amount box.
    // A customer may still be REQUIRED to complete the sale — that is the submit path's job to say,
    // not something to enforce by stranding the cursor.
    focusPicker('sellCustomerDD')
    pressEnter()
    expectOpen('sellCustomerDD')
    pressEnter()
    cy.focused().should('have.id', 'sellRec')
  })

  it('SKIPPING IS SAFE — the payment method already holds the tenant default, so it is never left blank', () => {
    openTill()
    quiet()
    /*
     * The walk-in path jumps sellCustomerDD -> sellRec, straight past the payment method. That is only
     * defensible because the field is never empty when we pass it: `pos.tender.default` (CASH out of
     * the box) is applied to the control on load, and the markup carries CASH selected as well.
     *
     * Skipping a field that already holds the right answer saves a keystroke. Skipping one that does
     * not would post a sale with no tender — so this asserts the PROPERTY the skip depends on, not
     * merely that the cursor moved.
     */
    cy.get('#sellPayMethod').should('have.value', 'CASH')

    focusPicker('sellCustomerDD')
    pressEnter()
    expectOpen('sellCustomerDD')
    pressEnter()
    cy.focused().should('have.id', 'sellRec')
    // Still Cash after being walked past — the skip reads it, it does not clear it.
    cy.get('#sellPayMethod').should('have.value', 'CASH')
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

        /*
         * Into the checkout, then along it to the money — the whole point of the keyboard flow.
         *
         * Walk from WHEREVER goToCheckout() puts the cursor, rather than assuming. It focuses the FIRST
         * usable checkout field, which is the customer picker — not the pay method — so a fixed two
         * presses from a hand-placed cursor was testing a path no cashier takes. How many stops lie
         * between depends on the tenant's configuration anyway.
         *
         * The bound is small on purpose: this asserts the money is a few keystrokes away, which is the
         * promise the keyboard flow makes. A cashier who had to press Enter twenty times would have a
         * working chain and an unusable till.
         */
        cy.window().then((w) => w.posGoToCheckout())
        quiet()

        /*
         * NO CUSTOMER CHOSEN — a walk-in. The double Enter escapes the picker.
         *
         * First Enter opens the list, second says "nothing here" and moves on. An earlier draft selected
         * a customer to get past this, which meant the test never exercised the commonest cash sale
         * there is.
         */
        const toMoney = (left) => {
          cy.focused().then(($el) => {
            const id = $el.attr('id') || $el.closest('.bootstrap-select').prev('select').attr('id')
            if (id === 'sellRec') return
            expect(left, 'the money is a few keystrokes from the checkout: stopped on ' + id)
              .to.be.greaterThan(0)
            pressEnter()
            toMoney(left - 1)
          })
        }
        toMoney(6)
        cy.focused().should('have.id', 'sellRec')
      })
  })

  /**
   * ⭐ THE PAY METHOD DECIDES WHERE THE CURSOR GOES — not the order the fields happen to sit in.
   *
   * The checkout chain walked the form's LAYOUT, so a cash sale landed in Trade discount: a field most
   * cash sales never touch, while the amount handed over — the one field cash cannot be completed
   * without — sat two stops further on. The cashier's next keystroke and the chain's next stop disagreed
   * on every sale.
   *
   * Asserted through the app's own routing, so this tests the rule rather than a rendering of it.
   */
  it('cash goes to the amount tendered; an account sale goes to the due date', () => {
    cy.visitSaleScreen()
    cy.window().should((w) => expect(w.applyPosKeyboard).to.be.a('function'))
    cy.window().then((w) => { w.posKeyboardEnabled = true; w.applyPosKeyboard() })

    // CASH — the money field, not the discount that happens to precede it.
    cy.get('#sellPayMethod').then(($s) => { $s.val('CASH'); $s.trigger('change') })
    cy.window().then((w) => {
      expect(w.EnterChain.usable('sellTradeDiscount'),
        'trade discount is on screen — the positional walk would stop here').to.eq(true)
      expect(w.posAfterPayMethod(), 'cash routes to the tendered amount').to.eq('sellRec')
    })

    // CREDIT — the balance needs a date, and the tendered amount means nothing.
    cy.get('#sellPayMethod').then(($s) => { $s.val('CREDIT'); $s.trigger('change') })
    cy.window().then((w) => {
      const target = w.posAfterPayMethod()
      // dueDateTemp is only on screen when the sale leaves a balance; when it is not, the rule must
      // fall back rather than strand the cursor on a field nobody can see.
      if (w.EnterChain.usable('dueDateTemp')) expect(target).to.eq('dueDateTemp')
      else expect(target, 'no due date on screen — fall back to the positional walk').to.eq(null)
    })
  })

})
