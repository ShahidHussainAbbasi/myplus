/**
 * Dashboard KPI cards drill through to their detail list.
 *
 * Asked for by the user: *"by clicking on kpi-card user should be redirected to the detail list like click on
 * dashVenders or Vendors it should land on vendor list same click on Vendor / Supplier"*.
 *
 * <h3>What was wrong</h3>
 * A KPI card states a number a person immediately wants to interrogate — 43 vendors, WHICH forty-three? — and
 * the card was a dead end. Worse, `.kpi-card:hover` already lifted the tile, so it *looked* interactive and
 * did nothing: the most annoying kind of non-control.
 *
 * <h3>⚠ The case that would have caught the near-miss: #4</h3>
 * Most sections are options on `#registrationType`. **Products and Installment plans are not** — they have no
 * option at all and are opened by a function (`showProducts` / `showInstallments`). A drill that only knew
 * about selects would have silently done nothing on Products, which is a card the user looks at daily. Every
 * navigation kind is asserted here for that reason, not just the one that was easy.
 *
 * Each case asserts the DESTINATION IS VISIBLE — not that a click handler ran, and not that a select changed
 * value. A card that switches a hidden select has not taken anyone anywhere.
 */

const OWNER = 'owner.business@myplus.com'

/** Click a KPI card by its widget name and assert where it lands. */
function drill(widget, destination) {
  cy.visitDashboardSettled()
  cy.get(`[data-widget="${widget}"]`).should('be.visible').click({ force: true })
  cy.get(`#${destination}`, { timeout: 20000 }).should('be.visible')
}

describe('KPI cards drill into their list', () => {
  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  it('⭐ 1. Vendors → the vendor list (the card the user named)', () => {
    drill('venders', 'VenderDiv')
  })

  it('2. Customers → the customer list', () => {
    drill('customers', 'CustomerDiv')
  })

  it('3. Companies → the company list', () => {
    drill('companies', 'CompanyDiv')
  })

  it('⭐ 4. Products → the product list (opened by a FUNCTION, not a select)', () => {
    /*
     * THE NEAR-MISS. `ProductDiv` is not an option on #registrationType — `showProducts()` opens it. A
     * select-only drill would have failed here alone, and silently, which is the worst way for a shortcut to
     * be broken: the card still lifts on hover, so it reads as unresponsive rather than unwired.
     */
    drill('products', 'ProductDiv')
  })

  it('5. Stock value → the product list as well', () => {
    // The value and the count describe the same stock, so they answer to the same list. A tile whose number
    // has no destination is the dead end this slice exists to remove.
    drill('stockValue', 'ProductDiv')
  })

  it('⭐ 6. Sales this month → the Sale Detail Report (a DIFFERENT select)', () => {
    /*
     * The second navigation kind: the report is a sale SUB-section on #sellType, not a top-level section on
     * #registrationType. Asserted because a drill hard-wired to one select would land nowhere here.
     */
    drill('monthlySales', 'SRDiv')
  })

  it('7. Revenue this month → the same report', () => {
    drill('monthlyRevenue', 'SRDiv')
  })

  it('⭐ 8. the keyboard reaches it too — Enter on a focused card navigates', () => {
    /*
     * A control only a mouse can reach is not a control, and this app ships a keyboard-first POS, so a tile
     * that swallowed Enter would be the odd one out. `role="button"` and `tabindex="0"` are applied by the
     * same script that handles the key, so the affordance cannot exist without the behaviour.
     */
    cy.visitDashboardSettled()
    cy.get('[data-widget="venders"]')
      .should('have.attr', 'role', 'button')
      .should('have.attr', 'tabindex', '0')
      .focus()
      /*
       * force:true on the TRIGGER only.
       *
       * `.kpi-card` carries a hover transform with a transition, so Cypress can never settle the element's
       * actionability — it keeps measuring movement. Actionability is a POINTER concern (is it visible, on
       * top, not moving under the cursor); a keydown needs none of that, only focus, which the line above
       * establishes and the assertions above prove is possible.
       *
       * ⚠ Nothing else is forced: role, tabindex and the destination below are all asserted normally, so
       * this cannot paper over a card that is hidden or unfocusable.
       */
      .trigger('keydown', { key: 'Enter', force: true })

    cy.get('#VenderDiv', { timeout: 20000 }).should('be.visible')
  })

  it('9. a drillable card LOOKS clickable', () => {
    // The affordance, asserted as a computed style. The hover lift was already there and promised a
    // navigation the card could not perform; the cursor is what makes the promise honest.
    cy.visitDashboardSettled()
    cy.get('[data-widget="venders"] .kpi-card')
      .should('have.css', 'cursor', 'pointer')
  })
})
