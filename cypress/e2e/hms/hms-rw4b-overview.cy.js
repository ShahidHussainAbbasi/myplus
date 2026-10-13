/**
 * HMS RW-4b — the owner's overview: "today at a glance" (client, 2026-10-13). Number cards — tokens today, waiting now,
 * doctors on duty, prescriptions waiting, takings today — each a ui_menu row (overview.*, V29), each linking to its
 * screen. The Administration workspace lands here.
 *
 *   RW4B-01  the owner sees every card, and each figure AGREES with the services' own data
 *   RW4B-02  a new token moves "tokens today" and "waiting now" by one
 *   RW4B-03  the Administration tile opens the overview
 *   RW4B-04  the front desk opening it sees no takings and no prescriptions card; the doctor sees no money either
 *
 * Cleanup: the token is cancelled and the patient retired; the clinic switch is reset (every gate does).
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-rw4b-overview.cy.js"
 */
import { HMS } from '../../support/hms-accounts'

describe('HMS RW-4b — the owner\'s overview', () => {
  const run = String(Date.now()).slice(-7)
  const phone = '035' + run.slice(-6) + String(Math.floor(Math.random() * 90) + 10).slice(-2)
  const S = {}
  const relogin = (email, phase = 'on') => cy.loginAs(email, HMS.PW, '/getBusinessDashboardStats', `hms-rw4b-${run}-${phase}`)
  const shot = (n) => cy.screenshot(`hms-rw4b/${n}`, { capture: 'viewport', overwrite: true })
  const num = (sel) => cy.get(sel, { timeout: 15000 }).should(($e) => expect($e.text().trim()).to.not.eq('—'))
    .invoke('text').then((t) => Number(t.replace(/[^\d.-]/g, '')))
  const clinicTotals = () => cy.request('/clinic/doctors').its('body.data').then((ds) => ({
    issued: ds.reduce((a, d) => a + Number(d.issuedToday || 0), 0),
    waiting: ds.reduce((a, d) => a + Number(d.waitingNow || 0), 0),
    onDuty: ds.filter((d) => !d.closedToday).length,
  }))

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(HMS.OWNER, 'clinic', 'ACTIVE', 'hms rw4b gate')
    relogin(HMS.OWNER, 'pre')
    cy.setCapability('clinic', true)
    relogin(HMS.OWNER)
    cy.hmsDoctorDay({ limit: 0 })
    cy.request('/clinic/doctors').then((r) => {
      S.doctor = (r.body.data || []).find((d) => d.name === HMS.DOCTOR_NAME)
      expect(S.doctor, HMS.DOCTOR_NAME).to.exist
    })
  })

  after(() => {
    relogin(HMS.OWNER)
    if (S.token) cy.postJson(`/clinic/tokens/${S.token.id}/cancel`, { reason: 'cypress cleanup' })
    if (S.patient) cy.postJson(`/clinic/patients/${S.patient.id}/retire`, { reason: 'cypress cleanup' })
    cy.hmsDoctorDay({ reset: true })
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  it('RW4B-01 the owner sees every card, and each figure agrees with the services\' data', () => {
    relogin(HMS.OWNER)
    cy.visit('/overview')
    ;['#ovTokensCard', '#ovWaitingCard', '#ovDoctorsCard', '#ovRxCard', '#ovTakingsCard'].forEach((s) => cy.get(s).should('exist'))
    clinicTotals().then((t) => {
      num('#ovTokens').should('eq', t.issued)
      num('#ovWaiting').should('eq', t.waiting)
      num('#ovDoctors').should('eq', t.onDuty)
    })
    cy.request('/prescriptionsWaitingCount').its('body.data').then((n) => num('#ovRx').should('eq', Number(n)))
    cy.request('/getBusinessDashboardStats').its('body.object.todayRevenue').then((v) => num('#ovTakings').should('eq', Number(v)))
    shot('RW4B-01-1-owner-overview')
  })

  it('RW4B-02 a new token moves "tokens today" and "waiting now" by one', () => {
    relogin(HMS.OWNER)
    cy.visit('/overview')
    num('#ovTokens').then((tokens) => {
      num('#ovWaiting').then((waiting) => {
        cy.postJson('/clinic/patients', { phone, name: 'RW4B Patient ' + run, cnic: '35202-' + run + '-3' }).then((r) => {
          expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
          S.patient = r.body.data
          return cy.postJson('/clinic/tokens', { patientId: S.patient.id, providerId: S.doctor.id })
        }).then((t) => {
          expect(t.body.success, JSON.stringify(t.body)).to.eq(true)
          S.token = t.body.data
        })
        cy.reload()
        num('#ovTokens').should('eq', tokens + 1)
        num('#ovWaiting').should('eq', waiting + 1)
      })
    })
  })

  it('RW4B-03 the Administration tile opens the overview', () => {
    cy.clearCookies()
    cy.visit('/login')
    cy.get('input[name="username"]').type(HMS.OWNER)
    cy.get('input[name="password"]').type(HMS.PW, { log: false })
    cy.get('#loginSubmit').click()
    cy.location('pathname', { timeout: 20000 }).should('eq', '/workspaces')
    cy.get('#wsTiles [data-ws="admin"]').click()
    cy.location('pathname').should('eq', '/overview')
  })

  it('RW4B-04 the front desk sees no takings or prescriptions card; the doctor sees no money', () => {
    relogin(HMS.RECEPTION, 'desk')
    cy.visit('/overview')
    cy.get('#ovTokensCard').should('exist')
    cy.get('#ovTakingsCard').should('not.exist')
    cy.get('#ovRxCard').should('not.exist')
    shot('RW4B-04-1-desk-overview')
    relogin(HMS.DOCTOR, 'doctor')
    cy.visit('/overview')
    cy.get('#ovTakingsCard').should('not.exist')
    cy.get('#ovRxCard').should('not.exist')
  })
})
