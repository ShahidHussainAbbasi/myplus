/**
 * HMS RW-3 — each person lands on their own WORKSPACE after signing in (client, 2026-10-12; decision D4: a tile home
 * for several jobs AND a switcher in the top bar). The workspaces are rows in auth-service (ui_workspace, V27), never
 * named in code.
 *
 *   RW3-01  the front desk signs in and lands on the clinic's Reception
 *   RW3-02  the doctor signs in and lands on My queue
 *   RW3-03  the pharmacist signs in and lands on the pharmacy dashboard
 *   RW3-04  the owner (every job) lands on the tile home; the top bar has the switcher; a tile opens its screen
 *   RW3-05  a plain shop (no clinic) signs in exactly where it always did — no tile home, no switcher
 *
 * Every sign-in is the real login form (the landing is decided at sign-in). The clinic is switched on for the
 * pharmacy business here and reset after (every gate does).
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-rw3-workspaces.cy.js"
 */
import { HMS } from '../../support/hms-accounts'

describe('HMS RW-3 — each person lands on their own workspace', () => {
  const run = String(Date.now()).slice(-7)
  const shot = (n) => cy.screenshot(`hms-rw3/${n}`, { capture: 'viewport', overwrite: true })
  const signIn = (email, password = HMS.PW) => {
    cy.clearCookies()
    cy.visit('/login')
    cy.get('input[name="username"]').clear().type(email)
    cy.get('input[name="password"]').clear().type(password, { log: false })
    cy.get('#loginSubmit').click()
    cy.location('pathname', { timeout: 20000 }).should('not.include', '/login')
  }

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(HMS.OWNER, 'clinic', 'ACTIVE', 'hms rw3 gate')
    cy.loginAs(HMS.OWNER, HMS.PW, '/getBusinessDashboardStats', `hms-rw3-${run}`)
    cy.setCapability('clinic', true)
  })

  after(() => {
    cy.loginAs(HMS.OWNER, HMS.PW, '/getBusinessDashboardStats', `hms-rw3-${run}`)
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  it('RW3-01 the front desk signs in and lands on the clinic\'s Reception', () => {
    signIn(HMS.RECEPTION)
    cy.location('pathname').should('eq', '/clinicDashboard')
    cy.get('#ReceptionDiv', { timeout: 15000 }).should('be.visible')
    cy.get('#wsSwitch').should('not.exist')                         // one job: no switcher
    shot('RW3-01-1-desk-lands-on-reception')
  })

  it('RW3-02 the doctor signs in and lands on My queue', () => {
    signIn(HMS.DOCTOR)
    cy.location('pathname').should('eq', '/clinicDashboard')
    cy.get('#MyQueueDiv', { timeout: 20000 }).should('be.visible')
    cy.get('#clinMeName').should('contain', HMS.DOCTOR_NAME)
    shot('RW3-02-1-doctor-lands-on-my-queue')
  })

  it('RW3-03 the pharmacist signs in and lands on the pharmacy dashboard', () => {
    signIn(HMS.PHARMACIST)
    cy.location('pathname').should('eq', '/businessDashboard')
    cy.get('#wsSwitch').should('not.exist')
    shot('RW3-03-1-pharmacist-lands-on-pharmacy')
  })

  it('RW3-04 the owner lands on the tile home, has the switcher, and a tile opens its screen', () => {
    signIn(HMS.OWNER)
    cy.location('pathname').should('eq', '/workspaces')
    cy.get('#wsTiles .ws-tile').should('have.length.greaterThan', 1)
    ;['frontdesk', 'doctor', 'pharmacy', 'admin'].forEach((k) => cy.get(`#wsTiles [data-ws="${k}"]`).should('exist'))
    cy.get('#wsSwitch').should('exist')
    shot('RW3-04-1-owner-tile-home')
    cy.get('#wsTiles [data-ws="frontdesk"]').click()
    cy.location('pathname').should('eq', '/clinicDashboard')
    cy.get('#ReceptionDiv', { timeout: 15000 }).should('be.visible')
    // the switcher takes the owner to another job without going home
    cy.get('#wsSwitch .lang-switch__btn').click()
    cy.get('#wsSwitch [data-ws="pharmacy"]').should('be.visible').click()
    cy.location('pathname').should('eq', '/businessDashboard')
    shot('RW3-04-2-owner-switched-to-pharmacy')
  })

  it('RW3-05 a plain shop (no clinic) signs in exactly where it always did', () => {
    signIn('owner.business@myplus.com')
    cy.location('pathname').should('not.eq', '/workspaces')
    cy.get('#wsSwitch').should('not.exist')
  })
})
