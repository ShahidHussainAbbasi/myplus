/**
 * Purchase Return (debit note) — returning goods to the vendor reverses the stock-in, cuts the vendor payable
 * (mirror of the sale-return reconcile), and posts a PURCHASE_RETURN reversal to the GL (books stay balanced).
 * Requires finance-service (GL) + business + gateway up.
 */
describe('Purchase Return — stock-out + AP reduction + GL reversal', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const vendorDue = (id) => cy.request('/getUserVender').then((r) => {
    const v = (r.body.collection || r.body.data || []).find((x) => x.id === id)
    return Number(v ? v.dueAmount || 0 : 0)
  })

  it('returning half of an on-credit purchase halves the vendor payable and keeps the GL balanced', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    const stamp = Date.now()
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'PRCo_' + stamp, email: `prco${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const companyId = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'PRCo_' + stamp).id
      cy.request({ method: 'POST', url: '/addVender', form: true, body: { name: 'PRVEN_' + stamp, companyId, mobile: '03006667777', email: `pr${stamp}@t.com` } })
      cy.request('/getUserVender').then((lr) => {
        const venderId = (lr.body.collection || lr.body.data || []).find((x) => x.name === 'PRVEN_' + stamp).id
        cy.seedProduct({ name: 'PRP_' + stamp, sellingPrice: 100, stock: 10 }).then(({ productId }) => {
          const inv = 'PRINV-' + stamp
          cy.request({
            method: 'POST', url: '/addPurchase', form: true,
            body: { productId, quantity: 10, venderId, paidAmount: 0, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 12, totalAmount: 100, netAmount: 100, purchaseInvoiceNo: inv },
            failOnStatusCode: false,
          }).then((pr) => expect(pr.body.status, JSON.stringify(pr.body)).to.eq('SUCCESS'))

          vendorDue(venderId).then((d) => expect(d, 'credit purchase → payable 100').to.eq(100))

          cy.request('/getUserPurchase').then((gp) => {
            const rec = (gp.body.collection || gp.body.data || []).find((x) => x.purchaseInvoiceNo === inv)
            expect(rec, 'purchase record').to.exist
            const purchaseId = rec.purchaseId || rec.id
            // return 5 of 10 → returned value 50 → payable drops to 50
            cy.request({ method: 'POST', url: '/purchaseReturn', form: true, body: { purchaseId, quantity: 5, reason: 'damaged' }, failOnStatusCode: false })
              .then((rr) => expect(rr.body.status, JSON.stringify(rr.body)).to.eq('SUCCESS'))

            vendorDue(venderId).then((d) => expect(d, 'payable halved after returning half').to.be.closeTo(50, 0.01))
            cy.request('/gl/trialBalance').then((tr) => {
              const tb = parse(tr.body)
              expect(tb.balanced, 'GL still balanced after purchase return').to.eq(true)
              expect(Number(tb.totalDebit)).to.eq(Number(tb.totalCredit))
            })
          })
        })
      })
    })
  })
})
