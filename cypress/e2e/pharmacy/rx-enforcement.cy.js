/**
 * Pharmacy — prescription-required enforcement (review finding B1).
 * Design: microservices/docs/pharmacy-rx-enforcement-design.md
 *
 * Before this slice the "prescription required" flag changed nothing: a prescription-only medicine sold to a
 * walk-in exactly like chewing gum. Now the flag lives on the catalog product (so the sell saga reads it off the
 * ProductRef it already fetches — no extra call at checkout) and SagaSellService refuses the line unless the sale
 * declares a prescription.
 *
 * Cases 4 and 5 are the regression that matters most: this guard sits in buildLines, the highest-traffic path in
 * the system, shared by POS / pharmacy / storefront. An ordinary product must be completely unaffected.
 *
 * Run headed.
 */
describe('Pharmacy — prescription-only enforcement', () => {
  beforeEach(() => { cy.loginAsPharma() })

  const post = (url, body) =>
    cy.request({ method: 'POST', url: url, body: body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })

  // Sell one unit, optionally declaring a prescription. Same payload shape the other pharmacy sell specs use
  // (insurance-copay / quarantine-register) so a failure here means the guard, not the request.
  const sell = (productId, name, prescriptionId) =>
    post('/addSell', {
      customer: { name: 'Walkin_' + Date.now(), contact: '0300RX', paidAmount: 10, dueAmount: 0 },
      sales: [{ productId: productId, quantity: 1, sellRate: 10, totalAmount: 10, netAmount: 10 }],
      tenders: [{ method: 'CASH', amount: 10, reference: '' }],
      idempotencyKey: 'cy-rx-' + Date.now() + '-' + Math.random().toString(36).slice(2),
      prescriptionId: prescriptionId || null,
    })

  it('a prescription-only medicine cannot be sold without a prescription', () => {
    const name = 'RxOnly_' + Date.now()
    cy.seedProduct({ name: name, sku: 'RX' + Date.now(), unit: 'tablet', stock: 30 }).then(({ productId }) => {
      // flag it prescription-only (goes to the catalog master via pharma → catalog)
      post('/saveClinical', { productId: productId, medicineName: name, rxRequired: true, controlledSubstance: false })
        .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

      sell(productId, name).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.not.eq('SUCCESS')
        expect(String(r.body.message || '')).to.match(/prescription-only/i)
      })
    })
  })

  it('the same medicine sells when the sale declares a prescription', () => {
    const name = 'RxOk_' + Date.now()
    cy.seedProduct({ name: name, sku: 'RK' + Date.now(), unit: 'tablet', stock: 30 }).then(({ productId }) => {
      post('/saveClinical', { productId: productId, medicineName: name, rxRequired: true, controlledSubstance: false })

      post('/addPrescription', { patientName: 'RxPat_' + Date.now(),
        items: [{ productId: productId, medicineName: name, quantity: 5 }] }).then((r) => {
        const rxId = r.body.data.id
        sell(productId, name, rxId).then((s) => {
          expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS')
          expect(s.body.object, 'an invoice number came back').to.be.a('string')
        })
      })
    })
  })

  it('an ordinary product is unaffected — no flag, no guard', () => {
    const name = 'Plain_' + Date.now()
    cy.seedProduct({ name: name, sku: 'PL' + Date.now(), unit: 'pack', stock: 30 }).then(({ productId }) => {
      sell(productId, name).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      })
    })
  })

  it('clearing the flag lets the medicine sell again (catalog is the source of truth)', () => {
    const name = 'RxClear_' + Date.now()
    cy.seedProduct({ name: name, sku: 'RC' + Date.now(), unit: 'tablet', stock: 30 }).then(({ productId }) => {
      post('/saveClinical', { productId: productId, medicineName: name, rxRequired: true, controlledSubstance: false })
      sell(productId, name).then((r) => expect(r.body.status).to.not.eq('SUCCESS'))

      post('/saveClinical', { productId: productId, medicineName: name, rxRequired: false, controlledSubstance: false })
      sell(productId, name).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    })
  })

  it('the flag round-trips through the Clinical screen from catalog', () => {
    const name = 'RxList_' + Date.now()
    cy.seedProduct({ name: name, sku: 'RL' + Date.now(), unit: 'tablet', stock: 5 }).then(({ productId }) => {
      post('/saveClinical', { productId: productId, medicineName: name, rxRequired: true, controlledSubstance: true })
      cy.request('/getClinical').then((r) => {
        const row = (r.body.data || []).find((c) => c.productId === productId)
        expect(row, 'the flagged medicine is listed').to.exist
        expect(row.rxRequired, 'read back from the catalog master').to.eq(true)
        expect(row.controlledSubstance).to.eq(true)
      })
    })
  })

  it('a cashier without ADMIN cannot flag a medicine (gate holds through the new catalog path)', () => {
    // The flag now travels pharma → catalog; both ends are ADMIN-gated. Uses the same accounts as
    // cypress/e2e/pharmacy/method-authz.cy.js.
    const GW = 'http://localhost:8765'
    cy.request({ method: 'POST', url: `${GW}/api/auth/login`, headers: { 'Content-Type': 'application/json' },
      body: { email: 'cashier.a@myplus.com', password: 'Demo@2025!' }, failOnStatusCode: false })
      .then((login) => {
        expect(login.status).to.eq(200)
        cy.request({ method: 'POST', url: `${GW}/api/pharma/clinical`,
          headers: { Authorization: `Bearer ${login.body.data.accessToken}`, 'Content-Type': 'application/json' },
          body: { productId: 999999, medicineName: 'nope', rxRequired: true }, failOnStatusCode: false })
          .then((r) => {
            if (r.status === 403) return
            expect(r.body && r.body.success, `a USER flagged a medicine: ${JSON.stringify(r.body)}`).to.not.eq(true)
          })
      })
  })
})
