/**
 * Pharmacy vertical (slice 33) — the pharmacy domain (userType PHARMA) reuses the business/trade
 * dashboard, white-labelled via module=PHARMA. These tests prove the three things unique to pharmacy:
 *   1. routing  — a PHARMA user's dashboard renders at /businessDashboard (the ONE shared commerce route;
 *                 there is no per-vertical route — see CommerceDashboardController / MvcConfig:80),
 *   2. theming  — module-theme.js applies "Pharmacy" branding + relabels sections (Item -> Medicine),
 *   3. reuse    — the same trade endpoints serve pharmacy with no backend change.
 *
 * Requires the monolith + auth-service rebuilt/restarted (seeds demo.pharma@myplus.com).
 * Run headed:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/pharmacy/dashboard.cy.js
 */
describe('Pharmacy dashboard (reuses trade, white-labelled PHARMA)', () => {
  beforeEach(() => {
    cy.loginAsPharma()
  })

  // There is no /pharmaDashboard route: CommerceDashboardController serves ONE commerce dashboard at
  // /businessDashboard and sets `module` from the logged-in user's TYPE (see MvcConfig:80). PHARMA, POS and
  // Store are the same page, white-labelled at runtime — so the pharmacy assertions below run there.
  it('renders the shared trade dashboard for a PHARMA user', () => {
    cy.visit('/businessDashboard')
    cy.get('#registrationType').should('exist') // the generic business engine is present
    cy.get('#DashboardDiv').should('exist')
  })

  it('applies pharmacy branding (module=PHARMA -> module-theme.js)', () => {
    cy.visit('/businessDashboard')
    // The title is only rewritten to this when window.MODULE === 'PHARMA' (set by CommerceDashboardController
    // from the user's type), so it proves the controller -> model attribute -> theme chain end to end.
    cy.title().should('eq', 'Pharmacy Dashboard — MyPlus')
    // A section heading relabelled by the terminology map. This asserted /Medicine/ ("Item Registration" ->
    // "Medicine Registration"), but the dashboard no longer has an Item OR Product registration heading at all
    // — the catalog migration moved that screen — so the assertion had become unsatisfiable regardless of
    // whether theming worked. "Customer Registration" -> "Patient Registration" exercises the same
    // dict -> textContent chain against a heading that actually exists.
    cy.get('.dash-page-title').then(($els) => {
      expect($els.text()).to.match(/Patient/)
    })
  })

  it('reuses the trade backend (same endpoints serve pharmacy)', () => {
    cy.request('/getBusinessDashboardStats').its('status').should('eq', 200)
    // M4e.d (slice 104): the medicine picker lists catalog Products (productId-native), not legacy Items.
    cy.request('/catalogProducts?size=10').its('status').should('eq', 200)
  })

  // Theming follows the USER'S VERTICAL, on the one shared route. This used to assert the reverse — that
  // /businessDashboard is never pharmacy-themed — which was true only while /pharmaDashboard existed as a
  // separate route. On the consolidated dashboard the same URL is Pharmacy for a PHARMA user and POS for a
  // BUSINESS user, so the meaningful check is that a POS user does NOT inherit pharmacy wording.
  it('a BUSINESS user gets POS wording on the same route', () => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    cy.title().should('eq', 'Business Dashboard — MyPlus')
  })
})
