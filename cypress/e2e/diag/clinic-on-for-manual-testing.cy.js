/**
 * One-off (2026-10-12): switch the clinic ON for owner.pharma's business so the manual testers' accounts see it —
 * SET-02 of the Test Book, done by the owner. Every gate spec switches it OFF again in its cleanup ("leave no server
 * state"), so run this after any spec run and before manual testing. Deliberately NO cleanup: ON is the goal.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/diag/clinic-on-for-manual-testing.cy.js"
 */
import { HMS } from '../../support/hms-accounts'

describe('manual testing — the clinic is on and reception sees it', () => {
  it('owner switches the clinic on; hms.reception sees Reception', () => {
    cy.loginAs(HMS.OWNER, HMS.PW, '/getBusinessDashboardStats', 'clinic-on-' + Date.now())
    cy.setCapability('clinic', true)
    cy.clearCookies()
    cy.visit('/login')
    cy.get('input[name="username"]').type(HMS.RECEPTION)
    cy.get('input[name="password"]').type(HMS.PW, { log: false })
    cy.get('#loginSubmit').click()
    cy.location('pathname', { timeout: 20000 }).should('not.include', '/login')
    // HC-1a — the pharmacy's "Clinic reception" menu is gone; the combined tenant enters through its Front desk tile.
    cy.visit('/workspaces')
    cy.get('#wsTiles [data-ws="frontdesk"]').should('be.visible')
    cy.screenshot('manual/reception-menu-clinic', { capture: 'viewport', overwrite: true })
    cy.get('#wsTiles [data-ws="frontdesk"]').click()
    cy.location('pathname').should('eq', '/clinicDashboard')
    cy.get('#ReceptionDiv').should('be.visible')
    cy.screenshot('manual/reception-desk', { capture: 'viewport', overwrite: true })
  })
})
