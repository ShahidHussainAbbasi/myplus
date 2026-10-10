/**
 * HMS S3b-2 — prescription templates: the doctor saves the lines on screen as "Fever + Flu", and on the next patient
 * Use fills them in, each still editable (a dose changed to 1+0+1) before Save. Case 05 of the Phase 1 plan.
 *
 * Design: microservices/docs/hms-phase1-design.md §4e. SLICE GATE — T-01 drives the real screen end to end.
 *
 * Roles: owner.pharma@ = reception and setup · admin.pharma@ = the DOCTOR (moved onto the built-in "Doctor" set in
 * before(), restored to "Administrator" in after()) · user.pharma@ = the front desk · owner.business@ = another clinic.
 *
 * Server state changed and put back in after(): templates made here → removed (retired); visits left with the doctor
 * → completed; waiting tokens → cancelled; prescriptions not dispensed → cancelled; the patient → retired;
 * admin.pharma's set → Administrator; both clinic switches → reset.
 *
 * Run (headless records reliably):
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-s3b2-templates.cy.js"
 */
describe('HMS S3b-2 — prescription templates', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const DOCTOR = 'admin.pharma@myplus.com'
  const FRONT_DESK = 'user.pharma@myplus.com'
  const OTHER = 'owner.business@myplus.com'
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  const phone = '035' + run.slice(-6) + String(Math.floor(Math.random() * 90) + 10).slice(-2)
  const MED_A = 'S3t Brufen ' + run
  const MED_B = 'S3t Arinac ' + run
  const TPL = 'Fever + Flu ' + run
  const S = { tokens: [], visits: [], templates: [], rxIds: [] }

  const relogin = (email, phase = 'on') => cy.loginAs(email, PW, '/getBusinessDashboardStats', `hms-s3b2-${run}-${phase}`)
  const post = (url, body) =>
    cy.request({ method: 'POST', url, body: body || {}, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
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
  const line = (productId, medicineName, quantity, dosage) => ({ productId, medicineName, quantity: String(quantity), dosage, frequency: 'BD', duration: '5 days' })
  const openMyQueue = () => {
    cy.visit('/clinicDashboard')
    cy.get('#clinNavMyQueue').click()
    cy.get('#clinMyDoctor').select(String(S.doctor.id))
  }
  // Reception issues a token; the doctor calls and starts it ON SCREEN (leaves the consultation open).
  const startOnScreen = () => {
    relogin(OWNER)
    issueToken().then((t) => { S.current = t })
    relogin(DOCTOR, 'doctor')
    openMyQueue()
    cy.then(() => {
      cy.contains('#clinQueueBody tr', S.current.tokenLabel).find('.clin-q-call').click()
      cy.contains('#clinQueueBody tr', S.current.tokenLabel).find('.clin-q-start').click()
    })
    cy.get('#clinIdBanner').should('contain', S.patient.name)
  }

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms s3b-2 gate')
    relogin(OWNER, 'pre')
    cy.setCapability('clinic', true)
    setOf(DOCTOR, 'Doctor')
    relogin(OWNER)
    post('/clinic/patients', { phone, name: 'S3t Patient ' + run, dateOfBirth: '1990-05-20', sex: 'M' }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      S.patient = r.body.data
    })
    cy.request('/clinic/doctors').then((r) => {
      const d = (r.body.data || []).find((x) => x.name === 'Dr Ahmed (gate)')
      if (d) { S.doctor = d; return }
      return post('/clinic/doctors', { name: 'Dr Ahmed (gate)', speciality: 'General medicine', dailyLimit: null })
        .then((c) => { S.doctor = c.body.data })
    })
    cy.seedProduct({ name: MED_A, unit: 'tablet', stock: 50, sellingPrice: 5 }).then(({ productId }) => { S.medA = productId })
    cy.seedProduct({ name: MED_B, unit: 'tablet', stock: 50, sellingPrice: 8 }).then(({ productId }) => { S.medB = productId })
  })

  after(() => {
    relogin(DOCTOR, 'doctor')
    // the encounter of every token this spec started — complete it (a visit with the doctor cannot be cancelled)
    S.tokens.forEach((id) => cy.request({ url: `/clinic/consult/tokens/${id}`, failOnStatusCode: false }).then((r) => {
      const v = r.body && r.body.data
      if (v && v.id && v.status !== 'COMPLETED') post(`/clinic/consult/encounters/${v.id}/complete`)
      if (v && v.rxId) S.rxIds.push(v.rxId)
    }))
    cy.then(() => S.templates.forEach((id) => post(`/clinic/consult/templates/${id}/retire`)))
    relogin(OWNER)
    cy.then(() => S.rxIds.forEach((id) => post('/cancelPrescription', { prescriptionId: id })))
    S.tokens.forEach((id) => post(`/clinic/tokens/${id}/cancel`, { reason: 'cypress cleanup' }))
    if (S.patient) post(`/clinic/patients/${S.patient.id}/retire`, { reason: 'cypress cleanup' })
    setOf(DOCTOR, 'Administrator')
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  // ── T-01 — case 05, on the real screen ─────────────────────────────────────────────────────────────
  it('T-01 [05] the doctor saves "Fever + Flu" as a template; on the next patient Use fills it and a dose is changed to 1+0+1', () => {
    // Visit 1: write two medicines → Save as template.
    startOnScreen()
    cy.get('#clinRxMedicines option').should('have.length.greaterThan', 1)
    cy.get('#clinRxMedicine').type(MED_A)
    cy.get('#clinRxQty').type('10')
    cy.get('#clinRxDose').type('1 tab')
    cy.get('#clinRxFreq').type('TDS')
    cy.get('#clinRxDays').type('5 days{enter}')
    cy.get('#clinRxMedicine').type(MED_B)
    cy.get('#clinRxQty').type('15')
    cy.get('#clinRxDose').type('1 tab')
    cy.get('#clinRxFreq').type('TDS')
    cy.get('#clinRxDays').type('5 days{enter}')
    cy.get('#clinRxBody tr').should('have.length', 2)
    cy.intercept('POST', '**/clinic/consult/templates').as('saveTpl')
    cy.get('#clinRxTemplateName').type(TPL)
    cy.get('#clinRxSaveTemplate').click()
    cy.wait('@saveTpl').then(({ response }) => {
      expect(response.body.success, JSON.stringify(response.body)).to.eq(true)
      S.templates.push(response.body.data.id)
      S.tplId = response.body.data.id
    })
    cy.get('#clinMsg').should('contain', 'Saved as the template').and('contain', TPL)
    cy.then(() => cy.get('#clinRxTemplate').should('have.value', String(S.tplId)))
    cy.screenshot('hms-s3b2/T-01-1-saved-as-template', { capture: 'viewport' })
    cy.get('#clinComplete').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('#clinStatus').should('have.attr', 'data-status', 'COMPLETED')

    // Visit 2: the next patient visit — the template fills an empty prescription.
    startOnScreen()
    cy.get('#clinRxBody tr').should('have.length', 0)
    cy.then(() => cy.get('#clinRxTemplate').select(String(S.tplId)))
    cy.get('#clinRxUseTemplate').click()
    cy.get('#clinMsg').should('contain', '2 medicine(s) from').and('contain', TPL)
    cy.get('#clinRxBody tr').should('have.length', 2)
    // the lines are editable: change the first dose to 1+0+1
    cy.contains('#clinRxBody tr', MED_A).find('input[data-f="dosage"]').should('have.value', '1 tab').clear().type('1+0+1')
    cy.screenshot('hms-s3b2/T-01-2-template-used-dose-changed', { capture: 'viewport' })
    cy.intercept('PUT', '**/clinic/consult/encounters/*/rx').as('saveRx')
    cy.get('#clinRxSave').click()
    cy.wait('@saveRx').then(({ response }) => {
      expect(response.body.success, JSON.stringify(response.body)).to.eq(true)
      const byName = Object.fromEntries(response.body.data.rxLines.map((l) => [l.medicineName, l]))
      expect(byName[MED_A].dosage, 'the changed dose was saved').to.eq('1+0+1')
      expect(byName[MED_B].quantity).to.eq('15')
    })
    // EXPECTED: the template itself is unchanged — Use copied it, editing the visit did not touch it
    cy.request('/clinic/consult/templates').then((r) => {
      const t = r.body.data.find((x) => x.id === S.tplId)
      expect(t.lines.find((l) => l.medicineName === MED_A).dosage).to.eq('1 tab')
    })
  })

  it('T-02 Use twice adds nothing twice: the screen says the medicines are already on the list', () => {
    // continues the open visit 2 of T-01
    relogin(DOCTOR, 'doctor')
    openMyQueue()
    cy.then(() => cy.contains('#clinQueueBody tr', S.current.tokenLabel).find('.clin-q-start').click())
    cy.get('#clinRxBody tr').should('have.length', 2)
    cy.then(() => cy.get('#clinRxTemplate').select(String(S.tplId)))
    cy.get('#clinRxUseTemplate').click()
    cy.get('#clinMsg').should('contain', '0 medicine(s)').and('contain', 'Already on the list').and('contain', MED_A).and('contain', MED_B)
    cy.get('#clinRxBody tr').should('have.length', 2)
  })

  it('T-03 a medicine the pharmacy no longer has is named, not silently dropped', () => {
    relogin(DOCTOR, 'doctor')
    // A template whose second medicine is not in this pharmacy's list (an id no product has).
    post('/clinic/consult/templates', { name: 'Old stock ' + run, lines: [line(S.medA, MED_A, 5, '1 tab'), line(987654321, 'Discontinued Syrup', 1, '5 ml')] })
      .then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        S.templates.push(r.body.data.id)
        S.oldTpl = r.body.data.id
      })
    openMyQueue()
    cy.then(() => cy.contains('#clinQueueBody tr', S.current.tokenLabel).find('.clin-q-start').click())
    // clear the lines on screen first, so MED_A can be added again
    cy.get('#clinRxBody .clin-rx-remove').each(() => cy.get('#clinRxBody .clin-rx-remove').first().click())
    cy.get('#clinRxBody tr').should('have.length', 0)
    cy.then(() => cy.get('#clinRxTemplate').select(String(S.oldTpl)))
    cy.get('#clinRxUseTemplate').click()
    cy.get('#clinMsg').should('have.class', 'alert-danger')
      .and('contain', "Not in the pharmacy's list any more: Discontinued Syrup")
    // SEEN, not only shown: inside the viewport while the doctor is down at the prescription (S4 fix — it used to
    // render at the top of the page, scrolled out of view; 'be.visible' alone passed then)
    cy.window().then((win) => cy.get('#clinMsg').then(($m) => {
      const r = $m[0].getBoundingClientRect()
      expect(r.top, 'message top is on screen').to.be.within(0, win.innerHeight - 20)
      expect(r.bottom, 'message bottom is on screen').to.be.within(20, win.innerHeight)
    }))
    cy.get('#clinRxBody tr').should('have.length', 1).and('contain', MED_A).and('not.contain', 'Discontinued')
    cy.screenshot('hms-s3b2/T-03-1-missing-medicine-named', { capture: 'viewport' })
  })

  it('T-04 a template name is unique in the clinic, in any letter case; Remove frees it', () => {
    relogin(DOCTOR, 'doctor')
    post('/clinic/consult/templates', { name: TPL.toUpperCase(), lines: [line(S.medB, MED_B, 1, '1 tab')] }).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.contain('exists already')
    })
    // Remove on the screen → gone from the list; the same name can then be saved again.
    openMyQueue()
    cy.then(() => cy.contains('#clinQueueBody tr', S.current.tokenLabel).find('.clin-q-start').click())
    cy.then(() => cy.get('#clinRxTemplate').select(String(S.tplId)))
    cy.get('#clinRxRetireTemplate').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('#clinMsg').should('contain', 'Template removed')
    cy.then(() => cy.get(`#clinRxTemplate option[value="${S.tplId}"]`).should('not.exist'))
    post('/clinic/consult/templates', { name: TPL, lines: [line(S.medB, MED_B, 1, '1 tab')] }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      S.templates.push(r.body.data.id)
    })
  })

  // ── T-05 / T-06 — who may not ──────────────────────────────────────────────────────────────────────
  it('T-05 [M-15] the front desk cannot read, save or remove templates', () => {
    relogin(FRONT_DESK)
    cy.request({ url: '/clinic/consult/templates', failOnStatusCode: false }).then((r) => {
      expect(r.status).to.eq(403)
      expect(JSON.stringify(r.body)).to.contain('Only a doctor').and.not.contain(TPL)
    })
    post('/clinic/consult/templates', { name: 'Desk ' + run, lines: [line(S.medA, MED_A, 1, '1 tab')] }).its('status').should('eq', 403)
    post(`/clinic/consult/templates/${S.oldTpl}/retire`).its('status').should('eq', 403)
  })

  it('T-06 [M-14] another clinic — its owner, clinic switched on — neither sees nor removes this clinic\'s templates', () => {
    cy.loginAsOperator()
    cy.setEntitlement(OTHER, 'clinic', 'ACTIVE', 'hms s3b-2 gate: cross-tenant control')
    relogin(OTHER, 'other-pre')
    cy.setCapability('clinic', true)
    relogin(OTHER, 'other')
    cy.request('/clinic/consult/templates').then((r) => {
      expect(r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)   // the owner holds clinic.consult: a real read
      expect(JSON.stringify(r.body.data)).to.not.contain(run)
    })
    post(`/clinic/consult/templates/${S.oldTpl}/retire`).then((r) => {
      expect(r.body.success).to.not.eq(true)
      expect(r.body.message).to.match(/Template not found/)
    })
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' } })
    // still there for its own clinic
    relogin(DOCTOR, 'doctor')
    cy.request('/clinic/consult/templates').its('body.data').then((list) => {
      expect(list.map((t) => t.id)).to.include(S.oldTpl)
    })
  })
})
