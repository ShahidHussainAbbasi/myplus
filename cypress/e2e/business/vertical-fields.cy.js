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

  it('POS/retail does NOT show the insurance co-pay field or the Insurance tender', () => {
    cy.loginAsBusiness()   // userType BUSINESS -> window.MODULE = 'BUSINESS'
    openSellForm()

    // The input stays in the DOM (business.js reads it and treats blank as 0) — it must simply not be shown.
    cy.get('#sellInsured').should('not.be.visible')
    // The tender, by contrast, must be gone entirely: a hidden <option> is still selectable.
    cy.get('#sellPayMethod option[value="INSURANCE"]').should('not.exist')
  })

  it('pharmacy still shows both (the field belongs to that vertical)', () => {
    cy.loginAsPharma()     // userType PHARMA
    openSellForm()

    cy.get('#sellInsured').should('be.visible')
    cy.get('#sellPayMethod option[value="INSURANCE"]').should('exist')
  })
})
