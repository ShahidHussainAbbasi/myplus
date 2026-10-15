/**
 * HMS H4 — a doctor's prescriptions land in the pharmacy branch the owner linked them to (client ruling L-4: the
 * pharmacy is a BRANCH of this business). Branch visibility follows the multi-location rule (P2b/P2c): the owner sees
 * every branch; branch staff see their branch plus prescriptions with no branch; staff with no grants (one-branch
 * business) are unconstrained.
 *
 *   H4-01  the owner links Dr Ahmed (gate) to "H4 Branch A" on the Doctors screen.
 *   H4-02  the doctor's Submit carries Branch A.
 *   H4-03  a pharmacist granted only Branch B neither finds nor opens it (search, by id); the owner does (control);
 *          granted Branch A too, the pharmacist finds it.
 *   H4-04  a doctor with no branch: the prescription is every branch's (Branch B's pharmacist finds it).
 *
 * Roles: owner.pharma@ · admin.pharma@ = the DOCTOR (Doctor set; restored to Administrator) · user.pharma@ = the
 * PHARMACIST (branch grants changed here; its ORIGINAL grants restored exactly in after()).
 *
 * Server state put back in after(): Dr Ahmed (gate) → every branch; user.pharma's branch grants → as they were;
 * admin.pharma → Administrator; visits completed; prescriptions cancelled; the patient retired; clinic switch reset.
 * "H4 Branch A" / "H4 Branch B" are found-or-created by name (stores cannot be deleted; reused every run).
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-h4-pharmacy-branch.cy.js"
 */
import { HMS, OWN_SET } from '../../support/hms-accounts'

describe('HMS H4 — the doctor\'s prescriptions land in the linked pharmacy branch', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const DOCTOR = HMS.DOCTOR   // HMS-4: linked to Dr HMS Test — works only that doctor's queue
  const PHARMACIST = HMS.PHARMACIST
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  const phone = '039' + run.slice(-6) + String(Math.floor(Math.random() * 90) + 10).slice(-2)
  const MED = 'H4 Panadol ' + run
  const S = { visits: [], rxIds: [], tokens: [] }

  const relogin = (email, phase = 'on') => cy.loginAs(email, PW, '/getBusinessDashboardStats', `hms-h4-${run}-${phase}`)
  const listOf = (b) => (b && (b.object || b.collection || b.data)) || []
  const branch = (name) => cy.request('/getStores').then((r) => {
    const found = listOf(r.body).find((s) => s.name === name)
    if (found) return found.id
    return cy.postJson('/addStore', { name }).then((c) => {   // JSON body (StoreController takes @RequestBody)
      const id = c.body && c.body.object && c.body.object.id
      expect(id, 'branch "' + name + '" created: ' + JSON.stringify(c.body)).to.be.a('number')
      return id
    })
  })
  const grant = (storeIds) => cy.postJson('/assignStores', { userId: S.pharmacistId, storeIds, replace: true })
    .its('body').then((b) => expect(JSON.stringify(b)).to.not.match(/"success":false|ERROR/))
  const submitFor = (doctor) => {
    relogin(OWNER)
    return cy.postJson('/clinic/tokens', { patientId: S.patient.id, providerId: doctor.id }).then((t) => {
      expect(t.body.success, JSON.stringify(t.body)).to.eq(true)
      S.tokens.push(t.body.data)
      relogin(DOCTOR, 'doctor')
      cy.postJson(`/clinic/tokens/${t.body.data.id}/call`).its('body.success').should('eq', true)
      return cy.postJson(`/clinic/consult/tokens/${t.body.data.id}/start`).then((s) => {
        const v = s.body.data.id
        S.visits.push(v)
        cy.putJson(`/clinic/consult/encounters/${v}/rx`, { lines: [{ productId: S.med, medicineName: MED, quantity: '2', dosage: '1 tab' }] })
          .its('body.success').should('eq', true)
        return cy.postJson(`/clinic/consult/encounters/${v}/rx/submit`).then((r) => {
          expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
          S.rxIds.push(r.body.data.rxId)
          // the doctor completes the visit, as a doctor does — the patient is then free for another doctor (04c)
          cy.postJson(`/clinic/consult/encounters/${v}/complete`).its('body.success').should('eq', true)
          return cy.wrap({ rxId: r.body.data.rxId, token: t.body.data, visitId: v })
        })
      })
    })
  }
  const pharmacistSees = (rxId, token) => {
    relogin(PHARMACIST, 'pharmacist-' + Math.random().toString(36).slice(2, 6))   // a fresh token: branch grants ride in it
    return cy.request(`/searchPrescriptions?q=${encodeURIComponent(token.tokenLabel)}`).then((r) => {
      const items = ((r.body && r.body.data && r.body.data.items) || [])
      return items.some((p) => p.id === rxId)
    })
  }

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms h4 gate')
    relogin(OWNER, 'pre')
    cy.setCapability('clinic', true)
    cy.setPermissionSet(DOCTOR, 'Doctor')
    relogin(OWNER)
    cy.request('/team/users').then((u) => {
      const m = listOf(u.body).find((x) => x.email === PHARMACIST)
      S.pharmacistId = m.userId
      S.pharmacistOriginal = (m.locationIds || []).slice()   // restored EXACTLY in after()
    })
    branch('H4 Branch A').then((id) => { S.branchA = id })
    branch('H4 Branch B').then((id) => { S.branchB = id })
    cy.postJson('/clinic/patients', { phone, name: 'H4 Patient ' + run }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      S.patient = r.body.data
    })
    cy.hmsDoctorDay({ limit: 0 })   // RW-1: no patients-a-day limit for the test doctor while this runs
    cy.request('/clinic/doctors').then((r) => {
      S.ahmed = (r.body.data || []).find((x) => x.name === HMS.DOCTOR_NAME)
      expect(S.ahmed, HMS.DOCTOR_NAME + ' (run hms-four-accounts first)').to.exist
    })
    cy.seedProduct({ name: MED, unit: 'tablet', stock: 50, sellingPrice: 3 }).then(({ productId }) => { S.med = productId })
  })

  after(() => {
    relogin(DOCTOR, 'doctor')
    cy.then(() => S.visits.forEach((v) => cy.postJson(`/clinic/consult/encounters/${v}/complete`)))
    relogin(OWNER)
    cy.then(() => { if (S.ahmed) cy.postJson(`/clinic/doctors/${S.ahmed.id}/pharmacy`, { storeId: null }) })   // back to every branch
    cy.then(() => S.rxIds.forEach((id) => cy.postJson('/cancelPrescription', { prescriptionId: id, reason: 'cypress cleanup' })))
    cy.then(() => { if (S.pharmacistId) cy.postJson('/assignStores', { userId: S.pharmacistId, storeIds: S.pharmacistOriginal, replace: true }) })
    cy.then(() => { if (S.patient) cy.postJson(`/clinic/patients/${S.patient.id}/retire`, { reason: 'cypress cleanup' }) })
    cy.setPermissionSet(DOCTOR, OWN_SET[DOCTOR])   // HMS-4: back on its own set
    cy.hmsDoctorDay({ reset: true })   // RW-1: the test doctor's usual day again
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  it('H4-01 [L-4] the owner links Dr Ahmed (gate) to "H4 Branch A" on the Doctors screen', () => {
    relogin(OWNER)
    cy.visit('/clinicDashboard')
    cy.get('#clinNavDoctors').click()
    // the table draws after three reads (team, branches, doctors — the last through appointment-service, slow when
    // cold): wait for the doctor's row itself, not the default 5 s
    cy.get('#clinDoctorBody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)
    cy.intercept('POST', '**/clinic/doctors/*/pharmacy').as('pharmacy')
    cy.then(() => cy.get(`#clinPh${S.ahmed.id}`, { timeout: 30000 }).select(String(S.branchA)))
    cy.wait('@pharmacy').its('response.body.success').should('eq', true)
    cy.then(() => cy.get(`#clinPh${S.ahmed.id}`).should('have.value', String(S.branchA)))
    cy.screenshot('hms-h4/H4-01-1-doctor-linked-to-branch', { capture: 'viewport' })
  })

  it("H4-02 [L-4] the doctor's Submit carries Branch A", () => {
    submitFor(S.ahmed).then((x) => {
      S.routed = x
      relogin(OWNER)
      cy.request(`/getPrescription?id=${x.rxId}`).its('body.data.storeId').should('eq', S.branchA)
    })
  })

  it('H4-03 [L-4] a pharmacist granted only Branch B neither finds nor opens it; the owner does; granted Branch A too, they do', () => {
    relogin(OWNER)
    grant([S.branchB])
    pharmacistSees(S.routed.rxId, S.routed.token).should('eq', false)
    cy.request({ url: `/getPrescription?id=${S.routed.rxId}`, failOnStatusCode: false })
      .its('body').then((b) => expect(JSON.stringify(b), 'by id: another branch\'s prescription is not found').to.match(/not found/i))
    // CONTROL: the owner sees every branch
    relogin(OWNER)
    cy.request(`/getPrescription?id=${S.routed.rxId}`).its('body.data.id').should('eq', S.routed.rxId)
    // granted Branch A as well → found
    grant([S.branchA, S.branchB])
    pharmacistSees(S.routed.rxId, S.routed.token).should('eq', true)
  })

  it("H4-04 [L-4] a doctor with no branch: the prescription is every branch's", () => {
    relogin(OWNER)
    cy.postJson(`/clinic/doctors/${S.ahmed.id}/pharmacy`, { storeId: null }).its('body.success').should('eq', true)
    submitFor(S.ahmed).then((x) => {
      relogin(OWNER)
      cy.request(`/getPrescription?id=${x.rxId}`).its('body.data.storeId').should('eq', null)
      grant([S.branchB])
      pharmacistSees(x.rxId, x.token).should('eq', true)
    })
  })
})
