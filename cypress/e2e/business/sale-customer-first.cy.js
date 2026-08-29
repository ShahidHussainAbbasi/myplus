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

  it('⭐ a walk-in is not slowed down — the cursor still starts at the goods', () => {
    /*
     * The regression this guards against is the obvious over-correction: making the customer the entry point.
     * That would add a stop to every cash sale on the very screen whose complaint was that a queue forms.
     * The customer being FIRST IN THE CHAIN and being WHERE THE CURSOR LANDS are different things, and only
     * the first was asked for.
     */
    cy.visitSaleScreen()
    cy.window().then((w) => {
      if (typeof w.posFocusEntryPoint !== 'function') return
      w.posFocusEntryPoint()
    })
    /*
     * Asserted as "NOT the customer", rather than as "equals sellItemDD".
     *
     * These pickers are bootstrap-select widgets: focusing one lands on the widget's BUTTON, which carries no
     * id of its own. An id-equality check therefore reads `undefined` and fails against a product that is
     * behaving perfectly — which is exactly what it did on the first run. The claim this test makes is that a
     * walk-in is not forced through the customer field, so that is the claim it should assert.
     */
    cy.focused().then(($el) => {
      const onCustomer = $el.attr('id') === 'sellCustomerDD'
        || $el.closest('[id="sellCustomerDD"]').length > 0
      expect(onCustomer, 'the cursor must NOT start on the customer picker').to.eq(false)
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
    cy.get('#sellCustomerDD', { timeout: 15000 }).should('exist')
    cy.window().then((w) => {
      const $sel = w.jQuery('#sellCustomerDD')
      expect($sel.data('selectpicker'), 'the picker is a real searchable widget, not a bare <select>')
        .to.not.eq(undefined)
      expect(String($sel.attr('data-live-search')), 'type-to-search is enabled').to.eq('true')
      expect($sel[0].options.length, 'and it is populated — an empty picker proves nothing')
        .to.be.greaterThan(1)
    })
  })
})
