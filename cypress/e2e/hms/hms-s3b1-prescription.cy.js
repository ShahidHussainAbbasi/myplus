/**
 * HMS S3b-1 — the doctor writes the prescription in the consultation and Submits it; the pharmacy finds it by the
 * token, marked as the doctor's, and Dispense sells it to the patient. Park / resume on screen.
 *
 * Design: microservices/docs/hms-phase1-design.md §4d. SLICE GATE — R-01 and R-02 drive the real screens end to end
 * (B-01: doctor → pharmacy → sale, nothing re-typed).
 *
 * Roles: owner.pharma@ = reception and setup · admin.pharma@ = the DOCTOR (moved onto the built-in "Doctor" set in
 * before(), restored to "Administrator" in after()) · user.pharma@ = the pharmacist / front desk (no clinic.consult).
 *
 * Server state changed and put back in after(): admin.pharma's set → Administrator; clinic switch → reset; a visit
 * left with the doctor → completed; waiting tokens → cancelled; prescriptions not dispensed → cancelled; the patient → retired. The sale (R-02) is a ledger
 * fact and stays, as in B-04 / L-04. Visits, notes and the doctor's lines are clinical records and stay.
 *
 * Run (headless records reliably):
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-s3b1-prescription.cy.js"
 */
describe('HMS S3b-1 — the doctor\'s prescription reaches the pharmacy', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const DOCTOR = 'admin.pharma@myplus.com'
  const PHARMACIST = 'user.pharma@myplus.com'
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  const phone = '036' + run.slice(-6) + String(Math.floor(Math.random() * 90) + 10).slice(-2)
  const MED_A = 'S3b Amoxil ' + run
  const MED_B = 'S3b Panadol ' + run
  const S = { tokens: [], rxIds: [] }

  const relogin = (email, phase = 'on') => cy.loginAs(email, PW, '/getBusinessDashboardStats', `hms-s3b1-${run}-${phase}`)
  const post = (url, body) =>
    cy.request({ method: 'POST', url, body: body || {}, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
  const put = (url, body) =>
    cy.request({ method: 'PUT', url, body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
  const setOf = (email, setName) => cy.request('/team/users').then((u) => {
    const member = ((u.body && (u.body.data || u.body.object)) || []).find((x) => String(x.email || '') === email)
    expect(member, email + ' is a member').to.exist
    return cy.request('/team/permissions').then((p) => {
      const set = (((p.body && (p.body.data || p.body.object)) || {}).sets || []).find((s) => s.name === setName)
      expect(set, 'the "' + setName + '" set exists').to.exist
      return post('/team/permissions/assign', { userId: member.userId || member.id, setId: set.id })
        .its('body').then((b) => expect(b && (b.success === true || b.status === 'SUCCESS'), JSON.stringify(b)).to.eq(true))
    })
  })
  const issueToken = () => post('/clinic/tokens', { patientId: S.patient.id, providerId: S.doctor.id }).then((t) => {
    expect(t.body.success, JSON.stringify(t.body)).to.eq(true)
    S.tokens.push(t.body.data.id)
    return t.body.data
  })
  // A started visit through the API (the doctor's session), for the cases that are not about the screen.
  const startedVisit = () => {
    relogin(OWNER)
    return issueToken().then((t) => {
      relogin(DOCTOR, 'doctor')
      post(`/clinic/tokens/${t.id}/call`).its('body.success').should('eq', true)
      return post(`/clinic/consult/tokens/${t.id}/start`).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        return r.body.data
      })
    })
  }
  const line = (productId, medicineName, quantity) => ({ productId, medicineName, quantity: String(quantity), dosage: '1 tab', frequency: 'TDS', duration: '3 days' })

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms s3b-1 gate')
    relogin(OWNER, 'pre')
    cy.setCapability('clinic', true)
    setOf(DOCTOR, 'Doctor')
    relogin(OWNER)
    post('/clinic/patients', { phone, name: 'S3b Patient ' + run, dateOfBirth: '1979-11-02', sex: 'F' }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.customerId, 'customer made at reception').to.be.a('number')
      S.patient = r.body.data
    })
    cy.request('/clinic/doctors').then((r) => {
      const d = (r.body.data || []).find((x) => x.name === 'Dr Ahmed (gate)')
      if (d) { S.doctor = d; return }
      return post('/clinic/doctors', { name: 'Dr Ahmed (gate)', speciality: 'General medicine', dailyLimit: null })
        .then((c) => { S.doctor = c.body.data })
    })
    // Two medicines in stock — the doctor chooses them from the pharmacy's own list.
    cy.seedProduct({ name: MED_A, unit: 'capsule', stock: 50, sellingPrice: 12 }).then(({ productId }) => { S.medA = productId })
    cy.seedProduct({ name: MED_B, unit: 'tablet', stock: 50, sellingPrice: 3 }).then(({ productId }) => { S.medB = productId })
    cy.then(() => issueToken()).then((t) => { S.first = t })
  })

  after(() => {
    // a visit with the doctor cannot be cancelled (only waiting / called can): the doctor completes the ones started
    relogin(DOCTOR, 'doctor')
    if (S.parked) post(`/clinic/consult/encounters/${S.parked.id}/complete`)
    relogin(OWNER)
    S.rxIds.forEach((id) => cy.request({ url: `/getPrescription?id=${id}`, failOnStatusCode: false }).then((r) => {
      if (r.body && r.body.data && r.body.data.status !== 'FULLY_DISPENSED') post('/cancelPrescription', { prescriptionId: id })
    }))
    S.tokens.forEach((id) => post(`/clinic/tokens/${id}/cancel`, { reason: 'cypress cleanup' }))
    if (S.patient) post(`/clinic/patients/${S.patient.id}/retire`, { reason: 'cypress cleanup' })
    setOf(DOCTOR, 'Administrator')
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  // ── R-01 — the doctor, on the real screen ──────────────────────────────────────────────────────────
  it('R-01 [B-01, 07] the doctor adds two medicines from the pharmacy\'s list and submits them', () => {
    relogin(DOCTOR, 'doctor')
    cy.visit('/clinicDashboard')
    cy.get('#clinNavMyQueue').click()
    cy.get('#clinMyDoctor').select(String(S.doctor.id))
    cy.contains('#clinQueueBody tr', S.first.tokenLabel).find('.clin-q-call').click()
    cy.contains('#clinQueueBody tr', S.first.tokenLabel).find('.clin-q-start').click()
    cy.get('#clinIdBanner').should('contain', S.patient.name)
    cy.get('#clinComplaint').type('Sore throat, fever')
    cy.get('#clinSaveVitals').click()
    cy.get('#clinRxEmpty').should('be.visible')
    // 1. A medicine typed but not in the list is refused, in words.
    cy.get('#clinRxMedicine').type('Not a real medicine ' + run)
    cy.get('#clinRxQty').type('5')
    cy.get('#clinRxAddLine').click()
    cy.get('#clinMsg').should('be.visible').and('contain', 'is not in the pharmacy')
    // 2. Two medicines from the list (the datalist offers them as the doctor types).
    cy.get('#clinRxMedicines option').should('have.length.greaterThan', 1)
    cy.get('#clinRxMedicine').clear().type(MED_A)
    cy.get('#clinRxQty').clear().type('10')
    cy.get('#clinRxDose').type('1 cap')
    cy.get('#clinRxFreq').type('TDS')
    cy.get('#clinRxDays').type('5 days')
    cy.get('#clinRxAddLine').click()
    cy.get('#clinRxMedicine').type(MED_B)
    cy.get('#clinRxQty').type('6')
    cy.get('#clinRxDose').type('1 tab')
    cy.get('#clinRxFreq').type('SOS')
    cy.get('#clinRxDays').type('3 days{enter}')       // Enter in the last box adds the line
    cy.get('#clinRxBody tr').should('have.length', 2)
    cy.screenshot('hms-s3b1/R-01-1-prescription-written', { capture: 'viewport' })
    // 3. Submit → confirm.
    cy.intercept('POST', '**/clinic/consult/encounters/*/rx/submit').as('submit')
    cy.get('#clinRxSubmit').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@submit').then(({ response }) => {
      expect(response.body.success, JSON.stringify(response.body)).to.eq(true)
      expect(response.body.data.rxId).to.be.a('number')
      expect(response.body.message).to.contain(S.first.tokenLabel)
      S.encounterId = response.body.data.id
      S.rxId = response.body.data.rxId
      S.rxIds.push(S.rxId)
    })
    // EXPECTED: sent, with the token; the list is locked (no Add, no Save, no Remove).
    cy.get('#clinRxSent').should('be.visible').and('contain', 'Sent to the pharmacy').and('contain', S.first.tokenLabel)
    cy.get('#clinRxAdd').should('not.be.visible')
    cy.get('#clinRxActions').should('not.be.visible')
    cy.get('#clinRxBody .clin-rx-remove').should('not.exist')
    cy.screenshot('hms-s3b1/R-01-2-sent-to-pharmacy', { capture: 'viewport' })
    // the visit is completed after the prescription, as a doctor does
    cy.get('#clinComplete').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('#clinStatus').should('have.attr', 'data-status', 'COMPLETED')
  })

  // ── R-02 — the pharmacy, on the real screen ────────────────────────────────────────────────────────
  it('R-02 [B-01, 07] the pharmacist types the token, sees "From the doctor", and Dispense sells it to the patient', () => {
    relogin(PHARMACIST)
    cy.intercept('GET', '**/searchPrescriptions*').as('search')
    cy.intercept('POST', '**/dispensePrescription').as('dispense')
    cy.visit('/businessDashboard')
    cy.get('#snavPharmacy > .snav-btn').click()
    cy.get('#snavPharmacy a[onclick^="showPrescriptions"]').click()
    cy.wait('@search')
    cy.get('#rxSearch').type(S.first.tokenLabel + '{enter}')
    cy.wait('@search')
    // EXPECTED: found AT ONCE (the person id was set by the Submit, not left to the background bridge), marked.
    cy.get(`#prescriptionBody tr[data-rx-id="${S.rxId}"]`).should('contain', 'PENDING')
      .find('.rx-from-doctor').should('contain', 'From the doctor').and('contain', S.first.tokenLabel)
    cy.get(`#prescriptionBody tr[data-rx-id="${S.rxId}"]`).should('contain', 'Dr Ahmed (gate)')
    cy.screenshot('hms-s3b1/R-02-1-pharmacy-finds-it-by-token', { capture: 'viewport' })
    // Dispense: both medicines in the cart and the patient as the customer — nothing re-typed.
    cy.get(`#prescriptionBody tr[data-rx-id="${S.rxId}"]`).contains('button', 'Dispense').click()
    cy.get('#sellDiv').should('be.visible')
    cy.get('#dispenseBanner').should('be.visible').and('contain', S.patient.name)
    cy.get('#tablesi tbody tr', { timeout: 20000 }).should('have.length', 2)
    cy.get('#tablesi tbody').should('contain', MED_A).and('contain', MED_B)
    cy.get('#sellCN').should('have.value', S.patient.name)
    cy.screenshot('hms-s3b1/R-02-2-sale-filled', { capture: 'viewport' })
    cy.get('#sellPayMethod').select('CASH', { force: true })
    cy.get('#sellRec').clear().type('1000')
    cy.clickAndConfirmSale('#addSell')   // waits for the dialog OR the sale: never races it
    cy.wait('@sale', { timeout: 30000 }).then(({ request, response }) => {
      expect(request.body.customer.customerId, 'the patient\'s customer, by id').to.eq(S.patient.customerId)
      expect(String(response.body.status), JSON.stringify(response.body).slice(0, 200)).to.eq('SUCCESS')
    })
    cy.wait('@dispense', { timeout: 30000 }).its('response.body.data.status').should('eq', 'FULLY_DISPENSED')
  })

  // ── R-03 — one prescription, however often Submit is pressed ───────────────────────────────────────
  it('R-03 [07c] a second Submit returns the same prescription; the lines cannot change once sent', () => {
    relogin(DOCTOR, 'doctor')
    const url = `/clinic/consult/encounters/${S.encounterId}`
    post(url + '/rx/submit').then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.rxId, 'the same prescription').to.eq(S.rxId)
    })
    put(url + '/rx', { lines: [line(S.medA, MED_A, 99)] }).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.contain('with the pharmacy already')
    })
    relogin(PHARMACIST)
    cy.request(`/searchPrescriptions?q=${encodeURIComponent(S.patient.mrn)}`).then((r) => {
      const mine = (r.body.data.items || []).filter((p) => p.encounterId === S.encounterId)
      expect(mine.map((p) => p.id), 'exactly one prescription for the visit').to.deep.eq([S.rxId])
    })
  })

  // ── R-04 — park for a test, come back ──────────────────────────────────────────────────────────────
  it('R-04 [06b] the doctor parks the visit for a test; the pharmacy sees nothing; Open brings the patient back', () => {
    startedVisit().then((v) => {
      S.parked = v
      put(`/clinic/consult/encounters/${v.id}/rx`, { lines: [line(S.medB, MED_B, 4)] }).its('body.success').should('eq', true)
    })
    cy.visit('/clinicDashboard')
    cy.get('#clinNavMyQueue').click()
    cy.get('#clinMyDoctor').select(String(S.doctor.id))
    cy.then(() => cy.contains('#clinQueueBody tr', S.parked.tokenLabel).find('.clin-q-start').click())
    cy.get('#clinRxBody tr').should('have.length', 1).and('contain', MED_B)
    // 1. Park needs a reason.
    cy.get('#clinPark').click()
    cy.get('#clinParkReason').should('be.focused')
    cy.get('#clinParkReason').type('CBC pending')
    cy.get('#clinPark').click()
    cy.then(() => cy.contains('#clinQueueBody tr', S.parked.tokenLabel).should('contain', 'Parked').and('contain', 'CBC pending'))
    cy.screenshot('hms-s3b1/R-04-1-parked-in-queue', { capture: 'viewport' })
    // 2. EXPECTED: the pharmacy has nothing for this visit (only Submit sends).
    relogin(PHARMACIST)
    cy.then(() => cy.request(`/searchPrescriptions?q=${encodeURIComponent(S.patient.mrn)}`)).then((r) => {
      expect((r.body.data.items || []).filter((p) => p.encounterId === S.parked.id), 'nothing for the parked visit').to.have.length(0)
    })
    // 3. Open → resumed; the medicine is still there.
    relogin(DOCTOR, 'doctor')
    cy.visit('/clinicDashboard')
    cy.get('#clinNavMyQueue').click()
    cy.get('#clinMyDoctor').select(String(S.doctor.id))
    cy.then(() => cy.contains('#clinQueueBody tr', S.parked.tokenLabel).find('.clin-q-start').click())
    cy.get('#clinStatus').should('have.attr', 'data-status', 'IN_CONSULTATION')
    cy.get('#clinRxBody tr').should('have.length', 1).and('contain', MED_B)
  })

  // ── R-05 / R-06 — what is refused ──────────────────────────────────────────────────────────────────
  it('R-05 each line is checked on the server: a medicine outside the list, a zero quantity, the same medicine twice', () => {
    relogin(DOCTOR, 'doctor')
    const url = `/clinic/consult/encounters/${S.parked.id}/rx`
    put(url, { lines: [line(null, 'Typed by hand', 5)] }).its('body.message').should('contain', 'not from the pharmacy')
    put(url, { lines: [line(S.medA, MED_A, 0)] }).its('body.message').should('contain', 'between 1 and 10000')
    put(url, { lines: [line(S.medA, MED_A, 1), line(S.medA, MED_A, 2)] }).its('body.message').should('contain', 'twice')
    post(`/clinic/consult/encounters/${S.parked.id}/rx/submit`).then((r) => {
      // the parked visit's one valid saved line (R-04) is still what Submit would send — nothing above replaced it
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.rxLines.map((l) => l.medicineName)).to.deep.eq([MED_B])
      S.rxIds.push(r.body.data.rxId)
    })
  })

  it('R-06 [M-15] the front desk cannot write or submit a prescription', () => {
    relogin(PHARMACIST)
    put(`/clinic/consult/encounters/${S.parked.id}/rx`, { lines: [line(S.medA, MED_A, 1)] }).then((r) => {
      expect(r.status).to.eq(403)
      expect(r.body.message).to.contain('Only a doctor')
    })
    post(`/clinic/consult/encounters/${S.parked.id}/rx/submit`).then((r) => {
      expect(r.status).to.eq(403)
      expect(r.body.message).to.contain('Only a doctor')
    })
  })
  it('R-07 the counter form cannot pass its prescription off as the doctor\'s (a visit reference needs clinic.consult)', () => {
    relogin(PHARMACIST)
    const counter = {
      patientName: S.patient.name, patientPhone: phone, doctorName: 'Dr Counter',
      items: [{ productId: S.medA, medicineName: MED_A, quantity: 1, dosage: '1 cap', frequency: 'OD', duration: '1 day' }],
    }
    // 1. CONTROL: the pharmacist records an ordinary counter prescription — accepted, marked COUNTER.
    //    Without this, a refusal below could be "the pharmacist may not record prescriptions at all" (pharma
    //    answers every 403 with the same "Access denied"), and the case would prove nothing.
    post('/addPrescription', counter).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.source).to.eq('COUNTER')
      S.rxIds.push(r.body.data.id)
    })
    // 2. The SAME prescription claiming a visit (reference, token, person) — refused; the only difference is the claim.
    post('/addPrescription', { ...counter, externalRef: 'enc-999999', tokenLabel: S.first.tokenLabel, partyId: S.patient.partyId }).then((r) => {
      if (r.body.data && r.body.data.id) S.rxIds.push(r.body.data.id)   // cleanup if it ever got through
      expect(r.body.success, JSON.stringify(r.body)).to.not.eq(true)
      expect(r.body.message).to.eq('Access denied')
    })
  })
})
