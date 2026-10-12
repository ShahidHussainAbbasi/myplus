/**
 * HMS H3c — the doctor sees what the pharmacy did with their prescription (client ruling L-3; design §4h).
 * NEEDS H3a (rx.dispense) and H3b (change / cancel with history) live.
 *
 *   C-01  the pharmacist changes the prescription with a reason; the doctor opens the visit on the real screen and
 *         sees "Changed by the pharmacy", the reason, and each line before → after.
 *   C-02  the pharmacist cancels it with a reason; the doctor sees "Cancelled by the pharmacy" and why.
 *
 * Roles: owner.pharma@ = reception setup · admin.pharma@ = the DOCTOR (Doctor set; restored to Administrator) ·
 * user.pharma@ = the PHARMACIST (Standard).
 *
 * Server state put back in after(): admin.pharma → Administrator; the visit completed; the patient retired; clinic
 * switch reset. The prescription ends CANCELLED (C-02).
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-h3c-doctor-sees.cy.js"
 */
describe('HMS H3c — the doctor sees what the pharmacy did', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const DOCTOR = 'admin.pharma@myplus.com'
  const PHARMACIST = 'user.pharma@myplus.com'
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  const phone = '038' + run.slice(-6) + String(Math.floor(Math.random() * 90) + 10).slice(-2)
  const MED_A = 'H3c Augmentin ' + run
  const MED_B = 'H3c Calpol ' + run
  const S = {}

  const relogin = (email, phase = 'on') => cy.loginAs(email, PW, '/getBusinessDashboardStats', `hms-h3c-${run}-${phase}`)
  const line = (productId, medicineName, quantity) => ({ productId, medicineName, quantity: String(quantity), dosage: '1 tab', frequency: 'TDS', duration: '3 days' })
  // The doctor opens their visit on the real screen (My queue → the token → Open).
  const doctorOpensVisit = () => {
    relogin(DOCTOR, 'doctor')
    cy.intercept('GET', '**/clinic/consult/encounters/*/rx/pharmacy').as('pharmacyCopy')
    cy.visit('/clinicDashboard')
    cy.get('#clinNavMyQueue').click()
    cy.get('#clinMyDoctor').select(String(S.doctor.id))
    cy.then(() => cy.contains('#clinQueueBody tr', S.token.tokenLabel).find('.clin-q-start').click())
    cy.wait('@pharmacyCopy')
    cy.get('#clinRxPharmacy').should('be.visible')
  }

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms h3c gate')
    relogin(OWNER, 'pre')
    cy.setCapability('clinic', true)
    cy.setPermissionSet(DOCTOR, 'Doctor')
    cy.setPermissionSet(PHARMACIST, 'Standard')
    relogin(OWNER)
    cy.postJson('/clinic/patients', { phone, name: 'H3c Patient ' + run }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      S.patient = r.body.data
    })
    cy.request('/clinic/doctors').then((r) => {
      S.doctor = (r.body.data || []).find((x) => x.name === 'Dr Ahmed (gate)')
      expect(S.doctor, 'Dr Ahmed (gate)').to.exist
    })
    cy.seedProduct({ name: MED_A, unit: 'tablet', stock: 50, sellingPrice: 20 }).then(({ productId }) => { S.medA = productId })
    cy.seedProduct({ name: MED_B, unit: 'tablet', stock: 50, sellingPrice: 4 }).then(({ productId }) => { S.medB = productId })
    cy.then(() => cy.postJson('/clinic/tokens', { patientId: S.patient.id, providerId: S.doctor.id })).then((t) => {
      expect(t.body.success, JSON.stringify(t.body)).to.eq(true)
      S.token = t.body.data
    })
    relogin(DOCTOR, 'doctor')
    cy.then(() => cy.postJson(`/clinic/tokens/${S.token.id}/call`)).its('body.success').should('eq', true)
    cy.then(() => cy.postJson(`/clinic/consult/tokens/${S.token.id}/start`)).then((r) => { S.visitId = r.body.data.id })
    cy.then(() => cy.putJson(`/clinic/consult/encounters/${S.visitId}/rx`, { lines: [line(S.medA, MED_A, 6), line(S.medB, MED_B, 4)] }))
      .its('body.success').should('eq', true)
    cy.then(() => cy.postJson(`/clinic/consult/encounters/${S.visitId}/rx/submit`)).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      S.rxId = r.body.data.rxId
    })
  })

  after(() => {
    relogin(DOCTOR, 'doctor')
    if (S.visitId) cy.postJson(`/clinic/consult/encounters/${S.visitId}/complete`)
    relogin(OWNER)
    if (S.patient) cy.postJson(`/clinic/patients/${S.patient.id}/retire`, { reason: 'cypress cleanup' })
    cy.setPermissionSet(DOCTOR, 'Administrator')
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  it('C-01 [L-3] the pharmacist changes it with a reason; the doctor sees the change, the reason and before → after', () => {
    relogin(PHARMACIST)
    cy.request(`/getPrescription?id=${S.rxId}`).its('body.data').then((p) => {
      cy.postJson('/amendPrescription', {
        prescriptionId: S.rxId, reason: '500mg out of stock; dose split', amendmentsSeen: 0,
        items: p.items.map((i) => ({ id: i.id, productId: i.productId, medicineName: i.medicineName, quantity: i.quantity,
                                     dosage: i.medicineName === MED_A ? '1+0+1' : i.dosage, frequency: i.frequency, duration: i.duration })),
      }).its('body.success').should('eq', true)
    })
    doctorOpensVisit()
    cy.get('#clinRxPharmacy').should('contain', 'At the pharmacy').and('contain', 'Waiting at the pharmacy')
      .and('contain', 'Changed by the pharmacy').and('contain', '500mg out of stock; dose split')
      .and('contain', MED_A + ' changed').and('contain', '1 tab').and('contain', '1+0+1')
    cy.get('#clinRxPharmacy').scrollIntoView()
    cy.screenshot('hms-h3c/C-01-1-doctor-sees-the-change', { capture: 'viewport' })
  })

  it('C-02 [L-3] the pharmacist cancels it with a reason; the doctor sees "Cancelled by the pharmacy" and why', () => {
    relogin(PHARMACIST)
    cy.postJson('/cancelPrescription', { prescriptionId: S.rxId, reason: 'Patient will buy it elsewhere' }).its('body.success').should('eq', true)
    doctorOpensVisit()
    cy.get('#clinRxPharmacy').should('contain', 'Cancelled by the pharmacy').and('contain', 'Patient will buy it elsewhere')
    cy.get('#clinRxPharmacy').scrollIntoView()
    cy.screenshot('hms-h3c/C-02-1-doctor-sees-the-cancellation', { capture: 'viewport' })
  })
})
