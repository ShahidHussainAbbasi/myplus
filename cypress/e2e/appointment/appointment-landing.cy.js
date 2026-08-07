/**
 * Slice-18 — role-based landing: a user whose JWT carries userType=APPOINTMENT is routed to the
 * appointment module's own dashboard on login (MySimpleUrlAuthenticationSuccessHandler:
 * /<userType>Dashboard), exactly like education/business.
 *
 * Requires the seeded demo user demo.appointment@myplus.com (auth-service SetupDataLoader, dev seed flag)
 * and the full stack up. Does a raw login (not cy.session) so the landing redirect is observable.
 */
describe('Slice-18 — APPOINTMENT user lands on /appointmentDashboard', () => {
  it('logs in and is routed to its own module dashboard', () => {
    cy.visit('/login')
    cy.get('input[name="username"]').type('demo.appointment@myplus.com')
    cy.get('input[name="password"]').type('Demo@2025!')
    cy.get('#loginSubmit').click()

    cy.url().should('include', '/appointmentDashboard')
    // SELECTOR REPAIRED 2026-08-07 (slice SCHED-1). The assertion's INTENT is unchanged — "the dashboard
    // chrome rendered" — but the element that represents it was renamed by the UI redesign
    // (cb4d6abb, 2026-07-09): appointmentDashboard.html now carries <nav id="app-sidebar">, and
    // #nav-subheader survives only in businessDashboard.html. This spec was last touched 2026-06-12, so
    // it has been RED since July and nobody ran it. Found while using it as SCHED-1's baseline.
    cy.get('#app-sidebar').should('be.visible')
    cy.get('#AppointmentsDiv').should('be.visible')
  })
})
