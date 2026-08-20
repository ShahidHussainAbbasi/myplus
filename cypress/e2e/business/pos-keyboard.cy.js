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
  cy.get('#sellStock').should('not.have.value', '')
}

describe('POS keyboard entry — OFF (default): the screen is unchanged', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('the row-entry layout class is not applied', () => {
    openSell(false)
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
      cy.get('#sellItems').type('3{enter}')
      // Still on the sale screen (the form never submits — it has no submit button) ...
      cy.get('#sellDiv').should('be.visible')
      // ... and nothing was committed to the cart.
      cy.window().its('data').should('have.length', 0)
    })
  })

  it('all four line fields are present and typeable', () => {
    openSell(false)
    cy.get('#sellItemDD').should('exist')
    cy.get('#sellItems').should('be.visible').and('not.have.attr', 'readonly')
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
      cy.get('#sellItems').should('have.value', '1')          // the default landed

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

      // Qty is where it must land: pre-filled by loadStock, but never "answered" by the cashier.
      cy.focused().should('have.id', 'sellItems')
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
  it('Enter on Qty goes to Price even though the catalog pre-filled it', () => {
    cy.seedProduct({ name: 'KbdRate_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      pickItem(productId)
      cy.get('#sellSellRate').should('not.have.value', '')     // the catalog filled it
      cy.get('#sellItems').clear().type('4{enter}')
      cy.focused().should('have.id', 'sellSellRate')
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
      cy.get('#sellItems').clear().type('2')
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
      cy.focused().type('{enter}')
      cy.focused().should('have.id', 'sellDiscount')
      cy.window().its('data').should('have.length', 0)
    })
  })

  it('Enter on Discount — the last field — commits the line, carrying the typed rate', () => {
    cy.seedProduct({ name: 'KbdEnd_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      pickItem(productId)
      cy.get('#sellItems').clear().type('2')
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

  it('Enter walks Qty → Price when the price is blank too', () => {
    cy.seedProduct({ name: 'KbdChain_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      pickItem(productId)
      cy.get('#sellSellRate').clear()
      cy.get('#sellItems').clear().type('2{enter}')
      cy.focused().should('have.id', 'sellSellRate')
    })
  })

  it('after a commit, focus returns to the scan box ready for the next line', () => {
    cy.seedProduct({ name: 'KbdFocus_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      pickItem(productId)
      // Walk the WHOLE chain: Qty -> Price -> Discount -> commit. Enter on Qty no longer commits.
      cy.get('#sellItems').clear().type('2{enter}')
      cy.get('#sellSellRate').type('{enter}')
      cy.get('#sellDiscount').type('{enter}')
      cy.window().its('data').should('have.length', 1)
      cy.focused().should('have.id', 'sellScan')
    })
  })

  it('Shift+Enter walks backwards', () => {
    cy.seedProduct({ name: 'KbdBack_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      pickItem(productId)
      // Backwards is the same linear walk in reverse, so Price lands on Qty.
      cy.get('#sellSellRate').focus().type('{shift}{enter}')
      cy.focused().should('have.id', 'sellItems')
    })
  })

  it('Esc clears the in-progress line without touching the cart', () => {
    cy.seedProduct({ name: 'KbdEsc_' + Date.now(), sellingPrice: 25, stock: 10 }).then(({ productId }) => {
      openSell(true)
      pickItem(productId)
      // Commit one line so there is a cart to protect — the full chain, since Qty no longer commits.
      cy.get('#sellItems').clear().type('1{enter}')
      cy.get('#sellSellRate').type('{enter}')
      cy.get('#sellDiscount').type('{enter}')
      cy.window().its('data').should('have.length', 1)

      // Start a second line, then abandon it.
      cy.get('#sellItems').clear().type('7')
      cy.get('#sellItems').type('{esc}')
      cy.get('#sellItems').should('not.have.value', '7')
      cy.window().its('data').should('have.length', 1)   // the committed line survived
    })
  })

  it('Enter on an empty row does not commit an empty line', () => {
    openSell(true)
    // No item chosen: commitLine() refuses and sends the cashier to the picker rather than raising a
    // validation error they then have to dismiss.
    cy.get('#sellItems').clear().type('{enter}')
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
      cy.get('#sellItems').clear().type('3')
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
      cy.get('#sellItems').clear().type('3')

      // Any .crud-overlay.open suppresses the contract — committing a line, or completing a sale,
      // from behind a dialog the cashier cannot see is the failure mode the guard exists for.
      cy.window().then((w) => {
        w.$('body').append('<div class="crud-overlay open" id="fakeOverlay"></div>')
      })
      // force:true DELIBERATELY, and only here: the element being covered is the very condition
      // under test, so Cypress's actionability check would be refusing the scenario itself.
      cy.get('#sellItems').type('{enter}', { force: true })
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
    cy.get('#sellItems').should('be.visible')
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
