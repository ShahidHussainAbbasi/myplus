/**
 * Slice-19 Phase C — "Reset demo" clears the trial end to end: the banner button calls /demo/reset,
 * which (1) clears the gateway write counters and (2) purges the demo account's module data (org-scoped,
 * DEMO_PRIVILEGE-guarded). After it, the appointment dashboard's table is empty.
 * Requires the stack up (monolith + gateway + appointment-service). Uses the appointment demo account.
 */
describe('Slice-19 — Reset demo clears data + counter', () => {
  it('purges the demo account data and resets the cap from the banner button', () => {
    cy.loginAs('demo.appointment@myplus.com', 'Demo@2025!', '/appointmentDashboard')
    cy.visit('/appointmentDashboard')

    // The Reset demo button lives in the demo banner (demo accounts only).
    cy.intercept('POST', '**/demo/reset').as('reset')
    cy.contains('Reset demo').should('be.visible').click()
    cy.wait('@reset').its('response.statusCode').should('eq', 200)

    // demo.js reloads the page; after the purge the appointments table is empty.
    cy.get('#apptTableBody', { timeout: 15000 }).should('contain', 'No appointments')
  })
})
