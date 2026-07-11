/**
 * F3b — auto-posting. Operational events post balanced GL journals: a credit sale → Dr AR / Cr Sales(+Tax); a
 * receipt → Dr Cash / Cr AR; a purchase → Dr Inventory / Cr Cash/AP. We assert the affected accounts move and the
 * trial balance stays balanced (Σdebit = Σcredit) throughout. Requires finance-service (GL) + business + gateway up.
 */
describe('F3b — events auto-post to the General Ledger', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const tb = () => cy.request('/gl/trialBalance').then((r) => parse(r.body))
  const acct = (rows, code) => (rows || []).find((x) => x.code === code) || { debit: 0, credit: 0 }
  // The trial balance NETS each account to one side, so an absolute "debit > 0" is brittle in a shared org where
  // other tests (refunds, purchase returns) credit the same account. Assert the signed net (debit − credit) MOVES.
  const net = (rows, code) => { const a = acct(rows, code); return Number(a.debit) - Number(a.credit) }

  it('a credit sale posts AR + Sales, then a receipt posts Cash − AR; balanced throughout', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    tb().then((before) => {
      const salesBefore = Number(acct(before.rows, '4000').credit)
      const arBefore = net(before.rows, '1100')     // AR net before the credit sale
      const cashBefore = net(before.rows, '1000')   // Cash net before the receipt (the sale is credit-only, no cash)
      const custName = 'GLB_' + Date.now()
      cy.seedProduct({ name: 'GLBP_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId }) => {
        cy.request({
          method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
          body: {
            customer: { name: custName, contact: '0300GLB', paidAmount: 0, dueAmount: 0 },
            sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
            paidAmount: 0, dueAmount: 0, grandTotal: 100,
          }, failOnStatusCode: false,
        }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

        tb().then((afterSale) => {
          expect(afterSale.balanced, 'GL balanced after sale').to.eq(true)
          expect(Number(afterSale.totalDebit)).to.eq(Number(afterSale.totalCredit))
          expect(Number(acct(afterSale.rows, '4000').credit), 'Sales credit increased').to.be.greaterThan(salesBefore)
          expect(net(afterSale.rows, '1100'), 'AR increased by the credit sale').to.be.greaterThan(arBefore)
        })

        // receive the payment → the recordPayment hook posts Dr Cash / Cr AR
        cy.request('/getUserCustomer?q=-1').then((cr) => {
          const c = (cr.body.collection || cr.body.data || []).find((x) => x.name === custName)
          const customerId = c.customerId || c.id
          const due = Number(c.dueAmount || 0)
          cy.request({ method: 'POST', url: '/receivePayment', form: true, body: { customerId, amount: due, method: 'CASH' }, failOnStatusCode: false })
            .then((p) => expect(p.body.status).to.eq('SUCCESS'))
          tb().then((afterPay) => {
            expect(afterPay.balanced, 'GL balanced after receipt').to.eq(true)
            expect(net(afterPay.rows, '1000'), 'Cash increased by the receipt').to.be.greaterThan(cashBefore)
          })
        })
      })
    })
  })

  it('a purchase posts Inventory; trial balance stays balanced', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    tb().then((before) => {
      const invBefore = net(before.rows, '1200')   // Inventory net before the purchase (returns credit it elsewhere)
      const stamp = Date.now()
      cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'GLPCo_' + stamp, email: `glpco${stamp}@t.com` } })
      cy.request('/getUserCompany').then((cr) => {
        const companyId = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'GLPCo_' + stamp).id
        cy.request({ method: 'POST', url: '/addVender', form: true, body: { name: 'GLPVEN_' + stamp, companyId, mobile: '03004445555', email: `glp${stamp}@t.com` } })
        cy.request('/getUserVender').then((lr) => {
          const venderId = (lr.body.collection || lr.body.data || []).find((x) => x.name === 'GLPVEN_' + stamp).id
          cy.seedProduct({ name: 'GLPP_' + stamp, sellingPrice: 100, stock: 10 }).then(({ productId }) => {
            cy.request({
              method: 'POST', url: '/addPurchase', form: true,
              body: { productId, quantity: 10, venderId, paidAmount: 0, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 12, totalAmount: 100, netAmount: 100, purchaseInvoiceNo: 'GLPINV-' + stamp },
              failOnStatusCode: false,
            }).then((pr) => expect(pr.body.status, JSON.stringify(pr.body)).to.eq('SUCCESS'))

            tb().then((after) => {
              expect(after.balanced, 'GL balanced after purchase').to.eq(true)
              expect(Number(after.totalDebit)).to.eq(Number(after.totalCredit))
              expect(net(after.rows, '1200'), 'Inventory increased by the purchase').to.be.greaterThan(invBefore)
            })
          })
        })
      })
    })
  })
})
