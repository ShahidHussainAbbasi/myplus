/**
 * Pharmacy P8 (slice 45) — alerts & controlled register. A controlled dispense lands on the controlled-substance
 * register; stock alerts reuse inventory-service. Run headed.
 */
describe('Pharmacy — alerts & controlled register', () => {
  beforeEach(() => { cy.loginAsPharma() })
  // A3 buys one batch; a failure before its own void must not leave it standing on the pharmacy.
  after(() => {
    cy.loginAsPharmaOwner()
    cy.request('/getUserPurchase').then((r) => (r.body.collection || [])
      .filter((p) => String(p.purchaseInvoiceNo).startsWith('ALR-') && p.status !== 'VOID')
      .forEach((p) => cy.request({ method: 'POST', url: '/voidPurchase', form: true, failOnStatusCode: false,
        body: { purchaseId: p.purchaseId, reason: 'ALERT-RETIRE gate cleanup' } })))
  })

  it('a controlled-substance dispense appears on the controlled register', () => {
    const iname = 'CtrlMed_' + Date.now()
    const invoiceNo = 'INV-CTRL-' + Date.now()
    // M4a (slice 90): seed the medicine via the catalog Product master (+ opening stock for the dispense).
    cy.seedProduct({ name: iname, sku: 'CT' + Date.now(), unit: 'tablet', stock: 50 }).then(({ productId }) => {

      // flag it controlled
      cy.request({ method: 'POST', url: '/saveClinical', body: { productId: productId, medicineName: iname, controlledSubstance: true }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })

      // prescription → dispense (controlled)
      cy.request({ method: 'POST', url: '/addPrescription', body: { patientName: 'Ctrl_' + Date.now(), items: [{ productId: productId, medicineName: iname, quantity: 5 }] }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false }).then((r) => {
        const rxId = r.body.data.id
        cy.request({ method: 'POST', url: '/dispensePrescription', body: { prescriptionId: rxId, invoiceNo: invoiceNo, items: [{ productId: productId, quantity: 5 }] }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false }).then((d) => {
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

  // ALERT-RETIRE (2026-10-10): inventory's stored stock_alerts were unscoped (every tenant's rows to every caller, a
  // cross-tenant read-all), duplicated hourly, and never held a near-expiry row. The screen now shows the SAME live,
  // per-tenant summary as the header badge (STK-ALERT). Design: selling-price-per-purchase-analysis.md §12.17.

  it('A2 the old stored-alerts proxy is gone', () => {
    cy.request({ url: '/getStockAlerts', failOnStatusCode: false }).then((r) =>
      expect(r.status, 'retired: ' + JSON.stringify(r.body).slice(0, 120)).to.be.oneOf([404, 405]))
  })

  it('A3 owner: the Alerts screen lists exactly what the live summary lists — names, not ids', () => {
    cy.loginAsPharmaOwner()
    const stamp = Date.now(), pname = 'ALR_' + stamp, inv = 'ALR-' + stamp
    const d = new Date()
    const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    cy.ensureCompany().then((companyId) => {
      const vName = 'ALRV_' + stamp
      cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
        body: { name: vName, companyId, mobile: '0307' + String(stamp).slice(-7), email: `alr${stamp}@t.com` } })
      cy.request('/getUserVenders').then((vr) => {
        const html = String(vr.body && vr.body.object != null ? vr.body.object : vr.body)
        const venderId = (new RegExp('<option value=(\\d+)[^>]*>' + vName).exec(html) || [])[1]
        cy.seedProduct({ name: pname, sellingPrice: 30 }).then(({ productId }) => {
          // a batch that expires TODAY: still sellable, so "expiring", and first in that list
          cy.request({ method: 'POST', url: '/addPurchase', form: true,
            body: { productId, venderId, quantity: 2, purchaseRate: 20, 'stock.bpurchaseRate': 20, 'stock.bsellRate': 30,
                    'stock.batchNo': inv, 'stock.bexpDate': today, totalAmount: 40, netAmount: 20, purchaseInvoiceNo: inv } })
            .its('body.status').should('eq', 'SUCCESS')
          cy.request('/stockAlertSummary').then((r) => {
            const s = r.body
            expect(s.success, JSON.stringify(s).slice(0, 200)).to.eq(true)
            const names = [].concat(s.expiredItems || [], s.expiringItems || [], s.lowItems || []).map((x) => x.name)
            cy.visit('/businessDashboard')
            cy.window().should('have.property', 'showPharmAlerts')
            cy.intercept('GET', '**/stockAlertSummary').as('sa')
            cy.window().then((w) => w.showPharmAlerts())
            cy.wait('@sa')
            cy.get('#stockAlertsBody tr td:nth-child(2)').should(($td) => {
              expect([...$td].map((td) => td.innerText.trim()), 'the screen lists the summary’s items').to.deep.eq(names)
            })
            cy.get('#stockAlertsBody').should('not.contain', '#' + productId)
            if ((s.expiringItems || []).some((x) => x.name === pname)) cy.get('#stockAlertsBody').should('contain', pname)
          })
          cy.request('/getUserPurchase').then((r) => {
            const p = (r.body.collection || []).find((x) => x.purchaseInvoiceNo === inv)
            cy.request({ method: 'POST', url: '/voidPurchase', form: true, body: { purchaseId: p.purchaseId, reason: 'ALERT-RETIRE gate' } })
              .its('body.status').should('eq', 'SUCCESS')
          })
        })
      })
    })
  })

  it('A4 a plain user: told the list is for owners and admins; the register is still there', () => {
    cy.loginAsTier('user', 'pharma')
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showPharmAlerts')
    cy.window().then((w) => w.showPharmAlerts())
    cy.get('#stockAlertsBody tr').should('have.length', 0)
    cy.get('#stockAlertsEmpty').should('be.visible').and('contain', 'owners and admins')
    cy.get('#tableControlled').should('exist')
  })
  it('Alerts & Register panel renders for PHARMA', () => {
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showPharmAlerts')
    cy.window().then((w) => w.showPharmAlerts())
    cy.get('#PharmAlertsDiv').should('be.visible')
    cy.get('#tableControlled').should('exist')
  })
})
