/**
 * HMS H2 — Register doctor, linked to a login (client ruling L-2; design §4g).
 *
 *   H2-01  the owner registers a doctor WITH a login on the real screen: the login is created and linked.
 *   H2-02  the owner links an EXISTING login (admin.pharma) to a doctor on the real screen.
 *   H2-03  that login — NOT on the Doctor set — opens My queue on its own doctor (no picker) and consults: the LINK
 *          grants clinical access (an admin can register doctors; sets stay owner-only).
 *   H2-04  a linked doctor cannot work another doctor's patient.
 *   H2-05  the front desk can no longer add or register doctors (RULE 0: POST /clinic/doctors was open to them).
 *   H2-06  an admin cannot link their own login; one login is one doctor.
 *
 * Why H2-03 uses admin.pharma and not the login H2-01 creates: a new login has only a throwaway password and a
 * set-password email, and dev mail does not reach Mailpit here (0 messages, checked 2026-10-10) — a known password
 * is needed to sign in. admin.pharma stays on "Administrator" (no clinic.consult) so only the link can let it consult.
 *
 * Roles: owner.pharma@ = the owner · admin.pharma@ = the linked doctor in H2-02..04, an admin in H2-06 ·
 * user.pharma@ = the front desk.
 *
 * Server state put back in after(): admin.pharma UNLINKED (other gates use it unlinked); tokens completed/cancelled;
 * the patient retired; clinic switch reset. ⚠ RESIDUE (no route exists to remove either): the login H2-01 registers
 * (h2.<run>@test.myplus.com) and the two doctors. A "remove team member" route does not exist anywhere — recorded.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-h2-register-doctor.cy.js"
 */
describe('HMS H2 — Register doctor, linked to a login', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const ADMIN = 'admin.pharma@myplus.com'
  const FRONT_DESK = 'user.pharma@myplus.com'
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  const phone = '031' + run.slice(-6) + String(Math.floor(Math.random() * 90) + 10).slice(-2)
  const NEW_DOC = 'Dr H2 ' + run
  const LINKED_DOC = 'Dr H2b ' + run
  const NEW_EMAIL = `h2.${run}@test.myplus.com`
  const S = { tokens: [] }

  const relogin = (email, phase = 'on') => cy.loginAs(email, PW, '/getBusinessDashboardStats', `hms-h2-${run}-${phase}`)
  const post = (url, body) =>
    cy.request({ method: 'POST', url, body: body || {}, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
  const doctorByName = (name) => cy.request('/clinic/doctors').then((r) => {
    const d = (r.body.data || []).find((x) => x.name === name)
    expect(d, name + ' is on the Doctors list').to.exist
    return d
  })
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
  const openDoctors = () => {
    cy.visit('/clinicDashboard')
    cy.get('#clinNavDoctors').click()
    cy.get('#clinDoctorBody tr', { timeout: 15000 }).should('have.length.greaterThan', 0)
  }

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms h2 gate')
    relogin(OWNER, 'pre')
    cy.setCapability('clinic', true)
    relogin(OWNER)
    // admin.pharma on Administrator — no clinic.consult — so only a LINK can let it consult (H2-03)
    setOf(ADMIN, 'Administrator')
    cy.request('/team/users').then((u) => {
      const list = (u.body && (u.body.data || u.body.object)) || []
      S.adminId = (list.find((x) => x.email === ADMIN) || {}).userId
      expect(S.adminId, 'admin.pharma user id').to.be.a('number')
    })
    post('/clinic/patients', { phone, name: 'H2 Patient ' + run }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      S.patient = r.body.data
    })
    cy.request('/clinic/doctors').then((r) => {
      S.ahmed = (r.body.data || []).find((x) => x.name === 'Dr Ahmed (gate)')
      expect(S.ahmed, 'Dr Ahmed (gate) (made by the S2 gate)').to.exist
    })
  })

  after(() => {
    relogin(OWNER)
    // unlink admin.pharma FIRST: every other gate uses it as an unlinked Doctor-set member
    cy.then(() => { if (S.linkedDoc) post(`/clinic/doctors/${S.linkedDoc.id}/unlink`) })
    relogin(ADMIN, 'admin')
    cy.then(() => S.tokens.forEach((id) => cy.request({ url: `/clinic/consult/tokens/${id}`, failOnStatusCode: false }).then((r) => {
      const v = r.body && r.body.data
      if (v && v.id && v.status !== 'COMPLETED') post(`/clinic/consult/encounters/${v.id}/complete`)
    })))
    relogin(OWNER)
    cy.then(() => S.tokens.forEach((id) => post(`/clinic/tokens/${id}/cancel`, { reason: 'cypress cleanup' })))
    cy.then(() => { if (S.patient) post(`/clinic/patients/${S.patient.id}/retire`, { reason: 'cypress cleanup' }) })
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  it('H2-01 [L-2] the owner registers a doctor with a login: the login is created and linked', () => {
    relogin(OWNER)
    openDoctors()
    cy.get('#clinDocName').type(NEW_DOC)
    cy.get('#clinDocSpeciality').type('Paediatrics')
    cy.get('#clinDocLimit').type('10')
    cy.get('#clinDocMakeLogin').should('be.checked')
    cy.get('#clinDocEmail').type(NEW_EMAIL)
    cy.screenshot('hms-h2/H2-01-1-register-form', { capture: 'viewport' })
    cy.intercept('POST', '**/clinic/doctors/register').as('register')
    cy.get('#clinDocSave').click()
    cy.wait('@register').then(({ response }) => {
      expect(response.body.success, JSON.stringify(response.body)).to.eq(true)
      expect(response.body.message).to.contain(NEW_DOC + ' registered').and.contain('set-password email')
      expect(response.body.data.linkedUserId, 'linked to the new login').to.be.a('number')
      S.newUserId = response.body.data.linkedUserId
    })
    // EXPECTED: the Doctors list shows the doctor WITH its login; the login is a team member
    cy.contains('#clinDoctorBody tr', NEW_DOC).find('.clin-login-cell').should('contain', NEW_EMAIL)
    cy.screenshot('hms-h2/H2-01-2-registered-with-login', { capture: 'viewport' })
    cy.request('/team/users').then((u) => {
      const m = ((u.body && (u.body.data || u.body.object)) || []).find((x) => x.email === NEW_EMAIL)
      expect(m, 'the new login is a team member').to.exist
      expect(m.userId).to.eq(S.newUserId)
    })
    // the same email again is refused in words, and no second login is made
    post('/clinic/doctors/register', { name: NEW_DOC + ' again', email: NEW_EMAIL, dailyLimit: 5 }).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.contain('already has a login')
    })
  })

  it('H2-02 [L-2] the owner links an existing login to a doctor (Link login)', () => {
    relogin(OWNER)
    openDoctors()
    cy.get('#clinDocName').type(LINKED_DOC)
    cy.get('#clinDocNoLimit').check()
    cy.get('#clinDocMakeLogin').uncheck()
    cy.get('#clinDocSave').click()
    cy.contains('#clinDoctorBody tr', LINKED_DOC).find('.clin-link').click()
    cy.contains('#clinDoctorBody tr', LINKED_DOC).find('.clin-link-user').select(ADMIN)
    cy.intercept('POST', '**/clinic/doctors/*/link').as('link')
    cy.contains('#clinDoctorBody tr', LINKED_DOC).find('.clin-link-save').click()
    cy.wait('@link').its('response.body.success').should('eq', true)
    cy.contains('#clinDoctorBody tr', LINKED_DOC).find('.clin-login-cell').should('contain', ADMIN)
    cy.screenshot('hms-h2/H2-02-1-login-linked', { capture: 'viewport' })
    doctorByName(LINKED_DOC).then((d) => { S.linkedDoc = d })
  })

  it('H2-03 [L-2] the linked login — not on the Doctor set — opens My queue on its own doctor and consults', () => {
    relogin(OWNER)
    cy.then(() => post('/clinic/tokens', { patientId: S.patient.id, providerId: S.linkedDoc.id })).then((t) => {
      expect(t.body.success, JSON.stringify(t.body)).to.eq(true)
      S.tokens.push(t.body.data.id)
      S.myToken = t.body.data
    })
    relogin(ADMIN, 'admin')
    cy.intercept('GET', '**/clinic/doctors/me').as('me')
    cy.visit('/clinicDashboard')
    cy.wait('@me').its('response.body.data').then((me) => {
      expect(me.canConsult, 'the link grants consult').to.eq(true)
      expect(me.name).to.eq(LINKED_DOC)
    })
    cy.get('#clinNavMyQueue').should('be.visible').click()
    // EXPECTED: no doctor to choose — the name instead — and their own patient in the line
    cy.get('#clinMyDoctorWrap').should('not.be.visible')
    cy.get('#clinMeName').should('be.visible').and('contain', LINKED_DOC)
    cy.then(() => cy.contains('#clinQueueBody tr', S.myToken.tokenLabel).should('contain', 'Waiting'))
    cy.screenshot('hms-h2/H2-03-1-own-queue-no-picker', { capture: 'viewport' })
    cy.then(() => cy.contains('#clinQueueBody tr', S.myToken.tokenLabel).find('.clin-q-call').click())
    cy.then(() => cy.contains('#clinQueueBody tr', S.myToken.tokenLabel).find('.clin-q-start').click())
    cy.get('#clinIdBanner').should('contain', S.patient.name)
  })

  it("H2-04 [L-2] a linked doctor cannot work another doctor's patient", () => {
    relogin(OWNER)
    // the same patient is with H2b (H2-03): Dr Ahmed's token is for another patient-free check — use a fresh patient
    post('/clinic/patients', { phone: '030' + run.slice(-6) + '77', name: 'H2 Other ' + run }).then((p) => {
      S.other = p.body.data
      return post('/clinic/tokens', { patientId: S.other.id, providerId: S.ahmed.id })
    }).then((t) => {
      expect(t.body.success, JSON.stringify(t.body)).to.eq(true)
      S.tokens.push(t.body.data.id)
      S.ahmedToken = t.body.data
    })
    relogin(ADMIN, 'admin')
    cy.then(() => post(`/clinic/tokens/${S.ahmedToken.id}/call`)).then((r) => {
      expect(r.status).to.eq(403)
      expect(r.body.message).to.eq("This is Dr Ahmed (gate)'s patient. You see your own queue.")
    })
    cy.then(() => post(`/clinic/consult/next?providerId=${S.ahmed.id}`)).its('status').should('eq', 403)
    cy.then(() => { if (S.other) { relogin(OWNER); post(`/clinic/patients/${S.other.id}/retire`, { reason: 'cypress cleanup' }) } })
  })

  it('H2-05 the front desk can no longer add or register doctors', () => {
    relogin(FRONT_DESK)
    cy.visit('/clinicDashboard')
    cy.get('#clinNavDoctors').click()
    cy.get('#clinDoctorForm').should('not.exist')
    post('/clinic/doctors', { name: 'Desk Doctor ' + run }).then((r) => {
      expect(r.status).to.eq(403)
      expect(r.body.message).to.eq('Only the owner or an admin can register doctors.')
    })
    post('/clinic/doctors/register', { name: 'Desk Doctor ' + run, email: `desk.${run}@test.myplus.com` }).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.status, 'refused at the login step: auth lets only the owner or an admin create members').to.eq(403)
    })
    relogin(OWNER)
    cy.request('/team/users').its('body').then((b) => {
      const list = (b.data || b.object || [])
      expect(list.map((x) => x.email), 'no login was made for the desk\'s attempt').to.not.include(`desk.${run}@test.myplus.com`)
    })
  })

  it('H2-06 an admin cannot link their own login; one login is one doctor', () => {
    relogin(ADMIN, 'admin')
    cy.then(() => post(`/clinic/doctors/${S.ahmed.id}/link`, { userId: S.adminId })).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.eq('You cannot link your own login as a doctor. The owner can.')
    })
    relogin(OWNER)
    cy.then(() => post(`/clinic/doctors/${S.ahmed.id}/link`, { userId: S.adminId })).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.eq('That login is already linked to another doctor of this clinic.')
    })
  })
})
