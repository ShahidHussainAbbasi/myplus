/**
 * HMS H1 — two server-side checks the client asked for / the S2 review found (docs/hms-phase1-design.md §4f).
 *
 *   H1-01..02  L-1: a doctor's prescription may name only medicines in THIS pharmacy's catalogue (pharma-service,
 *              live catalogue read at Submit). The doctor's screen only offers that list; these cases send what a
 *              crafted request could: an id no product has, and a real product of ANOTHER business.
 *   H1-03      S2 gap: a public booking named a doctor by id alone — another business's doctor could be booked into
 *              this venue (appointment-service bookPublicAttempt).
 *
 * The screens are unchanged (the doctor never sees an unknown medicine; the public form only lists the venue's
 * doctors), so these cases drive the server the way a crafted request would.
 *
 * Roles: owner.pharma@ = reception · admin.pharma@ = the DOCTOR (Doctor set in before(), Administrator in after()) ·
 * owner.business@ = another business (one of its products is borrowed by id) · no login for the public booking.
 *
 * Server state changed and put back in after(): the visit → completed; the prescription → cancelled; the patient →
 * retired; admin.pharma's set → Administrator; clinic switch → reset. The refused booking writes nothing.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-h1-hardening.cy.js"
 */
describe('HMS H1 — the doctor\'s medicines are the pharmacy\'s; a public booking names this venue\'s doctor', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const DOCTOR = 'admin.pharma@myplus.com'
  const OTHER = 'owner.business@myplus.com'
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  const phone = '033' + run.slice(-6) + String(Math.floor(Math.random() * 90) + 10).slice(-2)
  const MED = 'H1 Cefspan ' + run
  const S = {}

  const relogin = (email, phase = 'on') => cy.loginAs(email, PW, '/getBusinessDashboardStats', `hms-h1-${run}-${phase}`)
  const post = (url, body) =>
    cy.request({ method: 'POST', url, body: body || {}, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
  const put = (url, body) =>
    cy.request({ method: 'PUT', url, body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
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
  const line = (productId, medicineName, quantity) => ({ productId, medicineName, quantity: String(quantity), dosage: '1 tab', frequency: 'BD', duration: '3 days' })

  before(() => {
    cy.task('clearDemoCaps')
    // A real product of ANOTHER business, by id (its catalogue picker is the same lean list the till uses).
    relogin(OTHER, 'other')
    cy.request('/catalogProductPicker?page=0&size=50').then((r) => {
      // the same unwrapping as product-picker.js unwrap(): { data: { content: [...] } } or { data: [...] }
      const page = (r.body && r.body.data) ? r.body.data : r.body
      const list = Array.isArray(page) ? page : ((page && page.content) || [])
      const p = list.find((x) => x && x.id)
      expect(p, "owner.business has at least one product (borrowed by id)").to.exist
      S.foreign = { id: p.id, name: p.name }
    })
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms h1 gate')
    relogin(OWNER, 'pre')
    cy.setCapability('clinic', true)
    setOf(DOCTOR, 'Doctor')
    relogin(OWNER)
    post('/clinic/patients', { phone, name: 'H1 Patient ' + run }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      S.patient = r.body.data
    })
    cy.request('/clinic/doctors').then((r) => {
      const d = (r.body.data || []).find((x) => x.name === 'Dr Ahmed (gate)')
      expect(d, 'the gate doctor (made by the S2 gate)').to.exist
      S.doctor = d
    })
    cy.seedProduct({ name: MED, unit: 'tablet', stock: 20, sellingPrice: 9 }).then(({ productId }) => { S.med = productId })
    cy.then(() => post('/clinic/tokens', { patientId: S.patient.id, providerId: S.doctor.id })).then((t) => {
      expect(t.body.success, JSON.stringify(t.body)).to.eq(true)
      S.token = t.body.data
    })
    relogin(DOCTOR, 'doctor')
    cy.then(() => post(`/clinic/tokens/${S.token.id}/call`)).its('body.success').should('eq', true)
    cy.then(() => post(`/clinic/consult/tokens/${S.token.id}/start`)).then((r) => { S.visitId = r.body.data.id })
  })

  after(() => {
    relogin(DOCTOR, 'doctor')
    if (S.visitId) {
      cy.request({ url: `/clinic/consult/encounters/${S.visitId}`, failOnStatusCode: false }).then((r) => {
        const v = r.body && r.body.data
        if (v && v.rxId) S.rxId = v.rxId
      })
      post(`/clinic/consult/encounters/${S.visitId}/complete`)
    }
    relogin(OWNER)
    cy.then(() => { if (S.rxId) post('/cancelPrescription', { prescriptionId: S.rxId }) })
    if (S.patient) post(`/clinic/patients/${S.patient.id}/retire`, { reason: 'cypress cleanup' })
    setOf(DOCTOR, 'Administrator')
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  it('H1-01 [L-1] a medicine no product has is refused at Submit, naming it; nothing reaches the pharmacy', () => {
    relogin(DOCTOR, 'doctor')
    const url = `/clinic/consult/encounters/${S.visitId}`
    put(url + '/rx', { lines: [line(S.med, MED, 6), line(987654321, 'Discontinued Syrup', 1)] }).its('body.success').should('eq', true)
    post(url + '/rx/submit').then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(false)
      expect(r.body.message).to.eq("The pharmacy did not accept the prescription: 'Discontinued Syrup' is not in this pharmacy's list. Choose it again from the list.")
    })
    cy.request(url).its('body.data.rxId').should('be.null')
  })

  it("H1-02 [L-1] another business's real product is refused the same way; with the pharmacy's own medicine alone, Submit goes through", () => {
    relogin(DOCTOR, 'doctor')
    const url = `/clinic/consult/encounters/${S.visitId}`
    put(url + '/rx', { lines: [line(S.med, MED, 6), line(S.foreign.id, S.foreign.name, 1)] }).its('body.success').should('eq', true)
    post(url + '/rx/submit').then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(false)
      expect(r.body.message).to.contain(`'${S.foreign.name}' is not in this pharmacy's list`)
    })
    // CONTROL: the same visit with the pharmacy's own medicine only — accepted, so the refusals above were the check
    put(url + '/rx', { lines: [line(S.med, MED, 6)] }).its('body.success').should('eq', true)
    post(url + '/rx/submit').then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.rxId).to.be.a('number')
      S.rxId = r.body.data.rxId
    })
  })

  it("H1-03 [S2 gap] a public booking cannot name another business's doctor for this venue", () => {
    // A venue of the APPOINTMENT business (S2-11 makes "S2 Venue …" there). Read SIGNED IN as that business, as S2-11
    // does: anonymously the booking page lists NO venue at all on the Docker deploy (found here — the monolith's
    // anonymous read falls back to http://localhost:8091, which inside its container is not appointment-service).
    cy.loginAsAppointmentOwner()
    cy.request('/appointment').then((page) => {
      const m = /<option[^>]*value=["']?(\d+)["']?[^>]*>\s*S2 Venue/.exec(page.body)
      expect(m, 'an appointment-business venue (made by the S2 gate)').to.not.be.null
      const venueId = m[1]
      // The booking itself is anonymous (it goes through the gateway's open route, which works).
      // Dr Ahmed (gate) belongs to the PHARMACY business: named for the appointment business's venue.
      cy.clearCookies()
      cy.request({ method: 'POST', url: '/appointmentReq', form: true, failOnStatusCode: false,
                   body: { hospitalId: venueId, doctorId: S.doctor.id, name: 'H1 Crafted ' + run, mobile: phone, address: 'x' } })
        .then((r) => {
          expect(String(r.body.status), JSON.stringify(r.body)).to.eq('FAILURE')
          expect(r.body.error, 'refused exactly like an id that does not exist').to.eq('Doctor not found: ' + S.doctor.id)
        })
    })
  })
})
