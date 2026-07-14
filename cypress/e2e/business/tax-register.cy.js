/**
 * Tax-filing register (Phase A — output tax). With tax enabled, a taxed sale adds output tax to the register (from the
 * GL TAX account); voiding it records an adjustment that nets the output back down. Requires finance-service (GL) +
 * business + gateway up. Run headed.
 */
describe('Tax register — output tax', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const today = new Date().toISOString().slice(0, 10)
  const reg = () => cy.request(`/taxRegister?from=${today}&to=${today}`).then((r) => parse(r.body))

  it('a taxed sale adds output tax; voiding it records an adjustment', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    // Enable tax (output) at 10% so sales compute tax — the configurable per-org policy.
    cy.request({ method: 'POST', url: '/saveTaxSetting', form: true, body: { enabled: true, defaultRate: 10, taxMode: 'EXCLUSIVE' }, failOnStatusCode: false })
      .then((s) => expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS'))

    reg().then((before) => {
      const outBefore = Number(before.outputTax || 0)
      const adjBefore = Number(before.outputAdjusted || 0)

      cy.seedProduct({ name: 'TAXP_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId }) => {
        cy.request({
          method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
          body: {
            customer: { name: 'TAXC_' + Date.now(), contact: '0300TAX', paidAmount: 0, dueAmount: 0 },
            sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
            paidAmount: 0, dueAmount: 0, grandTotal: 100,
          }, failOnStatusCode: false,
        }).then((r) => {
          expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
          const invoiceNo = r.body.object

          // Output tax rose by the sale's tax (10% of 100 = 10), and a SALE credit line exists for the invoice.
          reg().then((afterSale) => {
            expect(Number(afterSale.outputTax), 'output tax increased').to.be.greaterThan(outBefore)
            expect(Number(afterSale.netPayable), 'net payable moved up').to.be.greaterThan(Number(before.netPayable || 0) - 0.001)
            const line = (afterSale.lines || []).find((l) => l.ref === invoiceNo && l.source === 'SALE')
            expect(line, 'SALE line for the invoice').to.exist
            expect(Number(line.credit), 'tax credited (~10)').to.be.greaterThan(0)
          })

          // Void → an adjustment (Dr TAX) appears and the net output drops back.
          cy.request({ method: 'POST', url: '/voidSell', form: true, body: { invoiceNo, reason: 'CY tax' }, failOnStatusCode: false })
            .then((v) => expect(v.body.status, JSON.stringify(v.body)).to.eq('SUCCESS'))
          reg().then((afterVoid) => {
            expect(Number(afterVoid.outputAdjusted), 'adjustment recorded').to.be.greaterThan(adjBefore)
            const adj = (afterVoid.lines || []).find((l) => l.ref === invoiceNo && l.source === 'SALE_RETURN')
            expect(adj, 'SALE_RETURN adjustment line for the invoice').to.exist
            expect(Number(adj.debit), 'tax debited back').to.be.greaterThan(0)
          })
        })
      })
    })
  })
})
