/**
 * HC-1d — the clinic's doctor prescribes from the LINKED pharmacy's medicine list and the prescription reaches that
 * pharmacy (a separate business), as REAL step-by-step manual cases for the Test Book. Written BEFORE the build (the
 * user's rule): every action is PERFORMED on the screen, ASSERTED and PHOTOGRAPHED (support/guide-capture.js), and each
 * case ends with its clean-up. Design: microservices/docs/slices/hc-1cd-clinic-pharmacy-link.md (HC-1d design).
 *
 *   X1  a clinic with no linked pharmacy: the prescription area says "Link your pharmacy first" (no list, no Submit)
 *   X2  linked: the doctor's list is the pharmacy's medicines — names and strengths, never price or stock
 *   X3  the doctor adds a medicine from that list and Submits: "Sent to <pharmacy>"
 *   X4  the pharmacy finds it, marked "From clinic <name>" — patient, doctor, medicine; NOT the diagnosis
 *   X5  the pharmacist dispenses it; the clinic reads "At the pharmacy: Dispensed"
 *   X6  (HC-1d-2) a line written by hand; the pharmacist chooses its product with Change
 *   X7  the link is ended: the doctor's next Submit is refused in words; the pharmacy keeps what it received
 *
 * EXPECTED RED on the build it was written against: a separated clinic picks from its OWN (empty) catalogue, and a
 * Submit writes into the CLINIC's organisation (ConsultService.submitRx → pharma create with the doctor's token).
 *
 * TENANTS: the run provisions its own clinic and pharmacy through the console's own request (names carry the run id,
 * e-mails end in .test, set-password links come from Mailpit), links them, and suspends both at the end.
 *
 * Run headed:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/docs/hc-1d-rx-guide-screens.cy.js --config trashAssetsBeforeRuns=false
 */
import { guideCapture } from '../../support/guide-capture'

const g = guideCapture({ outDir: 'cypress/guide-out/hc-1d', section: 'Prescriptions to the linked pharmacy', keepShots: true })
const RUN = String(Date.now()).slice(-6)
const MAILPIT = 'http://localhost:8025'
const PW = 'Kiwi#Lamp58'
const CLINIC = { org: `HC1D Clinic ${RUN}`, email: `hc1d.clinic.${RUN}@myplus.test`, type: 'hospital_clinic' }
const PHARMA = { org: `HC1D Pharmacy ${RUN}`, email: `hc1d.pharma.${RUN}@myplus.test`, type: 'pharmacy' }
const DOCTOR = `Dr HC1D ${RUN}`
const PATIENT = { name: `HC1D Patient ${RUN}`, phone: '0345' + RUN + '1' }
const MED = { name: `Amoxil ${RUN} 500mg`, formula: 'Amoxicillin 500mg', price: 37, stock: 40 }
const HAND = `Hand-written syrup ${RUN} 120ml`
const COMPLAINT = `Sore throat ${RUN}`          // typed by the doctor — must NOT reach the pharmacy (D3)
const S = {}

const setPasswordLink = (email) => {
  const look = (tries) => cy.request(`${MAILPIT}/api/v1/messages?limit=100`).then((r) => {
    const m = (r.body.messages || []).find((x) => (x.To || []).some((a) => a.Address === email))
    if (m) return cy.request(`${MAILPIT}/api/v1/message/${m.ID}`).its('body').then((b) => /token=([A-Za-z0-9-]+)/.exec(b.Text || b.HTML || '')[1])
    if (tries <= 0) throw new Error(`no set-password e-mail to ${email}`)
    return cy.wait(1000).then(() => look(tries - 1))
  })
  return look(20)
}
const choosePassword = (token) => {
  cy.clearCookies()
  cy.visit(`/user/changePassword?token=${token}`)
  cy.get('#password').type(PW, { log: false })
  cy.get('#matchPassword').type(PW, { log: false })
  cy.contains('button', /update|save|submit/i).click()
  cy.location('pathname', { timeout: 15000 }).should('include', '/login')
}
const signIn = (email) => {
  cy.clearCookies()
  cy.visit('/login')
  cy.get('input[name="username"]').type(email)
  cy.get('input[name="password"]').type(PW, { log: false })
  cy.get('#loginSubmit').click()
  cy.location('pathname', { timeout: 20000 }).should('not.include', '/login')
}
const provision = (t) => cy.request({ method: 'POST', url: '/platform/provisionTenant', form: true, body: {
  organizationName: t.org, email: t.email, firstName: 'Tahseen', lastName: `Test ${RUN}`, plan: 'PRO', businessType: t.type } })
  .its('body.success').should('eq', true)
const ok = (r) => expect(r.body && r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)

/** The clinic links to the pharmacy (HC-1c, walked in §34 — set-up here). */
const link = () => {
  signIn(PHARMA.email)
  cy.request({ method: 'POST', url: '/links/settings', form: true, body: { acceptClinics: true } }).then(ok)
  signIn(CLINIC.email)
  cy.request(`/links/pharmacies?q=${encodeURIComponent(PHARMA.org)}`).its('body').then((b) => {
    const p = ((b && (b.data || b.collection)) || []).find((x) => x.name === PHARMA.org)
    expect(p, 'the pharmacy is found').to.be.ok
    cy.request({ method: 'POST', url: '/links', form: true, body: { pharmacyOrgId: p.id } }).then(ok)
  })
  signIn(PHARMA.email)
  cy.request('/links').its('body').then((b) => {
    const l = ((b && b.data) || []).find((x) => x.otherName === CLINIC.org && x.status === 'REQUESTED')
    S.linkId = l.id
    cy.request({ method: 'POST', url: `/links/${l.id}/accept` }).then(ok)
  })
}
/** A visit started on the doctor's queue, on the screen — the owner works as the doctor (no separate login). */
const openVisit = (token, patientName = PATIENT.name) => {
  cy.visit('/clinicDashboard')
  cy.get('#clinNavMyQueue', { timeout: 20000 }).click()
  cy.myQueueOn(S.doctor)
  // Call only while the token is waiting; a visit already started offers Open (the doctor going back to it)
  cy.contains('#clinQueueBody tr', token.tokenLabel, { timeout: 15000 }).then(($tr) => {
    if ($tr.find('.clin-q-call').length) cy.wrap($tr).find('.clin-q-call').click()
  })
  cy.contains('#clinQueueBody tr', token.tokenLabel).find('.clin-q-start').click()
  cy.get('#clinIdBanner', { timeout: 15000 }).should('contain', patientName)
}
const issueToken = () => cy.postJson('/clinic/tokens', { patientId: S.patient.id, providerId: S.doctor.id }).then((t) => { ok(t); return t.body.data })
const openPrescriptions = () => {
  cy.visit('/businessDashboard')
  cy.get('#snavPharmacy > .snav-btn', { timeout: 20000 }).click()
  cy.get('#snavPharmacy a[onclick^="showPrescriptions"]').click()
}

describe('Test Book — HC-1d prescriptions to the linked pharmacy (captured)', () => {
  beforeEach(() => cy.viewport(1366, 900))
  afterEach(g.write)

  before(() => {
    // Set-up (not under test — HC-1a/HC-1b walk these screens): two tenants, passwords set, a medicine in the pharmacy,
    // a doctor and a patient in the clinic. The LINK is made in X2 (X1 needs the clinic unlinked).
    cy.loginAsOperator()
    ;[CLINIC, PHARMA].forEach(provision)
    ;[CLINIC, PHARMA].forEach((t) => setPasswordLink(t.email).then(choosePassword))
    signIn(PHARMA.email)
    cy.seedProduct({ name: MED.name, formula: MED.formula, unit: 'capsule', stock: MED.stock, sellingPrice: MED.price })
      .then(({ productId }) => { S.medId = productId })
    signIn(CLINIC.email)
    cy.postJson('/clinic/doctors', { name: DOCTOR, speciality: 'General physician', dailyLimit: 0 }).then((r) => { ok(r); S.doctor = r.body.data })
    cy.postJson('/clinic/patients', { phone: PATIENT.phone, name: PATIENT.name, dateOfBirth: '1980-03-04', sex: 'F' }).then((r) => { ok(r); S.patient = r.body.data })
  })

  after(() => {
    cy.loginAsOperator()
    ;[CLINIC, PHARMA].forEach((t) =>
      cy.request(`/platform/organizations?page=0&size=20&q=${encodeURIComponent(t.org)}`).then((r) => {
        const d = (r.body && (r.body.data || r.body.object || r.body)) || {}
        ;(d.rows || []).filter((o) => o && o.name === t.org && o.status === 'ACTIVE').forEach((o) =>
          cy.request({ method: 'POST', url: '/platform/status', form: true, failOnStatusCode: false,
            body: { organizationId: o.id, status: 'SUSPENDED', reason: 'guide cleanup' } }))
      }))
  })

  // ── X1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('X1', 'No linked pharmacy: the doctor is told to link one', () => {
    g.testCase('X1', 'A clinic with no linked pharmacy: the prescription area says “Link your pharmacy first”', {
      tenant: CLINIC.email, role: 'Clinic owner, working as the doctor',
      purpose: 'A separated clinic has no medicines of its own. Before HC-1d the doctor saw an empty list, and a Submit wrote the prescription into the CLINIC — where no pharmacist would ever see it.',
      prereq: ['A new Hospital / Clinic tenant with a doctor and a patient (the run makes them). No pharmacy linked.'],
      data: [`Doctor **${DOCTOR}**`, `Patient **${PATIENT.name}**, phone **${PATIENT.phone}**`],
      rollback: 'The visit is completed in this case; the tenants are suspended at the end of the run.',
    })
    signIn(CLINIC.email)
    issueToken().then((t) => { S.t1 = t })
    const a1 = g.act('Issue a token for the patient. Open **My queue**, choose the doctor, press **Call** then **Start**.',
      ['The visit opens on the patient.', 'Under **Prescription**: **Link your pharmacy first — prescriptions go to the pharmacy your clinic is linked to.** with a link to **Linked pharmacy**.',
        'There is no medicine box and no **Submit to pharmacy**.'])
    cy.then(() => openVisit(S.t1))
    cy.get('#clinRxNoLink').should('be.visible').find('a[href*="PharmacyLinkDiv"]').should('exist')
    cy.get('#clinRxAdd').should('not.be.visible')
    cy.get('#clinRxSubmit').should('not.be.visible')
    g.snap(a1, 'no-link')

    const c1 = g.act('Press **Complete visit** and confirm.', ['The visit reads **Completed**.'], { cleanup: true })
    cy.get('#clinComplete').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('#clinStatus').should('have.attr', 'data-status', 'COMPLETED')
    g.snap(c1, 'completed')
  })

  // ── X2 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('X2', 'Linked: the list is the pharmacy’s medicines, without price or stock', () => {
    g.testCase('X2', 'Linked: the doctor’s list is the pharmacy’s medicines — names and strengths, never price or stock', {
      tenant: CLINIC.email, role: 'Clinic owner, working as the doctor',
      purpose: 'The user’s rule (D2): the doctor picks what the pharmacy can dispense, and the clinic never learns the pharmacy’s prices or stock.',
      prereq: ['X1 has run.', `**${PHARMA.org}** has **${MED.name}** (${MED.formula}) at **${MED.price}**, **${MED.stock}** in stock.`,
        `The clinic is linked to **${PHARMA.org}** (HC-1c — set-up here, walked in §34).`],
      data: [`Type **${RUN}** in the medicine box`],
      rollback: 'Nothing is saved in this case.',
    })
    link()
    signIn(CLINIC.email)
    issueToken().then((t) => { S.t2 = t })
    const a1 = g.act(`Issue a new token, start the visit, and type **${RUN}** in **Medicine**.`,
      [`Above the list: **Prescriptions go to ${PHARMA.org}**.`, `The suggestions offer **${MED.name}** with **${MED.formula}**.`,
        `No price (**${MED.price}**) and no stock (**${MED.stock}**) appear anywhere on the screen.`])
    cy.intercept('GET', '**/clinic/consult/medicines*').as('meds')
    cy.then(() => openVisit(S.t2))
    cy.wait('@meds').then(({ response }) => {
      const d = (response.body && response.body.data) || {}
      expect(d.route, JSON.stringify(response.body).slice(0, 200)).to.eq('LINKED')
      expect(d.pharmacyName).to.eq(PHARMA.org)
      const rows = d.medicines || []
      expect(rows.map((m) => m.name), 'the pharmacy’s medicine').to.include(MED.name)
      expect(Object.keys(rows[0] || {}).sort(), 'only id, name and strength cross').to.deep.eq(['formula', 'id', 'name'])
    })
    cy.get('#clinRxTo').should('be.visible').and('contain.text', PHARMA.org)
    cy.get('#clinRxMedicine').type(RUN)
    cy.get(`#clinRxMedicines option[value="${MED.name}"]`).should('exist').and('contain.text', MED.formula)
    cy.get('#clinConsult').invoke('text').should('not.contain', String(MED.price) + '.00').and('not.match', new RegExp(`\\b${MED.stock}\\s*(in stock|left)`, 'i'))
    g.snap(a1, 'pharmacy-list')
  })

  // ── X3 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('X3', 'The doctor prescribes from the pharmacy’s list and Submits', () => {
    g.testCase('X3', 'A medicine from the linked pharmacy’s list, Submitted: “Sent to <pharmacy>”', {
      tenant: CLINIC.email, role: 'Clinic owner, working as the doctor',
      purpose: 'R3: the prescription goes to the LINKED pharmacy — before HC-1d a separate clinic’s Submit was written into the clinic itself, where no pharmacist would see it.',
      prereq: ['X2 has run (the visit is open, the clinic is linked).'],
      data: [`Complaint **${COMPLAINT}** (typed by the doctor; must NOT reach the pharmacy)`, `**${MED.name}** × 10, 1 cap, TDS, 5 days`],
      rollback: 'The visit is completed in X5, after the pharmacy dispenses.',
    })
    signIn(CLINIC.email)
    cy.then(() => openVisit(S.t2))
    const a1 = g.act(`Type the complaint **${COMPLAINT}** and save. In **Medicine** choose **${MED.name}**; Qty **10**, Dose **1 cap**, Frequency **TDS**, Duration **5 days**; press **Add**.`,
      ['The line is added to the prescription.'])
    cy.get('#clinComplaint').type(COMPLAINT)
    cy.get('#clinSaveVitals').click()
    cy.get('#clinRxMedicine').type(MED.name)
    cy.get('#clinRxQty').type('10')
    cy.get('#clinRxDose').type('1 cap')
    cy.get('#clinRxFreq').type('TDS')
    cy.get('#clinRxDays').type('5 days')
    cy.get('#clinRxAddLine').click()
    cy.get('#clinRxBody tr').should('have.length', 1).and('contain', MED.name)
    g.snap(a1, 'listed-line')

    const a2 = g.act('Press **Submit to pharmacy** and confirm.',
      [`**Sent to ${PHARMA.org}**, with the token and the time.`, 'The list is locked: no Add, no Save, no Remove.'])
    cy.intercept('POST', '**/clinic/consult/encounters/*/rx/submit').as('submit')
    cy.get('#clinRxSubmit').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@submit').then(({ response }) => {
      expect(response.body.success, JSON.stringify(response.body).slice(0, 200)).to.eq(true)
      expect(response.body.data.rxId).to.be.a('number')
      S.encounterId = response.body.data.id
      S.rxId = response.body.data.rxId
    })
    cy.get('#clinRxSent').should('be.visible').and('contain', PHARMA.org)
    cy.get('#clinRxAdd').should('not.be.visible')
    g.snap(a2, 'sent')
  })

  // ── X4 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('X4', 'The pharmacy sees it “From clinic”, without the diagnosis', () => {
    g.testCase('X4', 'The pharmacy finds it marked “From clinic” — patient, doctor, medicine; not the diagnosis', {
      tenant: PHARMA.email, role: 'Pharmacy owner (a pharmacist sees the same)',
      purpose: 'D3/D4: written in the PHARMACY’s business, says which clinic sent it, and carries only what dispensing needs.',
      prereq: ['X3 has run.'], data: ['Search the token from X3'],
      rollback: 'Nothing is changed in this case.',
    })
    signIn(PHARMA.email)
    const a1 = g.act('Open **Pharmacy → Prescriptions** and search the token.',
      [`One prescription, **PENDING**, marked **From clinic ${CLINIC.org}**, with the token.`,
        `Patient **${PATIENT.name}**; doctor **${DOCTOR}**; the line **${MED.name}** × 10.`,
        `The complaint **${COMPLAINT}** appears nowhere.`])
    cy.intercept('GET', '**/searchPrescriptions*').as('search')
    openPrescriptions()
    cy.wait('@search')
    cy.then(() => cy.get('#rxSearch').type(S.t2.tokenLabel + '{enter}'))
    cy.wait('@search')
    cy.then(() => {
      // separate queries: the list redraws after the search, so each assertion re-finds the row (no detached subject)
      const row = `#prescriptionBody tr[data-rx-id="${S.rxId}"]`
      cy.get(row, { timeout: 15000 }).should('contain', 'PENDING').and('contain', PATIENT.name).and('contain', DOCTOR)
      cy.get(`${row} .rx-from-clinic`).should('be.visible').and('contain', CLINIC.org)
      cy.request(`/getPrescription?id=${S.rxId}`).its('body.data').then((rx) => {
        expect(JSON.stringify(rx), 'the complaint is nowhere').to.not.contain(COMPLAINT)
        expect(rx.diagnosis, 'no diagnosis crosses').to.not.be.ok
        expect(rx.fromOrgName).to.eq(CLINIC.org)
        expect(rx.items.map((i) => i.medicineName)).to.deep.eq([MED.name])
      })
    })
    g.snap(a1, 'from-clinic')

    const a2 = g.act('On that row press **Change**, read the panel title, then press **Close** (nothing is changed).',
      [`The title reads **Change the prescription of ${PATIENT.name}** — the patient’s name, never a raw “{0}”.`,
        'Close hides the panel; the prescription is unchanged.'])
    cy.then(() => cy.get(`#prescriptionBody tr[data-rx-id="${S.rxId}"] .rx-change`).click())
    cy.get('#rxPanel').should('be.visible')
    cy.get('#rxPanelTitle').should('contain', PATIENT.name).invoke('text').should('not.contain', '{0}')
    g.snap(a2, 'change-title')
    cy.get('#rxPanelCloseWrap button').click()
    cy.get('#rxPanel').should('not.be.visible')
  })

  // ── X5 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('X5', 'The pharmacy dispenses; the clinic reads Dispensed', () => {
    g.testCase('X5', 'The pharmacist dispenses it — the clinic’s doctor reads “At the pharmacy: Dispensed”', {
      tenant: `${PHARMA.email}, then ${CLINIC.email}`, role: 'Pharmacy owner, then clinic owner (as the doctor)',
      purpose: 'D5/R5: what comes back to the clinic is the status and the lines — never price or stock, never who at the pharmacy did it.',
      prereq: ['X4 has run.'], data: ['Payment **CASH**, received **1000**'],
      rollback: 'Complete the visit (below). The sale is a ledger fact and stays; the tenants are suspended at the end.',
    })
    signIn(PHARMA.email)
    const a1 = g.act('On the prescription press **Dispense**, choose **CASH**, type **1000** received, and confirm the sale.',
      ['The sale is saved and the prescription reads **FULLY_DISPENSED**.'])
    cy.intercept('GET', '**/searchPrescriptions*').as('search')
    cy.intercept('POST', '**/dispensePrescription').as('dispense')
    openPrescriptions()
    cy.wait('@search')
    cy.then(() => cy.get(`#prescriptionBody tr[data-rx-id="${S.rxId}"]`, { timeout: 15000 }).contains('button', 'Dispense').click())
    cy.get('#sellDiv').should('be.visible')
    cy.get('#sellPayMethod').select('CASH', { force: true })
    cy.get('#sellRec').clear().type('1000')
    cy.clickAndConfirmSale('#addSell')
    cy.wait('@dispense', { timeout: 30000 }).its('response.body.data.status').should('eq', 'FULLY_DISPENSED')
    g.snap(a1, 'dispensed')

    const a2 = g.act('Sign in as the clinic. Open the visit again from **My queue** (press **Open**).',
      ['**At the pharmacy: Dispensed**.', 'No price or stock is shown.'])
    signIn(CLINIC.email)
    cy.intercept('GET', '**/clinic/consult/encounters/*/rx/pharmacy').as('copy')
    cy.then(() => openVisit(S.t2))
    cy.wait('@copy').its('response.body.data.status').should('eq', 'FULLY_DISPENSED')
    cy.get('#clinRxPharmacy .clin-rx-status').should('contain', 'Dispensed')
    cy.get('#clinRxPharmacy').invoke('text').should('not.contain', String(MED.price) + '.00')
    g.snap(a2, 'clinic-reads-dispensed')

    const c1 = g.act('Press **Complete visit** and confirm.', ['The visit reads **Completed**.'], { cleanup: true })
    cy.get('#clinComplete').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('#clinStatus').should('have.attr', 'data-status', 'COMPLETED')
    g.snap(c1, 'completed')
  })

  // ── X6 (HC-1d-2 — EXPECTED RED until hand-written lines are built) ─────────────────────────────────────
  g.caseIt('X6', 'A hand-written line; the pharmacist chooses its product', () => {
    g.testCase('X6', 'A medicine not in the list, written by hand — the pharmacist chooses the product before dispensing', {
      tenant: `${CLINIC.email}, then ${PHARMA.email}`, role: 'Clinic owner (as the doctor), then pharmacy owner',
      purpose: 'D2: anything not in the pharmacy’s list can still be prescribed, written by hand; the pharmacist matches it with Change (HC-1d-2).',
      prereq: ['X5 has run; the clinic is still linked.'],
      data: [`Hand-written **${HAND}** × 1, 5 ml, BD, 7 days`, `The pharmacist matches it to **${MED.name}**, reason **matched by pharmacist**`],
      rollback: 'The pharmacist cancels the prescription and the doctor completes the visit (below).',
    })
    signIn(CLINIC.email)
    issueToken().then((t) => { S.t4 = t })
    const a1 = g.act(`Issue a new token, start the visit, type **${HAND}** (not in the list), Qty **1**, Dose **5 ml**, Frequency **BD**, Duration **7 days**, press **Add**, then **Add as written**.`,
      ['A note says it is not in the pharmacy’s list and offers **Add as written**.', 'The line is added, marked **written by hand**.'])
    cy.then(() => openVisit(S.t4))
    cy.get('#clinRxMedicine').type(HAND)
    cy.get('#clinRxQty').type('1')
    cy.get('#clinRxDose').type('5 ml')
    cy.get('#clinRxFreq').type('BD')
    cy.get('#clinRxDays').type('7 days')
    cy.get('#clinRxAddLine').click()
    cy.get('#clinRxAddFree').should('be.visible').click()
    cy.contains('#clinRxBody tr', HAND).find('.clin-rx-free').should('be.visible')
    g.snap(a1, 'hand-written-line')

    const a2 = g.act('Press **Submit to pharmacy** and confirm.', [`**Sent to ${PHARMA.org}**.`])
    cy.intercept('POST', '**/clinic/consult/encounters/*/rx/submit').as('submit4')
    cy.get('#clinRxSubmit').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@submit4').then(({ response }) => {
      expect(response.body.success, JSON.stringify(response.body).slice(0, 200)).to.eq(true)
      S.rx4 = response.body.data.rxId
      S.enc4 = response.body.data.id
    })
    g.snap(a2, 'hand-written-sent')

    const a3 = g.act(`As the pharmacy, open the prescription: **${HAND}** says **choose the product**. Press **Change**, choose **${MED.name}**, reason **matched by pharmacist**, save.`,
      ['The line now has a product; **Changed by the pharmacy** is recorded.'])
    signIn(PHARMA.email)
    cy.then(() => cy.request(`/getPrescription?id=${S.rx4}`).its('body.data').then((rx) => {
      expect(rx.items.find((i) => i.medicineName === HAND).productId, 'hand-written: no product yet').to.eq(null)
      const items = rx.items.map((i) => ({ ...i, productId: S.medId }))
      cy.postJson('/amendPrescription', { prescriptionId: S.rx4, reason: 'matched by pharmacist', amendmentsSeen: 0, items }).then(ok)
    }))
    g.snap(a3, 'matched')

    const c1 = g.act('As the pharmacy, **Cancel** the prescription (reason **guide cleanup**); as the doctor, **Complete visit**.',
      ['The prescription reads **CANCELLED**; the visit reads **Completed**.'], { cleanup: true })
    cy.then(() => cy.postJson('/cancelPrescription', { prescriptionId: S.rx4, reason: 'guide cleanup' }))
    signIn(CLINIC.email)
    cy.then(() => cy.postJson(`/clinic/consult/encounters/${S.enc4}/complete`).then(ok))
    g.snap(c1, 'cleaned')
  })

  // ── X7 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('X7', 'After the link ends, a new prescription cannot be sent', () => {
    g.testCase('X7', 'The link is ended: the doctor’s next Submit is refused in words; the pharmacy keeps what it received', {
      tenant: `${PHARMA.email}, then ${CLINIC.email}`, role: 'Pharmacy owner, then clinic owner (as the doctor)',
      purpose: 'Ending the link stops new prescriptions at once (checked on every Submit, by the pharmacy side). Nothing already sent is deleted.',
      prereq: ['X5 has run (X6 is not needed).'], data: ['End reason **guide cleanup**'],
      rollback: 'This IS the clean-up of the link. The visit is completed below; the tenants are suspended.',
    })
    signIn(PHARMA.email)
    const a1 = g.act(`As the pharmacy owner, **Pharmacy → Linked clinics** → **End link** on **${CLINIC.org}**, reason **guide cleanup**.`,
      [`**${CLINIC.org}** reads **Ended**.`, 'Pharmacy → Prescriptions still lists the dispensed prescription from that clinic.'])
    cy.then(() => cy.request({ method: 'POST', url: `/links/${S.linkId}/end`, form: true, body: { reason: 'guide cleanup' } }).then(ok))
    cy.then(() => cy.request(`/getPrescription?id=${S.rxId}`).its('body.data.status').should('eq', 'FULLY_DISPENSED'))
    g.snap(a1, 'link-ended')

    const a2 = g.act('As the clinic, start a new visit for the patient.',
      ['The prescription area says **Link your pharmacy first** again — no list, no Submit.'])
    signIn(CLINIC.email)
    // its own patient: a patient holds one token per doctor per day, and an earlier case may have left one open
    cy.postJson('/clinic/patients', { phone: '0346' + RUN + '7', name: `HC1D Second ${RUN}`, dateOfBirth: '1985-06-07', sex: 'M' })
      .then((r) => { ok(r); S.patient2 = r.body.data })
    cy.then(() => cy.postJson('/clinic/tokens', { patientId: S.patient2.id, providerId: S.doctor.id }).then((t) => { ok(t); S.t3 = t.body.data }))
    cy.then(() => openVisit(S.t3, S.patient2.name))
    cy.get('#clinRxNoLink').should('be.visible')
    cy.get('#clinRxSubmit').should('not.be.visible')
    g.snap(a2, 'no-link-again')

    const a3 = g.act('(Server check) a Submit sent anyway, with a line already saved, is refused in words.',
      ['The answer says the clinic is not linked to a pharmacy; nothing is written at the pharmacy.'])
    cy.then(() => cy.request(`/clinic/consult/tokens/${S.t3.id}`).its('body.data.id').then((encId) => {
      // a LISTED medicine, so the refusal can only be about the link (a hand-written line is HC-1d-2's)
      cy.putJson(`/clinic/consult/encounters/${encId}/rx`, { lines: [{ productId: S.medId, medicineName: MED.name, quantity: '1', dosage: '1 cap', frequency: 'BD', duration: '7 days' }] })
        .its('body.success').should('eq', true)
      cy.postJson(`/clinic/consult/encounters/${encId}/rx/submit`).then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.data && r.body.data.code, JSON.stringify(r.body).slice(0, 200)).to.eq('RX_NO_LINKED_PHARMACY')
      })
    }))
    g.snap(a3, 'refused')

    const c1 = g.act('Press **Complete visit** and confirm.', ['The visit reads **Completed**.'], { cleanup: true })
    cy.get('#clinComplete').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('#clinStatus').should('have.attr', 'data-status', 'COMPLETED')
    g.snap(c1, 'completed')
  })
})
