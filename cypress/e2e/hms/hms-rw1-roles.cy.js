/**
 * HMS RW-1 — each clinic role does its own work, and the server refuses the rest (client, 2026-10-12;
 * microservices/docs/hms-role-workspaces-design.md §3.2; auth-service V23 + clinical-service ClinicAccess).
 *
 *   RW1-01  the front desk: registers a patient and gives a token; cannot change clinic settings, register a doctor,
 *           buy stock or add a product (pharmacy back office — removed from "Clinic reception" by V23, D5)
 *   RW1-02  the doctor: reads the queue and a patient; cannot register a patient, give or cancel a token
 *   RW1-03  the pharmacist (new "Pharmacist" set, D3): finds a patient; cannot see the queue, give a token or buy stock
 *   RW1-04  the owner: everything (control) — and the Team screen offers Pharmacist and Storekeeper
 *
 * Every refusal is asserted on the SERVER (a request the screen would never send), because a hidden button is a
 * convenience and the server refusal is the control (STANDARDS §0c). Every refused write is checked to have written
 * nothing.
 *
 * Accounts: cypress/support/hms-accounts.js (run hms/hms-four-accounts.cy.js first on a fresh stack).
 * Cleanup: the token is cancelled and the patient retired; the clinic switch is reset (every gate does).
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-rw1-roles.cy.js"
 */
import { HMS } from '../../support/hms-accounts'

describe('HMS RW-1 — each role does its own work', () => {
  const run = String(Date.now()).slice(-7)
  const phone = '036' + run.slice(-6) + String(Math.floor(Math.random() * 90) + 10).slice(-2)
  const S = {}
  const relogin = (email, phase = 'on') => cy.loginAs(email, HMS.PW, '/getBusinessDashboardStats', `hms-rw1-${run}-${phase}`)
  // the PERMISSION refusal, never a business one: a 403, or the clinic's own words for a role it does not include
  const PERMISSION = /role does not include|permission set|Only the owner or an admin|Only a doctor/i
  const refusedJson = (method, url, body) => cy.request({ method, url, body, failOnStatusCode: false })
    .then((r) => {
      const msg = (r.body && (r.body.message || r.body.error)) || ''
      const refused = r.status === 403 || (r.body && r.body.success === false && PERMISSION.test(String(msg)))
      expect(refused, `${method} ${url} is refused FOR THE ROLE — ${r.status} ${JSON.stringify(r.body).slice(0, 160)}`).to.eq(true)
      return r
    })
  const refusedForm = (url, body) => cy.request({ method: 'POST', url, body, form: true, failOnStatusCode: false })
    .then((r) => expect(r.status, `POST ${url} is refused (403) — ${JSON.stringify(r.body).slice(0, 160)}`).to.eq(403))

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(HMS.OWNER, 'clinic', 'ACTIVE', 'hms rw1 gate')
    relogin(HMS.OWNER, 'pre')
    cy.setCapability('clinic', true)
    relogin(HMS.OWNER)
    cy.hmsDoctorDay({ limit: 0 })   // the test doctor's patients-a-day must not refuse this gate's token
    cy.request('/clinic/doctors').then((r) => {
      S.doctor = (r.body.data || []).find((d) => d.name === HMS.DOCTOR_NAME)
      expect(S.doctor, HMS.DOCTOR_NAME + ' (run hms-four-accounts first)').to.exist
    })
  })

  after(() => {
    relogin(HMS.OWNER)   // the same session as the cases: a NEW sign-in would take another device slot (cap 5)
    if (S.token) cy.postJson(`/clinic/tokens/${S.token.id}/cancel`, { reason: 'cypress cleanup' })
    if (S.patient) cy.postJson(`/clinic/patients/${S.patient.id}/retire`, { reason: 'cypress cleanup' })
    cy.request(`/clinic/patients?q=${encodeURIComponent('RW1')}`).then((r) => {
      const d = r.body && r.body.data
      const rows = Array.isArray(d) ? d : ((d && d.patients) || [])
      rows.filter((x) => /^RW1 (Patient|Doctor Made) /.test(x.name || '') && (!S.patient || x.id !== S.patient.id))
        .forEach((x) => cy.postJson(`/clinic/patients/${x.id}/retire`, { reason: 'cypress cleanup (earlier run)' }))
    })
    cy.hmsDoctorDay({ reset: true })
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  it('RW1-01 the front desk registers a patient and gives a token — and nothing of the back office', () => {
    relogin(HMS.RECEPTION, 'desk')
    cy.postJson('/clinic/patients', { phone, name: 'RW1 Patient ' + run, cnic: '35202-' + run.slice(-7) + '-1' }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      S.patient = r.body.data
    })
    cy.then(() => cy.postJson('/clinic/tokens', { patientId: S.patient.id, providerId: S.doctor.id })).then((t) => {
      expect(t.body.success, JSON.stringify(t.body)).to.eq(true)
      S.token = t.body.data
    })
    // refused: clinic settings, registering a doctor, buying stock, adding a product
    refusedForm('/clinic/settings', { key: 'clinic.patient.cnicRequired', value: 'true' })
    refusedJson('POST', '/clinic/doctors', { name: 'RW1 Not A Doctor ' + run })
    refusedForm('/addPurchase', { billNo: 'RW1-' + run })
    refusedForm('/addProduct', { name: 'RW1 Product ' + run })
    // nothing was written by the refusals: no such doctor
    relogin(HMS.OWNER)
    cy.request('/clinic/doctors').its('body.data').then((ds) => {
      expect((ds || []).some((d) => d.name === 'RW1 Not A Doctor ' + run), 'no doctor was created').to.eq(false)
    })
  })

  it('RW1-02 the doctor reads the queue and the patient — but does not do the front desk\'s work', () => {
    relogin(HMS.DOCTOR, 'doctor')
    cy.request(`/clinic/patients/${S.patient.id}`).its('body.data.id').should('eq', S.patient.id)
    cy.request('/clinic/queue').its('body.success').should('eq', true)
    refusedJson('POST', '/clinic/patients', { phone: '037' + run.slice(-6) + '12', name: 'RW1 Doctor Made ' + run })
    refusedJson('POST', '/clinic/tokens', { patientId: S.patient.id, providerId: S.doctor.id })
    refusedJson('POST', `/clinic/tokens/${S.token.id}/cancel`, { reason: 'doctor tries' })
    // the token is untouched
    relogin(HMS.OWNER)
    cy.request(`/clinic/tokens/${S.token.id}`).its('body.data.status').should('eq', 'WAITING')
  })

  it('RW1-03 the pharmacist finds a patient — no queue, no tokens, no buying stock', () => {
    relogin(HMS.PHARMACIST, 'pharmacist')
    cy.request(`/clinic/patients?phone=${phone}`).its('body.success').should('eq', true)
    refusedJson('GET', '/clinic/queue')
    refusedJson('POST', '/clinic/tokens', { patientId: S.patient.id, providerId: S.doctor.id })
    refusedForm('/addPurchase', { billNo: 'RW1-PH-' + run })
  })

  it('RW1-04 the owner does everything; the Team screen offers Pharmacist and Storekeeper', () => {
    relogin(HMS.OWNER)
    cy.request('/clinic/queue').its('body.success').should('eq', true)
    cy.request('/team/permissions').its('body').then((b) => {
      const sets = (b.data || b.object).sets || []
      const named = (n) => sets.find((s) => s.name === n)
      expect(named('Pharmacist'), 'Pharmacist is offered').to.exist
      expect(named('Storekeeper'), 'Storekeeper is offered').to.exist
      expect(named('Pharmacist').codes).to.include('rx.dispense').and.not.include('purchase.create')
      expect(named('Storekeeper').codes).to.include('purchase.create').and.not.include('sale.create')
      expect(named('Clinic reception').codes).to.include('clinic.token.issue').and.include('sale.create')
        .and.not.include('purchase.create').and.not.include('report.view')
    })
  })
})
