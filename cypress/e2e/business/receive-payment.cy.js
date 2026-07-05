/**
 * Receive Payment (AR subledger) — a credit sale leaves the customer owing; /receivePayment FIFO-allocates a
 * receipt to their open invoice(s), recomputes the due, and records it in the shared finance-service ledger.
 * The test is tolerant of exact invoice math: it reads the customer's ACTUAL due after the sale, then settles it.
 * Requires finance-service + business-service + gateway up. Run headed.
 */
describe('Receive Payment — AR subledger settles a customer due', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const dueOf = (customerId) =>
    cy.request('/getUserCustomer?q=-1').then((r) => {
      const list = r.body.collection || r.body.data || []
      const mine = list.find((c) => c.customerId === customerId || c.id === customerId)
      return mine ? Number(mine.dueAmount || 0) : 0
    })

  it('a credit sale creates a due; receivePayment settles it and returns a receipt', () => {
    const custName = 'PAY_' + Date.now()
    cy.seedProduct({ name: 'PP_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      // credit sale: pay 0 of a 100 bill -> customer owes 100
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: custName, contact: '0300PAY', paidAmount: 0, dueAmount: 100 },
          sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
          paidAmount: 0, dueAmount: 100, grandTotal: 100,
        }, failOnStatusCode: false,
      }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      // find the customer + their (real) outstanding due
      cy.request('/getUserCustomer?q=-1').then((r) => {
        const list = r.body.collection || r.body.data || []
        const mine = list.find((c) => c.name === custName)
        expect(mine, 'seeded credit customer').to.exist
        const customerId = mine.customerId || mine.id
        const due0 = Number(mine.dueAmount || 0)
        expect(due0, 'credit sale left a due').to.be.greaterThan(0)

        // pay half -> due drops by half
        const half = Math.round((due0 / 2) * 100) / 100
        cy.request({
          method: 'POST', url: '/receivePayment', form: true,
          body: { customerId, amount: half, method: 'CASH' }, failOnStatusCode: false,
        }).then((r2) => {
          expect(r2.body.status, JSON.stringify(r2.body)).to.eq('SUCCESS')
          expect(r2.body.object, 'receipt payload').to.exist
          expect(r2.body.object.receiptNo, 'receipt number from the ledger').to.match(/RCPT-/)
        })
        dueOf(customerId).then((d) => expect(d, 'due dropped by the payment').to.be.closeTo(due0 - half, 0.01))

        // pay the rest -> due 0
        cy.request({
          method: 'POST', url: '/receivePayment', form: true,
          body: { customerId, amount: due0 - half, method: 'CASH' }, failOnStatusCode: false,
        }).then((r3) => expect(r3.body.status).to.eq('SUCCESS'))
        dueOf(customerId).then((d) => expect(d, 'fully settled').to.eq(0))
      })
    })
  })

  it('profile edit does NOT wipe the customer due (clobber fix)', () => {
    const custName = 'CL_' + Date.now()
    cy.seedProduct({ name: 'CLP_' + Date.now(), sellingPrice: 50, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: custName, contact: '0300CL', paidAmount: 0, dueAmount: 50 },
          sales: [{ productId, quantity: 1, sellRate: 50, totalAmount: 50, netAmount: 50 }],
          paidAmount: 0, dueAmount: 50, grandTotal: 50,
        }, failOnStatusCode: false,
      }).then((r) => expect(r.body.status).to.eq('SUCCESS'))

      cy.request('/getUserCustomer?q=-1').then((r) => {
        const mine = (r.body.collection || []).find((c) => c.name === custName)
        const customerId = mine.customerId || mine.id
        const due0 = Number(mine.dueAmount || 0)
        expect(due0).to.be.greaterThan(0)
        // edit ONLY the contact (no due field on the form) — due must survive
        cy.request({
          method: 'POST', url: '/addCustomer', form: true,
          body: { customerId, name: custName, contact: '0311EDITED', email: '', address: 'x' }, failOnStatusCode: false,
        }).then((r2) => expect(r2.body.status).to.eq('SUCCESS'))
        dueOf(customerId).then((d) => expect(d, 'due preserved after profile edit').to.eq(due0))
      })
    })
  })
})
