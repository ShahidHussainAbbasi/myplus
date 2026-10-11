/**
 * HMS H3b — only the PHARMACIST dispenses, changes or cancels a doctor's prescription, and every change is kept
 * (client ruling L-3; design §4h). NEEDS H3a live (auth V21: rx.dispense in Standard / Administrator; "Clinic
 * reception" without it).
 *
 *   B-01  the pharmacist changes the prescription on the real screen (a dose, a quantity, a medicine added) with a
 *         reason; History shows each line before → after.
 *   B-02  the rules: a reason is required; a medicine outside this pharmacy's catalogue is refused; a screen that
 *         has not seen the latest change is refused.
 *   B-03  cancelling the doctor's prescription needs a reason; History shows it.
 *   B-04  the front desk on "Clinic reception" sees the list but cannot dispense, change or cancel (screen + server).
 *   B-05  the doctor who submitted it cannot change or cancel it.
 *
 * Roles: owner.pharma@ = reception setup · admin.pharma@ = the DOCTOR (Doctor set; restored to Administrator) ·
 * user.pharma@ = the PHARMACIST on Standard (B-01..03), then the FRONT DESK on Clinic reception (B-04; restored to
 * Standard in after()).
 *
 * Server state put back in after(): user.pharma → Standard; admin.pharma → Administrator; the visit completed; the
 * prescription cancelled (B-03); the patient retired; clinic switch reset.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-h3b-pharmacist.cy.js"
 */
describe('HMS H3b — the pharmacist changes or cancels the prescription, and it is kept', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const DOCTOR = 'admin.pharma@myplus.com'
  const PHARMACIST = 'user.pharma@myplus.com'
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  const phone = '030' + run.slice(-6) + String(Math.floor(Math.random() * 90) + 10).slice(-2)
  const MED_A = 'H3b Augmentin ' + run
  const MED_B = 'H3b Calpol ' + run
  const MED_C = 'H3b Brufen ' + run
  const S = {}

  const relogin = (email, phase = 'on') => cy.loginAs(email, PW, '/getBusinessDashboardStats', `hms-h3b-${run}-${phase}`)
  const post = (url, body) =>
    cy.request({ method: 'POST', url, body: body || {}, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
  const put = (url, body) =>
    cy.request({ method: 'PUT', url, body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
  const setOf = (email, setName) => cy.request('/team/users').then((u) => {
    const member = ((u.body && (u.body.data || u.body.object)) || []).find((x) => String(x.email || '') === email)
    expect(member, email + ' is a member').to.exist
    return cy.request('/team/permissions').then((p) => {
      const set = (((p.body && (p.body.data || p.body.object)) || {}).sets || []).find((s) => s.name === setName)
      expect(set, 'the "' + setName + '" set exists (H3a / V21 for Clinic reception)').to.exist
      return post('/team/permissions/assign', { userId: member.userId || member.id, setId: set.id })
        .its('body').then((b) => expect(b && (b.success === true || b.status === 'SUCCESS'), JSON.stringify(b)).to.eq(true))
    })
  })
  const line = (productId, medicineName, quantity) => ({ productId, medicineName, quantity: String(quantity), dosage: '1 tab', frequency: 'TDS', duration: '3 days' })
  const rx = () => cy.request(`/getPrescription?id=${S.rxId}`).its('body.data')
  const openPrescriptions = () => {
    cy.intercept('GET', '**/searchPrescriptions*').as('search')
    cy.visit('/businessDashboard')
    cy.get('#snavPharmacy > .snav-btn').click()
    cy.get('#snavPharmacy a[onclick^="showPrescriptions"]').click()
    cy.wait('@search')
    cy.get('#rxSearch').type(S.token.tokenLabel + '{enter}')
    cy.wait('@search')
    cy.get(`#prescriptionBody tr[data-rx-id="${S.rxId}"]`).should('be.visible')
  }

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms h3b gate')
    relogin(OWNER, 'pre')
    cy.setCapability('clinic', true)
    setOf(DOCTOR, 'Doctor')
    setOf(PHARMACIST, 'Standard')
    relogin(OWNER)
    post('/clinic/patients', { phone, name: 'H3b Patient ' + run }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      S.patient = r.body.data
    })
    cy.request('/clinic/doctors').then((r) => {
      S.doctor = (r.body.data || []).find((x) => x.name === 'Dr Ahmed (gate)')
      expect(S.doctor, 'Dr Ahmed (gate)').to.exist
    })
    cy.seedProduct({ name: MED_A, unit: 'tablet', stock: 50, sellingPrice: 20 }).then(({ productId }) => { S.medA = productId })
    cy.seedProduct({ name: MED_B, unit: 'tablet', stock: 50, sellingPrice: 4 }).then(({ productId }) => { S.medB = productId })
    cy.seedProduct({ name: MED_C, unit: 'tablet', stock: 50, sellingPrice: 6 }).then(({ productId }) => { S.medC = productId })
    cy.then(() => post('/clinic/tokens', { patientId: S.patient.id, providerId: S.doctor.id })).then((t) => {
      expect(t.body.success, JSON.stringify(t.body)).to.eq(true)
      S.token = t.body.data
    })
    relogin(DOCTOR, 'doctor')
    cy.then(() => post(`/clinic/tokens/${S.token.id}/call`)).its('body.success').should('eq', true)
    cy.then(() => post(`/clinic/consult/tokens/${S.token.id}/start`)).then((r) => { S.visitId = r.body.data.id })
    cy.then(() => put(`/clinic/consult/encounters/${S.visitId}/rx`, { lines: [line(S.medA, MED_A, 6), line(S.medB, MED_B, 4)] }))
      .its('body.success').should('eq', true)
    cy.then(() => post(`/clinic/consult/encounters/${S.visitId}/rx/submit`)).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      S.rxId = r.body.data.rxId
    })
  })

  after(() => {
    relogin(DOCTOR, 'doctor')
    if (S.visitId) post(`/clinic/consult/encounters/${S.visitId}/complete`)
    relogin(OWNER)
    cy.then(() => { if (S.rxId) post('/cancelPrescription', { prescriptionId: S.rxId, reason: 'cypress cleanup' }) })
    if (S.patient) post(`/clinic/patients/${S.patient.id}/retire`, { reason: 'cypress cleanup' })
    setOf(PHARMACIST, 'Standard')
    setOf(DOCTOR, 'Administrator')
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  it('B-01 [L-3] the pharmacist changes the prescription with a reason; History shows each line before → after', () => {
    relogin(PHARMACIST)
    openPrescriptions()
    cy.get(`#prescriptionBody tr[data-rx-id="${S.rxId}"]`).find('.rx-change').click()
    cy.get('#rxPanel').should('be.visible')
    cy.get('#rxAmendBody tr').should('have.length', 2)
    // 1. a dose and a quantity changed, a medicine added
    cy.contains('#rxAmendBody tr', MED_A).find('input[data-f="dosage"]').clear().type('1+0+1')
    cy.contains('#rxAmendBody tr', MED_B).find('input[data-f="quantity"]').clear().type('2')
    cy.get('#rxAmendMedicines option', { timeout: 20000 }).should('have.length.greaterThan', 1)
    cy.get('#rxAmendMedicine').type(MED_C)
    cy.get('#rxAmendQty').type('5')
    cy.get('#rxAmendAdd').click()
    cy.get('#rxAmendBody tr').should('have.length', 3)
    cy.get('#rxPanelReason').type('Calpol short today; Brufen added after speaking to the doctor')
    cy.screenshot('hms-h3b/B-01-1-change-with-reason', { capture: 'viewport' })
    cy.intercept('POST', '**/amendPrescription').as('amend')
    cy.get('#rxAmendSave').click()
    cy.wait('@amend').its('response.body.success').should('eq', true)
    rx().then((p) => {
      const by = Object.fromEntries(p.items.map((i) => [i.medicineName, i]))
      expect(by[MED_A].dosage).to.eq('1+0+1')
      expect(by[MED_B].quantity).to.eq(2)
      expect(by[MED_C].quantity).to.eq(5)
    })
    // 2. History: who changed what, why
    cy.get(`#prescriptionBody tr[data-rx-id="${S.rxId}"]`).find('.rx-history-link').click()
    cy.get('#rxHistoryPanel').should('be.visible')
      .and('contain', 'Calpol short today')
      .and('contain', MED_A + ' changed')
      .and('contain', MED_C + ' added')
    cy.screenshot('hms-h3b/B-01-2-history', { capture: 'viewport' })
  })

  it('B-02 [L-3] a reason is required; a medicine outside the catalogue is refused; a stale screen is refused', () => {
    relogin(PHARMACIST)
    rx().then((p) => {
      const items = p.items.map((i) => ({ id: i.id, productId: i.productId, medicineName: i.medicineName, quantity: i.quantity, dosage: i.dosage }))
      post('/amendPrescription', { prescriptionId: S.rxId, reason: ' ', amendmentsSeen: 1, items })
        .its('body.message').should('contain', 'Say why')
      post('/amendPrescription', { prescriptionId: S.rxId, reason: 'x', amendmentsSeen: 1,
                                   items: items.concat([{ productId: 987654321, medicineName: 'Ghost Syrup', quantity: 1 }]) })
        .its('body.message').should('contain', "'Ghost Syrup' is not in this pharmacy's list")
      post('/amendPrescription', { prescriptionId: S.rxId, reason: 'x', amendmentsSeen: 0, items })
        .its('body.message').should('contain', 'changed on another screen')
    })
  })

  // A change the PHARMACIST would be allowed to make (B-01 made one): only who asks can refuse it.
  const validChange = () => rx().then((p) => ({
    prescriptionId: S.rxId, reason: 'role check', amendmentsSeen: 1,
    items: p.items.map((i) => ({ id: i.id, productId: i.productId, medicineName: i.medicineName, quantity: i.quantity,
                                 dosage: i.medicineName === MED_A ? 'ROLE-CHECK' : i.dosage, frequency: i.frequency, duration: i.duration })),
  }))
  const unchanged = () => rx().then((p) => {
    expect(p.status, 'not cancelled').to.not.eq('CANCELLED')
    expect(p.items.find((i) => i.medicineName === MED_A).dosage, 'not changed').to.eq('1+0+1')
  })

  it('B-04 [L-3] the front desk on Clinic reception sees the list but cannot dispense, change or cancel', () => {
    relogin(OWNER)
    setOf(PHARMACIST, 'Clinic reception')
    relogin(PHARMACIST, 'desk')   // a fresh token: the set's codes (no rx.dispense) ride in it
    openPrescriptions()
    cy.get(`#prescriptionBody tr[data-rx-id="${S.rxId}"]`).should('contain', 'with the pharmacist').and('not.contain', 'Dispense')
      .find('.rx-change').should('not.exist')
    cy.screenshot('hms-h3b/B-04-1-front-desk-read-only', { capture: 'viewport' })
    validChange().then((body) => post('/amendPrescription', body)).its('body.success').should('not.eq', true)
    post('/cancelPrescription', { prescriptionId: S.rxId, reason: 'role check' }).its('body.success').should('not.eq', true)
    unchanged()   // the proof: a valid change and a valid cancel were both refused
  })

  it('B-05 [L-3] the doctor who submitted it cannot change or cancel it', () => {
    relogin(DOCTOR, 'doctor')
    validChange().then((body) => post('/amendPrescription', body)).its('body.success').should('not.eq', true)
    post('/cancelPrescription', { prescriptionId: S.rxId, reason: 'role check' }).its('body.success').should('not.eq', true)
    relogin(OWNER)
    unchanged()
  })

  it("B-03 [L-3] cancelling the doctor's prescription needs a reason; History shows it", () => {
    relogin(OWNER)
    setOf(PHARMACIST, 'Standard')   // back to the pharmacist (B-04 moved them)
    relogin(PHARMACIST, 'pharmacist-again')
    post('/cancelPrescription', { prescriptionId: S.rxId }).its('body.message').should('contain', 'the doctor sees it')
    openPrescriptions()
    cy.get(`#prescriptionBody tr[data-rx-id="${S.rxId}"]`).contains('button', 'Cancel').click()
    cy.get('#rxPanel').should('be.visible')
    cy.get('#rxAmendBox').should('not.be.visible')
    cy.get('#rxPanelReason').type('Patient chose to buy elsewhere')
    cy.intercept('POST', '**/cancelPrescription').as('cancel')
    cy.get('#rxCancelConfirm').click()
    cy.wait('@cancel').its('response.body.success').should('eq', true)
    rx().its('status').should('eq', 'CANCELLED')
    cy.get(`#prescriptionBody tr[data-rx-id="${S.rxId}"]`).find('.rx-history-link').click()
    cy.get('#rxHistoryPanel').should('contain', 'Cancelled').and('contain', 'Patient chose to buy elsewhere')
    cy.screenshot('hms-h3b/B-03-1-cancelled-with-reason', { capture: 'viewport' })
  })

})
