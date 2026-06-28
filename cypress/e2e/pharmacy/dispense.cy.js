/**
 * Pharmacy P6 (slice 43) — Dispense = reuse the Sell sale + a prescription link. API: dispensing a prescription
 * bumps dispensedQuantity + sets status; UI: the Dispense action switches to the Sell screen with the banner.
 * Run headed.
 */
describe('Pharmacy — dispense (sale + Rx link)', () => {
  beforeEach(() => { cy.loginAsPharma() })

  it('dispensing a prescription updates quantities + status (FULLY_DISPENSED)', () => {
    const iname = 'DispMed_' + Date.now()
    let rxId

    // M4a (slice 90): seed the medicine via the catalog Product master (+ opening stock for the dispense).
    cy.seedProduct({ name: iname, sku: 'DSP' + Date.now(), unit: 'tablet', stock: 50 }).then(({ itemId }) => {

      cy.request({
        method: 'POST', url: '/addPrescription',
        body: { patientName: 'Disp_' + Date.now(), items: [{ itemId: itemId, medicineName: iname, quantity: 20, dosage: '1', frequency: 'BD', duration: '5d' }] },
        headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        rxId = r.body.data.id
      })

      cy.then(() => {
        cy.request({
          method: 'POST', url: '/dispensePrescription',
          body: { prescriptionId: rxId, invoiceNo: 'INV-TEST', items: [{ itemId: itemId, quantity: 20 }] },
          headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        }).then((r) => {
          expect(r.status).to.eq(200)
          expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
          expect(r.body.data.status).to.eq('FULLY_DISPENSED')
          expect(r.body.data.items[0].dispensedQuantity).to.eq(20)
        })
      })
    })
  })

  it('Dispense action switches to the Sell screen with the dispense banner', () => {
    const patient = 'UiDisp_' + Date.now()
    cy.request({
      method: 'POST', url: '/addPrescription',
      body: { patientName: patient, items: [{ itemId: 1, medicineName: 'X', quantity: 1 }] },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    }).then((r) => {
      const rxId = r.body.data.id
      cy.visit('/businessDashboard')
      cy.window().then((w) => w.showPrescriptions())
      cy.get('#PrescriptionDiv').should('be.visible')
      cy.window().then((w) => w.dispenseFromPrescription(rxId))
      cy.get('#sellDiv').should('be.visible')
      cy.get('#dispenseBanner').should('be.visible')
      cy.window().its('dispensingPrescriptionId').should('eq', rxId)
    })
  })
})
