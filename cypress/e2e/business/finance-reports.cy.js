/**
 * F2 — Party statements + aging (AR & AP). A credit sale/purchase makes a party owe; aging buckets the balance
 * (a fresh doc lands in 0-30) and the statement lists bills + payments with a running balance.
 * Requires finance-service + business-service + gateway up. Run headed.
 */
describe('F2 — statements + aging', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const num = (v) => Number(v || 0)

  it('AR: credit sale → customer aging (0-30) + statement with running balance', () => {
    const stamp = Date.now()
    const custName = 'AGE_' + stamp
    cy.seedProduct({ name: 'AGP_' + stamp, sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      // credit sale: owe the full bill
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: custName, contact: '0300AGE', paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 100,
        }, failOnStatusCode: false,
      }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      cy.request('/getUserCustomer?q=-1').then((cr) => {
        const c = (cr.body.collection || cr.body.data || []).find((x) => x.name === custName)
        expect(c, 'seeded customer').to.exist
        const customerId = c.customerId || c.id
        const due = num(c.dueAmount)
        expect(due, 'credit sale created a due').to.be.greaterThan(0)

        // aging: the fresh invoice is current → its whole balance sits in 0-30, total == the due
        cy.request('/customerAging').then((ar) => {
          expect(ar.body.status, JSON.stringify(ar.body)).to.eq('SUCCESS')
          const mine = (ar.body.collection || ar.body.data || []).find((p) => p.partyId === customerId)
          expect(mine, 'customer on the aging report').to.exist
          expect(num(mine.total), 'aging total == due').to.be.closeTo(due, 0.01)
          expect(num(mine.b0_30), 'fresh invoice in 0-30 bucket').to.be.closeTo(due, 0.01)
        })

        // pay half, then the statement shows a BILL + a PAYMENT and a running balance == remaining
        const half = Math.round((due / 2) * 100) / 100
        cy.request({ method: 'POST', url: '/receivePayment', form: true, body: { customerId, amount: half, method: 'CASH' }, failOnStatusCode: false })
          .then((p) => expect(p.body.status).to.eq('SUCCESS'))

        cy.request('/customerStatement?customerId=' + customerId).then((sr) => {
          expect(sr.body.status, JSON.stringify(sr.body)).to.eq('SUCCESS')
          const lines = sr.body.collection || sr.body.data || []
          expect(lines.some((l) => l.type === 'BILL'), 'a BILL line').to.be.true
          const pay = lines.find((l) => l.type === 'PAYMENT')
          expect(pay, 'a PAYMENT line').to.exist
          expect(num(pay.credit), 'payment credit == amount paid').to.be.closeTo(half, 0.01)
          const closing = num(lines[lines.length - 1].balance)
          expect(closing, 'closing balance == remaining owed').to.be.closeTo(due - half, 0.01)
        })
      })
    })
  })

  it('AP: on-credit purchase → vendor aging (0-30) + statement', () => {
    const stamp = Date.now()
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'AGCo_' + stamp, email: `agco${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const companyId = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'AGCo_' + stamp).id
      const vName = 'AGVEN_' + stamp
      cy.request({ method: 'POST', url: '/addVender', form: true, body: { name: vName, companyId, mobile: '03007778888', email: `ag${stamp}@t.com` } })
        .then((vr) => expect(vr.body.status).to.eq('SUCCESS'))

      cy.request('/getUserVender').then((lr) => {
        const venderId = (lr.body.collection || lr.body.data || []).find((x) => x.name === vName).id
        cy.seedProduct({ name: 'AGVP_' + stamp, sellingPrice: 100, stock: 10 }).then(({ productId }) => {
          cy.request({
            method: 'POST', url: '/addPurchase', form: true,
            body: { productId, quantity: 10, venderId, paidAmount: 0, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 12, totalAmount: 100, netAmount: 100, purchaseInvoiceNo: 'AGINV-' + stamp },
            failOnStatusCode: false,
          }).then((pr) => expect(pr.body.status, JSON.stringify(pr.body)).to.eq('SUCCESS'))

          cy.request('/vendorAging').then((ar) => {
            expect(ar.body.status).to.eq('SUCCESS')
            const mine = (ar.body.collection || ar.body.data || []).find((p) => p.partyId === venderId)
            expect(mine, 'vendor on the aging report').to.exist
            expect(num(mine.b0_30), 'fresh bill in 0-30').to.be.closeTo(100, 0.01)
            expect(num(mine.total)).to.be.closeTo(100, 0.01)
          })

          cy.request('/vendorStatement?venderId=' + venderId).then((sr) => {
            expect(sr.body.status).to.eq('SUCCESS')
            const lines = sr.body.collection || sr.body.data || []
            const bill = lines.find((l) => l.type === 'BILL')
            expect(bill, 'a BILL line').to.exist
            expect(num(bill.debit), 'bill debit == total owed').to.be.closeTo(100, 0.01)
            expect(num(lines[lines.length - 1].balance), 'closing == owed 100').to.be.closeTo(100, 0.01)
          })
        })
      })
    })
  })
})
