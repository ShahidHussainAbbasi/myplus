/**
 * HMS Phase 1 — reception → token queue → doctor → park/resume → pharmacy sale by token.
 *
 * ⚠ MOST SCREENS THIS SPEC DRIVES DO NOT EXIST YET. It is the executable ACCEPTANCE CONTRACT for Phase 1:
 *   design   microservices/docs/hms-phase1-design.md (client decisions of 2026-10-09 in §1)
 *   plan     microservices/docs/hms-clinic-programme.md
 *   page     https://claude.ai/artifact/RTCGToYCyqJHVsR7VVnfAm
 * Gated so a normal run reports it PENDING, never green:
 *
 *   npx cypress run --headed --browser chrome --spec "cypress/e2e/hms/hms-phase1.cy.js" --env hms=1
 *
 * Each slice (S1…S5) makes its part of the SEL / API / USERS maps true and its cases pass. The pharmacy half
 * reuses TODAY's screens on purpose: Prescriptions list → Dispense → the sale form, already filled with what is
 * owed and carrying + / − per line (RX-FILL-1/2, pharma.js). Phase 1 adds the patient as the sale's customer,
 * search by token, and the doctor as the prescription's author.
 *
 * Client decisions encoded here (design §1): phone is the ONLY mandatory field and identifies the patient (one
 * patient per phone unless the clinic turns on "family on one phone"); the doctor is preselected (only doctor, else
 * the patient's last doctor); one patient may hold tokens with several doctors a day, seen in sequence; "submitted"
 * ≠ "dispensed" — the doctor's prescription is locked at submit, the pharmacist's + / − changes only the sale.
 *
 * Every case = numbered actions → EXPECTED (asserted) → CLEANUP (after()).
 */
const ENABLED = !!Cypress.env('hms')

// ── The contract: make these true when building Phase 1 ─────────────────────────────────────────────────
const USERS = {
  reception: Cypress.env('hmsReception') || 'reception.clinic@myplus.com',
  doctor: Cypress.env('hmsDoctor') || 'doctor.clinic@myplus.com',      // "Dr Ahmed"
  doctor2: Cypress.env('hmsDoctor2') || 'doctor2.clinic@myplus.com',   // "Dr Sana"
  pharmacist: Cypress.env('hmsPharmacist') || 'pharmacist.clinic@myplus.com',
  admin: Cypress.env('hmsAdmin') || 'owner.clinic@myplus.com',
  otherClinic: Cypress.env('hmsOtherClinic') || 'owner.appointment@myplus.com',
}
const PW = Cypress.env('hmsPassword') || 'Demo@2025!'

const SEL = {
  // S1/S2 reception (clinicDashboard)
  tabReception: '#clinNavReception', patPhone: '#clinPatPhone', patFound: '#clinPatFound',
  patName: '#clinPatName', patCnic: '#clinPatCnic', patDob: '#clinPatDob', patSex: '#clinPatSex',
  patSave: '#clinPatSave', patMrn: '#clinPatMrn', patFamilyAdd: '#clinPatFamilyAdd',
  doctorSelect: '#clinDoctor', issueToken: '#clinIssueToken', tokenNo: '#clinTokenNo', queueBoard: '#clinQueueBoard',
  // S3 doctor
  tabMyQueue: '#clinNavMyQueue', queueRow: '#clinQueueBody tr', tokenSearch: '#clinTokenSearch',
  callNext: '#clinCallNext', startConsult: '#clinStartConsult', idBanner: '#clinIdBanner',
  history: '#clinHistory', vitalBp: '#clinVitalBp', vitalTemp: '#clinVitalTemp', vitalPulse: '#clinVitalPulse',
  complaint: '#clinComplaint', note: '#clinNote', templateSearch: '#clinTemplateSearch',
  rxLines: '#clinRxBody tr', park: '#clinPark', parkTests: '#clinParkTests', parkReason: '#clinParkReason',
  confirmPark: '#clinConfirmPark', parkedTab: '#clinParkedTab', resume: '#clinResume',
  submitRx: '#clinSubmitRx', complete: '#clinComplete', status: '#clinStatus',
  // S4 pharmacy — TODAY's screens plus a search box
  pharmacyNav: '#snavPharmacy > .snav-btn', prescriptionsLink: '#snavPharmacy a[onclick^="showPrescriptions"]',
  rxSearch: '#rxSearch', rxRows: '#prescriptionBody tr', sellDiv: '#sellDiv', dispenseBanner: '#dispenseBanner',
  sellCustomer: '#sellCustomerDD', stepUp: '.ctr-step[data-d="1"]', stepDown: '.ctr-step[data-d="-1"]',
  // S5 access log
  accessLogNav: '#clinNavAccessLog', accessLogRows: '#clinAccessLogBody tr', accessLogPatient: '#clinAccessLogPatient',
}

const API = {
  patients: '/clinic/patients',                         // POST register, GET ?phone=
  patient: (id) => `/clinic/patients/${id}`,
  history: (id) => `/clinic/patients/${id}/history`,
  tokens: '/clinic/tokens',                             // POST {patientId, providerId}
  queue: (providerId) => `/clinic/queue?providerId=${providerId}`,
  token: (id) => `/clinic/tokens/${id}`,                // GET; POST …/call, …/cancel
  encounter: (id) => `/clinic/encounters/${id}`,
  settings: '/clinic/settings',
  audit: (patientId) => `/clinic/patients/${patientId}/access-log`,
}

;(ENABLED ? describe : describe.skip)('HMS Phase 1 — OPD to pharmacy sale (contract)', () => {
  const s = `${Date.now()}`.slice(-7)
  const phone = '0300' + s                              // 11 digits, unique per run
  const phone2 = '0301' + s
  const patientName = 'Ali Khan ' + s
  const created = { patientIds: [], tokenIds: [] }
  const state = {}                                      // carried case → case

  const login = (email) => cy.loginAs(email, PW, '/clinicDashboard')
  const post = (url, body) =>
    cy.request({ method: 'POST', url, body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })

  after(() => {
    // CLEANUP — cancel every token this run opened (frees the queue), then retire the test patients.
    // Clinical records are append-only: a patient is RETIRED, never hard-deleted.
    login(USERS.reception)
    created.tokenIds.forEach((id) => post(`${API.token(id)}/cancel`, { reason: 'cypress cleanup' }))
    created.patientIds.forEach((id) => post(`${API.patient(id)}/retire`, { reason: 'cypress cleanup' }))
  })

  // ── S1 — patient desk ───────────────────────────────────────────────────────────────────────────────
  it('M-01 reception registers a patient with ONLY a phone; an MRN is issued', () => {
    login(USERS.reception)
    cy.intercept('POST', `**${API.patients}`).as('register')
    // 1. Reception tab → type the phone (the first and only required field).
    cy.get(SEL.tabReception).click()
    cy.get(SEL.patPhone).should('be.visible').type(phone).blur()
    // 2. Unknown number → the form stays open for a new patient. Type a name (optional) and save.
    cy.get(SEL.patFound).should('not.be.visible')
    cy.get(SEL.patName).type(patientName)
    cy.get(SEL.patSave).click()
    // EXPECTED: saved with nothing else filled; MRN shown in the per-clinic format.
    cy.wait('@register').then(({ response }) => {
      expect(response.body.success, JSON.stringify(response.body)).to.eq(true)
      state.patientId = response.body.data.id
      state.mrn = response.body.data.mrn
      created.patientIds.push(state.patientId)
      expect(state.mrn).to.match(/^MRN-[A-Z0-9]+-\d{2}-\d{6}$/)
      expect(response.body.data.phone, 'stored normalised').to.eq('92' + phone.slice(1))
      // One person, two roles: the same Save made the pharmacy's customer, linked by party id (design §1 B-01(2)).
      state.partyId = response.body.data.partyId
      state.customerId = response.body.data.customerId
      expect(state.partyId, 'one party').to.be.a('number')
      expect(state.customerId, 'customer created at reception').to.be.a('number')
    })
    cy.get(SEL.patMrn).should('be.visible').invoke('text').should('match', /MRN-/)
  })

  it('M-01b an invalid phone is refused with a reason', () => {
    login(USERS.reception)
    ;['12345', '0300123', '04235761234', 'abcdefghijk'].forEach((bad) =>
      post(API.patients, { phone: bad, name: 'Bad Phone' }).then((r) => {
        expect(r.body.success, bad).to.eq(false)
        expect(r.body.message, bad).to.match(/phone/i)
      }))
  })

  it('M-02 the same phone again is refused and points at the existing MRN', () => {
    login(USERS.reception)
    // API: a second registration on the number — even with another name — is refused, never merged (B-07).
    ;[patientName, 'Someone Else ' + s].forEach((name) =>
      post(API.patients, { phone: '+92' + phone.slice(1), name }).then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.message).to.match(/already registered/i)
        expect(r.body.message, 'names the existing MRN').to.contain(state.mrn)
      }))
    // Screen: typing the number opens the existing patient instead of a blank form.
    cy.get(SEL.tabReception).click()
    cy.get(SEL.patPhone).clear().type(phone).blur()
    cy.get(SEL.patFound).should('be.visible').and('contain', patientName).and('contain', state.mrn)
    // "Add family member" is offered only when the clinic turned it on (default OFF).
    cy.get(SEL.patFamilyAdd).should('not.exist')
  })

  // ── S2 — token & queue ──────────────────────────────────────────────────────────────────────────────
  it('02 a token for today: the doctor is preselected, WAITING, with a queue position', () => {
    login(USERS.reception)
    cy.intercept('POST', `**${API.tokens}`).as('token')
    cy.get(SEL.tabReception).click()
    cy.get(SEL.patPhone).clear().type(phone).blur()
    cy.get(SEL.patFound).should('contain', state.mrn)
    // First visit, so no "last doctor": with one doctor on duty it is that one; with several, pick Dr Ahmed.
    cy.get(SEL.doctorSelect).then(($d) => {
      const opts = $d.find('option[value!=""]')
      if (opts.length === 1) expect($d.val(), 'the only doctor is preselected').to.eq(opts.val())
      else cy.get(SEL.doctorSelect).select('Dr Ahmed', { force: true })
    })
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

  it('02b a second token with the SAME doctor the same day is refused, naming the first', () => {
    login(USERS.reception)
    post(API.tokens, { patientId: state.patientId, providerId: state.providerId }).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(JSON.stringify(r.body)).to.contain(state.token)
    })
  })

  it('02c on the next visit the patient\'s last doctor is preselected', () => {
    login(USERS.reception)
    cy.get(SEL.tabReception).click()
    cy.get(SEL.patPhone).clear().type(phone).blur()
    cy.get(SEL.doctorSelect).find('option:selected').should('contain', 'Dr Ahmed')
  })

  it('03 the doctor\'s queue lists WAITING patients in order', () => {
    login(USERS.doctor)
    cy.get(SEL.tabMyQueue).click()
    cy.get(SEL.queueRow).should('have.length.at.least', 1)
    cy.contains(SEL.queueRow, state.token).should('contain', 'WAITING')
    cy.request(API.queue(state.providerId)).then((r) => {
      const pos = r.body.data.map((t) => t.queuePosition)
      expect(pos, 'sorted').to.deep.eq([...pos].sort((a, b) => a - b))
    })
  })

  it('M-04 a token issued at reception reaches the doctor\'s open screen without a reload', () => {
    login(USERS.doctor)
    cy.get(SEL.tabMyQueue).click()
    cy.get(SEL.queueRow).its('length').then((before) => {
      post(API.patients, { phone: phone2, name: 'Live Queue ' + s }).then((p) => {
        created.patientIds.push(p.body.data.id)
        state.patient2Id = p.body.data.id
        post(API.tokens, { patientId: p.body.data.id, providerId: state.providerId })
          .then((t) => created.tokenIds.push(t.body.data.id))
      })
      // 5 s poll today (design §4 S2); SSE later tightens this to the blueprint's 3 s.
      cy.get(SEL.queueRow, { timeout: 7000 }).should('have.length', before + 1)
    })
  })

  // ── S3 — doctor workspace ───────────────────────────────────────────────────────────────────────────
  it('M-15 reception cannot open the doctor\'s workspace or a clinical note', () => {
    login(USERS.reception)
    cy.request({ url: API.history(state.patientId), failOnStatusCode: false })
      .then((r) => expect(r.status === 403 || r.body.success === false, 'refused').to.eq(true))
    cy.visit('/clinicDashboard')
    cy.get(SEL.tabMyQueue).should('not.exist')
  })

  it('04 search the token, start, check identity, record vitals and a note', () => {
    login(USERS.doctor)
    cy.get(SEL.tabMyQueue).click()
    cy.get(SEL.tokenSearch).type(state.token + '{enter}')
    cy.get(SEL.startConsult).click()
    // Two identifiers before any clinical act: name + MRN (+ DOB when recorded).
    cy.get(SEL.idBanner).should('contain', patientName).and('contain', state.mrn)
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
    cy.then(() => cy.request(API.encounter(state.encounterId))).its('body.data.vitals.bp').should('eq', '120/80')
  })

  it('04b a nonsense vital is refused (temperature 986)', () => {
    login(USERS.doctor)
    cy.request({ method: 'PUT', url: API.encounter(state.encounterId), body: { vitals: { temp: 986 } }, failOnStatusCode: false })
      .its('body.success').should('eq', false)
  })

  it('04c two doctors never share a token; one patient is seen by one doctor at a time', () => {
    // (a) Dr Sana cannot claim Dr Ahmed's token.
    login(USERS.doctor2)
    post(`${API.token(state.tokenId)}/call`, {}).its('body.success').should('eq', false)
    // (b) multi-doctor setting ON (default): the same patient may get a token with Dr Sana today…
    login(USERS.reception)
    post(API.tokens, { patientId: state.patientId, providerId: Cypress.env('hmsDoctor2ProviderId') }).then((r) => {
      expect(r.body.success, 'second doctor, same day').to.eq(true)
      state.token2Id = r.body.data.id
      created.tokenIds.push(state.token2Id)
    })
    // …but Dr Sana cannot call it while the patient is still with Dr Ahmed.
    login(USERS.doctor2)
    cy.then(() => post(`${API.token(state.token2Id)}/call`, {})).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.match(/with Dr Ahmed/i)
    })
  })

  it('05 the Fever + Flu template fills the prescription; every line stays editable', () => {
    login(USERS.doctor)
    cy.get(SEL.templateSearch).type('Fever')
    cy.contains('Fever + Flu').click()
    cy.get(SEL.rxLines).should('have.length.at.least', 2)
    cy.get(SEL.rxLines).first().find('input[name=dosage]').clear().type('1+0+1')
    cy.get(SEL.rxLines).first().find('input[name=dosage]').should('have.value', '1+0+1')
  })

  it('06 park for CBC + LFT: PARKED, token kept, reason saved', () => {
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

  it('06b while parked, the draft prescription is NOT in the pharmacy list', () => {
    login(USERS.pharmacist)
    cy.request('/getPrescriptions?q=' + state.token).its('body.data').then((rows) =>
      expect((rows || []).map((r) => r.tokenNo)).not.to.include(state.token))
  })

  it('07 resume, submit, complete — the prescription appears in the pharmacy list', () => {
    login(USERS.doctor)
    cy.get(SEL.parkedTab).click()
    cy.contains(SEL.queueRow, state.token).click()
    cy.get(SEL.resume).click()
    cy.get(SEL.status).should('contain', 'IN_CONSULTATION')
    cy.intercept('POST', '**/addPrescription').as('submit')
    cy.get(SEL.submitRx).click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@submit').then(({ response }) => {
      expect(response.body.success, JSON.stringify(response.body)).to.eq(true)
      state.rxId = response.body.data.id
      expect(response.body.data.source).to.eq('DOCTOR')
      expect(response.body.data.tokenNo).to.eq(state.token)
      expect(response.body.data.status).to.eq('PENDING')
    })
    cy.get(SEL.complete).click()
    cy.get(SEL.status).should('contain', 'COMPLETED')
  })

  it('07b a COMPLETED token cannot be called again', () => {
    login(USERS.doctor)
    post(`${API.token(state.tokenId)}/call`, {}).its('body.success').should('eq', false)
  })

  it('07c a SUBMITTED prescription cannot be edited in place (submitted ≠ dispensed)', () => {
    login(USERS.doctor)
    cy.request({ method: 'PUT', url: `/clinic/prescriptions/${state.rxId}`, body: { items: [] }, failOnStatusCode: false })
      .its('body.success').should('eq', false)
  })

  // ── S4 — pharmacy: search by token → Dispense → the sale form, filled ──────────────────────────────────
  it('08 the pharmacist finds the token, Dispense fills the sale with the patient as customer, adjusts, completes', () => {
    login(USERS.pharmacist)
    cy.visit('/businessDashboard')
    cy.get(SEL.pharmacyNav).click()
    cy.get(SEL.prescriptionsLink).click()
    // 1. Search by token (MRN and phone work the same way).
    cy.get(SEL.rxSearch).should('be.visible').type(state.token + '{enter}')
    cy.get(SEL.rxRows).should('have.length', 1).and('contain', patientName).and('contain', 'Dr Ahmed')
    // 2. Dispense → the sale form, nothing typed by the pharmacist.
    cy.get(SEL.rxRows).first().contains('button', 'Dispense').click()
    cy.get(SEL.sellDiv).should('be.visible')
    cy.get(SEL.dispenseBanner).should('be.visible').and('contain', patientName)
    cy.get(SEL.sellCustomer).find('option:selected').should('contain', patientName)
    cy.get(SEL.stepUp).should('have.length.at.least', 2)
    // 3. The patient wants one less of the first medicine: −.
    cy.get(SEL.stepDown).first().click()
    cy.get('#dispenseFillNote').should('contain', 'left for another day')
    // 4. Complete Sale → receipt.
    cy.intercept('POST', '**/addSell').as('sell')
    cy.get('#sellDiv').contains('button', /complete sale/i).click()
    cy.wait('@sell').then(({ response }) => {
      expect(response.body.status).to.eq('SUCCESS')
      // EXPECTED: the sale names the patient as its customer; the script keeps what is still owed.
      cy.request(`/getPrescription?id=${state.rxId}`).its('body.data.status').should('eq', 'PARTIALLY_DISPENSED')
    })
  })

  // ── S5 — isolation and the access log ────────────────────────────────────────────────────────────────
  it('M-14 another clinic cannot read this patient, token or encounter', () => {
    login(USERS.otherClinic)
    ;[API.patient(state.patientId), API.token(state.tokenId), API.encounter(state.encounterId)].forEach((url) =>
      cy.request({ url, failOnStatusCode: false }).then((r) => {
        expect(r.body && r.body.success, url).to.not.eq(true)
        expect(JSON.stringify(r.body || ''), 'no name leaks').to.not.contain(patientName)
      }))
  })

  it('SEC-007 the owner reads who opened the patient on the Access log screen', () => {
    login(USERS.admin)
    cy.get(SEL.accessLogNav).click()
    cy.get(SEL.accessLogPatient).type(state.mrn + '{enter}')
    cy.contains(SEL.accessLogRows, 'Dr Ahmed').should('contain', 'Viewed')
    cy.get(SEL.accessLogRows).first().invoke('text').should('match', /\d{2}:\d{2}/)
  })
})
