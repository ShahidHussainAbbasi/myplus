/**
 * Pharmacy P5 (slice 41, reuse-first; M4e.d productId-native) — prescription intake referencing an existing
 * catalog Product (productId, the same id the sell flow uses). Medicine registration reuses the Product screen;
 * this is the net-new clinical screen. Run headed.
 */
describe('Pharmacy — prescription intake (on catalog productId)', () => {
  beforeEach(() => { cy.loginAsPharma() })

  it('records a prescription for an existing product and lists it', () => {
    const patient = 'Rx_' + Date.now()
    const iname = 'RxItem_' + Date.now()
    // M4e.d (slice 104): the medicine is created via the catalog Product master; the Rx references its productId.
    cy.seedProduct({ name: iname, sku: 'RX' + Date.now(), unit: 'tablet' }).then(({ productId }) => {

      cy.request({
        method: 'POST', url: '/addPrescription',
        body: {
          patientName: patient, patientPhone: '0300RX', doctorName: 'Dr House', doctorLicense: 'LIC-9', diagnosis: 'Fever',
          items: [{ productId: productId, medicineName: iname, quantity: 10, dosage: '1 tab', frequency: 'BD', duration: '5d' }],
        },
        headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.status).to.eq(200)
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        expect(r.body.data.status).to.eq('PENDING')
        expect(r.body.data.items[0].productId).to.eq(productId)
      })

      cy.request('/getPrescriptions').then((r) => {
        expect(r.body.success).to.eq(true)
        const mine = (r.body.data || []).find((p) => p.patientName === patient)
        expect(mine, 'prescription appears in the list').to.exist
      })
    })
  })

  it('PHARMA dashboard shows the Pharmacy nav + Prescription panel', () => {
    cy.visit('/businessDashboard')
    cy.window().its('MODULE').should('eq', 'PHARMA')
    cy.get('#snavPharmacy').should('be.visible')
    cy.window().then((w) => w.showPrescriptions())
    cy.get('#PrescriptionDiv').should('be.visible')
    cy.get('#rxPatient').should('be.visible')
  })
})
