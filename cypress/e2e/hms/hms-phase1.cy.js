/**
 * HMS Phase 1 — reception → token queue → doctor → park/resume → pharmacy by token.
 *
 * ⚠ THE SCREENS THIS SPEC DRIVES DO NOT EXIST YET. It is the executable ACCEPTANCE CONTRACT for Phase 1 of
 * microservices/docs/hms-clinic-programme.md, written from the client blueprint's 8 Cypress stubs and its
 * manual cases M-01..M-15. It is gated so a normal run reports it PENDING, never green:
 *
 *   npx cypress run --headed --browser chrome --spec "cypress/e2e/hms/hms-phase1.cy.js" --env hms=1
 *
 * What runs green today is hms-baseline-today.cy.js. When the Phase 1 slice lands, the implementer makes
 * the SEL / API / USERS maps below true (one place), drops the gate, and this file becomes the slice gate.
 *
 * Corrections to the blueprint, applied here deliberately (each traced to the codebase, see the Test Book):
 *   - Route: one /clinicDashboard (D-5), not /patients/new, /doctor/queue, /pharmacy/queue as separate pages.
 *   - Selectors: the platform's id convention (#clin…), not data-cy — the blueprint's data-cy exist nowhere.
 *   - Tenant: the org comes from the JWT (activeOrgId → X-Org-Id). The blueprint's `cy.login(role, tenant)`
 *     and X-Tenant-Id header would let a client CHOOSE its tenant — the cross-tenant write this repo has paid
 *     for before. Isolation is tested by logging in as another org's user, never by passing an org id.
 *   - Token: per doctor per day from the database (org_document_seq-style, allocated late), not Redis INCR.
 *   - Status: WAITING → CALLED → IN_CONSULTATION → PARKED → IN_CONSULTATION → COMPLETED (+ CANCELLED,
 *     NO_SHOW). The blueprint's "DONE" is COMPLETED; CALLED is added so two doctors cannot claim one patient.
 *   - Identity: name + MRN + date of birth shown before any clinical act; the token alone is never enough.
 *   - Pharmacist never edits the doctor's signed Rx; a changed line records original, dispensed, reason.
 *
 * Every case = numbered actions → EXPECTED (asserted) → CLEANUP (after()).
 */
const ENABLED = !!Cypress.env('hms')

// ── The contract: make these true when building Phase 1 ─────────────────────────────────────────────────
const USERS = {
  reception: Cypress.env('hmsReception') || 'reception.clinic@myplus.com',
  doctor: Cypress.env('hmsDoctor') || 'doctor.clinic@myplus.com',
  doctor2: Cypress.env('hmsDoctor2') || 'doctor2.clinic@myplus.com',
  pharmacist: Cypress.env('hmsPharmacist') || 'pharmacist.clinic@myplus.com',
  admin: Cypress.env('hmsAdmin') || 'owner.clinic@myplus.com',
  otherClinic: Cypress.env('hmsOtherClinic') || 'owner.appointment@myplus.com',
}
const PW = Cypress.env('hmsPassword') || 'Demo@2025!'

const SEL = {
  // reception
  tabReception: '#clinNavReception', patSearch: '#clinPatSearch', patNew: '#clinPatNew',
  patName: '#clinPatName', patCnic: '#clinPatCnic', patPhone: '#clinPatPhone', patDob: '#clinPatDob',
  patSex: '#clinPatSex', patPhoto: '#clinPatPhoto', patSave: '#clinPatSave', patMrn: '#clinPatMrn',
  dupWarning: '#clinPatDuplicate', doctorSelect: '#clinDoctor', issueToken: '#clinIssueToken',
  tokenNo: '#clinTokenNo', queueBoard: '#clinQueueBoard',
  // doctor
  tabMyQueue: '#clinNavMyQueue', queueRow: '#clinQueueBody tr', tokenSearch: '#clinTokenSearch',
  callNext: '#clinCallNext', startConsult: '#clinStartConsult', idBanner: '#clinIdBanner',
  history: '#clinHistory', vitalBp: '#clinVitalBp', vitalTemp: '#clinVitalTemp', vitalPulse: '#clinVitalPulse',
  complaint: '#clinComplaint', note: '#clinNote', templateSearch: '#clinTemplateSearch',
  rxLines: '#clinRxBody tr', park: '#clinPark', parkTests: '#clinParkTests', parkReason: '#clinParkReason',
  confirmPark: '#clinConfirmPark', parkedTab: '#clinParkedTab', resume: '#clinResume',
  signRx: '#clinSignRx', complete: '#clinComplete', status: '#clinStatus',
  // pharmacy
  rxQueue: '#clinRxQueue', rxTokenSearch: '#clinRxTokenSearch', createOrder: '#clinRxCreateOrder',
  orderLine: '#clinRxOrderBody tr', patientConfirmed: '#clinRxPatientConfirmed', confirmBill: '#clinRxConfirmBill',
  invoiceNo: '#clinRxInvoiceNo', print: '#clinRxPrint', expiryWarn: '.clin-expiry-warn',
}

const API = {
  patients: '/clinic/patients',                         // POST register, GET ?q=
  patient: (id) => `/clinic/patients/${id}`,
  history: (id) => `/clinic/patients/${id}/history`,
  tokens: '/clinic/tokens',                             // POST {patientId, providerId}
  queue: (providerId) => `/clinic/queue?providerId=${providerId}`,
  token: (id) => `/clinic/tokens/${id}`,                // GET; POST …/cancel in cleanup
  encounter: (id) => `/clinic/encounters/${id}`,
  pharmacyQueue: '/clinic/pharmacy/queue',
  audit: (patientId) => `/clinic/patients/${patientId}/access-log`,
}

;(ENABLED ? describe : describe.skip)('HMS Phase 1 — OPD to pharmacy (contract)', () => {
  const s = `${Date.now()}`.slice(-7)
  const cnic = `42201-${s}-1`
  const patientName = 'Ali Khan ' + s
  const created = { patientIds: [], tokenIds: [] }
  const state = {}                                      // carried case → case, like the blueprint's @mrn

  const login = (email) => cy.loginAs(email, PW, '/clinicDashboard')
  const post = (url, body) =>
    cy.request({ method: 'POST', url, body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })

  after(() => {
    // CLEANUP — cancel every token this run opened (frees the doctor's queue), then retire the test patients.
    // Clinical records are append-only: a patient is RETIRED (merged-away/inactive), never hard-deleted.
    login(USERS.reception)
    created.tokenIds.forEach((id) => post(`${API.token(id)}/cancel`, { reason: 'cypress cleanup' }))
    created.patientIds.forEach((id) => post(`${API.patient(id)}/retire`, { reason: 'cypress cleanup' }))
  })

  // ── 01 / M-01 / M-02 — registration, MRN, duplicate ─────────────────────────────────────────────────
  it('01 reception registers a new patient and an MRN is issued (M-01)', () => {
    login(USERS.reception)
    cy.intercept('POST', `**${API.patients}`).as('register')
    // 1. Reception tab → search the CNIC first (the blueprint's "search before register").
    cy.get(SEL.tabReception).click()
    cy.get(SEL.patSearch).should('be.visible').type(cnic + '{enter}')
    // 2. Nothing found → New patient.
    cy.get(SEL.patNew).should('be.visible').click()
    // 3. Fill the identity.
    cy.get(SEL.patName).type(patientName)
    cy.get(SEL.patCnic).type(cnic)
    cy.get(SEL.patPhone).type('03001234567')
    cy.get(SEL.patDob).type('1990-05-14')
    cy.get(SEL.patSex).select('M', { force: true })
    cy.get(SEL.patPhoto).selectFile('cypress/fixtures/patient-photo.png', { force: true })
    // 4. Save.
    cy.get(SEL.patSave).click()
    // EXPECTED: 200, an MRN in the per-org format, shown on screen, photo stored.
    cy.wait('@register').then(({ response }) => {
      expect(response.body.success, JSON.stringify(response.body)).to.eq(true)
      state.patientId = response.body.data.id
      state.mrn = response.body.data.mrn
      created.patientIds.push(state.patientId)
      expect(state.mrn).to.match(/^MRN-[A-Z0-9]+-\d{2}-\d{6}$/)
      expect(response.body.data.photoUrl, 'photo stored, private path').to.be.a('string')
    })
    cy.get(SEL.patMrn).should('be.visible').invoke('text').should('match', /MRN-/)
  })

  it('M-02 the same CNIC again is refused and points at the existing MRN', () => {
    login(USERS.reception)
    post(API.patients, { name: 'Ali Khan duplicate', cnic, phone: '03001234567', dob: '1990-05-14', sex: 'M' })
      .then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.message).to.match(/already registered/i)
        expect(r.body.message, 'names the existing MRN').to.contain(state.mrn)
      })
    // Also on screen: typing the CNIC surfaces the warning before Save.
    cy.get(SEL.tabReception).click()
    cy.get(SEL.patNew).click()
    cy.get(SEL.patCnic).type(cnic).blur()
    cy.get(SEL.dupWarning).should('be.visible').and('contain', state.mrn)
  })

  it('M-01b an invalid CNIC is refused with a reason (PAT-006)', () => {
    login(USERS.reception)
    post(API.patients, { name: 'Bad Cnic', cnic: '12345', phone: '03001234567' })
      .its('body.success').should('eq', false)
  })

  // ── 02 / M-03 — token ───────────────────────────────────────────────────────────────────────────────
  it('02 reception issues a token for Dr Ahmed today; queue position shown (M-03)', () => {
    login(USERS.reception)
    cy.intercept('POST', `**${API.tokens}`).as('token')
    cy.get(SEL.tabReception).click()
    cy.get(SEL.patSearch).clear().type(state.mrn + '{enter}')
    cy.contains(SEL.queueBoard + ', body', patientName)
    cy.get(SEL.doctorSelect).select('Dr Ahmed', { force: true })
    cy.get(SEL.issueToken).click()
    cy.wait('@token').then(({ response }) => {
      expect(response.body.success, JSON.stringify(response.body)).to.eq(true)
      state.tokenId = response.body.data.id
      state.token = response.body.data.tokenNo
      state.providerId = response.body.data.providerId
      created.tokenIds.push(state.tokenId)
      expect(state.token).to.match(/^A-\d{3}$/)
      expect(response.body.data.status).to.eq('WAITING')
      expect(response.body.data.queuePosition).to.be.greaterThan(0)
    })
    cy.get(SEL.tokenNo).should('be.visible').and('contain', 'A-')
  })

  it('02b a second token for the same patient + doctor + day is refused (APT-003, idempotent)', () => {
    login(USERS.reception)
    post(API.tokens, { patientId: state.patientId, providerId: state.providerId }).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(JSON.stringify(r.body)).to.contain(state.token)
    })
  })

  // ── 03 / M-05 / M-15 — doctor's queue and privileges ───────────────────────────────────────────────
  it('03 the doctor sees the queue WAITING, sorted by position (M-05)', () => {
    login(USERS.doctor)
    cy.get(SEL.tabMyQueue).click()
    cy.get(SEL.queueRow).should('have.length.at.least', 1)
    cy.contains(SEL.queueRow, state.token).should('contain', 'WAITING')
    cy.request(API.queue(state.providerId)).then((r) => {
      const pos = r.body.data.map((t) => t.queuePosition)
      expect(pos, 'sorted').to.deep.eq([...pos].sort((a, b) => a - b))
    })
  })

  it('M-15 reception cannot open the doctor\'s workspace or a clinical note', () => {
    login(USERS.reception)
    cy.request({ url: API.history(state.patientId), failOnStatusCode: false })
      .then((r) => expect(r.status === 403 || r.body.success === false, 'refused').to.eq(true))
    cy.visit('/clinicDashboard')
    cy.get(SEL.tabMyQueue).should('not.exist')
  })

  // ── 04 / M-06 / M-07 — call, identify, history, vitals ─────────────────────────────────────────────
  it('04 the doctor searches the token, starts the consult, sees identity + history, records vitals (M-06, M-07)', () => {
    login(USERS.doctor)
    cy.get(SEL.tabMyQueue).click()
    cy.get(SEL.tokenSearch).type(state.token + '{enter}')
    cy.get(SEL.startConsult).click()
    // Two identifiers before any clinical act.
    cy.get(SEL.idBanner).should('contain', patientName).and('contain', state.mrn).and('contain', '1990')
    cy.get(SEL.history).should('be.visible')
    cy.get(SEL.status).should('contain', 'IN_CONSULTATION')
    cy.get(SEL.vitalBp).type('120/80')
    cy.get(SEL.vitalTemp).type('98.6')
    cy.get(SEL.vitalPulse).type('78')
    cy.get(SEL.complaint).type('Fever 3 days')
    cy.get(SEL.note).type('Throat congested, no rash.').blur()
    cy.request(API.token(state.tokenId)).then((r) => {
      state.encounterId = r.body.data.encounterId
      expect(r.body.data.status).to.eq('IN_CONSULTATION')
    })
    cy.then(() => cy.request(API.encounter(state.encounterId)))
      .its('body.data.vitals.bp').should('eq', '120/80')
  })

  it('04b a nonsense vital is refused (temperature 986)', () => {
    login(USERS.doctor)
    cy.request({ method: 'PUT', url: API.encounter(state.encounterId), body: { vitals: { temp: 986 } }, failOnStatusCode: false })
      .its('body.success').should('eq', false)
  })

  it('04c a second doctor cannot claim the same patient (APT-007)', () => {
    login(USERS.doctor2)
    post(`${API.token(state.tokenId)}/call`, {}).its('body.success').should('eq', false)
  })

  // ── 05 / M-08 — template ───────────────────────────────────────────────────────────────────────────
  it('05 the Fever + Flu template fills the prescription, every line editable (M-08)', () => {
    login(USERS.doctor)
    cy.get(SEL.templateSearch).type('Fever')
    cy.contains('Fever + Flu').click()
    cy.get(SEL.rxLines).should('have.length.at.least', 2)
    cy.get(SEL.rxLines).first().find('input[name=dosage]').clear().type('1+0+1')
    cy.get(SEL.rxLines).first().find('input[name=dosage]').should('have.value', '1+0+1')
  })

  // ── 06 / M-09 — park for tests ─────────────────────────────────────────────────────────────────────
  it('06 the doctor parks the patient for CBC + LFT; the token is kept (M-09)', () => {
    login(USERS.doctor)
    cy.get(SEL.park).click()
    cy.get(SEL.parkTests).select(['CBC', 'LFT'], { force: true })
    cy.get(SEL.parkReason).type('CBC, LFT pending')
    cy.get(SEL.confirmPark).click()
    cy.get(SEL.status).should('contain', 'PARKED')
    cy.request(API.token(state.tokenId)).then((r) => {
      expect(r.body.data.status).to.eq('PARKED')
      expect(r.body.data.tokenNo, 'same token').to.eq(state.token)
      expect(r.body.data.parkReason).to.contain('CBC')
    })
  })

  it('06b a parked prescription is not visible to the pharmacy yet', () => {
    login(USERS.pharmacist)
    cy.request(API.pharmacyQueue).its('body.data').then((rows) =>
      expect(rows.map((r) => r.tokenNo)).not.to.include(state.token))
  })

  // ── 07 / M-10 / M-11 — resume, sign, complete ──────────────────────────────────────────────────────
  it('07 the doctor resumes from Parked, signs the Rx, completes; the Rx reaches the pharmacy (M-10, M-11)', () => {
    login(USERS.doctor)
    cy.get(SEL.parkedTab).click()
    cy.contains(SEL.queueRow, state.token).click()
    cy.get(SEL.resume).click()
    cy.get(SEL.status).should('contain', 'IN_CONSULTATION')
    cy.get(SEL.signRx).click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get(SEL.complete).click()
    cy.get(SEL.status).should('contain', 'COMPLETED')
    cy.request(API.token(state.tokenId)).its('body.data.status').should('eq', 'COMPLETED')
  })

  it('07b a COMPLETED token cannot be called again (illegal transition)', () => {
    login(USERS.doctor)
    post(`${API.token(state.tokenId)}/call`, {}).its('body.success').should('eq', false)
  })

  it('07c a signed prescription cannot be edited in place', () => {
    login(USERS.doctor)
    cy.request(API.encounter(state.encounterId)).then((r) => {
      const rxId = r.body.data.prescriptionId
      cy.request({ method: 'PUT', url: `/clinic/prescriptions/${rxId}`, body: { items: [] }, failOnStatusCode: false })
        .its('body.success').should('eq', false)
    })
  })

  // ── 08 / M-12 / M-13 — pharmacy by token, bill, print ──────────────────────────────────────────────
  it('08 the pharmacist finds the token, confirms with the patient, bills and prints (M-12, M-13)', () => {
    login(USERS.pharmacist)
    cy.intercept('POST', '**/addSell').as('sell')
    cy.get(SEL.rxQueue).should('contain', state.token)
    cy.get(SEL.rxTokenSearch).type(state.token + '{enter}')
    cy.get(SEL.createOrder).click()
    cy.get(SEL.orderLine).should('have.length.at.least', 2)
    // FEFO picks a batch; one inside 30 days of expiry is flagged, not hidden.
    cy.get(SEL.expiryWarn).should('exist')
    cy.get(SEL.patientConfirmed).check()
    cy.get(SEL.confirmBill).click()
    cy.wait('@sell').its('response.body.status').should('eq', 'SUCCESS')
    cy.get(SEL.invoiceNo).invoke('text').should('match', /\S+/)
    cy.window().then((w) => cy.stub(w, 'print').as('print'))
    cy.get(SEL.print).click()
    cy.get('@print').should('have.been.called')
  })

  // ── M-14 — isolation; M-04 — live queue ────────────────────────────────────────────────────────────
  it('M-14 another clinic cannot read this patient, token or encounter', () => {
    login(USERS.otherClinic)
    ;[API.patient(state.patientId), API.token(state.tokenId), API.encounter(state.encounterId)].forEach((url) =>
      cy.request({ url, failOnStatusCode: false }).then((r) => {
        expect(r.body && r.body.success, url).to.not.eq(true)
        expect(JSON.stringify(r.body || ''), 'no name leaks').to.not.contain(patientName)
      }))
  })

  it('M-04 a token issued at reception reaches the doctor\'s open screen within 3s', () => {
    login(USERS.doctor)
    cy.get(SEL.tabMyQueue).click()
    cy.get(SEL.queueRow).its('length').then((before) => {
      // Reception action from the same test via the API (a second browser is not possible in Cypress).
      post(API.patients, { name: 'Live Queue ' + s, cnic: `42201-${s}-2`, phone: '03009990000', dob: '1985-01-01', sex: 'F' })
        .then((p) => {
          created.patientIds.push(p.body.data.id)
          post(API.tokens, { patientId: p.body.data.id, providerId: state.providerId })
            .then((t) => created.tokenIds.push(t.body.data.id))
        })
      cy.get(SEL.queueRow, { timeout: 3000 }).should('have.length', before + 1)
    })
  })

  it('SEC-007 reading the patient left an access-log entry naming the doctor', () => {
    login(USERS.admin)       // the PHI access log is owner/admin-only, like the audit trail
    cy.request(API.audit(state.patientId)).its('body.data').then((rows) => {
      expect(rows.some((r) => r.actor === USERS.doctor && r.action === 'READ')).to.eq(true)
    })
  })
})
