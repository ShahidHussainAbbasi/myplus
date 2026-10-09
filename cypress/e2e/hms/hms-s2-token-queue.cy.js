/**
 * HMS S2 — token & queue: doctors with daily limits, a token per patient per doctor per day, today's board, and the
 * state machine behind it.
 *
 * Design: microservices/docs/hms-phase1-design.md §4b (D-3 revised: the token lives in clinical-service). Test page:
 * https://claude.ai/artifact/RTCGToYCyqJHVsR7VVnfAm. SLICE GATE — real screens; the first cases drive the UI.
 *
 * Tenant: owner.pharma@ (pharmacy + clinic). Cross-tenant control: owner.business@ with its own clinic on.
 * Venue check (S2-11): owner.appointment@ owns a venue that owner.business@ tries to put a doctor in.
 *
 * Re-runnable on the same day: two fixed gate doctors are found or created ("Dr Ahmed (gate)", "Dr Sana (gate)") and
 * every limit is set RELATIVE to what the doctor already issued today, so a second run never trips the first run's
 * tokens. S2-01 adds one new doctor per run on purpose — it is the "add a doctor" screen case — and there is no
 * "remove doctor" yet, so those accumulate (named "Dr Gate <run>"); stated, not hidden.
 *
 * Server state changed and put back in after(): clinic switch (owner.pharma, owner.business) → reset; setting
 * multiDoctorPerDay → reset; today's limit overrides → reset; live tokens → cancelled; patients → retired; their
 * test customers → deleted.
 *
 * Run (headless records reliably; a hidden headed window pauses animations):
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-s2-token-queue.cy.js"
 */
describe('HMS S2 — token & queue', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const OTHER_CLINIC = 'owner.business@myplus.com'
  const VENUE_OWNER = 'owner.appointment@myplus.com'
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  let seq = 0
  const newPhone = () => '035' + run.slice(-6) + String(seq++ % 10) + String(Math.floor(Math.random() * 10))
  const PREFIXES = ['S2 Patient ']

  const created = { patients: [], customers: [], tokens: [], dayResets: [] }
  const doc = {}          // { ahmed: DoctorView, sana: DoctorView }
  const pt = {}           // patients by key

  const relogin = (email, phase = 'on') => cy.loginAs(email, PW, '/getBusinessDashboardStats', `hms-s2-${run}-${phase}`)
  const post = (url, body) =>
    cy.request({ method: 'POST', url, body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
  const doctors = () => cy.request('/clinic/doctors').then((r) => {
    expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
    return r.body.data
  })
  const findOrAddDoctor = (name) => doctors().then((list) => {
    const found = list.find((d) => d.name === name)
    if (found) return found
    return post('/clinic/doctors', { name, speciality: 'General medicine', dailyLimit: null }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      return r.body.data
    })
  })
  const doctorNow = (id) => doctors().then((list) => list.find((d) => d.id === id))
  const setDay = (providerId, body) => post('/clinic/doctors/day', Object.assign({ providerId }, body)).then((r) => {
    expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
    if (!created.dayResets.includes(providerId)) created.dayResets.push(providerId)
    return r.body.data
  })
  const newPatient = (key) => post('/clinic/patients', { phone: newPhone(), name: 'S2 Patient ' + key + ' ' + run }).then((r) => {
    expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
    created.patients.push(r.body.data.id)
    if (r.body.data.customerId) created.customers.push(r.body.data.customerId)
    pt[key] = r.body.data
    return r.body.data
  })
  const issue = (patientId, providerId) => post('/clinic/tokens', { patientId, providerId }).then((r) => {
    if (r.body && r.body.success) created.tokens.push(r.body.data.id)
    return r
  })
  const move = (id, action, reason) => post(`/clinic/tokens/${id}/${action}`, reason ? { reason } : {})

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms s2 gate')
    relogin(OWNER, 'pre')
    cy.setCapability('clinic', true)
    relogin(OWNER)
    findOrAddDoctor('Dr Ahmed (gate)').then((d) => { doc.ahmed = d })
    findOrAddDoctor('Dr Sana (gate)').then((d) => { doc.sana = d })
  })

  // testIsolation clears the session between tests: every test starts signed in as the clinic's owner
  beforeEach(() => relogin(OWNER))

  after(() => {
    relogin(OWNER, 'cleanup')
    created.tokens.forEach((id) => move(id, 'cancel', 'cypress cleanup'))
    created.dayResets.forEach((id) => post('/clinic/doctors/day', { providerId: id, reset: true }))
    cy.request({ method: 'POST', url: '/clinic/settings/reset?key=clinic.queue.multiDoctorPerDay', failOnStatusCode: false })
    // this run's patients, and any an interrupted run left (same names, 7-digit suffix)
    const sweep = new Set(created.patients)
    const customers = new Set(created.customers)
    cy.request({ url: '/clinic/patients?q=S2%20Patient', failOnStatusCode: false }).then((r) => {
      ;((r.body && r.body.data) || []).forEach((p) => {
        if (PREFIXES.some((x) => new RegExp('^' + x + '\\S+ \\d{7}$').test(p.name))) {
          sweep.add(p.id)
          if (p.customerId) customers.add(p.customerId)
        }
      })
    })
    cy.then(() => {
      sweep.forEach((id) => post(`/clinic/patients/${id}/retire`, { reason: 'cypress cleanup' }))
      customers.forEach((id) =>
        cy.request({ method: 'POST', url: '/deleteCustomer', form: true, body: { checked: String(id) }, failOnStatusCode: false }))
    })
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  // ── S2-01 — the Doctors screen ─────────────────────────────────────────────────────────────────────
  it('S2-01 [B-03] the owner adds a doctor with a daily limit on the Doctors screen; the doctor gets a token letter', () => {
    const name = 'Dr Gate ' + run
    cy.visit('/clinicDashboard')
    // 1. Doctors.
    cy.get('#clinNavDoctors').click()
    cy.get('#DoctorsDiv').should('be.visible')
    // 2. Name, speciality, fee, 12 patients a day.
    cy.get('#clinDocName').type(name)
    cy.get('#clinDocSpeciality').type('Paediatrics')
    cy.get('#clinDocFee').type('1500')
    cy.get('#clinDocLimit').type('12')
    cy.intercept('POST', '**/clinic/doctors').as('add')
    cy.get('#clinDocSave').click()
    cy.wait('@add').then(({ response }) => {
      expect(response.body.success, JSON.stringify(response.body)).to.eq(true)
      expect(response.body.data.usualLimit).to.eq(12)
      expect(response.body.data.tokenPrefix, 'a token letter').to.match(/^[A-Z]{1,2}$/)
    })
    // EXPECTED: the row shows the letter, 12 as the usual limit and today, nothing issued yet.
    cy.contains('#clinDoctorBody tr', name).within(() => {
      cy.get('td').eq(1).invoke('text').should('match', /^[A-Z]{1,2}$/)
      cy.get('td').eq(2).should('have.text', '12')
      cy.get('td').eq(4).should('have.text', '0')
    })
    cy.screenshot('hms-s2/S2-01-doctor-added', { capture: 'viewport' })

    // 3. "No limit" disables the number and saves a doctor without a limit.
    cy.get('#clinDocName').type(name + ' NL')
    cy.get('#clinDocNoLimit').check()
    cy.get('#clinDocLimit').should('be.disabled')
    cy.get('#clinDocSave').click()
    cy.wait('@add').its('response.body.data.usualLimit').should('eq', null)
    cy.contains('#clinDoctorBody tr', name + ' NL').find('td').eq(2).should('have.text', 'No limit')
  })

  // ── S2-02 — reception issues a token ───────────────────────────────────────────────────────────────
  it('S2-02 [02] reception finds the patient, chooses the doctor and issues a token; the slip shows it', () => {
    newPatient('Ali').then((p) => {
      cy.visit('/clinicDashboard')
      // 1. Type the phone → the patient opens, and under it the token panel.
      cy.get('#clinPatPhone').type(p.phone + '{enter}')
      cy.get('#clinPatFound').should('contain', p.name)
      cy.get('#clinTokenPanel').should('be.visible')
      // 2. Choose Dr Ahmed (gate) — a first visit has no "last doctor".
      cy.get('#clinDoctor').select(String(doc.ahmed.id))
      cy.intercept('POST', '**/clinic/tokens').as('token')
      cy.get('#clinIssueToken').click()
      cy.wait('@token').then(({ response }) => {
        expect(response.body.success, JSON.stringify(response.body)).to.eq(true)
        const t = response.body.data
        created.tokens.push(t.id)
        pt.aliToken = t
        expect(t.status).to.eq('WAITING')
        expect(t.tokenLabel).to.match(new RegExp('^' + doc.ahmed.tokenPrefix + '-\\d{3}$'))
        expect(t.providerName).to.eq('Dr Ahmed (gate)')
        expect(t.mrn).to.eq(p.mrn)
        // EXPECTED on screen: the big slip with the token and the doctor.
        cy.get('#clinTokenSlip').should('be.visible')
        cy.get('#clinTokenNo').should('have.text', t.tokenLabel)
        cy.get('#clinTokenMeta').should('contain', 'Dr Ahmed (gate)')
      })
      cy.screenshot('hms-s2/S2-02-token-issued', { capture: 'viewport' })
    })
  })

  it('S2-03 [02b] a second token with the same doctor the same day is refused, naming the first', () => {
    issue(pt.Ali.id, doc.ahmed.id).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.contain('already has token ' + pt.aliToken.tokenLabel + ' with Dr Ahmed (gate)')
    })
  })

  it('S2-04 [02c] on the next lookup the patient\'s last doctor is preselected, and the screen says why', () => {
    cy.visit('/clinicDashboard')
    cy.get('#clinPatPhone').type(pt.Ali.phone + '{enter}')
    cy.get('#clinTokenPanel').should('be.visible')
    cy.get('#clinDoctor').should('have.value', String(doc.ahmed.id))
    cy.get('#clinDoctorHint').should('have.text', 'Last visit was with this doctor.')
    cy.screenshot('hms-s2/S2-04-last-doctor-preselected', { capture: 'viewport' })
  })

  // ── S2-05 / S2-06 — limits and closed days ─────────────────────────────────────────────────────────
  it('S2-05 [B-03] the day\'s limit is enforced; "no limit today" lifts it; "usual" puts it back', () => {
    doctorNow(doc.sana.id).then((d) => {
      // 1. Today's limit = what Dr Sana already has + 1 (re-runnable the same day).
      setDay(d.id, { limit: d.issuedToday + 1 }).its('todayLimit').should('eq', d.issuedToday + 1)
      newPatient('Limit1').then((a) => issue(a.id, d.id).its('body.success').should('eq', true))
      // 2. The next one is over the limit.
      newPatient('Limit2').then((b) => {
        issue(b.id, d.id).then((r) => {
          expect(r.body.success).to.eq(false)
          expect(r.body.message).to.contain("Dr Sana (gate)'s limit for today (" + (d.issuedToday + 1) + ' patients) is reached')
        })
        // 3. 0 = no limit today → the same patient now gets a token.
        setDay(d.id, { limit: 0 }).its('todayLimit').should('eq', null)
        issue(b.id, d.id).its('body.success').should('eq', true)
      })
      // 4. Back to the usual.
      setDay(d.id, { reset: true }).its('todayChanged').should('eq', false)
    })
  })

  it('S2-06 "Not today" closes the doctor for the day: the picker disables them and the server refuses', () => {
    setDay(doc.ahmed.id, { closed: true }).its('closedToday').should('eq', true)
    newPatient('Closed').then((p) => {
      issue(p.id, doc.ahmed.id).then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.message).to.eq('Dr Ahmed (gate) is not seeing patients today.')
      })
      cy.visit('/clinicDashboard')
      cy.get('#clinPatPhone').type(p.phone + '{enter}')
      cy.get(`#clinDoctor option[value="${doc.ahmed.id}"]`).should('be.disabled').and('contain', 'not today')
      cy.screenshot('hms-s2/S2-06-doctor-not-today', { capture: 'viewport' })
    })
    // On the Doctors screen "Open again" restores the day.
    cy.visit('/clinicDashboard')
    cy.get('#clinNavDoctors').click()
    cy.contains('#clinDoctorBody tr', 'Dr Ahmed (gate)').within(() => {
      cy.contains('Not today')
      cy.contains('button', 'Open again').click()
    })
    cy.contains('#clinDoctorBody tr', 'Dr Ahmed (gate)').should('not.contain', 'Not today')
  })

  // ── S2-07 — the board ──────────────────────────────────────────────────────────────────────────────
  it('S2-07 [M-04 board] today\'s queue lists the tokens; Cancel takes one out and it can be issued again', () => {
    cy.visit('/clinicDashboard')
    cy.get('#clinNavQueue').click()
    cy.get('#QueueDiv').should('be.visible')
    cy.contains('#clinQueueBoard tr', pt.aliToken.tokenLabel).within(() => {
      cy.contains('Waiting')
      cy.contains(pt.Ali.name)
      cy.contains(pt.Ali.mrn)
    })
    cy.get('#clinQueueSummary').should('contain', 'waiting')
    cy.screenshot('hms-s2/S2-07-1-board', { capture: 'viewport' })

    // A token issued elsewhere appears without a reload (the board refreshes every 5 s).
    newPatient('Live').then((p) => issue(p.id, doc.sana.id).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      cy.contains('#clinQueueBoard tr', r.body.data.tokenLabel, { timeout: 8000 }).should('contain', p.name)
    }))

    // Cancel Ali's token through the confirm dialog.
    cy.contains('#clinQueueBoard tr', pt.aliToken.tokenLabel).find('button[data-action="cancel"]').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.contains('#clinQueueBoard tr', pt.aliToken.tokenLabel).should('contain', 'Cancelled')
    cy.screenshot('hms-s2/S2-07-2-cancelled', { capture: 'viewport' })

    // A cancelled token frees the place: Ali can be given a new one with the same doctor (uq_token_live).
    issue(pt.Ali.id, doc.ahmed.id).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.tokenLabel).to.not.eq(pt.aliToken.tokenLabel)
      pt.aliToken = r.body.data
    })
  })

  // ── S2-08 / S2-09 — the state machine and several doctors ─────────────────────────────────────────
  it('S2-08 [07b] only the drawn moves are allowed, and a refusal says why', () => {
    const t = pt.aliToken
    move(t.id, 'call').its('body.data.status').should('eq', 'CALLED')
    move(t.id, 'call').then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.eq(t.tokenLabel + ' is called — it cannot be called.')
    })
    move(t.id, 'start').its('body.data.status').should('eq', 'IN_CONSULTATION')
    move(t.id, 'park').then((r) => expect(r.body.message).to.contain('Say why'))
    move(t.id, 'park', 'CBC, LFT pending').its('body.data.parkReason').should('eq', 'CBC, LFT pending')
    move(t.id, 'cancel').then((r) => expect(r.body.message).to.eq(t.tokenLabel + ' is parked — it cannot be cancelled.'))
    move(t.id, 'resume').its('body.data.status').should('eq', 'IN_CONSULTATION')
    move(t.id, 'complete').its('body.data.status').should('eq', 'COMPLETED')
    move(t.id, 'call').then((r) => expect(r.body.message).to.eq(t.tokenLabel + ' is completed — it cannot be called.'))
  })

  it('S2-09 [04c] a patient may see two doctors a day, one at a time; the setting can forbid the second', () => {
    newPatient('Two').then((p) => {
      issue(p.id, doc.ahmed.id).then((ra) => {
        expect(ra.body.success, JSON.stringify(ra.body)).to.eq(true)
        issue(p.id, doc.sana.id).then((rb) => {
          expect(rb.body.success, 'a second doctor the same day').to.eq(true)
          move(ra.body.data.id, 'call').its('body.success').should('eq', true)
          move(rb.body.data.id, 'call').then((r) => {
            expect(r.body.success).to.eq(false)
            expect(r.body.message).to.eq('This patient is with Dr Ahmed (gate) now (' + ra.body.data.tokenLabel + ').')
          })
        })
      })
    })
    cy.request({ method: 'POST', url: '/clinic/settings?key=clinic.queue.multiDoctorPerDay&value=false' })
      .its('body.success').should('eq', true)
    newPatient('One').then((p) => {
      issue(p.id, doc.ahmed.id).its('body.success').should('eq', true)
      issue(p.id, doc.sana.id).then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.message).to.contain('one token per patient per day')
      })
    })
    cy.request({ method: 'POST', url: '/clinic/settings/reset?key=clinic.queue.multiDoctorPerDay' })
  })

  // ── S2-10 / S2-11 — isolation ──────────────────────────────────────────────────────────────────────
  it('S2-10 [M-14] another clinic — clinic switched on — cannot read a token or use this clinic\'s doctor', () => {
    cy.loginAsOperator()
    cy.setEntitlement(OTHER_CLINIC, 'clinic', 'ACTIVE', 'hms s2 gate: cross-tenant control')
    relogin(OTHER_CLINIC, 'pre')
    cy.setCapability('clinic', true)
    relogin(OTHER_CLINIC, 'on')
    cy.request({ url: `/clinic/tokens/${pt.aliToken.id}`, failOnStatusCode: false }).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message, JSON.stringify(r.body)).to.match(/Token not found/)
    })
    cy.request('/clinic/queue').its('body.data').then((rows) =>
      expect(rows.map((x) => x.tokenLabel), 'another clinic\'s board').to.not.include(pt.aliToken.tokenLabel))
    post('/clinic/tokens', { patientId: pt.Ali.id, providerId: doc.ahmed.id }).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.match(/not found/)
    })
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' } })
    relogin(OWNER)
  })

  it('S2-11 a doctor cannot be put in another organisation\'s venue (appointment-service, fixed in S2)', () => {
    const venue = 'S2 Venue ' + run
    cy.loginAsAppointmentOwner()
    cy.request({ method: 'POST', url: '/registerHospital', form: true,
                 body: { name: venue, phone: '03001234567', email: `v${run}@test.com`, countryCode: 'PK', state: 'Sindh', geoId: 'Karachi', hours: '24' } })
    cy.request('/appointment').then((page) => {
      const m = new RegExp('<option[^>]*value=["\']?(\\d+)["\']?[^>]*>\\s*' + venue).exec(page.body)
      expect(m, 'the venue is on the public booking page').to.not.be.null
      const venueId = m[1]
      relogin(OTHER_CLINIC, 'idor')
      cy.request({ method: 'POST', url: '/registerDoctor', form: true, failOnStatusCode: false,
                   body: { hospitalId: venueId, name: 'Intruder ' + run, speciality: 'X', email: `i${run}@test.com`, mobile: '03000000000',
                           address: 'x', availabe: 'All', dayFrom: 'Monday', dayTo: 'Friday', timeIn: '09:00', timeOut: '17:00',
                           appointmentOfferType: 'count', appointmentOfferValue: '5' } })
        .its('body.error').should('eq', 'RegisterFailed')
      cy.loginAsAppointmentOwner()
      cy.request(`/loadDoctorsByHospital?hospitalId=${venueId}`).its('body').should('not.contain', 'Intruder ' + run)
    })
    relogin(OWNER)
  })
})
