/**
 * Pharmacy vertical (slice 33) — the pharmacy domain (userType PHARMA) reuses the business/trade
 * dashboard, white-labelled via module=PHARMA. These tests prove the three things unique to pharmacy:
 *   1. routing  — a PHARMA user's dashboard renders at /pharmaDashboard (the shared trade engine),
 *   2. theming  — module-theme.js applies "Pharmacy" branding + relabels sections (Item -> Medicine),
 *   3. reuse    — the same trade endpoints serve pharmacy with no backend change.
 *
 * Requires the monolith + auth-service rebuilt/restarted (seeds demo.pharma@myplus.com + /pharmaDashboard).
 * Run headed:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/pharmacy/dashboard.cy.js
 */
describe('Pharmacy dashboard (reuses trade, white-labelled PHARMA)', () => {
  beforeEach(() => {
    cy.loginAsPharma()
  })

  it('renders the shared trade dashboard at /pharmaDashboard', () => {
    cy.visit('/pharmaDashboard')
    cy.get('#registrationType').should('exist') // the generic business engine is present
    cy.get('#DashboardDiv').should('exist')
  })

  it('applies pharmacy branding (module=PHARMA -> module-theme.js)', () => {
    cy.visit('/pharmaDashboard')
    // The title is only rewritten to this when window.MODULE === 'PHARMA' (set by PharmaDashboardController),
    // so it proves the controller -> model attribute -> theme chain end to end.
    cy.title().should('eq', 'Pharmacy Dashboard — MyPlus')
    // "Item Registration" -> "Medicine Registration" via the terminology map.
    cy.get('.dash-page-title').then(($els) => {
      expect($els.text()).to.match(/Medicine/)
    })
  })

  it('reuses the trade backend (same endpoints serve pharmacy)', () => {
    cy.request('/getBusinessDashboardStats').its('status').should('eq', 200)
    cy.request('/getUserItems').its('status').should('eq', 200)
  })

  it('does NOT theme the business dashboard (theming is module-scoped, not user-scoped)', () => {
    cy.visit('/businessDashboard')
    cy.title().should('eq', 'Business Dashboard — MyPlus')
  })
})
