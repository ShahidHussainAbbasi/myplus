/**
 * HMS — four standing accounts for manual testing of the HMS Phase 1 Test Book (2026-10-12), created on the screens,
 * the way the owner of a clinic would.
 *
 *   Owner       owner.pharma@myplus.com      the business owner (holds everything; the Test Book's clinic lives here)
 *   Reception   hms.reception@myplus.test    Team → "Clinic reception"
 *   Doctor      hms.doctor@myplus.test       Doctors → "Register a doctor" with "Create their login" → "Dr HMS Test"
 *   Pharmacist  hms.pharmacist@myplus.test   Team → "Pharmacist" (RW-1: dispenses, sells; no stock — decision D3)
 *
 * Nobody is ever given a password: each staff member sets their own from the e-mail. This test stack keeps every
 * e-mail in Mailpit (http://localhost:8025); .test addresses can never reach a real inbox.
 * Password for all four on this TEST stack: Demo@2025! (the same as every seeded test account here).
 *
 *   FA-01  owner adds the reception and pharmacist on Team and puts each on their set from the row
 *   FA-02  owner registers the doctor with their own login (Doctor set, linked to the clinic doctor)
 *   FA-03  each of the three sets their password from the e-mail (Forgot password? → Mailpit → link)
 *   FA-04  reception signs in: the reception desk opens; may NOT dispense
 *   FA-05  doctor signs in: "My queue" is there; may NOT dispense
 *   FA-06  pharmacist signs in: may dispense
 *
 * Safe to run again: a step already done (member exists, doctor exists, password works) is skipped, never repeated.
 * Cleanup: none on purpose — these are the standing manual-test accounts. Nothing else is changed.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-four-accounts.cy.js"
 */
describe('HMS — the four manual-test accounts', () => {
  const PW = 'Demo@2025!'
  const OWNER = 'owner.pharma@myplus.com'
  const RECEPTION = { email: 'hms.reception@myplus.test', first: 'Reception', last: 'HMS Test', set: 'Clinic reception' }
  const PHARMACIST = { email: 'hms.pharmacist@myplus.test', first: 'Pharmacist', last: 'HMS Test', set: 'Pharmacist' }
  const DOCTOR = { email: 'hms.doctor@myplus.test', name: 'Dr HMS Test', set: 'Doctor' }
  const MAILPIT = 'http://localhost:8025'
  const shot = (n) => cy.screenshot(`four-accounts/${n}`, { capture: 'viewport', overwrite: true })

  const signIn = (email, password) => {
    cy.clearCookies()
    cy.visit('/login')
    cy.get('input[name="username"]').clear().type(email)
    cy.get('input[name="password"]').clear().type(password, { log: false })
    cy.get('#loginSubmit').click()
  }
  /** true when the password already signs in (form post → 302 to anything but /login) */
  const passwordWorks = (email) => {
    cy.clearCookies()
    return cy.request({ method: 'POST', url: '/login', form: true, followRedirect: false, failOnStatusCode: false,
      body: { username: email, password: PW } })
      .then((r) => r.status === 302 && !/\/login/.test(String(r.headers.location || '')))
  }
  const newestMailTo = (to, after) => {
    const look = (tries) => cy.request(`${MAILPIT}/api/v1/messages?limit=100`).then((r) => {
      const m = (r.body.messages || []).find((x) => (x.To || []).some((a) => a.Address === to) && x.Created > after)
      if (m) return cy.request(`${MAILPIT}/api/v1/message/${m.ID}`).its('body')
      if (tries <= 0) throw new Error(`no new e-mail to ${to} in Mailpit`)
      return cy.wait(1000).then(() => look(tries - 1))
    })
    return look(20)
  }
  const teamMember = (email) => cy.request('/team/users').its('body').then((b) => (b.data || b.object || []).find((x) => x.email === email) || null)   // null, never undefined: undefined makes Cypress yield the BODY (truthy)
  const setId = (name) => cy.request('/team/permissions').its('body').then((b) => (((b.data || b.object).sets || []).find((s) => s.name === name) || {}).id || null)

  // the clinic is switched OFF by every spec's cleanup — this spec switches it on, like the others, and puts it back
  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms four accounts')
    cy.loginAs(OWNER, PW, '/getBusinessDashboardStats', 'hms-fa-pre')
    cy.setCapability('clinic', true)
  })
  after(() => {
    cy.loginAs(OWNER, PW, '/getBusinessDashboardStats', 'hms-fa-post')
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  it('FA-01 owner adds the reception and the pharmacist on Team, each on their set', () => {
    signIn(OWNER, PW)
    cy.location('pathname', { timeout: 20000 }).should('not.include', '/login')
    ;[RECEPTION, PHARMACIST].forEach((p, i) => {
      cy.visit('/businessDashboard')
      cy.window().then((w) => w.showTeam())
      cy.get('#TeamDiv').should('be.visible')
      teamMember(p.email).then((m) => {
        if (m) { cy.log(`${p.email} already on the Team — not added again`); return }
        cy.get('#teamFirstName').clear().type(p.first)
        cy.get('#teamLastName').clear().type(p.last)
        cy.get('#teamEmail').clear().type(p.email)
        cy.get('#teamRole').select('USER', { force: true })
        if (i === 0) shot('FA-01-1-team-add-reception')
        cy.intercept('POST', '**/team/users').as('add')
        cy.get('#addTeamUser').click()
        cy.wait('@add').its('response.statusCode').should('eq', 200)
      })
      cy.contains('#TeamDiv tr', p.email, { timeout: 15000 }).find('select.js-permset').then(($s) => {
        const opt = [...$s[0].options].find((o) => o.textContent.replace(/\s*·\s*built-in\s*$/, '').trim() === p.set)
        expect(opt, `"${p.set}" is offered`).to.exist
        if ($s.val() === opt.value) return
        cy.intercept('POST', '**/team/permissions/assign').as('assign')
        // found AGAIN: the Team list re-draws after an add, and a dropdown held from before is detached
        cy.contains('#TeamDiv tr', p.email).find('select.js-permset').select(opt.value, { force: true })
        cy.wait('@assign').its('response.body').then((b) => expect(b && (b.success === true || b.status === 'SUCCESS'), JSON.stringify(b)).to.eq(true))
      })
      setId(p.set).then((sid) => teamMember(p.email).its('permissionSetId').should('eq', sid))
    })
    cy.contains('#TeamDiv tr', PHARMACIST.email).scrollIntoView()
    shot('FA-01-2-team-rows-sets')
  })

  it('FA-02 owner registers the doctor with their own login (Doctor set, linked)', () => {
    signIn(OWNER, PW)
    cy.location('pathname', { timeout: 20000 }).should('not.include', '/login')
    cy.visit('/clinicDashboard')
    cy.get('#clinNavDoctors').click()
    cy.get('#DoctorsDiv').should('be.visible')
    teamMember(DOCTOR.email).then((m) => {
      if (m) { cy.log('doctor login exists — not registered again'); return }
      cy.get('#clinDocName').clear().type(DOCTOR.name)
      cy.get('#clinDocSpeciality').clear().type('General medicine')
      cy.get('#clinDocFee').clear().type('1000')
      cy.get('#clinDocLimit').clear().type('20')
      cy.get('#clinDocMakeLogin').check()
      cy.get('#clinDocEmail').clear().type(DOCTOR.email)
      shot('FA-02-1-register-doctor')
      cy.intercept('POST', '**/clinic/doctors/register').as('reg')
      cy.get('#clinDocSave').click()
      cy.wait('@reg').its('response.body.success').should('eq', true)
    })
    cy.contains('#clinDoctorBody tr', DOCTOR.name, { timeout: 15000 }).should('contain.text', DOCTOR.email)
    setId(DOCTOR.set).then((sid) => teamMember(DOCTOR.email).its('permissionSetId').should('eq', sid))
    shot('FA-02-2-doctor-linked')
  })

  ;[['reception', RECEPTION.email], ['doctor', DOCTOR.email], ['pharmacist', PHARMACIST.email]].forEach(([role, email]) => {
    it(`FA-03-${role} ${email} sets the password from the e-mail`, () => {
      passwordWorks(email).then((ok) => {
        if (ok) { cy.log('password already set — skipped'); return }
        const since = new Date(Date.now() - 2000).toISOString()
        cy.clearCookies()
        cy.visit('/login')
        cy.get('#loginForgot').click()
        cy.get('#email').type(email)
        cy.intercept('POST', '**/user/resetPassword').as('reset')
        cy.get('button[type="submit"]').click()
        cy.wait('@reset').its('response.statusCode').should('eq', 200)
        newestMailTo(email, since).then((msg) => {
          // the TOKEN, opened on THIS stack: the link's host follows how auth-service was deployed (a hand deploy drops
          // .env.local and the link points at production) — a manual tester does the same: replace the start with this stack
          const tok = /changePassword\?token=([A-Za-z0-9-]+)/.exec(msg.Text || msg.HTML || '')
          expect(tok, 'the set-password link').to.exist
          cy.visit('/user/changePassword?token=' + tok[1])
          cy.get('#password').type(PW, { log: false })
          cy.get('#matchPassword').type(PW, { log: false })
          shot(`FA-03-${role}-1-set-password`)
          cy.contains('button', /submit|update|save/i).click()
          cy.location('pathname', { timeout: 15000 }).should('include', '/login')
          cy.get('.msg-bar').should('contain.text', 'Password reset successfully')
        })
      })
      passwordWorks(email).should('eq', true)
    })
  })

  it('FA-04 reception signs in: the reception desk opens; may NOT dispense', () => {
    signIn(RECEPTION.email, PW)
    cy.location('pathname', { timeout: 20000 }).should('not.include', '/login')
    cy.visit('/businessDashboard')
    cy.get('body').then(($b) => expect($b.find('#rxCanDispense').length, 'reception may not dispense').to.eq(0))
    cy.visit('/clinicDashboard')
    cy.get('#clinNavReception').click()
    cy.get('#ReceptionDiv').should('be.visible')
    shot('FA-04-1-reception-desk')
  })

  it('FA-05 doctor signs in: "My queue" is there; may NOT dispense', () => {
    signIn(DOCTOR.email, PW)
    cy.location('pathname', { timeout: 20000 }).should('not.include', '/login')
    cy.visit('/businessDashboard')
    cy.get('body').then(($b) => expect($b.find('#rxCanDispense').length, 'a doctor may not dispense').to.eq(0))
    cy.visit('/clinicDashboard')
    cy.get('#clinNavMyQueueWrap', { timeout: 15000 }).should('be.visible')
    cy.get('#clinNavMyQueue').click()
    cy.get('#MyQueueDiv').should('be.visible')
    shot('FA-05-1-doctor-my-queue')
  })

  it('FA-06 pharmacist signs in: may dispense', () => {
    signIn(PHARMACIST.email, PW)
    cy.location('pathname', { timeout: 20000 }).should('not.include', '/login')
    cy.visit('/businessDashboard')
    cy.get('#rxCanDispense').should('exist')
    shot('FA-06-1-pharmacist-dashboard')
  })
})
