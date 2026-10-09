/**
 * HMS S1 — the clinic's front desk: patients by phone, one MRN each, registered as the pharmacy's customer too.
 *
 * Design: microservices/docs/hms-phase1-design.md (client decisions of 2026-10-09, §1). Test page:
 * https://claude.ai/artifact/RTCGToYCyqJHVsR7VVnfAm. This is the SLICE GATE — real screens, not gated by a flag.
 * The first case drives the screen end to end; the rest prove the rules behind it.
 *
 * Tenant: owner.pharma@ — a pharmacy that also runs a clinic (one organisation, design §1 Q-1). Ladder: user.pharma@
 * (the front desk). Cross-tenant control: owner.business@ (another organisation).
 *
 * Server state this spec changes, and puts back in after() (GATE-RUNBOOK §5):
 *   - entitlement `clinic` ACTIVE for owner.pharma's org (granted by the operator; left ACTIVE like MKT-0a — the
 *     module itself is switched OFF again, which is what decides behaviour)
 *   - capability `clinic` switched on → reset to its default (OFF)
 *   - the same entitlement + switch for owner.business@ (S1-08's cross-tenant control) → switch reset in the case
 *   - clinic settings familyOnOnePhone / cnicRequired → reset to defaults
 *   - patients created → RETIRED (clinical records are never hard-deleted); their pharmacy customers → deleted
 *
 * Run headed:
 *   npx cypress run --headed --browser chrome --spec "cypress/e2e/hms/hms-s1-patient-desk.cy.js"
 */
describe('HMS S1 — patient desk (reception → MRN → pharmacy customer)', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const FRONT_DESK = 'user.pharma@myplus.com'
  const OTHER_CLINIC = 'owner.business@myplus.com'
  const PW = 'Demo@2025!'

  const run = String(Date.now()).slice(-7)
  // Unique mobile numbers per run: 03 + 9 digits. One patient per phone means a re-run must never reuse one.
  let seq = 0
  const newPhone = () => '034' + run.slice(-6) + String(seq++ % 10) + String(Math.floor(Math.random() * 10))
  const created = { patients: [], customers: [] }
  // S1-01's patient, read by later cases. A plain variable: Cypress clears aliases between tests.
  let ali = null
  // Names this spec gives its patients — the after() sweep retires ANY of them left by an interrupted run.
  const PREFIXES = ['Ali Khan ', 'Cnic Check ', 'Rashid Ahmed ', 'Usman Rashid ', 'Front Desk ']

  // One session per (account, phase). A FRESH token is needed only when the clinic switch changes, because the
  // capability travels in the token; any other case reuses the phase's session.
  const relogin = (email, phase = 'on') => cy.loginAs(email, PW, '/getBusinessDashboardStats', `hms-s1-${run}-${phase}`)
  const post = (url, body) =>
    cy.request({ method: 'POST', url, body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
  const lookup = (phone) =>
    cy.request({ url: '/clinic/patients?phone=' + encodeURIComponent(phone), failOnStatusCode: false })
  const setClinicSetting = (key, value) =>
    cy.request({ method: 'POST', url: `/clinic/settings?key=${key}&value=${value}`, failOnStatusCode: false })
      .then((r) => expect(r.body && r.body.success, `${key}=${value}: ${JSON.stringify(r.body)}`).to.eq(true))
  const register = (body) => post('/clinic/patients', body).then((r) => {
    if (r.body && r.body.success) {
      created.patients.push(r.body.data.id)
      if (r.body.data.customerId) created.customers.push(r.body.data.customerId)
    }
    return r
  })

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms s1 gate')
    relogin(OWNER, 'pre')
    cy.setCapability('clinic', true)
    relogin(OWNER)                       // the capability travels in the token: a new token carries it
  })

  after(() => {
    relogin(OWNER, 'cleanup')
    // This run's patients, plus any a previous interrupted run left behind (same names, 7-digit run suffix).
    const sweep = new Set(created.patients)
    const customers = new Set(created.customers)
    PREFIXES.forEach((prefix) =>
      cy.request({ url: '/clinic/patients?q=' + encodeURIComponent(prefix.trim()), failOnStatusCode: false }).then((r) => {
        ;((r.body && r.body.data) || []).forEach((p) => {
          if (PREFIXES.some((x) => new RegExp('^' + x + '\\d{7}$').test(p.name))) {
            sweep.add(p.id)
            if (p.customerId) customers.add(p.customerId)
          }
        })
      }))
    cy.then(() => {
      sweep.forEach((id) => post(`/clinic/patients/${id}/retire`, { reason: 'cypress cleanup' }))
      customers.forEach((id) =>
        cy.request({ method: 'POST', url: '/deleteCustomer', form: true, body: { checked: String(id) }, failOnStatusCode: false }))
    })
    ;['clinic.patient.familyOnOnePhone', 'clinic.patient.cnicRequired'].forEach((k) =>
      cy.request({ method: 'POST', url: `/clinic/settings/reset?key=${k}`, failOnStatusCode: false }))
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  // ── S1-01 — the real screen first ──────────────────────────────────────────────────────────────────
  it('S1-01 [M-01] reception registers a patient with only the phone; MRN shown; the pharmacy customer is linked', () => {
    const phone = newPhone()
    const name = 'Ali Khan ' + run
    relogin(OWNER)
    cy.intercept('GET', '**/clinic/patients?phone=*').as('lookup')
    cy.intercept('POST', '**/clinic/patients').as('register')

    // 1. Pharmacy menu → Clinic reception.
    cy.visit('/businessDashboard')
    cy.get('#snavPharmacy > .snav-btn').should('be.visible').click()
    cy.get('#navClinic').should('be.visible').click()
    cy.location('pathname').should('eq', '/clinicDashboard')
    cy.get('#ReceptionDiv').should('be.visible')
    cy.screenshot('hms-s1/S1-01-1-reception', { capture: 'viewport' })

    // 2. Type the phone and press Enter.
    cy.get('#clinPatPhone').should('be.visible').type(phone + '{enter}')
    cy.wait('@lookup').its('response.body.data.patients').should('have.length', 0)

    // 3. Unknown number → the registration form, phone shown, everything else optional. Type only the name.
    cy.get('#clinPatForm').should('be.visible')
    cy.get('#clinFormPhone').should('contain', phone)
    cy.get('#clinPatName').type(name)
    cy.screenshot('hms-s1/S1-01-2-new-patient-form', { capture: 'viewport' })

    // 4. Register.
    cy.get('#clinPatSave').click()
    cy.wait('@register').then(({ response }) => {
      expect(response.body.success, JSON.stringify(response.body)).to.eq(true)
      const p = response.body.data
      created.patients.push(p.id)
      if (p.customerId) created.customers.push(p.customerId)
      // EXPECTED: an MRN in the clinic's format; the person and the pharmacy customer both linked.
      expect(p.mrn).to.match(/^MRN-[A-Z0-9]+-\d{2}-\d{6}$/)
      expect(p.phone).to.eq(phone)
      expect(p.partyId, 'one person in party-service').to.be.a('number')
      expect(p.customerId, 'registered as the pharmacy customer at the same time').to.be.a('number')
      expect(p.linkPending).to.eq(false)
      ali = p
    })

    // EXPECTED on screen: the patient card with the MRN and "✓ Linked"; the patient at the top of the list.
    cy.get('#clinPatFound').should('be.visible').and('contain', name)
    cy.get('#clinPatMrn').invoke('text').should('match', /^MRN-/)
    cy.get('#clinPatFound').should('contain', '✓ Linked')
    cy.get('#clinPatBody tr').first().should('contain', name).and('contain', phone)
    cy.screenshot('hms-s1/S1-01-3-registered-with-mrn', { capture: 'viewport' })
  })

  it('S1-02 [B-01(2)] the patient IS the pharmacy customer — same name, phone and person, no second record', () => {
    relogin(OWNER)
    cy.then(() => {
      cy.request('/getUserCustomer').then((r) => {
        const list = (r.body && (r.body.collection || r.body.data || r.body.object)) || []
        const c = list.find((x) => Number(x.customerId || x.id) === Number(ali.customerId))
        expect(c, `customer ${ali.customerId} in the pharmacy's list: ${JSON.stringify(r.body).slice(0, 300)}`).to.exist
        expect(c.name).to.eq(ali.name)
        expect(c.contact).to.eq(ali.phone)
        expect(Number(c.partyId), 'the same person as the patient').to.eq(Number(ali.partyId))
      })
      // Linking again is idempotent: still the same customer, never a second.
      post(`/clinic/patients/${ali.id}/link`, {}).its('body.data.customerId').should('eq', ali.customerId)
    })
  })

  // ── S1-03 — one patient per phone ──────────────────────────────────────────────────────────────────
  it('S1-03 [M-02] the same phone again opens the existing patient; a second registration is refused with the MRN', () => {
    relogin(OWNER)
    cy.then(() => {
      // 1. On screen: type the known number (another spelling of it).
      cy.visit('/clinicDashboard')
      cy.get('#clinPatPhone').type('+92' + ali.phone.slice(1) + '{enter}')
      // EXPECTED: the patient opens — no form, no family button (the clinic keeps one patient per phone).
      cy.get('#clinPatFound').should('be.visible').and('contain', ali.name).and('contain', ali.mrn)
      cy.get('#clinPatForm').should('not.be.visible')
      cy.get('#clinPatFamilyAdd').should('not.exist')
      cy.screenshot('hms-s1/S1-03-known-phone-opens-patient', { capture: 'viewport' })

      // 2. Through the API, the same number under ANOTHER name (the B-07 shape) is refused, naming who is on it.
      ;['Usman Rashid ' + run, ali.name].forEach((name) =>
        post('/clinic/patients', { phone: ali.phone, name }).then((r) => {
          expect(r.body.success, JSON.stringify(r.body)).to.eq(false)
          expect(r.body.message).to.contain('already registered to ' + ali.name).and.contain(ali.mrn)
          expect(r.body.data.existing.id, 'the refusal carries the patient').to.eq(ali.id)
        }))
    })
  })

  it('S1-04 [M-01b] a number that is not a mobile is refused with a reason, on screen and by the server', () => {
    relogin(OWNER)
    cy.visit('/clinicDashboard')
    cy.get('#clinPatPhone').type('0300123{enter}')
    cy.get('#clinPatError').should('be.visible').and('contain', 'is not a mobile number')
    cy.get('#clinPatForm').should('not.be.visible')
    cy.screenshot('hms-s1/S1-04-invalid-phone', { capture: 'viewport' })
    ;['12345', '04235761234', 'abcdefghijk', '0300123456789'].forEach((bad) =>
      post('/clinic/patients', { phone: bad, name: 'Bad ' + bad }).then((r) => {
        expect(r.body.success, bad).to.eq(false)
        expect(r.body.message, bad).to.match(/mobile number/i)
      }))
  })

  // ── S1-05 / S1-06 — the clinic's own rules ─────────────────────────────────────────────────────────
  it('S1-05 with "Require CNIC" on, a patient without one is refused; with it, the CNIC is stored formatted', () => {
    relogin(OWNER)
    setClinicSetting('clinic.patient.cnicRequired', 'true')
    const phone = newPhone()
    register({ phone, name: 'Cnic Check ' + run }).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.match(/CNIC/)
    })
    register({ phone, name: 'Cnic Check ' + run, cnic: '4220112345671' }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.cnic).to.eq('42201-1234567-1')
    })
    cy.request({ method: 'POST', url: '/clinic/settings/reset?key=clinic.patient.cnicRequired' })
  })

  it('S1-06 with "family on one phone" on, reception adds a family member deliberately; each gets an MRN', () => {
    relogin(OWNER)
    setClinicSetting('clinic.patient.familyOnOnePhone', 'true')
    const phone = newPhone()
    const father = 'Rashid Ahmed ' + run
    const son = 'Usman Rashid ' + run
    register({ phone, name: father }).its('body.success').should('eq', true)

    // 1. On screen: the known number now offers "Add family member on this number".
    cy.visit('/clinicDashboard')
    cy.get('#clinPatPhone').type(phone + '{enter}')
    cy.get('#clinPatFound').should('contain', father)
    cy.get('#clinPatFamilyAdd').should('be.visible').click()
    cy.get('#clinPatForm').should('be.visible')
    cy.get('#clinFormPhone').should('contain', 'family member')
    cy.get('#clinPatName').type(son)
    cy.get('input[name=clinPatSex][value=M]').check({ force: true })
    cy.intercept('POST', '**/clinic/patients').as('family')
    cy.get('#clinPatSave').click()
    cy.wait('@family').then(({ response }) => {
      expect(response.body.success, JSON.stringify(response.body)).to.eq(true)
      created.patients.push(response.body.data.id)
      expect(response.body.data.familySeq).to.eq(1)
      expect(response.body.data.sex).to.eq('M')
    })
    // 2. The number now lists both, each with their own MRN.
    lookup(phone).its('body.data.patients').then((ps) => {
      expect(ps.map((p) => p.name)).to.deep.eq([father, son])
      expect(ps[0].mrn).to.not.eq(ps[1].mrn)
    })
    cy.screenshot('hms-s1/S1-06-family-member-added', { capture: 'viewport' })
    cy.request({ method: 'POST', url: '/clinic/settings/reset?key=clinic.patient.familyOnOnePhone' })
  })

  // ── S1-07 — the ladder ─────────────────────────────────────────────────────────────────────────────
  it('S1-07 the front desk (user tier) registers and finds patients but cannot change clinic settings', () => {
    relogin(FRONT_DESK)
    const phone = newPhone()
    register({ phone, name: 'Front Desk ' + run }).its('body.success').should('eq', true)
    lookup(phone).its('body.data.patients').should('have.length', 1)
    cy.request({ method: 'POST', url: '/clinic/settings?key=clinic.patient.familyOnOnePhone&value=true', failOnStatusCode: false })
      .then((r) => expect(r.status === 403 || (r.body && r.body.success === false), JSON.stringify(r.body)).to.eq(true))
  })

  // ── S1-08 — another clinic ─────────────────────────────────────────────────────────────────────────
  it('S1-08 [M-14] another organisation — with its OWN clinic switched on — cannot read the patient, by id or by phone', () => {
    // The other organisation gets the clinic too: otherwise the module switch refuses first and this case would
    // pass even with org scoping broken (a gate that tests nothing). Its switch is reset at the end of the case.
    cy.loginAsOperator()
    cy.setEntitlement(OTHER_CLINIC, 'clinic', 'ACTIVE', 'hms s1 gate: cross-tenant control')
    relogin(OTHER_CLINIC, 'pre')
    cy.setCapability('clinic', true)
    relogin(OTHER_CLINIC, 'on')
    cy.then(() => {
      cy.request({ url: `/clinic/patients/${ali.id}`, failOnStatusCode: false }).then((r) => {
        expect(r.body && r.body.success, 'refused').to.not.eq(true)
        // Refused BY SCOPING: "not found", exactly like an id that does not exist — not "not switched on".
        expect(r.body.message, JSON.stringify(r.body)).to.match(/Patient not found/)
        expect(JSON.stringify(r.body || ''), 'no name leaks').to.not.contain(ali.name)
      })
      // By phone: the number is simply unknown in this clinic — the answer is an empty list, not Ali.
      lookup(ali.phone).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        expect(r.body.data.patients, 'another clinic sees nobody on this number').to.have.length(0)
      })
    })
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' } })
  })

  // ── S1-09 — the switch is real (last: it turns the module off) ─────────────────────────────────────
  it('S1-09 with the clinic switched off, the menu hides it and the server refuses patient reads', () => {
    cy.then(() => {
      relogin(OWNER)
      cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' } })
      relogin(OWNER, 'off')
      cy.visit('/businessDashboard')
      cy.get('li[data-capability="clinic"]').should('have.class', 'cap-off')
      cy.request({ url: `/clinic/patients/${ali.id}`, failOnStatusCode: false }).then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.message).to.match(/not switched on/)
      })
      cy.visit('/clinicDashboard')
      cy.get('#clinModuleOff').should('be.visible').and('contain', 'not switched on')
      cy.screenshot('hms-s1/S1-09-module-off', { capture: 'viewport' })
      // put it back on so after() (a fresh 'cleanup' token) can retire this run's patients
      cy.setCapability('clinic', true)
    })
  })
})
