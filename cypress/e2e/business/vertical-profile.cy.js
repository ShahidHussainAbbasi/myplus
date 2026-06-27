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
    // POS keeps the default wording — the registration "Item" option is not relabelled.
    cy.get('#registrationType option').then(($o) => {
      const texts = [...$o].map((o) => o.textContent.trim())
      expect(texts).to.include('Item')
    })
  })
})

describe('Vertical profile — Pharmacy (PHARMA)', () => {
  beforeEach(() => { cy.loginAsPharma() })

  it('renders the SAME dashboard in PHARMA module with pharmacy wording', () => {
    cy.visit('/businessDashboard')
    cy.window().its('MODULE').should('eq', 'PHARMA')
    cy.window().its('VERTICAL_PROFILE.brand').should('contain', 'Pharmacy')
    // module-theme.js relabels the off-screen nav options: Item → Medicine, Customer → Patient.
    cy.get('#registrationType option').then(($o) => {
      const texts = [...$o].map((o) => o.textContent.trim())
      expect(texts).to.include('Medicine')
      expect(texts).to.include('Patient')
      expect(texts).to.not.include('Item')
    })
  })
})
