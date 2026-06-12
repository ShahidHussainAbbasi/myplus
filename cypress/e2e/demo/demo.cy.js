/**
 * Slice-19 — demo free-trial UX.
 *  - Login page: "No account? Try a live demo" → pick a module → one-click into its dashboard.
 *  - Banner: a demo session shows the "Demo mode" banner + Register CTA.
 *  - Upsell: a create rejected with 403 DEMO_LIMIT pops the upsell modal (stubbed; real cap verified at gateway).
 * Requires the stack up (monolith + auth-service + the module service); demo users are seeded by auth-service.
 */
describe('Slice-19 — demo trial UX', () => {
  it('reveals demo login details on module select, then lands on the dashboard with the banner', () => {
    cy.visit('/login')
    cy.contains('No account? Try a live demo').should('be.visible')
    // Before selecting, the details are hidden and Launch is disabled.
    cy.get('#demoCreds').should('not.be.visible')
    cy.get('#demoLaunch').should('be.disabled')
    // Pick a module -> the demo login details appear so the user can start working.
    cy.get('#demoDomain').select('demo.appointment@myplus.com', { force: true })
    cy.get('#demoCreds').should('be.visible')
    cy.get('#demoCredEmail').should('have.text', 'demo.appointment@myplus.com')
    cy.get('#demoCredPw').should('contain', 'Demo@2025!')
    cy.get('#demoLaunch').should('not.be.disabled').click()
    cy.url().should('include', '/appointmentDashboard')
    cy.contains('Demo mode').should('be.visible')
    cy.contains('Register for full access').should('be.visible')
  })

  it('shows the upsell modal when a create hits the DEMO_LIMIT', () => {
    cy.loginAs('demo.appointment@myplus.com', 'Demo@2025!', '/appointmentDashboard')
    cy.visit('/appointmentDashboard')
    cy.intercept('POST', '**/registerHospital', {
      statusCode: 403,
      body: {
        success: false, code: 'DEMO_LIMIT',
        message: "You've reached the 50-entry demo limit. Register at maxtheservice.com to unlock the full features.",
      },
    }).as('cap')

    cy.get('#apptNav').select('HospitalDiv', { force: true })
    cy.get('#hName').type('CapHospital')
    cy.get('#hPhone').type('03001234567')
    cy.get('#hEmail').type('cap@test.com')
    cy.get('#addHospital').click()
    cy.wait('@cap')

    cy.get('#demoUpsellOverlay').should('be.visible')
    cy.get('#demoUpsellOverlay').contains('demo limit')
    cy.get('#demoUpsellOverlay').contains('a', 'Register free').should('have.attr', 'href', '/registration.html')
  })
})
