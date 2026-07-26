/**
 * common-settings behaviour wiring #1 — pos.receipt.showTaxBreakdown.
 *
 * Proves the owner's Configuration toggle actually changes a sale artefact: the authoritative receipt
 * (/getReceipt) carries `showTaxBreakdown` reflecting the org's setting, and the thermal printer (receipt.js)
 * renders per-rate tax rows only when it is on. This is the end-to-end proof that a common-settings flag is not
 * just stored but honoured by behaviour.
 *
 * Flow: sell a taxed item → toggle the flag OFF → the receipt says showTaxBreakdown=false (client collapses the
 * per-rate rows) → toggle ON → receipt says true. Restores the default (on) so reruns/other specs are clean.
 * Run headed. demo.business passes authz and is the established selling org.
 */
describe('common-settings: receipt tax breakdown honours the owner toggle', () => {
  const KEY = 'pos.receipt.showTaxBreakdown'

  beforeEach(() => { cy.loginAsBusiness() })

  const setFlag = (value) =>
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: KEY, value },
      failOnStatusCode: false })
      .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${KEY}=${value}: ${JSON.stringify(r.body)}`).to.eq(true))

  const receiptOf = (invoiceNo) =>
    cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo)).then((rc) => rc.body.object || rc.body.data)

  after(() => {
    // Leave the org on the default (on) regardless of where the test stopped.
    cy.loginAsBusiness()
    setFlag('true')
  })

  it('the receipt reflects the flag: off → showTaxBreakdown=false, on → true', () => {
    cy.seedProduct({ name: 'TB_' + Date.now(), sellingPrice: 100, taxRate: 17, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: 'TB_' + Date.now(), contact: '0300TB', paidAmount: 117, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
          tenders: [{ method: 'CASH', amount: 117 }], paidAmount: 117, dueAmount: 0, grandTotal: 117,
        }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        const invoiceNo = r.body.object

        // Sanity: the sale actually carries tax, so the breakdown is meaningful.
        receiptOf(invoiceNo).then((inv) => {
          expect(Number(inv.taxTotal), 'sale is taxed (17%)').to.be.greaterThan(0)
        })

        // Owner turns the breakdown OFF → the receipt tells the client to collapse per-rate rows.
        setFlag('false')
        receiptOf(invoiceNo).then((inv) => {
          expect(inv.showTaxBreakdown, 'flag off flows onto the receipt').to.eq(false)
        })

        // Owner turns it back ON → the receipt shows the breakdown again.
        setFlag('true')
        receiptOf(invoiceNo).then((inv) => {
          expect(inv.showTaxBreakdown, 'flag on flows onto the receipt').to.eq(true)
        })
      })
    })
  })
})
