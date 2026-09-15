/**
 * POS keyboard sale entry (UI/UX P1) — the gate for pos-keyboard.js + pos-rowentry.css.
 *
 * TWO HALVES, AND THE FIRST ONE MATTERS MOST.
 * Every tenant on this platform has the feature OFF. So the regression that would actually hurt is
 * not "the shortcut misbehaves" — it is "the sale screen changed for a shop that never asked". The
 * OFF block below is therefore the real gate: the layout class absent, Enter still inert, no field
 * pulled out of the tab order.
 *
 * The flag is a per-org SETTING, and toggling it through the Configuration screen inside a spec
 * would make every test depend on a persisted write that leaks into the next one. Instead each ON
 * test sets window.posKeyboardEnabled directly — the same variable loadPosFeatureFlags() writes,
 * read live on every keystroke — which is exactly the state the setting produces, without the
 * cross-test contamination.
 *
 * Run headed.
 */

/**
 * Open the sell screen with the keyboard feature in a known state.
 *
 * ASSERTS THE MODULE IS LOADED, rather than guarding with `if (typeof … === 'function')`.
 * The first draft of this helper did guard, and when pos-keyboard.js was missing from the served
 * classpath the helper quietly did nothing — producing seven unrelated-looking assertion failures
 * ("expected #sellStock to have attribute tabindex") instead of the one true fact, "the script 404'd".
 * A test helper that swallows a missing dependency costs more than the test is worth.
 */
/*
 * ── THE RULE, NOT A FIELD LIST ──────────────────────────────────────────────────────────────────
 *
 * RULE 1  the chain walks the fields in the order they appear ON SCREEN.
 * RULE 2  a field the tenant switched off is skipped; the cursor moves to the next available one.
 *
 * Every stop in this file used to be a hardcoded id, which encoded ONE tenant's screen rather than the
 * rule. SER-3c put #sellSerials between the item and the quantity and the spec went red; #sellBonus
 * between quantity and price and it went red again - both times the CHAIN was right and the spec was
 * describing a screen that no longer existed.
 *
 * `nextOnScreen` derives the expectation from the DOM instead: the next field a person could actually
 * type into, in document order, inside the line strip. That is RULE 1 and RULE 2 stated together, and
 * it is what makes these cases hold on any tenant - serial on or off, bonus on or off - with no login
 * override and nothing pinned.
 *
 * ⚠ NOT CIRCULAR. The expectation comes from the SCREEN (document order + what is on it); the app
 * computes its jump from CHAIN, an array in pos-keyboard.js. The test passes only when those two
 * agree, which is exactly the property RULE 1 asserts and exactly what both counter-reported defects
 * broke. `FocusFlow.skip` is shared deliberately - "can a cursor go here?" must have ONE answer, and
 * keyboard-chain-order.cy.js case 7 covers the chain's own completeness.
 */
const lineFields = (w) =>
  Array.from(w.document.querySelectorAll('#sellDiv .pos-cell'))
    .flatMap((c) => Array.from(c.querySelectorAll('input, select, textarea')))
    .filter((el) => el.id && !w.FocusFlow.skip(el))

/** The id Enter should land on after `fromId`, read off the screen. Null past the last field. */
const nextOnScreen = (w, fromId) => {
  const ids = lineFields(w).map((el) => el.id)
  const i = ids.indexOf(fromId)
  expect(i, `${fromId} is a usable field on the line strip`).to.be.greaterThan(-1)
  return i + 1 < ids.length ? ids[i + 1] : null
}

/**
 * Press Enter in `fromId` and assert the cursor lands where the SCREEN says it should.
 * Returns the id it landed on, so a caller can walk several stops.
 */
const enterFrom = (fromId) => {
  let expected = null
  cy.window().then((w) => { expected = nextOnScreen(w, fromId) })
  cy.get('#' + fromId).focus().type('{enter}')
  return cy.window().should((w) => {
    expect(Cypress.focusedPicker(w), `Enter from ${fromId} goes to the next field on screen`)
      .to.eq(expected)
  }).then(() => expected)
}

/** Walk Enter forward until `targetId`, asserting EVERY stop against the screen on the way. */
const walkTo = (fromId, targetId) => {
  const step = (cur, guard) => {
    if (cur === targetId) return cy.wrap(cur)
    expect(guard, `reached ${targetId} within the line strip`).to.be.greaterThan(0)
    return enterFrom(cur).then((next) => {
      expect(next, `the walk from ${fromId} must reach ${targetId}, not run off the end`).to.not.eq(null)
      return step(next, guard - 1)
    })
  }
  return step(fromId, 12)
}

function openSell(enabled) {
  // visitSaleScreen waits for loadPosFeatureFlags() to finish writing window.pos* — otherwise the
  // assignment below is racing it, and a failed config call (which fails CLOSED) silently wins.
  cy.visitSaleScreen()
  cy.window().should((w) => {
    expect(w.applyPosKeyboard, 'pos-keyboard.js is loaded (is the monolith rebuilt?)').to.be.a('function')
    expect(w.applyPosFieldVisibility, 'business.js exposes applyPosFieldVisibility').to.be.a('function')
  })
  cy.window().then((w) => {
    w.posKeyboardEnabled = enabled === true
    w.applyPosKeyboard()
  })
}

/**
 * Put a real product on the line form and wait for its async pre-fill to land.
 *
 * The Enter chain reads the LIVE form, so a test that types a quantity with no item selected is not
 * testing the chain — `calculateNetSell()` sees batchStock 0, flags the field red and the state under
 * test is a stock error rather than a keyboard path. Every chain test starts from a real selection.
 */
function pickItem(productId) {
  /*
   * WAIT FOR THE OPTION TO EXIST before selecting it.
   *
   * The picker is filled by PagedFetch, which walks every page of the catalogue — org 6 is now ~1,285
   * products across 3 pages — and a product seeded moments earlier is simply not in the <select> yet
   * when the test reaches this line. `cy.select()` then fails with "could not find a single <option>
   * with value 1941", which reads like a missing product and is really a race.
   *
   * It got worse as the catalogue grew, which is the tell: the same specs passed for months and then
   * began failing on a machine that had done nothing except accumulate test data.
   */
  cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 20000 }).should('exist')
  cy.get('#sellItemDD').select(String(productId), { force: true })   // bootstrap-select hides the real <select>
  cy.get('#sellSellRate', { timeout: 10000 }).should('not.have.value', '')

  /*
   * ⚠ #sellStock NEEDS A LONGER BUDGET THAN THE PRICE, NOT THE DEFAULT 5s.
   *
   * The price arrives with /productStock. The stock badge is filled by a SECOND round trip CHAINED
   * after it - business.js fires /productSellable from inside that response and only then writes
   * #sellStock - so it can never land before the price and routinely lands well after, on a picker that
   * now carries several thousand options and reflows the row as it re-renders.
   *
   * It was asserted with the default 5s while the strictly-earlier field got 10s: the budget did not
   * match the number of round trips, so this line timed out first and every case in this file reported
   * a failure inside pickItem with an empty expected/actual - which reads like a broken screen rather
   * than an assertion that ran out of time. Eight cases at once, on a database that had done nothing
   * except accumulate products.
   */
  cy.get('#sellStock', { timeout: 20000 }).should('not.have.value', '')

  /*
   * ...and wait for the SCREEN to stop moving, not just for the values to arrive.
   *
   * Selecting an item fires loadStock(), and every AJAX response re-syncs the pickers on this page.
   * Re-rendering a list of ~290 options reflows the sale row, so for a few frames afterwards the inputs
   * are still settling. Cypress refuses to type into a moving element (ensureNotAnimating), and the
   * failure names the input rather than the reflow, which reads as a broken field.
   *
   * Waiting for the overlay to lift is the honest signal that the fetch and its re-render are done.
   * force:true would also make the error go away, by typing into a control mid-flight — which is not
   * what a cashier does and not what this spec is for.
   */
  cy.get('#appAjaxOverlay', { timeout: 30000 }).should('not.be.visible')
}

describe('POS keyboard entry — OFF (default): the screen is unchanged', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('the row-entry layout follows ITS OWN setting, not the keyboard flag', () => {
    openSell(false)
    /*
     * These are TWO switches and this test used to conflate them.
     *
     * It asserted that turning the keyboard off removed `pos-rowentry`, which held only while the
     * single-row layout was part of the keyboard feature. P7 made the layout a tenant setting of its
     * own — `pos.entry.compactRow`, default ON — because a shop may want the compact till without the
     * keyboard chain, or the chain on the classic layout. Tying the class to `posKeyboardEnabled`
     * would make one of those two configurations unreachable.
     *
     * So the contract is: the class tracks posRowLayoutEnabled, whatever the keyboard flag says.
     */
    cy.window().then((w) => {
      const expected = w.posRowLayoutEnabled === true
      cy.get('#sellDiv').should(expected ? 'have.class' : 'not.have.class', 'pos-rowentry')
    })

    // And it really is independent: flip the layout off with the keyboard still off, and it goes.
    cy.window().then((w) => { w.posRowLayoutEnabled = false; w.applyPosRowEntry() })
    cy.get('#sellDiv').should('not.have.class', 'pos-rowentry')
  })

  it('read-only display fields keep their place in the tab order', () => {
    openSell(false)
    // These are exactly the fields P1 takes out of the tab order when ON. With the flag OFF they
    // must be untouched — a tabindex left behind would silently change every cashier's Tab path.
    cy.get('#sellStock').should('not.have.attr', 'tabindex')
    cy.get('#sellTotalAmount').should('not.have.attr', 'tabindex')
    cy.get('#sellCh').should('not.have.attr', 'tabindex')
  })

  it('Enter in the quantity field does nothing — no cart line, no navigation', () => {
    cy.seedProduct({ name: 'KbdOff_' + Date.now(), sellingPrice: 10, stock: 5 }).then(() => {
      openSell(false)
      cy.get('#sellQuantity').type('3{enter}')
      // Still on the sale screen (the form never submits — it has no submit button) ...
      cy.get('#sellDiv').should('be.visible')
      // ... and nothing was committed to the cart.
      cy.window().its('data').should('have.length', 0)
    })
  })

  it('all four line fields are present and typeable', () => {
    openSell(false)
    cy.get('#sellItemDD').should('exist')
    cy.get('#sellQuantity').should('be.visible').and('not.have.attr', 'readonly')
    cy.get('#sellSellRate').should('be.visible').and('not.have.attr', 'readonly')
    cy.get('#addInviceItem').should('be.visible')
  })
})

describe('POS keyboard entry — ON', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('read-only display fields are taken out of the tab order', () => {
    openSell(true)
    cy.get('#sellStock').should('have.attr', 'tabindex', '-1')
    cy.get('#bexpDate').should('have.attr', 'tabindex', '-1')
    cy.get('#sellTotalAmount').should('have.attr', 'tabindex', '-1')
    cy.get('#sellCh').should('have.attr', 'tabindex', '-1')
    cy.get('#sellDueThis').should('have.attr', 'tabindex', '-1')
  })

  it('turning the flag back off restores the tab order without a reload', () => {
    openSell(true)
    cy.get('#sellStock').should('have.attr', 'tabindex', '-1')
    cy.window().then((w) => { w.posKeyboardEnabled = false; w.applyPosKeyboard() })
    cy.get('#sellStock').should('not.have.attr', 'tabindex')
  })

  /**
   * REGRESSION — the chain used to skip Qty entirely.
   *
   * `loadStock()` pre-fills Qty with `pos.entry.defaultQty` (1) the instant an item is picked, and an
   * earlier `satisfied()` counted any positive Qty as "already answered". So Enter on the item flew
   * past the one field a cashier always types and landed in the optional discount. A DEFAULT is not a
   * decision. This test pins the distinction, because every other test in this file passed while the
   * feature was wrong.
   */
  it('Enter on the item stops at Qty even though it is pre-filled with the default', () => {
    cy.seedProduct({ name: 'KbdQty_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      pickItem(productId)
      cy.get('#sellQuantity').should('have.value', '1')          // the default landed

      // Drive the chain the way a cashier does. bootstrap-select replaces the <select> with a button,
      // so the keystroke has to go there — asserting the wrapper exists first, because a silently
      // missing picker would make this test pass for the wrong reason.
      //
      // keyCode/which are MANDATORY here, not decoration. bootstrap-select v1.6.2 binds its own
      // keydown to this button and evaluates `b.keyCode.toString(10)`; a synthetic event carrying
      // only `key` gives it undefined and it throws inside the library. A real browser always sends
      // both, so an event without them is not the event under test.
      //
      // (Checked in the library source: with keyCode 13 and the menu closed, bootstrap-select matches
      // none of its branches — "13" fails /(^9$|27)/ and String.fromCharCode(13) fails /([0-9]|[A-z])/ —
      // so it leaves the key alone and our handler is what moves focus.)
      cy.get('#sellItemDD').next('.bootstrap-select').should('exist')
      cy.get('#sellItemDD').next('.bootstrap-select').find('button').first()
        .focus()
        .trigger('keydown', { key: 'Enter', keyCode: 13, which: 13, bubbles: true })

      /*
       * ⚠ THE NEXT STOP IS THE SERIAL BOX, not Qty - and that is the rule working, not a defect.
       *
       * SER-3c moved #sellSerials in front of #sellQuantity on the sale form, and RULE 1 in
       * pos-keyboard.js says the chain must match the screen: CHAIN is
       * [... 'sellItemDD', 'sellSerials', 'sellQuantity', ...]. keyboard-chain-order.cy.js reads that
       * array out of the shipped file and compares it with the DOM, and it is green.
       *
       * This case was written before that field existed. Asserting the serial box here keeps it
       * honest about where the cursor goes; the Qty stop it was really about is asserted one Enter
       * further on.
       */
      // Wherever the SCREEN says - the serial box on a shop that tracks IMEIs, the quantity on one
      // that does not. Both are the rule working.
      cy.window().should((w) => {
        expect(Cypress.focusedPicker(w), 'Enter off the item picker follows the screen')
          .to.eq(nextOnScreen(w, 'sellItemDD'))
      })

      // And the walk reaches Qty - the stop this case exists for: pre-filled by loadStock, but never
      // "answered" by the cashier. Every stop on the way is asserted against the screen.
      cy.window().then((w) => {
        const first = nextOnScreen(w, 'sellItemDD')
        if (first !== 'sellQuantity') walkTo(first, 'sellQuantity')
      })
    })
  })

  /**
   * THE CHAIN IS LINEAR: Item → Qty → Price → Discount → commit.
   *
   * Enter stops on the price EVEN WHEN the catalog pre-filled it. An earlier version skipped a
   * pre-filled price on the theory that it was already answered — true at a retail counter, wrong
   * wherever the rate is negotiated per line, which is most trade selling. The price the system
   * proposes is a suggestion; the cashier passes through it to accept or change it.
   */
  /**
   * ⭐ THE PICKER HANDS OVER BY ITSELF — through the picker's OWN UI, with no focus staged for it.
   *
   * The case above proves the Enter HANDLER works, but it focuses the button first and then synthesizes
   * the keystroke. That skips the very step that broke on a real till: after a selection, bootstrap-select
   * decides where focus goes, and it does not always choose the button. When it chose the menu anchor
   * instead, the auto-advance guard — which whitelisted the places focus was expected to be — missed, the
   * cursor stayed on the picker, and Enter did nothing at all.
   *
   * So this one touches nothing but the widget: open it, click the option, and assert the cursor arrives
   * in Qty on its own. A cashier does not focus a button before pressing Enter, and neither does this.
   */
  it('picking through the dropdown lands the cursor in Qty by itself', () => {
    const name = 'KbdHandover_' + Date.now()
    cy.seedProduct({ name, sellingPrice: 30, stock: 8 }).then(({ productId }) => {
      openSell(true)

      cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 20000 }).should('exist')

      /*
       * Drive the WIDGET, not the hidden <select>: the button opens the menu, the menu item is clicked.
       *
       * ⚠ Matched on the FULL name. `.contains('KbdHandover_')` matches the PREFIX, so on a database
       * carrying leftovers from earlier runs it clicks somebody else's product - the picker ends up
       * holding an id this case never seeded, and the failure reads as a broken chain rather than a
       * mis-aimed click. Every seeded name is unique; the selector has to use all of it.
       */
      cy.get('#sellItemDD').next('.bootstrap-select').find('button').first().click({ force: true })
      cy.get('#sellItemDD').next('.bootstrap-select')
        .find(`.dropdown-menu li a:contains("${name}")`).first().click({ force: true })

      cy.get('#sellItemDD').should('have.value', String(productId))

      // No focus staged, no keystroke sent. The advance is the app's job once the item is chosen.
      // Whatever the screen has next - the point is that a SELECTION moved the cursor at all.
      cy.window({ timeout: 10000 }).should((w) => {
        expect(Cypress.focusedPicker(w), 'choosing an item advances to the next field on screen')
          .to.eq(nextOnScreen(w, 'sellItemDD'))
      })
    })
  })

  it('Enter on Qty goes to Price even though the catalog pre-filled it', () => {
    cy.seedProduct({ name: 'KbdRate_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      pickItem(productId)
      cy.get('#sellSellRate').should('not.have.value', '')     // the catalog filled it
      cy.get('#sellQuantity').clear().type('4')
      // The subject is that a PRE-FILLED price still gets a stop - not how many fields precede it.
      // walkTo asserts every stop on the way against the screen, so a shop with a bonus box passes
      // through it and a shop without one does not.
      walkTo('sellQuantity', 'sellSellRate')
      cy.window().its('data').should('have.length', 0)          // nothing committed yet
    })
  })

  it('Enter on Price goes to the discount TYPE picker, then to Discount', () => {
    // The chain is Item -> Qty -> Price -> DiscountType -> Discount. #sellDiscountTypeDD was added as
    // a stop in P5 so a per-line concession can be switched between % and amount without the mouse;
    // this test predates that and expected Price to reach #sellDiscount directly.
    cy.seedProduct({ name: 'KbdDisc_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      pickItem(productId)
      cy.get('#sellQuantity').clear().type('2')
      cy.get('#sellSellRate').clear().type('30{enter}')
      // bootstrap-select hides the real <select> behind a button, so focus lands on that button.
      cy.focused().should(($el) => {
        expect(Cypress.$($el).closest('.bootstrap-select').prev('#sellDiscountTypeDD').length,
               'Price reaches the discount type picker').to.eq(1)
      })
      cy.window().its('data').should('have.length', 0)   // still nothing committed

      // ONE Enter moves on, exactly as it does on the item picker.
      //
      // The handler's "double Enter" only describes the case where the menu is already OPEN. On a
      // CLOSED picker it calls preventDefault(), which stops the button's click — so the menu never
      // opens and the chain advances on the first press. An earlier version of this test pressed
      // Enter twice; the second press landed on #sellDiscount, the last field, and COMMITTED the
      // line — which is why focus was found on the scan box rather than the discount.
      // Landing on the LAST field on screen, whatever the tenant's last field is - the point is that
      // one press advanced and did not commit, not which id it stopped on.
      cy.focused().type('{enter}')
      cy.window().should((w) => {
        const ids = lineFields(w).map((el) => el.id)
        expect(Cypress.focusedPicker(w), 'one press advanced along the screen, it did not commit')
          .to.eq(ids[ids.length - 1])
      })
      cy.window().its('data').should('have.length', 0)
    })
  })

  it('Enter on Discount — the last field — commits the line, carrying the typed rate', () => {
    cy.seedProduct({ name: 'KbdEnd_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      pickItem(productId)
      cy.get('#sellQuantity').clear().type('2')
      cy.get('#sellSellRate').clear().type('30')                // override the catalog price
      cy.get('#sellDiscount').clear().type('{enter}')
      cy.window().its('data').should('have.length', 1)
      cy.window().its('data.0.quantity').should('eq', '2')
      cy.window().its('data').then((d) => {
        // The whole point of stopping at Price: the cashier's rate is what reaches the cart.
        expect(Number(d[0].sellRate), 'the typed rate, not the catalog price').to.eq(30)
      })
    })
  })

  /**
   * ⭐⭐ THE DEFECT REPORTED FROM THE COUNTER, 2026-09-06.
   *
   * "after sellQuantity there is sellBonus visible but control moved to sellSellRate."
   *
   * #sellBonus sits between Qty and Price ON SCREEN and was absent from CHAIN entirely, so a shop
   * selling on free-goods watched Enter jump the quantity straight to the price, past a box they then
   * had to reach for the mouse to fill - on a screen whose whole promise is that they never have to.
   *
   * The bonus field is switched ON here, because the defect only exists when it is visible: with it
   * off the old chain was correct, which is why every gate stayed green (pos.entry.showBonus defaults
   * TRUE, but the tenants these specs run on carried an explicit false).
   */
  it('⭐⭐ Enter walks Qty → BONUS → Price when the shop sells on free goods', () => {
    cy.seedProduct({ name: 'KbdBonus_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      cy.setPosFields({ bonus: true })
      pickItem(productId)

      /*
       * Bonus is pinned ON above as a PRECONDITION, not as inherited configuration: the defect only
       * exists while the field is visible. With it visible the screen says the next stop IS the bonus
       * box - so this asserts the rule and the specific defect at once. Before the fix the cursor went
       * to sellSellRate and the field was unreachable from the keyboard.
       */
      cy.window().should((w) => {
        expect(nextOnScreen(w, 'sellQuantity'), 'with free goods on, the screen puts bonus after quantity')
          .to.eq('sellBonus')
      })

      cy.get('#sellQuantity').clear().type('2')
      enterFrom('sellQuantity')                     // -> sellBonus, asserted against the screen
      enterFrom('sellBonus')                     // -> and the rest of the chain is unchanged
      cy.window().should((w) => {
        expect(Cypress.focusedPicker(w), 'and on to the price').to.eq('sellSellRate')
      })
    })
  })

  it('Enter walks Qty → Price when the price is blank too', () => {
    cy.seedProduct({ name: 'KbdChain_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      pickItem(productId)
      cy.get('#sellSellRate').clear()
      cy.get('#sellQuantity').clear().type('2')
      walkTo('sellQuantity', 'sellSellRate')
    })
  })

  it('after a commit, focus returns to the scan box ready for the next line', () => {
    cy.seedProduct({ name: 'KbdFocus_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      /*
       * ⚠ NAME THE CUSTOMER FIRST, or this case measures the other branch.
       *
       * commitLine() sends the cursor to the goods ONLY once the sale-level question is answered:
       *     if (customerAnswered()) { focusGoodsEntry(); return; }
       *     focusEntryPoint();
       * and customerAnswered() reads #sellCustomerDD / #sellCN. Neither openSell() nor pickItem()
       * touches either, so nothing had been answered and the till correctly went back to the customer.
       *
       * The comment below used to claim "pickItem() names a customer". It does not - it waits for the
       * option, selects the item and waits for the rate and stock badges, and that is all. Naming the
       * customer here answers the question the way a cashier does, so the assertion measures the rule.
       *
       * ⚠ And NOT by changing the expectation to sellCustomerDD: that is the regression reported from
       * the counter (a five-line basket paying five stops on a name that never changed), so an
       * expectation flipped to match it would lock the bug in and the case would prove nothing.
       */
      cy.get('#btnModeManual').click({ force: true })
      cy.get('#sellCN').clear().type('Kbd Buyer ' + Date.now())
      pickItem(productId)
      // Walk the WHOLE chain: Qty -> Price -> Discount -> commit. Enter on Qty no longer commits.
      cy.get('#sellQuantity').clear().type('2{enter}')
      cy.get('#sellSellRate').type('{enter}')
      cy.get('#sellDiscount').type('{enter}')
      cy.window().its('data').should('have.length', 1)

      /*
       * ⭐ Back to the GOODS, not the customer.
       *
       * commitLine() used to end on focusEntryPoint() - the customer (task #13) - after EVERY line. But
       * the customer is a SALE-level question asked once, and a line is not a new sale: a five-line
       * basket paid five stops on a name that had not changed since the first. Reported from the counter.
       *
       * The customer is named at the top of this case, so the question IS answered and the cursor goes
       * to where the NEXT line is typed - the scan box on a shop that scans and the item picker
       * otherwise, so it is read off the screen rather than named.
       */
      cy.window({ timeout: 10000 }).should((w) => {
        const ids = Cypress.screenFields(w, '#sellDiv')
        const goods = ids.indexOf('sellScan') >= 0 ? 'sellScan' : 'sellItemDD'
        expect(Cypress.focusedPicker(w), 'the next line starts where goods are typed').to.eq(goods)
      })
    })
  })

  it('Shift+Enter walks backwards', () => {
    cy.seedProduct({ name: 'KbdBack_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      pickItem(productId)
      /*
       * Backwards is the same linear walk in reverse, so Shift+Enter from Price lands on whatever the
       * screen puts immediately BEFORE it - the bonus box where a shop sells free goods, the quantity
       * where it does not. Derived from the screen for the same reason as the forward cases.
       */
      cy.window().then((w) => {
        const ids = lineFields(w).map((el) => el.id)
        const back = ids[ids.indexOf('sellSellRate') - 1]
        cy.get('#sellSellRate').focus().type('{shift}{enter}')
        cy.window().should((w2) => {
          expect(Cypress.focusedPicker(w2), 'Shift+Enter reverses one stop along the screen')
            .to.eq(back)
        })
      })
    })
  })

  it('Esc clears the in-progress line without touching the cart', () => {
    cy.seedProduct({ name: 'KbdEsc_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      pickItem(productId)
      // Commit one line so there is a cart to protect — the full chain, since Qty no longer commits.
      cy.get('#sellQuantity').clear().type('1{enter}')
      cy.get('#sellSellRate').type('{enter}')
      cy.get('#sellDiscount').type('{enter}')
      cy.window().its('data').should('have.length', 1)

      // Start a second line, then abandon it.
      cy.get('#sellQuantity').clear().type('7')
      cy.get('#sellQuantity').type('{esc}')
      cy.get('#sellQuantity').should('not.have.value', '7')
      cy.window().its('data').should('have.length', 1)   // the committed line survived

      /*
       * ⭐ ESC BACKS OUT ONE LEVEL. Abandoning a half-typed line leaves you at the start of a LINE,
       * so the cursor goes to the goods - not back to the customer, who was answered once for the sale.
       */
      cy.window({ timeout: 10000 }).should((w) => {
        const ids = Cypress.screenFields(w, '#sellDiv')
        const goods = ids.indexOf('sellScan') >= 0 ? 'sellScan' : 'sellItemDD'
        expect(Cypress.focusedPicker(w), 'Esc on a half-typed line returns to the goods').to.eq(goods)
      })

      /*
       * ⭐ AND AGAIN, on a line that is now EMPTY, steps back to the sale itself. Two presses to leave
       * line entry entirely - and neither of them destroys anything. Wiping the CART is F9, which names
       * how many lines are about to go and waits for an answer.
       */
      // keyCode/which are MANDATORY — the same trap as the Enter case above: bootstrap-select 1.6.2 reads
      // `b.keyCode.toString(10)` on this button, and a synthetic Escape without one threw there on every run.
      cy.get('#sellItemDD').next('.bootstrap-select').find('button').first()
        .focus().trigger('keydown', { key: 'Escape', keyCode: 27, which: 27, bubbles: true })
      cy.window({ timeout: 10000 }).should((w) => {
        expect(Cypress.focusedPicker(w), 'a second Esc backs out to the sale-level entry point')
          .to.eq('sellCustomerDD')
      })
      cy.window().its('data').should('have.length', 1)   // still nothing destroyed
    })
  })

  it('Enter on an empty row does not commit an empty line', () => {
    openSell(true)
    // No item chosen: commitLine() refuses and sends the cashier to the picker rather than raising a
    // validation error they then have to dismiss.
    cy.get('#sellQuantity').clear().type('{enter}')
    cy.window().its('data').should('have.length', 0)
  })

  it('a field the tenant switched off is skipped by the Enter chain', () => {
    cy.seedProduct({ name: 'KbdCfg_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      pickItem(productId)
      // Switch the line discount off exactly as the Configuration screen does.
      cy.window().then((w) => {
        w.posFields = { lineDiscount: false }
        w.applyPosFieldVisibility()
      })
      cy.get('#sellDiscount').should('not.be.visible')
      // Price -> (discount hidden) -> commit. If the chain had stopped at the hidden discount the
      // cart would still be empty and focus would be stuck on an invisible field.
      cy.get('#sellQuantity').clear().type('3')
      cy.get('#sellSellRate').focus().type('{enter}')
      cy.window().its('data').should('have.length', 1)
      cy.focused().should('not.have.id', 'sellDiscount')
    })
  })

  it('switching the line discount off also hides its TYPE picker — and does not save that', () => {
    // The type picker (% / amount) only says HOW a line discount applies. With the discount switched
    // off it is a control with nothing to control, and a dead stop in the Enter chain. It is hidden
    // as a CONSEQUENCE, so the tenant's own discountType setting must survive: switch the discount
    // back on and their chooser returns. A derived value that quietly persists becomes a preference
    // nobody chose.
    openSell(true)
    cy.window().then((w) => {
      w.posFields = { lineDiscount: false, discountType: true }
      w.applyPosFieldVisibility()
    })
    cy.get('#sellDiscount').should('not.be.visible')
    cy.get('#sellDiscountTypeDD').parent().should('not.be.visible')
    cy.window().then((w) => {
      // The stored setting is UNTOUCHED — only the rendering changed.
      expect(w.posFields.discountType, 'the tenant setting is not overwritten').to.eq(true)
      // Switching the discount back on restores their chooser.
      w.posFields = { lineDiscount: true, discountType: true }
      w.applyPosFieldVisibility()
    })
    cy.get('#sellDiscount').should('be.visible')
    cy.get('#sellDiscountTypeDD').parent().should('be.visible')
  })

  it('Enter is ignored while a modal is open', () => {
    cy.seedProduct({ name: 'KbdModal_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      pickItem(productId)
      cy.get('#sellQuantity').clear().type('3')

      // Any .crud-overlay.open suppresses the contract — committing a line, or completing a sale,
      // from behind a dialog the cashier cannot see is the failure mode the guard exists for.
      cy.window().then((w) => {
        w.$('body').append('<div class="crud-overlay open" id="fakeOverlay"></div>')
      })
      // force:true DELIBERATELY, and only here: the element being covered is the very condition
      // under test, so Cypress's actionability check would be refusing the scenario itself.
      cy.get('#sellQuantity').type('{enter}', { force: true })
      cy.window().its('data').should('have.length', 0)
      cy.window().then((w) => { w.$('#fakeOverlay').remove() })
    })
  })
})

describe('POS row-entry layout', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('the layout class drives the compact row, and only when the flag is on', () => {
    openSell(true)
    // The compact row is its OWN setting (pos.entry.compactRow -> window.posRowLayoutEnabled), not a
    // side effect of the keyboard flag. They were one switch when this test was written; they were
    // split so a tenant can have keyboard entry without the one-row layout, which ships OFF.
    cy.window().then((w) => { w.posRowLayoutEnabled = true; w.applyPosRowEntry() })
    cy.get('#sellDiv').should('have.class', 'pos-rowentry')
    // The four typed fields stay visible in the compact layout ...
    cy.get('#sellItemDD').should('exist')
    cy.get('#sellQuantity').should('be.visible')
    cy.get('#sellSellRate').should('be.visible')
    // ... and the fields moved off the row are hidden but STILL IN THE DOM, because FormData
    // submits display:none controls and dropping them would strip columns off the invoice.
    cy.get('#sellItemDesc').should('exist').and('not.be.visible')
    cy.get('#sellrm').should('exist').and('not.be.visible')
  })

  it('fields moved off the row are never disabled — they must keep submitting', () => {
    openSell(true)
    cy.window().then((w) => { w.posKeyboardEnabled = true; w.applyPosRowEntry() })
    // `disabled` would drop them from FormData; `readonly`/hidden does not.
    cy.get('#sellItemDesc').should('not.be.disabled')
    cy.get('#sellDiscountTypeDD').should('not.be.disabled')
  })
})
