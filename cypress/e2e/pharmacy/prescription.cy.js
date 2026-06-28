/**
 * Pharmacy P5 (slice 41, reuse-first) — prescription intake referencing an existing business Item (itemId, the
 * same id the sell flow uses). Medicine registration reuses the Item screen; this is the net-new clinical screen.
 * Run headed.
 */
describe('Pharmacy — prescription intake (on itemId bridge)', () => {
  beforeEach(() => { cy.loginAsPharma() })

  it('records a prescription for an existing item and lists it', () => {
    const patient = 'Rx_' + Date.now()
    const iname = 'RxItem_' + Date.now()
    // M4a (slice 90): the medicine is created via the catalog Product master (projected to a bridged Item the Rx references).
    cy.seedProduct({ name: iname, sku: 'RX' + Date.now(), unit: 'tablet' }).then(({ itemId }) => {

      cy.request({
        method: 'POST', url: '/addPrescription',
        body: {
          patientName: patient, patientPhone: '0300RX', doctorName: 'Dr House', doctorLicense: 'LIC-9', diagnosis: 'Fever',
          items: [{ itemId: itemId, medicineName: iname, quantity: 10, dosage: '1 tab', frequency: 'BD', duration: '5d' }],
        },
        headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.status).to.eq(200)
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        expect(r.body.data.status).to.eq('PENDING')
        expect(r.body.data.items[0].itemId).to.eq(itemId)
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
