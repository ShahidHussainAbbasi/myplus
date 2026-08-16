/**
 * Vertical-aware sale form: fields that belong to ONE vertical must not appear on the others.
 *
 * The commerce dashboard is a single template white-labelled per user type (BUSINESS = POS, PHARMA = Pharmacy,
 * MARKETPLACE = Store), so a pharmacy-only control like the insurer-covered amount was showing up on a retail
 * till, where there is no insurer to bill. module-theme.js hides [data-vertical-only] elements for other
 * verticals — and REMOVES disallowed <option>s, because display:none on an option is not reliable.
 *
 * Both directions are asserted: absent for retail, present for pharmacy. A "hide it" change that also hid it
 * from the vertical that needs it would otherwise pass unnoticed.
 */
describe('Sale form: vertical-specific fields', () => {
  const openSellForm = () => {
    cy.visit('/businessDashboard')
    cy.get('#sellType', { timeout: 10000 }).select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')
  }

  // The insurer-covered amount field (#sellInsured) was REMOVED by decision 2026-08-16, so these cases
  // now assert the TENDER only. That is still the property worth guarding: the Insurance option is
  // pharmacy-only and must be absent — not merely hidden — for retail, because a hidden <option> is
  // still selectable and would let a POS sale settle against an insurer that does not exist.
  it('POS/retail does NOT offer the Insurance tender', () => {
    cy.loginAsBusiness()   // userType BUSINESS -> window.MODULE = 'BUSINESS'
    openSellForm()

    cy.get('#sellPayMethod option[value="INSURANCE"]').should('not.exist')
    cy.get('#sellInsured').should('not.exist')   // the field is gone from every vertical
  })

  it('pharmacy offers the Insurance tender', () => {
    cy.loginAsPharma()     // userType PHARMA
    openSellForm()

    cy.get('#sellPayMethod option[value="INSURANCE"]').should('exist')
    cy.get('#sellInsured').should('not.exist')   // removed; the split is not captured on this screen
  })
})
