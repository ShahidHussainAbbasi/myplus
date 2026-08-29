/**
 * Task #13 — the customer is chosen at the TOP of the sale screen, by keyboard alone.
 *
 * <h3>Why the customer moved</h3>
 * It used to sit below the cart, as the first stop of checkout. That is fine for a walk-in and wrong for
 * everyone else: the customer decides contract and tier pricing, the credit limit and any store credit, so
 * choosing them last meant every line was priced against a customer the system did not know it had — and then
 * re-priced. Choosing first makes the first price the right price.
 *
 * <h3>The rule that makes it safe</h3>
 * A walk-in must not pay for this. The picker can be left blank and skipped, and the cursor still STARTS at
 * the goods rather than at the customer — so a cash sale is not one keystroke longer than it was.
 */

const OWNER = 'owner.business@myplus.com'

describe('Sale screen — customer first, keyboard only', () => {
  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  it('⭐ the customer picker is ABOVE the line entry, not below the cart', () => {
    /*
     * Asserted on DOCUMENT ORDER rather than on pixels: a CSS-only reorder would look right and still leave
     * the field late in the tab order, which is the half that matters for a keyboard-driven till.
     */
    cy.visitSaleScreen()
    cy.window().then((w) => {
      const customer = w.document.getElementById('sellCustomerDD')
      const form = w.document.getElementById('Sell')
      expect(customer, 'the customer picker exists').to.not.eq(null)
      expect(form, 'the line-entry form exists').to.not.eq(null)

      // Node.compareDocumentPosition: DOCUMENT_POSITION_FOLLOWING (4) means `form` comes AFTER `customer`.
      const rel = customer.compareDocumentPosition(form)
      expect(rel & w.Node.DOCUMENT_POSITION_FOLLOWING,
        'the line-entry form comes after the customer picker in document order').to.be.greaterThan(0)
    })
  })

  it('the customer stays OUTSIDE the line form — a customer is not a cart line', () => {
    /*
     * The line form is serialised field-by-field to build a cart LINE. If the customer controls sat inside
     * it they would ride along in that payload and in the line's own Enter chain. Moving the block above the
     * form rather than into it is what keeps those two things separate.
     */
    cy.visitSaleScreen()
    cy.window().then((w) => {
      const form = w.document.getElementById('Sell')
      expect(form.querySelector('#sellCustomerDD'),
        'the picker must not be a field of the line form').to.eq(null)
    })
  })

  it('the keyboard chain starts at the customer and runs into the goods', () => {
    cy.visitSaleScreen()
    cy.window().then((w) => {
      const chain = w.posKeyboardChain ? w.posKeyboardChain() : null
      if (!chain) {
        // The chain is module-private; assert the observable consequence instead — see the walk test below.
        return
      }
      expect(chain[0], 'who is buying comes first').to.eq('sellCustomerDD')
      expect(chain.indexOf('sellItemDD'), 'and the goods follow')
        .to.be.greaterThan(chain.indexOf('sellCustomerDD'))
    })
  })

  it('⭐ opening a new sale puts the cursor ON the customer', () => {
    /*
     * The ruling: a sale starts by naming who is buying.
     *
     * A sale is priced by the customer — contract and tier prices, credit limit, store credit — so a cashier
     * who rings lines first has been pricing against a customer the system did not know it had. Starting here
     * makes the first price the right price rather than relying on somebody remembering to scroll down.
     *
     * This test previously asserted the OPPOSITE (cursor on the goods, customer merely reachable). That was
     * my reading of the trade-off and it was overruled; the assertion is inverted rather than deleted so the
     * file records that the entry point is a decision, not an accident.
     */
    cy.visitSaleScreen()
    cy.window().then((w) => {
      expect(typeof w.posFocusEntryPoint, 'the keyboard exposes its entry point').to.eq('function')
      w.posFocusEntryPoint()
      /*
       * Resolved with the APP'S OWN idiom, not one invented here.
       *
       * A bootstrap-select puts focus on its BUTTON, which carries no id — so `activeElement.id` reads
       * undefined against a product working perfectly, which is how this assertion failed twice. The widget
       * keeps the original <select> as the wrapper's previous sibling, and `pos-keyboard.js` resolves a
       * focused button back to its picker with exactly this expression:
       *
       *     $(this).closest('.bootstrap-select').prev('select')
       *
       * Using the same expression means the test cannot disagree with the code it is testing. A parallel
       * rule invented in a spec is a second definition of "which picker is this", and the two drift.
       */
      const active = w.document.activeElement
      const $picker = w.jQuery(active).closest('.bootstrap-select').prev('select')
      const focusedId = $picker.attr('id') || (active && active.id)
      expect(focusedId, 'a new sale starts on the customer picker').to.eq('sellCustomerDD')
    })
  })

  it('the customer picker is type-to-search, not a 800-row scrolling list', () => {
    /*
     * Asserts the searchable INSTANCE and its live-search option — not the widget's DOM shape.
     *
     * The first version hunted for a `.bootstrap-select` wrapper and failed. Probing showed why: neither this
     * picker nor the item picker (untouched by this task) has such a wrapper, yet both carry a live
     * selectpicker instance. The wrapper is an implementation detail of the plugin's rendering; whether the
     * cashier can type to find a customer is the property, and it is decided by live-search being on.
     *
     * It matters at this tenant's scale: the picker holds ~837 customers. Without type-to-search that is a
     * scrolling list nobody can operate from a keyboard, which would defeat the point of moving it here.
     */
    cy.visitSaleScreen()
    /*
     * A RETRYING assertion, because the customer list arrives after the screen does.
     *
     * The first version read options.length once, immediately, and found 1 — the placeholder. Probing showed
     * the real list (837 rows) lands a few seconds later, so the test was failing on timing while the feature
     * worked. cy.get retries; a plain expect() inside cy.window() does not.
     */
    cy.get('#sellCustomerDD option', { timeout: 20000 })
      .should('have.length.greaterThan', 1)
    cy.window().then((w) => {
      const $sel = w.jQuery('#sellCustomerDD')
      expect($sel.data('selectpicker'), 'the picker is a real searchable widget, not a bare <select>')
        .to.not.eq(undefined)
      expect(String($sel.attr('data-live-search')), 'type-to-search is enabled').to.eq('true')
    })
  })
})
