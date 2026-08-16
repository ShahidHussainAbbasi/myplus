/**
 * Pharmacy P12 (slice 59) — insurance + co-pay. A dispense is split between an insurer (INSURANCE tender) and the
 * patient's co-pay; the sale settles fully (no due, mode SPLIT). Also checks the sell screen offers the Insurance
 * method + covered-amount field. Run headed.
 */
describe('Pharmacy — insurance / co-pay (P12)', () => {
  let productId
  const pname = 'InsMed_' + Date.now()

  beforeEach(() => cy.loginAsPharma())

  before(() => {
    cy.loginAsPharma()
    cy.request({ method: 'POST', url: '/addProduct', body: { name: pname, sku: 'INS' + Date.now(), sellingPrice: 100, taxRate: 0, unit: 'pack' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => { productId = r.body.data.id })
    cy.then(() => cy.request({ method: 'POST', url: '/addProductStock', body: { productId, quantity: 5 }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false }))
  })

  it('insurance + co-pay settles the dispense with no due (mode SPLIT)', () => {
    let invoiceNo
    cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body: {
        customer: { name: 'InsPatient_' + Date.now(), contact: '0300INS', paidAmount: 20, dueAmount: 0 },
        sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
        tenders: [{ method: 'CASH', amount: 20, reference: '' }, { method: 'INSURANCE', amount: 80, reference: '' }],
      },
      failOnStatusCode: false,
    }).then((r) => {
      expect(r.body.status, JSON.stringify(r.body).substring(0, 200)).to.eq('SUCCESS')
      invoiceNo = r.body.object
      expect(invoiceNo, 'invoice number returned').to.exist
    })

    cy.then(() => cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo)).then((r) => {
      const rec = r.body.object || {}
      expect(rec.paymentMode, 'two tenders -> SPLIT').to.eq('SPLIT')   // insurance counted as a paid tender
      expect(Number(rec.dueAmount), 'fully settled, no due').to.eq(0)
    }))
  })

  // The covered-amount field (#sellInsured) was REMOVED by decision 2026-08-16 — a pharmacy still settles
  // against an insurer via the tender, it just does not split the amount on this screen. Asserting its
  // ABSENCE rather than dropping the case keeps the decision visible: if the field ever returns, this
  // fails and whoever brings it back has to say so.
  it('the sell screen offers the Insurance tender (the covered-amount field is deliberately gone)', () => {
    cy.visit('/businessDashboard')
    cy.get('#sellType', { timeout: 10000 }).select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')
    cy.get('#sellPayMethod option[value="INSURANCE"]').should('exist')
    cy.get('#sellInsured').should('not.exist')
  })
})
