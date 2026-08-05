/**
 * Vertical-profile dashboard (slice 36) — ONE template/route (businessDashboard) white-labelled by user type.
 * BUSINESS → POS wording; PHARMA → pharmacy wording (Medicine / Dispense / Patient). Run headed.
 */

describe('Vertical profile — POS (BUSINESS)', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('renders the dashboard in BUSINESS module with POS branding', () => {
    cy.visit('/businessDashboard')
    cy.window().its('MODULE').should('eq', 'BUSINESS')
    cy.window().its('VERTICAL_PROFILE.brand').should('contain', 'POS')
    // POS is the BASELINE vertical: module-theme.js relabels nothing, so the dropdown keeps its own wording.
    // (Slice 106: this asserted 'Item', an option deleted with the Item entity in ed34a435. 'Customer' is the
    // stable baseline term — and it is exactly what PHARMA relabels below, so the pair proves the mechanism.)
    cy.get('#registrationType option').then(($o) => {
      const texts = [...$o].map((o) => o.textContent.trim())
      expect(texts, 'baseline wording, unrelabelled').to.include('Customer')
      expect(texts, 'and not a vertical term').to.not.include('Patient')
    })
  })
})

describe('Vertical profile — Pharmacy (PHARMA)', () => {
  beforeEach(() => { cy.loginAsPharma() })

  it('renders the SAME dashboard in PHARMA module with pharmacy wording', () => {
    cy.visit('/businessDashboard')
    cy.window().its('MODULE').should('eq', 'PHARMA')
    cy.window().its('VERTICAL_PROFILE.brand').should('contain', 'Pharmacy')
    // module-theme.js relabels the registration options for this vertical: Customer → Patient.
    // (Slice 106: the Item → Medicine pair was dropped — #registrationType no longer HAS an Item option, so
    // it asserted a relabel that could never fire. Customer → Patient is the live one and still proves it.)
    cy.get('#registrationType option').then(($o) => {
      const texts = [...$o].map((o) => o.textContent.trim())
      expect(texts, 'relabelled for pharmacy').to.include('Patient')
      expect(texts, 'and the baseline term is gone').to.not.include('Customer')
    })
  })
})
