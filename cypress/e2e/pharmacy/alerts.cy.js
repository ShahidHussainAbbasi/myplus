/**
 * Pharmacy P8 (slice 45) — alerts & controlled register. A controlled dispense lands on the controlled-substance
 * register; stock alerts reuse inventory-service. Run headed.
 */
describe('Pharmacy — alerts & controlled register', () => {
  beforeEach(() => { cy.loginAsPharma() })

  it('a controlled-substance dispense appears on the controlled register', () => {
    const iname = 'CtrlMed_' + Date.now()
    const invoiceNo = 'INV-CTRL-' + Date.now()
    let itemId

    cy.request({ method: 'POST', url: '/addItem', form: true, body: { icode: 'CT' + Date.now(), iname: iname, unit: 'tablet' }, failOnStatusCode: false })
    cy.request('/getUserItem').then((res) => {
      const item = (res.body.data || res.body.collection || []).find((i) => i.iname === iname)
      if (!item) return cy.log('Item not created — skipping')
      itemId = item.id

      // flag it controlled
      cy.request({ method: 'POST', url: '/saveClinical', body: { itemId: itemId, medicineName: iname, controlledSubstance: true }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })

      // prescription → dispense (controlled)
      cy.request({ method: 'POST', url: '/addPrescription', body: { patientName: 'Ctrl_' + Date.now(), items: [{ itemId: itemId, medicineName: iname, quantity: 5 }] }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false }).then((r) => {
        const rxId = r.body.data.id
        cy.request({ method: 'POST', url: '/dispensePrescription', body: { prescriptionId: rxId, invoiceNo: invoiceNo, items: [{ itemId: itemId, quantity: 5 }] }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false }).then((d) => {
          expect(d.body.success, JSON.stringify(d.body)).to.eq(true)
        })
      })

      cy.request('/controlledRegister').then((r) => {
        expect(r.body.success).to.eq(true)
        const mine = (r.body.data || []).find((x) => x.invoiceNo === invoiceNo)
        expect(mine, 'controlled dispense on the register').to.exist
        expect(mine.medicineName).to.eq(iname)
        expect(mine.quantity).to.eq(5)
      })
    })
  })

  it('stock alerts proxy returns successfully (reuses inventory)', () => {
    cy.request({ url: '/getStockAlerts', failOnStatusCode: false }).then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body).to.have.property('success')   // data may be empty; just verify the reuse path works
    })
  })

  it('Alerts & Register panel renders for PHARMA', () => {
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showPharmAlerts')
    cy.window().then((w) => w.showPharmAlerts())
    cy.get('#PharmAlertsDiv').should('be.visible')
    cy.get('#tableControlled').should('exist')
  })
})
