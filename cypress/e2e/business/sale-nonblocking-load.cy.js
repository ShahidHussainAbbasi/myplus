/**
 * TIER-1b — the till is typeable while its lists are still arriving.
 *
 * <h3>The complaint this answers</h3>
 * "On the sale form it takes time to load the products/stock, and the customer waiting in the row will not
 * have patience." The screen held a BLOCKING overlay until every list had loaded: jQuery raises the overlay
 * on the first request and drops it only when the LAST one finishes, so the counter was frozen for the
 * slowest call. The two remaining holders were `catalogProductPicker` (~850ms) and `customerOptions`.
 *
 * <h3>What is asserted, and why it is asserted this way</h3>
 * Every request here is DELIBERATELY DELAYED by the test. On localhost these calls return in milliseconds,
 * so a spec that just opened the screen would pass whether or not the fix worked — it would be measuring the
 * developer's machine, not the change. Holding the responses open reproduces the shop's connection, and the
 * assertions then run in the window that actually hurt.
 *
 * <h3>⚠ The trap this spec exists to catch</h3>
 * `global: false` is what keeps a request off the blocking overlay — but it excludes that request from
 * **every** global jQuery AJAX handler, including the `ajaxComplete` hook in searchable-selects.js that
 * redraws AJAX-filled pickers. Make a picker's loader non-blocking without adding an explicit refresh and
 * the &lt;option&gt;s land in the DOM while the widget never repaints: the cashier stares at an empty
 * "Select Customer" that will never fill, and a DOM-only assertion (`option` count) still passes.
 *
 * So the customer test asserts the WIDGET, not the DOM — bootstrap-select renders the current selection into
 * `.filter-option`, so that text is proof a refresh really ran.
 */

const OWNER = 'owner.business@myplus.com'

/** Hold a response open long enough to assert against the loading window. */
function slow(pattern, alias, ms) {
  cy.intercept({ method: 'GET', url: pattern }, (req) => {
    req.on('response', (res) => res.setDelay(ms))
  }).as(alias)
}

describe('Sale screen — background loading', () => {
  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  it('⭐ the item box is typeable while the customer and product lists are still loading', () => {
    /*
     * THE WHOLE POINT. Both list reads are held open for 4s; the cashier must be able to type inside that
     * window. Before tier-1b the overlay covered the screen for the full delay and this failed.
     */
    slow('**/customerOptions*', 'customers', 4000)
    slow('**/catalogProductPicker*', 'products', 4000)
    // The grid read the pickers hang off. It is STILL blocking (it is the section's own content), so the
    // honest assertion is "once the grid is done the overlay lets go" — the pickers must not EXTEND it.
    // Waiting for it also stops this racing a slow grid and flaking for a reason unrelated to the change.
    cy.intercept('GET', '**/getUserSell*').as('grid')

    cy.visitSaleScreen()
    cy.wait('@grid', { timeout: 30000 })

    // The blocking overlay must NOT be up — asserted on BOTH elements, because Cypress names whichever is
    // on top and it has named each on different runs.
    // Before tier-1b this failed: the two picker reads were part of the same ajaxStart/ajaxStop wave, so
    // the overlay stayed up for the full 4s delay above.
    cy.get('#appAjaxOverlay').should('not.be.visible')
    cy.get('.ao-box').should('not.be.visible')

    // "Typeable" is the honest test of a till, and it is stronger than "visible": a rendered screen whose
    // quantity box is disabled is not a counter anyone can sell from.
    cy.get('#sellItems').should('not.be.disabled').type('2').should('have.value', '2')

    // Only now let the slow reads land, so the assertions above were genuinely made mid-flight.
    cy.wait('@customers')
    cy.wait('@products')
  })

  it('the customer picker says it is LOADING rather than looking empty', () => {
    /*
     * A non-blocking load makes the picker's own text the only signal of which state it is in, and
     * "Select Customer" over an empty list reads as "this shop has no customers" exactly when the truth is
     * "not here yet". That is a worse lie than the spinner it replaced.
     */
    slow('**/customerOptions*', 'customers', 4000)

    cy.visitSaleScreen()
    cy.get('#sellCustomerDD option').first().should(($o) => {
      expect($o.text().toLowerCase()).to.match(/loading/)
    })

    cy.wait('@customers')
  })

  it('⭐ the customer picker really REDRAWS once the rows arrive (not just the DOM)', () => {
    /*
     * The `global: false` / ajaxComplete trap, asserted directly.
     *
     * The DOM half is necessary but NOT sufficient — options can be appended to a <select> that
     * bootstrap-select never repaints. `.filter-option` is what the cashier actually reads, so if it still
     * says "loading" after the rows landed, the widget is stale and the picker is unusable no matter how
     * many <option> elements the DOM contains.
     */
    slow('**/customerOptions*', 'customers', 1500)

    cy.visitSaleScreen()
    cy.wait('@customers')

    // Retrying assertion: the ~800 rows arrive well after visitSaleScreen resolves, so a single read is a
    // guaranteed flake (this exact mistake failed three assertions against correct code before).
    cy.get('#sellCustomerDD option', { timeout: 30000 })
      .should('have.length.greaterThan', 1)

    cy.get('#sellCustomerDD')
      .next('.bootstrap-select')
      .find('.filter-option')
      .should(($f) => {
        expect($f.text().toLowerCase(), 'the WIDGET repainted after the background load')
          .to.not.match(/loading/)
      })
  })

  it('the product picker still fills the item dropdown', () => {
    // Same trap, other picker. loadUserItems() refreshes itself, so this guards that it keeps doing so
    // now that the read no longer participates in the global AJAX lifecycle.
    cy.visitSaleScreen()
    cy.get('#sellItemDD option', { timeout: 30000 }).should('have.length.greaterThan', 1)
  })

  it('the new-sale cursor still lands on the customer while the list loads', () => {
    /*
     * REGRESSION GUARD for the entry point (task #13, user-ruled: a sale is priced by WHO is buying).
     *
     * The tempting way to show a loading state is to DISABLE the picker — and `EnterChain.usable()` treats
     * a disabled control as skippable, so that would send the cursor straight past the customer to #sellCN.
     * It would only misbehave on a slow connection, which is precisely where nobody is watching.
     *
     * focus lands on bootstrap-select's BUTTON, which has no id — resolve the underlying <select> with the
     * app's own idiom rather than inventing a parallel rule.
     */
    slow('**/customerOptions*', 'customers', 3000)

    cy.visitSaleScreen()
    cy.get('#sellCustomerDD').should('not.be.disabled')

    cy.window().then((w) => {
      /*
       * The entry point must be INVOKED, not assumed.
       *
       * Opening the section does not move the cursor — that happens on the New Sale action. And
       * `visitSaleScreen()` reaches the screen with `cy.get('#sellType').select(...)`, which FOCUSES the
       * off-screen nav select: a first version of this test read `cy.focused()` straight after the visit
       * and got 'sellType', measuring Cypress's own .select() rather than the product.
       *
       * Same call the shipped entry-point gate uses (sale-customer-first.cy.js), so the two cannot drift.
       */
      expect(typeof w.posFocusEntryPoint, 'the keyboard exposes its entry point').to.eq('function')
      w.posFocusEntryPoint()

      // PICKER FIRST, raw id second. A bootstrap-select focuses its BUTTON, which carries no id, so
      // reading `activeElement.id` first returns the wrong answer for exactly the control under test.
      const active = w.document.activeElement
      const $picker = w.jQuery(active).closest('.bootstrap-select').prev('select')
      const focusedId = $picker.attr('id') || (active && active.id)
      expect(focusedId, 'the new-sale cursor starts on the customer even mid-load').to.eq('sellCustomerDD')
    })

    cy.wait('@customers')
  })
})
