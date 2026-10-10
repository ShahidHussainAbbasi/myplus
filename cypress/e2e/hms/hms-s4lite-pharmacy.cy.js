/**
 * HMS S4-lite — the pharmacy finds a clinic patient's prescription by token / MRN / phone / name, and Dispense puts
 * the PATIENT on the sale as the customer (never a second customer typed at the counter).
 *
 * Design: microservices/docs/hms-phase1-design.md §1 (B-01, 07), §4 S4-lite. Test page:
 * https://claude.ai/artifact/RTCGToYCyqJHVsR7VVnfAm. SLICE GATE — the search and the dispense drive the real screens.
 *
 * Roles: owner.pharma@ is reception (registers the patient → the customer belongs to the owner). user.pharma@ is the
 * pharmacist, who CANNOT see the owner's customers in the sale form's list — the case that used to create a duplicate
 * customer. owner.business@ is another organisation (the tenancy check on a sale's customer id).
 *
 * Server state changed and put back in after(): clinic switch → reset; the prescription → cancelled when not
 * dispensed; the token → cancelled; the patient → retired. The sale is a ledger fact and stays (as in B-04).
 *
 * Run (headless records reliably):
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-s4lite-pharmacy.cy.js"
 */
describe('HMS S4-lite — pharmacy search and the patient as customer', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const PHARMACIST = 'user.pharma@myplus.com'
  const OTHER = 'owner.business@myplus.com'
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  const phone = '036' + run.slice(-6) + String(Math.floor(Math.random() * 90) + 10).slice(-2)
  const S = {}   // patient, token, rx, product

  const relogin = (email, phase = 'on') => cy.loginAs(email, PW, '/getBusinessDashboardStats', `hms-s4l-${run}-${phase}`)
  const post = (url, body) =>
    cy.request({ method: 'POST', url, body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })

  /** The prescription's person link is stamped after commit (best-effort bridge): wait for it, loudly. */
  const waitForParty = (rxId, tries = 15) =>
    cy.request(`/getPrescription?id=${rxId}`).then((r) => {
      if (r.body.data && r.body.data.partyId) return r.body.data
      if (tries <= 0) throw new Error('prescription ' + rxId + ' never got its person (partyId) — the party bridge did not run')
      cy.wait(1000)
      return waitForParty(rxId, tries - 1)
    })

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms s4-lite gate')
    relogin(OWNER, 'pre')
    cy.setCapability('clinic', true)
    relogin(OWNER)
    // Reception: the patient (→ person + customer) and a token with the gate doctor.
    post('/clinic/patients', { phone, name: 'S4 Patient ' + run }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.customerId, 'customer made at reception').to.be.a('number')
      S.patient = r.body.data
    })
    cy.request('/clinic/doctors').then((r) => {
      const d = (r.body.data || []).find((x) => x.name === 'Dr Ahmed (gate)') || (r.body.data || [])[0]
      expect(d, 'a doctor (the S2 gate creates "Dr Ahmed (gate)")').to.exist
      cy.then(() => post('/clinic/tokens', { patientId: S.patient.id, providerId: d.id })).then((t) => {
        expect(t.body.success, JSON.stringify(t.body)).to.eq(true)
        S.token = t.body.data
      })
    })
    // A medicine in stock, and the doctor's slip recorded at the pharmacy (today's path; S4 makes the doctor author it).
    cy.seedProduct({ name: 'S4 Paracetamol ' + run, unit: 'tablet', stock: 50, sellingPrice: 10 }).then(({ productId }) => {
      S.productId = productId
      cy.then(() => post('/addPrescription', {
        patientName: S.patient.name, patientPhone: phone, doctorName: 'Dr Ahmed (gate)',
        items: [{ productId, medicineName: 'S4 Paracetamol ' + run, quantity: 6, dosage: '1 tab', frequency: 'TDS', duration: '2d' }],
      })).then((rx) => {
        expect(rx.body.success, JSON.stringify(rx.body)).to.eq(true)
        S.rxId = rx.body.data.id
      })
    })
    cy.then(() => waitForParty(S.rxId)).then((rx) => {
      expect(rx.partyId, 'the prescription and the patient are one person').to.eq(S.patient.partyId)
    })
  })

  after(() => {
    relogin(OWNER)
    cy.request({ url: `/getPrescription?id=${S.rxId}`, failOnStatusCode: false }).then((r) => {
      if (r.body && r.body.data && r.body.data.status !== 'FULLY_DISPENSED') post('/cancelPrescription', { prescriptionId: S.rxId })
    })
    if (S.token) post(`/clinic/tokens/${S.token.id}/cancel`, { reason: 'cypress cleanup' })
    if (S.patient) post(`/clinic/patients/${S.patient.id}/retire`, { reason: 'cypress cleanup' })
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  // ── L-01 — search by token, on the real screen ─────────────────────────────────────────────────────
  it('L-01 [B-01] the pharmacist types the clinic token and finds the patient\'s prescription', () => {
    relogin(PHARMACIST)
    cy.intercept('GET', '**/searchPrescriptions*').as('search')
    cy.visit('/businessDashboard')
    cy.get('#snavPharmacy > .snav-btn').click()
    cy.get('#snavPharmacy a[onclick^="showPrescriptions"]').click()
    cy.wait('@search')
    // 1. Type the token the patient holds, loosely ("a-17" for A-017), and press Enter.
    const loose = S.token.tokenLabel.replace(/-0+/, '-').toLowerCase()
    cy.get('#rxSearch').should('be.visible').type(loose + '{enter}')
    cy.wait('@search').then(({ response }) => {
      expect(response.body.resolved && response.body.resolved.mrn, JSON.stringify(response.body).slice(0, 300)).to.eq(S.patient.mrn)
      expect(response.body.data.hasMore, 'hasMore for one person: ' + JSON.stringify(response.body.data).slice(0, 200)).to.eq(false)
    })
    // EXPECTED: who the token is, and the prescription — this patient's only.
    cy.get('#rxSearchFound').should('be.visible').and('contain', S.patient.name).and('contain', S.patient.mrn)
    cy.get('#prescriptionBody tr').should('have.length.at.least', 1)
    cy.get('#prescriptionBody tr').each(($tr) => expect($tr.text()).to.contain(S.patient.name))
    cy.get(`#prescriptionBody tr[data-rx-id="${S.rxId}"]`).should('contain', 'PENDING')
    // One person, one prescription: nothing more to load (the L-01 screenshot of 2026-10-10 showed "Load more" here).
    cy.get('#prescriptionBody tr').its('length').then((n) => {
      if (n < 25) cy.get('#rxLoadMore').should('not.be.visible')
    })
    cy.screenshot('hms-s4l/L-01-found-by-token', { capture: 'viewport' })
  })

  it('L-02 the same prescription is found by MRN, by phone and by name; a stranger finds nothing', () => {
    relogin(PHARMACIST)
    const find = (q) => cy.request(`/searchPrescriptions?q=${encodeURIComponent(q)}`).its('body')
    ;[S.patient.mrn, '+92' + phone.slice(1), S.patient.name].forEach((q) =>
      find(q).then((b) => {
        const ids = ((b.data && b.data.items) || []).map((x) => x.id)
        expect(ids, q).to.include(S.rxId)
      }))
    find('Nobody By This Name ' + run).its('data.items').should('have.length', 0)
  })

  it('L-03 the list is paged: "Load more" brings the next page instead of drawing everything at once', () => {
    relogin(PHARMACIST)
    cy.request('/searchPrescriptions').its('body.data').then((d) => {
      expect(d.items.length).to.be.at.most(25)
      if (!d.hasMore) return   // a small pharmacy: nothing more to load, and the button stays hidden
      cy.visit('/businessDashboard')
      cy.get('#snavPharmacy > .snav-btn').click()
      cy.get('#snavPharmacy a[onclick^="showPrescriptions"]').click()
      cy.get('#prescriptionBody tr').should('have.length', 25)
      cy.get('#rxLoadMore').should('be.visible').click()
      cy.get('#prescriptionBody tr').should('have.length.greaterThan', 25)
    })
  })

  // ── L-04 — dispense: the patient is the customer ──────────────────────────────────────────────────
  it('L-04 [07] Dispense fills the sale AND names the patient as the customer — by id, so no duplicate is made', () => {
    relogin(PHARMACIST)
    cy.intercept('POST', '**/dispensePrescription').as('dispense')
    cy.visit('/businessDashboard')
    cy.get('#snavPharmacy > .snav-btn').click()
    cy.get('#snavPharmacy a[onclick^="showPrescriptions"]').click()
    cy.get('#rxSearch').type(S.patient.mrn + '{enter}')
    cy.get(`#prescriptionBody tr[data-rx-id="${S.rxId}"]`).contains('button', 'Dispense').click()
    // 1. The sale form: the medicine in the cart, the patient as the customer — nothing typed by the pharmacist.
    cy.get('#sellDiv').should('be.visible')
    cy.get('#dispenseBanner').should('be.visible').and('contain', S.patient.name)
    cy.get('#tablesi tbody tr', { timeout: 20000 }).should('have.length.greaterThan', 0)
    cy.get('#sellCN').should('have.value', S.patient.name)
    cy.get('#sellCC').should('have.value', phone)
    cy.screenshot('hms-s4l/L-04-1-sale-with-patient', { capture: 'viewport' })
    // 2. Cash, complete.
    cy.get('#sellPayMethod').select('CASH', { force: true })
    cy.get('#sellRec').clear().type('1000')
    cy.clickAndConfirmSale('#addSell')   // waits for the dialog OR the sale: never races it
    cy.wait('@sale', { timeout: 30000 }).then(({ request, response }) => {
      // EXPECTED: the sale carried the PATIENT'S customer id — the one made at reception — and succeeded.
      expect(request.body.customer.customerId, 'the patient\'s customer, by id').to.eq(S.patient.customerId)
      expect(String(response.body.status), JSON.stringify(response.body).slice(0, 200)).to.eq('SUCCESS')
    })
    // 3. The script is satisfied — recorded by the dispense call that FOLLOWS the sale (wait for it: reading the
    //    prescription straight after the sale races it) — and the person still has exactly the one customer.
    cy.wait('@dispense', { timeout: 30000 }).its('response.body.data.status').should('eq', 'FULLY_DISPENSED')
    cy.request(`/getPrescription?id=${S.rxId}`).its('body.data.status').should('eq', 'FULLY_DISPENSED')
    relogin(OWNER)
    cy.request(`/customerForParty?partyId=${S.patient.partyId}`).its('body.object.customerId').should('eq', S.patient.customerId)
    cy.request('/getUserCustomer').then((r) => {
      const all = (r.body && (r.body.collection || r.body.data)) || []
      const mine = all.filter((c) => c.contact === phone)
      expect(mine.map((c) => c.customerId), 'one customer for this phone, not two').to.deep.eq([S.patient.customerId])
    })
  })

  // ── L-05 — a sale cannot borrow another organisation's customer ─────────────────────────────────────
  it('L-05 a sale naming another organisation\'s customer id is refused, and that customer is untouched', () => {
    relogin(OTHER, 'other')
    cy.seedProduct({ name: 'S4 Other ' + run, unit: 'pcs', stock: 5, sellingPrice: 10 }).then(({ productId }) => {
      post('/addSell', {
        customer: { customerId: S.patient.customerId, name: 'Hijack', contact: '0300', paidAmount: 10, dueAmount: 0 },
        sales: [{ productId, quantity: 1, sellRate: 10, totalAmount: 10, netAmount: 10 }],
        tenders: [{ method: 'CASH', amount: 10, reference: '' }],
        idempotencyKey: 'cy-s4l-hijack-' + run,
      }).then((r) => {
        expect(String(r.body.status), JSON.stringify(r.body).slice(0, 300)).to.not.eq('SUCCESS')
        expect(JSON.stringify(r.body)).to.match(/Customer not found/)
      })
    })
    // Still the pharmacy's customer, in the pharmacy's organisation.
    relogin(OWNER)
    cy.request(`/customerForParty?partyId=${S.patient.partyId}`).its('body.object.customerId').should('eq', S.patient.customerId)
  })
})
