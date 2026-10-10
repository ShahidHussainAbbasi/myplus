/**
 * HMS S3a — the doctor's workspace: My Queue, call, identity before anything clinical, vitals (range-checked),
 * append-only notes, complete, and the patient's earlier visits. Reception cannot read any of it (M-15).
 *
 * Design: microservices/docs/hms-phase1-design.md §4c. SLICE GATE — D-01 drives the real screen end to end.
 *
 * Roles: owner.pharma@ = reception and setup · admin.pharma@ = the DOCTOR (moved onto the built-in "Doctor" permission
 * set in before(), restored to "Administrator" in after() — a restore in a hook, never at the end of a case, per
 * permission-sets.cy.js) · user.pharma@ = the front desk (no clinic.consult) · owner.business@ = another organisation.
 *
 * Server state changed and put back in after(): admin.pharma's set → Administrator; clinic switch → reset; live
 * tokens → cancelled; the patient → retired. Encounters and notes are clinical records and stay (append-only).
 *
 * Run (headless records reliably):
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-s3a-doctor.cy.js"
 */
describe('HMS S3a — the doctor\'s workspace', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const DOCTOR = 'admin.pharma@myplus.com'
  const FRONT_DESK = 'user.pharma@myplus.com'
  const OTHER = 'owner.business@myplus.com'
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  const phone = '037' + run.slice(-6) + String(Math.floor(Math.random() * 90) + 10).slice(-2)
  const S = { tokens: [] }

  const relogin = (email, phase = 'on') => cy.loginAs(email, PW, '/getBusinessDashboardStats', `hms-s3a-${run}-${phase}`)
  const post = (url, body) =>
    cy.request({ method: 'POST', url, body: body || {}, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
  const put = (url, body) =>
    cy.request({ method: 'PUT', url, body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
  const setOf = (email, setName) => cy.request('/team/users').then((u) => {
    const member = ((u.body && (u.body.data || u.body.object)) || []).find((x) => String(x.email || '') === email)
    expect(member, email + ' is a member').to.exist
    return cy.request('/team/permissions').then((p) => {
      const set = (((p.body && (p.body.data || p.body.object)) || {}).sets || []).find((s) => s.name === setName)
      expect(set, 'the "' + setName + '" set exists (auth-service V20 for Doctor)').to.exist
      return post('/team/permissions/assign', { userId: member.userId || member.id, setId: set.id })
        .its('body').then((b) => expect(b && (b.success === true || b.status === 'SUCCESS'), JSON.stringify(b)).to.eq(true))
    })
  })
  const issueToken = () => post('/clinic/tokens', { patientId: S.patient.id, providerId: S.doctor.id }).then((t) => {
    expect(t.body.success, JSON.stringify(t.body)).to.eq(true)
    S.tokens.push(t.body.data.id)
    return t.body.data
  })

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms s3a gate')
    relogin(OWNER, 'pre')
    cy.setCapability('clinic', true)
    setOf(DOCTOR, 'Doctor')
    relogin(OWNER)
    post('/clinic/patients', { phone, name: 'S3 Patient ' + run, dateOfBirth: '1988-03-14', sex: 'M' }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      S.patient = r.body.data
    })
    cy.request('/clinic/doctors').then((r) => {
      const d = (r.body.data || []).find((x) => x.name === 'Dr Ahmed (gate)')
      if (d) { S.doctor = d; return }
      return post('/clinic/doctors', { name: 'Dr Ahmed (gate)', speciality: 'General medicine', dailyLimit: null })
        .then((c) => { S.doctor = c.body.data })
    })
    cy.then(() => issueToken()).then((t) => { S.first = t })
  })

  after(() => {
    // a visit with the doctor cannot be cancelled (only waiting / called can): the doctor completes D-03's
    relogin(DOCTOR, 'doctor')
    if (S.secondVisit) post(`/clinic/consult/encounters/${S.secondVisit}/complete`)
    relogin(OWNER)
    S.tokens.forEach((id) => post(`/clinic/tokens/${id}/cancel`, { reason: 'cypress cleanup' }))
    if (S.patient) post(`/clinic/patients/${S.patient.id}/retire`, { reason: 'cypress cleanup' })
    setOf(DOCTOR, 'Administrator')
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  // ── D-01 — the real screen, end to end ─────────────────────────────────────────────────────────────
  it('D-01 the doctor calls the patient, sees who it is, records vitals and a note, and completes the visit', () => {
    relogin(DOCTOR, 'doctor')     // a fresh token: the Doctor set's clinic.consult rides in it
    cy.visit('/clinicDashboard')
    // 1. My Queue (shown to a doctor only), this doctor's line.
    cy.get('#clinNavMyQueue').should('be.visible').click()
    cy.get('#clinMyDoctor').select(String(S.doctor.id))
    cy.contains('#clinQueueBody tr', S.first.tokenLabel).should('contain', 'Waiting')
    // 2. Call the patient → Called; Start.
    cy.contains('#clinQueueBody tr', S.first.tokenLabel).find('.clin-q-call').click()
    cy.contains('#clinQueueBody tr', S.first.tokenLabel).should('contain', 'Called')
    cy.intercept('POST', '**/clinic/consult/tokens/*/start').as('start')
    cy.contains('#clinQueueBody tr', S.first.tokenLabel).find('.clin-q-start').click()
    cy.wait('@start').its('response.body.success').should('eq', true)
    // 3. EXPECTED: who it is, before anything clinical — name, MRN, date of birth.
    cy.get('#clinIdBanner').should('be.visible').and('contain', S.patient.name).and('contain', S.patient.mrn).and('contain', '1988-03-14')
    cy.get('#clinStatus').should('have.attr', 'data-status', 'IN_CONSULTATION').and('contain', 'With doctor')
    cy.screenshot('hms-s3a/D-01-1-identity-first', { capture: 'viewport' })
    // 4. Complaint and vitals → Save.
    cy.get('#clinComplaint').clear().type('Fever 3 days')
    cy.get('#clinVitalBp').type('120/80')
    cy.get('#clinVitalPulse').type('78')
    cy.get('#clinVitalTemp').type('101.2')
    cy.get('#clinVitalSpo2').type('97')
    cy.intercept('PUT', '**/clinic/consult/encounters/*').as('save')
    cy.get('#clinSaveVitals').click()
    cy.wait('@save').then(({ response }) => {
      expect(response.body.success, JSON.stringify(response.body)).to.eq(true)
      expect(response.body.data.bloodPressure).to.eq('120/80')
      S.encounterId = response.body.data.id
    })
    // 5. A note.
    cy.get('#clinNote').type('Throat congested, no rash. Paracetamol, fluids.')
    cy.get('#clinAddNote').click()
    cy.get('#clinNotes li').should('have.length', 1).and('contain', 'Throat congested')
    cy.screenshot('hms-s3a/D-01-2-vitals-and-note', { capture: 'viewport' })
    // 6. Complete → confirm.
    cy.get('#clinComplete').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('#clinStatus').should('have.attr', 'data-status', 'COMPLETED').and('contain', 'Done')
    cy.get('#clinVitalBp').should('be.disabled')
    cy.screenshot('hms-s3a/D-01-3-completed', { capture: 'viewport' })
  })

  it('D-02 vitals are range-checked; a stale or completed visit is not edited; a note can still correct it', () => {
    relogin(DOCTOR, 'doctor')
    const url = `/clinic/consult/encounters/${S.encounterId}`
    put(url, { temperatureF: '986', version: 0 }).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.match(/between 90 and 110|completed/)
    })
    put(url, { pulse: '80' }).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.contain('Add a note to correct it')   // completed in D-01
    })
    cy.request(url).its('body.data.notes').then((notes) => {
      post(url + '/notes', { body: 'Correction: temperature was 100.2 °F.', amendsNoteId: notes[0].id }).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        const ns = r.body.data.notes
        expect(ns, 'the original note is still there').to.have.length(2)
        expect(ns[0].body).to.contain('Throat congested')
        expect(ns[1].amendsNoteId).to.eq(notes[0].id)
      })
    })
  })

  it('D-03 the next visit shows the earlier one: complaint, vitals and notes', () => {
    relogin(OWNER)
    issueToken().then((t) => {
      relogin(DOCTOR, 'doctor')
      post(`/clinic/tokens/${t.id}/call`).its('body.success').should('eq', true)
      post(`/clinic/consult/tokens/${t.id}/start`).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        S.secondVisit = r.body.data.id
        const h = r.body.data.history
        expect(h, 'one earlier visit').to.have.length(1)
        expect(h[0].chiefComplaint).to.eq('Fever 3 days')
        expect(h[0].bloodPressure).to.eq('120/80')
        expect(h[0].notes.map((n) => n.body).join(' ')).to.contain('Throat congested')
        // a second Start of the same token is the same visit, never a second one
        post(`/clinic/consult/tokens/${t.id}/start`).its('body.data.id').should('eq', r.body.data.id)
      })
    })
  })

  // ── D-04 / D-05 — who may not ──────────────────────────────────────────────────────────────────────
  it('D-04 [M-15] the front desk cannot see My Queue, open a visit, or call a patient', () => {
    relogin(FRONT_DESK)
    cy.intercept('GET', '**/clinic/doctors/me').as('me')
    cy.visit('/clinicDashboard')
    cy.get('#clinNavReception').should('be.visible')
    // H2: the link is in the page for everyone and revealed only to a doctor (/clinic/doctors/me) — the desk never sees it
    cy.wait('@me').its('response.body.data.canConsult').should('eq', false)
    cy.get('#clinNavMyQueue').should('not.be.visible')
    cy.request({ url: `/clinic/consult/encounters/${S.encounterId}`, failOnStatusCode: false }).then((r) => {
      expect(r.status).to.eq(403)
      expect(r.body.message).to.contain('Only a doctor')
      expect(JSON.stringify(r.body)).to.not.contain('Throat congested')
    })
    post(`/clinic/tokens/${S.tokens[S.tokens.length - 1]}/call`).then((r) => {
      expect(r.status).to.eq(403)
      expect(r.body.message).to.contain('Only a doctor')
    })
  })

  it('D-05 [M-14] another organisation — its owner, with its own clinic on — cannot open this visit', () => {
    // its own clinic ON, or the switch would refuse first and scoping would go untested (the S1-08 lesson)
    cy.loginAsOperator()
    cy.setEntitlement(OTHER, 'clinic', 'ACTIVE', 'hms s3a gate: cross-tenant control')
    relogin(OTHER, 'other-pre')
    cy.setCapability('clinic', true)
    relogin(OTHER, 'other')
    cy.request({ url: `/clinic/consult/encounters/${S.encounterId}`, failOnStatusCode: false }).then((r) => {
      expect(r.body.success).to.not.eq(true)
      expect(r.body.message, JSON.stringify(r.body)).to.match(/Visit not found/)   // scoping, not "not switched on"
      expect(JSON.stringify(r.body)).to.not.contain('Throat congested')
      expect(JSON.stringify(r.body)).to.not.contain(S.patient.name)
    })
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' } })
  })
})
