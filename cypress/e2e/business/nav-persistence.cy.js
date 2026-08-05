/**
 * Sidebar selection must survive a click anywhere else on the page.
 *
 * The bug: each dashboard carried its own document-level "click away closes the menus" handler that removed
 * .snav-open from EVERY group. That silently undid the active-state logic in sidebar.js — the instant you
 * clicked into the very form you had just navigated to, the sidebar collapsed and forgot where you were. The
 * shared handler now spares the group holding the current selection (and restores it if a peek at another
 * group collapsed it).
 */
describe('Sidebar: the selected nav item stays visible', () => {
  beforeEach(() => {
    cy.loginAsOwner()
    cy.visit('/businessDashboard')
  })

  const pickCustomer = () => {
    cy.get('#snavRegister .snav-btn').click()
    cy.get('#snavRegister .snav-menu a').contains('Customer').click({ force: true })
    cy.get('#CustomerDiv').should('be.visible')
    cy.get('#snavRegister').should('have.class', 'snav-open')
  }

  it('survives clicking into the form area', () => {
    pickCustomer()

    // The exact gesture that used to wipe it: click on the page, outside the nav.
    cy.get('#CustomerDiv').click('topRight', { force: true })

    cy.get('#snavRegister', { timeout: 4000 }).should('have.class', 'snav-open')
    cy.get('#snavRegister .snav-menu a.active').should('contain', 'Customer')
    cy.get('#snavRegister > .snav-btn').should('have.class', 'snav-active')
  })

  it('survives clicking on the page background', () => {
    pickCustomer()
    cy.get('body').click(600, 12)   // a dead area outside both the sidebar and the form
    cy.get('#snavRegister').should('have.class', 'snav-open')
    cy.get('#snavRegister .snav-menu a.active').should('contain', 'Customer')
  })

  it('a peek at another group collapses back to the selection, not to nothing', () => {
    pickCustomer()

    // Open a different group WITHOUT choosing anything from it, then click away.
    cy.get('#snavSell .snav-btn').click()
    cy.get('#snavSell').should('have.class', 'snav-open')
    cy.get('body').click(600, 12)

    cy.get('#snavSell').should('not.have.class', 'snav-open')          // the peeked group closes...
    cy.get('#snavRegister').should('have.class', 'snav-open')          // ...and the selection comes back
    cy.get('#snavRegister .snav-menu a.active').should('contain', 'Customer')
  })

  it('picking a different item moves the selection (it is not sticky forever)', () => {
    pickCustomer()

    // Slice 106: was 'Vender'. The nav label comes from the bundle (`ui.venderSupplier2=Vendor / Supplier`),
    // NOT from the template's inline text — Thymeleaf's inline copy is only a design-time placeholder. The
    // i18n pass corrected the spelling to "Vendor"; the misspelling survives only in the ENTITY name
    // (`Vender`), which is why this reads wrong but is right.
    cy.get('#snavRegister .snav-menu a').contains('Vendor').click({ force: true })
    cy.get('#snavRegister .snav-menu a.active').should('contain', 'Vendor')
    cy.get('#snavRegister .snav-menu a.active').should('have.length', 1)   // exactly one, not two
  })
})
