/**
 * HMS S4 — the pharmacist dispenses the DOCTOR'S prescription, adjusts it on the till and completes the sale; what is
 * not sold today stays on the script for another day. Case 08 of the Phase 1 plan.
 *
 * Built on RX-FILL (pharma.js fillCartFromRx / rxFillNote, business.js +/−, built 2026-10-09 by another session):
 * this slice adds NO dispense code — it proves the doctor's path (S3b-1) goes through it, end to end, twice.
 *
 * Roles: owner.pharma@ = reception and setup · admin.pharma@ = the DOCTOR (on the "Doctor" set in before(), restored
 * to "Administrator" in after()) · user.pharma@ = the pharmacist.
 *
 * Server state changed and put back in after(): the visit → completed; the patient → retired; admin.pharma's set →
 * Administrator; clinic switch → reset. The two sales are ledger facts and stay (as in B-04 / L-04 / R-02); the
 * prescription ends FULLY_DISPENSED by the second sale, so there is nothing to cancel.
 *
 * Run (headless records reliably):
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-s4-dispense-adjust.cy.js"
 */
describe('HMS S4 — dispense the doctor\'s prescription, adjust, and the rest another day', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const DOCTOR = 'admin.pharma@myplus.com'
  const PHARMACIST = 'user.pharma@myplus.com'
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  const phone = '034' + run.slice(-6) + String(Math.floor(Math.random() * 90) + 10).slice(-2)
  const MED_A = 'S4d Augmentin ' + run
  const MED_B = 'S4d Calpol ' + run
  const S = {}

  const relogin = (email, phase = 'on') => cy.loginAs(email, PW, '/getBusinessDashboardStats', `hms-s4-${run}-${phase}`)
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
  const line = (productId, medicineName, quantity) => ({ productId, medicineName, quantity: String(quantity), dosage: '1 tab', frequency: 'TDS', duration: '2 days' })
  // The pharmacist finds the script by the token and presses Dispense (the real screen).
  const dispenseByToken = () => {
    // Wait for each list draw (the recent list, then the search result) before touching a row: the list is redrawn
    // between them, and a row found in the first draw is detached by the second (D4-02, first run).
    cy.intercept('GET', '**/searchPrescriptions*').as('search')
    cy.visit('/businessDashboard')
    cy.get('#snavPharmacy > .snav-btn').click()
    cy.get('#snavPharmacy a[onclick^="showPrescriptions"]').click()
    cy.wait('@search')
    cy.get('#rxSearch').type(S.token.tokenLabel + '{enter}')
    cy.wait('@search')
    cy.get(`#prescriptionBody tr[data-rx-id="${S.rxId}"]`).should('be.visible')
    cy.get(`#prescriptionBody tr[data-rx-id="${S.rxId}"]`).contains('button', 'Dispense').click()
    cy.get('#sellDiv').should('be.visible')
    cy.get('#dispenseBanner').should('be.visible').and('contain', S.patient.name)
  }
  const completeCash = () => {
    cy.intercept('POST', '**/dispensePrescription').as('dispense')
    cy.get('#sellPayMethod').select('CASH', { force: true })
    cy.get('#sellRec').clear().type('1000')
    cy.clickAndConfirmSale('#addSell')   // waits for the dialog OR the sale: never races it
    cy.wait('@sale', { timeout: 30000 }).then(({ request, response }) => {
      expect(String(response.body.status), JSON.stringify(response.body).slice(0, 200)).to.eq('SUCCESS')
      expect(request.body.customer.customerId, "the patient's customer, by id").to.eq(S.patient.customerId)
    })
  }

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms s4 gate')
    relogin(OWNER, 'pre')
    cy.setCapability('clinic', true)
    setOf(DOCTOR, 'Doctor')
    relogin(OWNER)
    post('/clinic/patients', { phone, name: 'S4d Patient ' + run }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      S.patient = r.body.data
    })
    cy.request('/clinic/doctors').then((r) => {
      const d = (r.body.data || []).find((x) => x.name === 'Dr Ahmed (gate)')
      if (d) { S.doctor = d; return }
      return post('/clinic/doctors', { name: 'Dr Ahmed (gate)', speciality: 'General medicine', dailyLimit: null })
        .then((c) => { S.doctor = c.body.data })
    })
    cy.seedProduct({ name: MED_A, unit: 'tablet', stock: 50, sellingPrice: 20 }).then(({ productId }) => { S.medA = productId })
    cy.seedProduct({ name: MED_B, unit: 'tablet', stock: 50, sellingPrice: 4 }).then(({ productId }) => { S.medB = productId })
    // The doctor's part (S3b-1, proven on screen there): call, start, two medicines, Submit.
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
    cy.request({ url: `/getPrescription?id=${S.rxId}`, failOnStatusCode: false }).then((r) => {
      if (r.body && r.body.data && r.body.data.status !== 'FULLY_DISPENSED') post('/cancelPrescription', { prescriptionId: S.rxId })
    })
    if (S.patient) post(`/clinic/patients/${S.patient.id}/retire`, { reason: 'cypress cleanup' })
    setOf(DOCTOR, 'Administrator')
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  // ── D4-01 — case 08, on the real screen ────────────────────────────────────────────────────────────
  it('D4-01 [08] Dispense fills the sale; − once on the first medicine; the banner says 1 is left; the script is partly dispensed', () => {
    relogin(PHARMACIST)
    dispenseByToken()
    // 1. Filled: both medicines at the prescribed quantities, the patient as the customer — nothing typed.
    cy.get('#tablesi tbody tr', { timeout: 20000 }).should('have.length', 2)
    cy.get('#tablesi tbody').should('contain', MED_A).and('contain', MED_B)
    cy.get('#sellCN').should('have.value', S.patient.name)
    cy.get('#dispenseFillNote').should('contain', MED_A + ': 6 left on the script · 6 in this sale')
    cy.screenshot('hms-s4/D4-01-1-filled-from-the-doctor', { capture: 'viewport' })
    // 2. The patient takes one less of the first medicine today: −.
    cy.contains('#tablesi tbody tr', MED_A).find('.ctr-step[data-d="-1"]').click()
    cy.get('#dispenseFillNote').should('contain', MED_A + ': 6 left on the script · 5 in this sale — 1 left for another day')
    cy.get('#dispenseFillNote').should('not.contain', MED_B + ': 4 left on the script · 4 in this sale —')
    cy.screenshot('hms-s4/D4-01-2-one-left-for-another-day', { capture: 'viewport' })
    // 3. Complete → the sale; the script records what was sold and stays open for the rest.
    completeCash()
    cy.wait('@dispense', { timeout: 30000 }).its('response.body.data.status').should('eq', 'PARTIALLY_DISPENSED')
    cy.request(`/getPrescription?id=${S.rxId}`).its('body.data').then((rx) => {
      const byName = Object.fromEntries(rx.items.map((i) => [i.medicineName, i]))
      expect(byName[MED_A].dispensedQuantity, MED_A).to.eq(5)
      expect(byName[MED_B].dispensedQuantity, MED_B).to.eq(4)
    })
  })

  it('D4-02 [08] another day: Dispense again fills ONLY what is still owed — 1 of the first medicine — and completes the script', () => {
    relogin(PHARMACIST)
    dispenseByToken()
    cy.get('#tablesi tbody tr', { timeout: 20000 }).should('have.length', 1).and('contain', MED_A).and('not.contain', MED_B)
    cy.get('#dispenseFillNote').should('contain', MED_A + ': 1 left on the script · 1 in this sale')
    cy.screenshot('hms-s4/D4-02-1-only-what-is-owed', { capture: 'viewport' })
    completeCash()
    cy.wait('@dispense', { timeout: 30000 }).its('response.body.data.status').should('eq', 'FULLY_DISPENSED')
  })

  it('D4-03 [07c] the pharmacist changed the SALE, never the doctor\'s prescription', () => {
    relogin(DOCTOR, 'doctor')
    cy.request(`/clinic/consult/encounters/${S.visitId}`).its('body.data').then((v) => {
      expect(v.rxId).to.eq(S.rxId)
      expect(v.rxLines.map((l) => [l.medicineName, l.quantity])).to.deep.eq([[MED_A, '6'], [MED_B, '4']])
    })
    relogin(PHARMACIST)
    cy.request(`/getPrescription?id=${S.rxId}`).its('body.data').then((rx) => {
      const byName = Object.fromEntries(rx.items.map((i) => [i.medicineName, i]))
      expect(byName[MED_A].quantity, 'prescribed, unchanged').to.eq(6)
      expect(byName[MED_A].dispensedQuantity, 'sold over two days').to.eq(6)
      expect(rx.source).to.eq('DOCTOR')
    })
  })
})
