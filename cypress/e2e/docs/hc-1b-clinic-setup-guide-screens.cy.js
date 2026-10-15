/**
 * HC-1b — a NEW Hospital / Clinic runs on its own: the owner's Clinic home (Today + "Set up your clinic"), adding a
 * doctor and a receptionist, and the first patient seen — as REAL step-by-step manual cases for the Test Book (§33).
 * Written BEFORE the build (the user's rule): every action is PERFORMED on the screen, ASSERTED and PHOTOGRAPHED
 * (support/guide-capture.js), and each case ends with its clean-up. Design: microservices/docs/slices/hc-1b-clinic-home-and-setup.md.
 *
 *   K1  the owner's Clinic home: Today (Tokens · Waiting · Doctors on duty — no Prescriptions, no Takings) and the
 *       "Set up your clinic" checklist, nothing ticked
 *   K2  the owner adds a doctor WITH a login on the Doctors screen → the checklist ticks "doctors"
 *   K3  the owner opens Team on the clinic's own page, invites a receptionist and puts them on "Clinic reception"
 *       → the checklist ticks "staff"
 *   K4  the receptionist sets a password, lands on Reception, registers the first patient by phone and issues a token
 *   K5  the owner's Today reads Tokens 1 · Waiting 1
 *   K6  the doctor sets a password, lands on My queue and calls the patient in
 *   (K7, the shop's and the school's Team screens after the shared-fragment change, is the existing specs
 *    auth/team-invite-first-login + auth/team-members-manage — run beside this one.)
 *
 * EXPECTED RED on the build it was written against: no Today cards or checklist on /clinicHome, no Team on the clinic
 * page; and K4/K6 are the first time a patient is registered on a CLINIC-type tenant at all.
 *
 * TENANT: the run provisions its own clinic (name carries the run id, e-mails end in .test, set-password links are read
 * from Mailpit) through the console's own request, and suspends it on screen at the end. Run the cases in order.
 *
 * Run headed:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/docs/hc-1b-clinic-setup-guide-screens.cy.js --config trashAssetsBeforeRuns=false
 */
import { guideCapture } from '../../support/guide-capture'

const g = guideCapture({ outDir: 'cypress/guide-out/hc-1b', section: 'A new clinic, set up and seeing its first patient', keepShots: true })
const RUN = String(Date.now()).slice(-6)
const MAILPIT = 'http://localhost:8025'
const PW = 'Kiwi#Lamp58'
const CLINIC = { org: `HC1B Clinic ${RUN}`, email: `hc1b.owner.${RUN}@myplus.test`, first: 'Tahseen', last: `Test ${RUN}` }
const DOCTOR = { name: `Dr Ayesha ${RUN}`, email: `hc1b.doctor.${RUN}@myplus.test` }
const RECEPTION = { first: 'Sana', last: `Desk ${RUN}`, email: `hc1b.desk.${RUN}@myplus.test` }
const PATIENT = { name: `Bilal Ahmed ${RUN}`, phone: '0301' + RUN.padStart(7, '7') }
const S = {}   // what one case hands the next (the token label)

/** The newest set-password link e-mailed to `email`, from Mailpit. */
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
const signInOwner = () => signIn(CLINIC.email)

describe('Test Book — HC-1b a new clinic, set up and seeing its first patient (captured)', () => {
  beforeEach(() => cy.viewport(1366, 900))
  afterEach(g.write)

  before(() => {
    // Set-up (not under test — HC-1a's own cases walk this screen): provision the clinic through the console's own
    // request, then its owner chooses a password from the e-mail.
    cy.loginAsOperator()
    cy.request({ method: 'POST', url: '/platform/provisionTenant', form: true, body: {
      organizationName: CLINIC.org, email: CLINIC.email, firstName: CLINIC.first, lastName: CLINIC.last,
      plan: 'PRO', businessType: 'hospital_clinic' } })
      .its('body.success').should('eq', true)
    setPasswordLink(CLINIC.email).then(choosePassword)
  })

  after(() => {
    // Clean-up on screen is K6's last step; this only guarantees a failed run does not leave the tenant ACTIVE.
    cy.loginAsOperator()
    cy.request(`/platform/organizations?page=0&size=20&q=${encodeURIComponent(CLINIC.org)}`).then((r) => {
      const d = (r.body && (r.body.data || r.body.object || r.body)) || {}
      ;(d.rows || []).filter((o) => o && o.name === CLINIC.org && o.status === 'ACTIVE').forEach((o) =>
        cy.request({ method: 'POST', url: '/platform/status', form: true, failOnStatusCode: false,
          body: { organizationId: o.id, status: 'SUSPENDED', reason: 'guide cleanup' } }))
    })
  })

  // ── K1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('K1', 'The owner\'s Clinic home: Today and Set up your clinic', () => {
    g.testCase('K1', 'The owner\'s Clinic home shows Today and a "Set up your clinic" checklist', {
      tenant: CLINIC.email, role: 'Owner of a brand-new Hospital / Clinic',
      purpose: 'A clinic opens on today: how many tokens, how many waiting, how many doctors on duty. A new clinic also needs to know what to set up before its first patient. Prescriptions and takings belong to the pharmacy (a separate business) and are not shown here.',
      prereq: ['A new Hospital / Clinic tenant whose owner has chosen a password (the run provisions it).'],
      data: ['—'],
      rollback: 'Nothing changes.',
    })
    const a1 = g.act(`Sign in as **${CLINIC.email}**.`,
      ['The **Clinic home** opens (address **/clinicHome**).',
        '**Today** shows three cards: **Tokens today 0**, **Waiting now 0**, **Doctors on duty 0**.',
        'There is **no Prescriptions** card and **no Takings** card.',
        '**Set up your clinic** lists **Add your doctors** and **Add your staff**, neither ticked.'])
    signInOwner()
    cy.location('pathname').should('eq', '/clinicHome')
    cy.get('#chToday').should('be.visible')
    cy.get('#ovTokens', { timeout: 15000 }).should('have.text', '0')
    cy.get('#ovWaiting').should('have.text', '0')
    cy.get('#ovDoctors').should('have.text', '0')
    cy.get('#ovRxCard').should('not.exist')
    cy.get('#ovTakingsCard').should('not.exist')
    cy.get('#chSetup [data-step="doctors"]').should('be.visible').and('have.attr', 'data-done', 'false')
    cy.get('#chSetup [data-step="staff"]').should('be.visible').and('have.attr', 'data-done', 'false')
    g.snap(a1, 'home')
  })

  // ── K2 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('K2', 'The owner adds a doctor with a login', () => {
    g.testCase('K2', 'The owner adds a doctor with their own login — and the checklist ticks', {
      tenant: CLINIC.email, role: 'Clinic owner',
      purpose: 'The doctor gets a login (they set their own password from the e-mail) and is linked to it, so My queue opens on their own patients.',
      prereq: ['K1 has run.'],
      data: [`Doctor **${DOCTOR.name}**, speciality **General physician**, no daily limit, login **${DOCTOR.email}**`],
      rollback: 'The clinic is suspended at the end of K6.',
    })
    const a1 = g.act('On the Clinic home, press **Add your doctors**.', ['The clinic screen opens on **Doctors**.'])
    signInOwner()
    cy.get('#chSetup [data-step="doctors"] a').click()
    cy.location('pathname', { timeout: 20000 }).should('eq', '/clinicDashboard')
    cy.get('#DoctorsDiv').should('be.visible')
    g.snap(a1, 'doctors')

    const a2 = g.act(`Type **${DOCTOR.name}**, speciality **General physician**, tick **No limit**, keep **Make a login** ticked, type **${DOCTOR.email}**, press **Save**.`,
      [`The list shows **${DOCTOR.name}** with the login **${DOCTOR.email}**.`])
    cy.get('#clinDocName').type(DOCTOR.name)
    cy.get('#clinDocSpeciality').type('General physician')
    cy.get('#clinDocNoLimit').check()
    cy.get('#clinDocMakeLogin').should('be.checked')
    cy.get('#clinDocEmail').type(DOCTOR.email)
    cy.get('#clinDocSave').click()
    cy.contains('#clinDoctorBody tr', DOCTOR.name, { timeout: 15000 }).find('.clin-login-cell').should('contain', DOCTOR.email)
    g.snap(a2, 'doctor-added')

    const a3 = g.act('Go back to the Clinic home.', ['**Add your doctors** is ticked; **Doctors on duty** reads **1**.'])
    cy.visit('/clinicHome')
    cy.get('#chSetup [data-step="doctors"]', { timeout: 15000 }).should('have.attr', 'data-done', 'true')
    cy.get('#ovDoctors').should('have.text', '1')
    g.snap(a3, 'doctors-ticked')
  })

  // ── K3 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('K3', 'The owner invites a receptionist from the clinic\'s own Team', () => {
    g.testCase('K3', 'Team is on the clinic\'s own page: invite a receptionist on "Clinic reception"', {
      tenant: CLINIC.email, role: 'Clinic owner',
      purpose: 'Until now Team lived only on the shop and school dashboards, so a clinic owner could not add anyone. The receptionist gets only the front desk.',
      prereq: ['K2 has run.'],
      data: [`Receptionist **${RECEPTION.first} ${RECEPTION.last}**, **${RECEPTION.email}**, role **User**, permission set **Clinic reception**`],
      rollback: 'The clinic is suspended at the end of K6.',
    })
    const a1 = g.act('On the Clinic home, press **Add your staff**.', ['The clinic screen opens on **Team**.'])
    signInOwner()
    cy.get('#chSetup [data-step="staff"] a').click()
    cy.location('pathname', { timeout: 20000 }).should('eq', '/clinicDashboard')
    cy.get('#TeamDiv').should('be.visible')
    g.snap(a1, 'team')

    const a2 = g.act(`Type first name **${RECEPTION.first}**, last name **${RECEPTION.last}**, e-mail **${RECEPTION.email}**, role **User**, and press **Add**.`,
      [`**${RECEPTION.email}** is listed as **USER**, waiting to set a password.`])
    cy.get('#teamFirstName').type(RECEPTION.first)
    cy.get('#teamLastName').type(RECEPTION.last)
    cy.get('#teamEmail').type(RECEPTION.email)
    cy.get('#teamRole').select('USER', { force: true })
    cy.get('#addTeamUser').click()
    cy.contains('#TeamDiv tr', RECEPTION.email, { timeout: 15000 }).should('contain.text', 'USER')
    g.snap(a2, 'invited')

    const a3 = g.act(`On **${RECEPTION.email}**'s row choose the permission set **Clinic reception**.`,
      ['The row reads **Clinic reception**.'])
    cy.intercept('POST', '**/team/permissions/assign').as('assign')
    cy.contains('#TeamDiv tr', RECEPTION.email).find('select.js-permset').then(($s) => {
      // built-in sets read 'Clinic reception · built-in' on the row
      const opt = [...$s[0].options].find((o) => o.textContent.replace(/\s*·\s*built-in\s*$/, '').trim() === 'Clinic reception')
      expect(opt, 'the "Clinic reception" set is offered to a clinic').to.exist
      // found AGAIN: the Team list re-draws after an add, and a dropdown held from before is detached
      cy.contains('#TeamDiv tr', RECEPTION.email).find('select.js-permset').select(opt.value, { force: true })
      cy.wait('@assign').its('response.body').then((b) => expect(b && (b.success === true || b.status === 'SUCCESS'), JSON.stringify(b)).to.eq(true))
    })
    cy.contains('#TeamDiv tr', RECEPTION.email).find('select.js-permset option:selected').invoke('text').should('match', /^Clinic reception/)
    g.snap(a3, 'set-chosen')

    const a4 = g.act('Go back to the Clinic home.', ['**Add your staff** is ticked.'])
    cy.visit('/clinicHome')
    cy.get('#chSetup [data-step="staff"]', { timeout: 15000 }).should('have.attr', 'data-done', 'true')
    g.snap(a4, 'staff-ticked')
  })

  // ── K4 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('K4', 'The receptionist registers the first patient and issues a token', () => {
    g.testCase('K4', 'The receptionist registers the clinic\'s first patient and issues a token', {
      tenant: RECEPTION.email, role: 'Receptionist (Clinic reception)',
      purpose: 'The first time a patient is registered on a Hospital / Clinic tenant. The patient gets a medical record number; the token puts them in the doctor\'s queue.',
      prereq: ['K3 has run (the receptionist was invited).'],
      data: [`Patient **${PATIENT.name}**, mobile **${PATIENT.phone}**`, `Doctor **${DOCTOR.name}**`],
      rollback: 'The clinic is suspended at the end of K6.',
    })
    const a1 = g.act('Open the set-password e-mail, follow its link, type the password twice, press **Update Password**, then sign in.',
      ['The clinic screen opens on **Reception** — the receptionist\'s only workspace.'])
    setPasswordLink(RECEPTION.email).then(choosePassword)
    signIn(RECEPTION.email)
    cy.location('pathname', { timeout: 20000 }).should('eq', '/clinicDashboard')
    cy.get('#ReceptionDiv').should('be.visible')
    cy.get('#clinNavTeam').should('not.exist')
    g.snap(a1, 'reception')

    const a2 = g.act(`Type **${PATIENT.phone}** and press **Enter**; type the name **${PATIENT.name}** and press **Save**.`,
      ['The patient is shown with a medical record number **MRN-…**.'])
    cy.get('#clinPatPhone').type(PATIENT.phone + '{enter}')
    cy.get('#clinPatForm', { timeout: 15000 }).should('be.visible')
    cy.get('#clinPatName').type(PATIENT.name)
    cy.get('#clinPatSave').click()
    cy.get('#clinPatFound', { timeout: 15000 }).should('be.visible').and('contain', PATIENT.name)
    cy.get('#clinPatMrn').invoke('text').should('match', /^MRN-/)
    g.snap(a2, 'registered')

    const a3 = g.act(`Choose **${DOCTOR.name}** and press **Issue token**.`,
      ['A token slip shows the token number and the doctor\'s name.'])
    cy.get('#clinTokenPanel').should('be.visible')
    cy.get('#clinDoctor option').contains(DOCTOR.name).then(($o) => cy.get('#clinDoctor').select($o.val()))
    cy.get('#clinIssueToken').click()
    cy.get('#clinTokenSlip', { timeout: 15000 }).should('be.visible')
    cy.get('#clinTokenMeta').should('contain', DOCTOR.name)
    cy.get('#clinTokenNo').invoke('text').then((t) => { S.token = t.trim() })
    g.snap(a3, 'token')
  })

  // ── K5 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('K5', 'The owner sees the patient waiting', () => {
    g.testCase('K5', 'The owner\'s Today counts the token and the patient waiting', {
      tenant: CLINIC.email, role: 'Clinic owner',
      purpose: 'Today is read from the queue itself, so the owner sees the front desk\'s work at once.',
      prereq: ['K4 has run.'], data: ['—'], rollback: 'Nothing changes.',
    })
    const a1 = g.act('Sign in as the owner (or reload the Clinic home).', ['**Tokens today 1**, **Waiting now 1**.'])
    signInOwner()
    cy.get('#ovTokens', { timeout: 15000 }).should('have.text', '1')
    cy.get('#ovWaiting').should('have.text', '1')
    g.snap(a1, 'waiting-one')
  })

  // ── K6 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('K6', 'The doctor calls the patient in', () => {
    g.testCase('K6', 'The doctor sets a password, lands on My queue and calls the first patient in', {
      tenant: DOCTOR.email, role: 'Doctor (login made on the Doctors screen)',
      purpose: 'A doctor works only their own queue: My queue opens on them, without choosing a doctor.',
      prereq: ['K4 has run (a token is waiting for this doctor).'],
      data: ['—'],
      rollback: 'Suspend the clinic on screen as the operator (below). Nothing is deleted.',
    })
    const a1 = g.act('Open the set-password e-mail, follow its link, choose a password, then sign in.',
      ['The clinic screen opens on **My queue**, already on this doctor.', 'The token from K4 is **Waiting**.'])
    setPasswordLink(DOCTOR.email).then(choosePassword)
    signIn(DOCTOR.email)
    cy.location('pathname', { timeout: 20000 }).should('eq', '/clinicDashboard')
    cy.get('#MyQueueDiv', { timeout: 20000 }).should('be.visible')
    cy.get('#clinMeName').should('contain', DOCTOR.name)
    cy.then(() => cy.contains('#clinQueueBody tr', S.token, { timeout: 15000 }).should('contain', 'Waiting'))
    g.snap(a1, 'my-queue')

    const a2 = g.act('Press **Call** on the token, then **Start**.', [`The consultation opens on **${PATIENT.name}**.`])
    cy.then(() => cy.contains('#clinQueueBody tr', S.token).find('.clin-q-call').click())
    cy.then(() => cy.contains('#clinQueueBody tr', S.token).find('.clin-q-start').click())
    cy.get('#clinIdBanner', { timeout: 15000 }).should('contain', PATIENT.name)
    g.snap(a2, 'called-in')

    const c1 = g.act(`As the operator, open **${CLINIC.org}** in Tenants, set the status to **SUSPENDED**, reason **guide cleanup**, and confirm.`,
      ['The status reads **SUSPENDED**; nothing is deleted.'], { cleanup: true })
    cy.loginAsOperator()
    cy.visit('/platformDashboard')
    cy.get('#platSearch', { timeout: 20000 }).clear().type(CLINIC.org)
    cy.contains('[data-testid="tenant-row"]', CLINIC.org, { timeout: 20000 }).click()
    cy.get('#platStatusSelect', { timeout: 20000 }).select('SUSPENDED')
    cy.get('#platStatusSave').click()
    cy.get('.uiC-input').type('guide cleanup')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('#platStatusSelect', { timeout: 20000 }).should('have.value', 'SUSPENDED')
    g.snap(c1, 'suspended')
  })
})
