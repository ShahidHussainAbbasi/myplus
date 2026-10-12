/**
 * HMS RW-2 — each person's MENUS show only their own work (client, 2026-10-12; hms-role-workspaces-design.md §5).
 * The screens, not the server: RW-1 (hms-rw1-roles) proves the server refuses; this proves nobody is SHOWN a door
 * the server will refuse — the reception desk no longer sees Purchase or Clinic settings.
 *
 *   RW2-01  the front desk: Register (customers only), Sale, Till, the clinic's Reception · Today's queue · Doctors
 *           (a doctor's day, but not adding doctors); no Purchase, no suppliers, no Clinic settings
 *   RW2-02  the doctor: the clinic opens on My queue; no pharmacy menus, no Reception / Today's queue / Doctors /
 *           Clinic settings, and no way to the pharmacy
 *   RW2-03  the pharmacist: Sale, Till, Prescriptions; no Purchase, no suppliers; on the clinic page no front-desk
 *           screens and no Clinic settings
 *   RW2-04  the owner: every menu (control)
 *
 * Accounts: cypress/support/hms-accounts.js. The clinic is switched on here and reset after (every gate does).
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-rw2-menus.cy.js"
 */
import { HMS } from '../../support/hms-accounts'

describe('HMS RW-2 — each person sees their own menus', () => {
  const run = String(Date.now()).slice(-7)
  const relogin = (email, phase) => cy.loginAs(email, HMS.PW, '/getBusinessDashboardStats', `hms-rw2-${run}-${phase}`)
  const shot = (n) => cy.screenshot(`hms-rw2/${n}`, { capture: 'viewport', overwrite: true })
  const has = (sel) => cy.get(sel).should('exist')
  const lacks = (sel) => cy.get('body').find(sel).should('have.length', 0)
  const openMenu = (id) => cy.get(`#${id} > .snav-btn`).click()

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(HMS.OWNER, 'clinic', 'ACTIVE', 'hms rw2 gate')
    relogin(HMS.OWNER, 'pre')
    cy.setCapability('clinic', true)
  })

  after(() => {
    relogin(HMS.OWNER, 'pre')
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  it('RW2-01 the front desk sees the desk, the till and the doctors\' day — not purchases, suppliers or settings', () => {
    relogin(HMS.RECEPTION, 'desk')
    cy.visit('/businessDashboard')
    has('#snavSell'); has('#snavTill'); has('#snavPharmacy'); has('#snavRegister')
    lacks('#snavPurchase'); lacks('#snavFinance'); lacks('#snavSettings'); lacks('#snavTeam')
    openMenu('snavRegister')
    cy.get('#snavRegister').should('contain', 'Customer').and('not.contain', 'Supplier')
    shot('RW2-01-1-desk-pharmacy-menus')
    cy.visit('/clinicDashboard')
    cy.get('#ReceptionDiv').should('be.visible')                   // opens on Reception
    has('#clinNavReception'); has('#clinNavQueue'); has('#clinNavDoctors')
    lacks('#clinNavSettings')
    cy.get('#clinNavDoctors').click()
    cy.get('#clinDoctorBody tr', { timeout: 20000 }).should('have.length.greaterThan', 0)
    lacks('#clinDoctorForm')                                       // no adding doctors
    cy.get('#clinDoctorBody .clin-day').should('have.length.greaterThan', 0)   // but a doctor's day (D2)
    cy.get('#clinDoctorBody .clin-login-link').should('have.length', 0)        // and no linking logins
    shot('RW2-01-2-desk-clinic-doctors-day-only')
  })

  it('RW2-02 the doctor\'s clinic opens on My queue; no pharmacy and no front-desk screens', () => {
    relogin(HMS.DOCTOR, 'doctor')
    cy.visit('/businessDashboard')
    lacks('#snavPurchase'); lacks('#snavSell'); lacks('#snavTill'); lacks('#snavRegister')
    shot('RW2-02-1-doctor-pharmacy-dashboard')
    cy.visit('/clinicDashboard')
    cy.get('#MyQueueDiv', { timeout: 20000 }).should('be.visible')  // opens on My queue
    cy.get('#clinNavMyQueueWrap').should('be.visible')
    lacks('#clinNavReception'); lacks('#clinNavQueue'); lacks('#clinNavDoctors'); lacks('#clinNavSettings')
    lacks('#clinNavPharmacy')
    shot('RW2-02-2-doctor-my-queue-only')
  })

  it('RW2-03 the pharmacist sees selling, the till and prescriptions — not purchases, suppliers or the desk', () => {
    relogin(HMS.PHARMACIST, 'pharmacist')
    cy.visit('/businessDashboard')
    has('#snavSell'); has('#snavTill'); has('#snavPharmacy')
    lacks('#snavPurchase')
    openMenu('snavPharmacy')
    cy.get('#snavPharmacy').should('contain', 'Prescriptions')
    openMenu('snavRegister')
    cy.get('#snavRegister').should('not.contain', 'Supplier')
    shot('RW2-03-1-pharmacist-menus')
    cy.visit('/clinicDashboard')
    lacks('#clinNavReception'); lacks('#clinNavQueue'); lacks('#clinNavDoctors'); lacks('#clinNavSettings')
    has('#clinNavPharmacy')
  })

  it('RW2-04 the owner sees every menu', () => {
    relogin(HMS.OWNER, 'pre')
    cy.visit('/businessDashboard')
    ;['#snavRegister', '#snavPurchase', '#snavSell', '#snavPharmacy', '#snavTill', '#snavFinance', '#snavSettings', '#snavTeam']
      .forEach(has)
    cy.visit('/clinicDashboard')
    ;['#clinNavReception', '#clinNavQueue', '#clinNavDoctors', '#clinNavSettings', '#clinNavPharmacy'].forEach(has)
    cy.get('#clinNavDoctors').click()
    has('#clinDoctorForm')
    shot('RW2-04-1-owner-clinic-everything')
  })
})
