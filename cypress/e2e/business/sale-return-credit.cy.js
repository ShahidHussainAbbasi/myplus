/**
 * SF-5 — return reconciles the invoice payment (Model A, cash refund).
 *
 * Bug: saleReturn restocked + wrote a negative REFUND payment-line but never lowered the header paidAmount, so a
 * return on a PAID invoice left paidAmount > grandTotal → dueAmount went falsely POSITIVE and the customer's
 * running credit was floored away (lost). Fix: on return, refund = max(0, paidAmount − newGrandTotal), drop
 * paidAmount to what's retained, due = paid − grandTotal (≤ 0).
 *
 * The only OBSERVABLE discriminator (via /getReceipt) is the overpayment case — Test 1 below. We use a 2-line sale
 * and return ONE line so the invoice still has a line (getReceipt returns NOT_FOUND for a line-less invoice), and
 * pay the EXACT grandTotal via /receivePayment (read back first) so the assertion is tax-agnostic.
 * Requires finance-service + business-service + gateway up. Run headed.
 */
describe('SF-5 — sale return reconciles paidAmount (no vanished credit, no over-refund)', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const receiptOf = (invoiceNo) =>
    cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo)).then((rc) => rc.body.object || rc.body.data)

  const customerIdByName = (name) =>
    cy.request('/getUserCustomer?q=-1').then((r) => {
      const mine = (r.body.collection || r.body.data || []).find((c) => c.name === name)
      expect(mine, 'seeded customer ' + name).to.exist
      return mine.customerId || mine.id
    })

  const sellIdOfProduct = (productId) =>
    cy.request('/getUserSell').then((gs) => {
      const line = (gs.body.collection || gs.body.data || []).find((s) => s.productId === productId)
      expect(line, 'sell line for product ' + productId).to.exist
      return line.sellId
    })

  it('PAID sale, return one line → overpayment refunded, paidAmount reconciled, due 0, credit not lost', () => {
    const custName = 'RET_' + Date.now()
    // two distinct products so getUserSell can pick the exact line to return
    cy.seedProduct({ name: 'RA_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId: prodA }) => {
      cy.seedProduct({ name: 'RB_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId: prodB }) => {
        // credit sale of BOTH lines (pay nothing yet), unique customer
        cy.request({
          method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
          body: {
            customer: { name: custName, contact: '0300RET', paidAmount: 0, dueAmount: 0 },
            sales: [
              { productId: prodA, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 },
              { productId: prodB, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 },
            ],
            paidAmount: 0, dueAmount: 0, grandTotal: 200,
          }, failOnStatusCode: false,
        }).then((r) => {
          expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
          const invoiceNo = r.body.object

          // read the ACTUAL grand total (tax-inclusive) then pay it in FULL via the AR path → paidAmount == grand
          receiptOf(invoiceNo).then((inv0) => {
            const grand0 = Number(inv0.grandTotal)
            expect(grand0, 'sale has a grand total').to.be.greaterThan(0)
            customerIdByName(custName).then((customerId) => {
              cy.request({
                method: 'POST', url: '/receivePayment', form: true,
                body: { customerId, amount: grand0, method: 'CASH' }, failOnStatusCode: false,
              }).then((rp) => expect(rp.body.status, JSON.stringify(rp.body)).to.eq('SUCCESS'))

              receiptOf(invoiceNo).then((invPaid) => {
                expect(Number(invPaid.paidAmount), 'invoice now fully paid').to.eq(grand0)
                expect(Number(invPaid.dueAmount), 'nothing owed after full payment').to.eq(0)
              })

              // return line A fully → invoice keeps line B; the return OVERPAYS the shrunken invoice
              sellIdOfProduct(prodA).then((sellId) => {
                cy.request({
                  method: 'POST', url: '/saleReturn', form: true,
                  body: { sellId, quantity: 1 }, failOnStatusCode: false,
                }).then((rr) => expect(rr.body.status, JSON.stringify(rr.body)).to.eq('SUCCESS'))

                receiptOf(invoiceNo).then((invAfter) => {
                  const grandAfter = Number(invAfter.grandTotal)
                  expect(grandAfter, 'grand total dropped to the surviving line').to.be.lessThan(grand0)
                  // SF-5 fix: paidAmount reconciled DOWN to the retained total (buggy left it at grand0)
                  expect(Number(invAfter.paidAmount), 'paidAmount reconciled to the new grand total').to.eq(grandAfter)
                  // due settles to 0, never falsely positive (buggy: +overpayment)
                  expect(Number(invAfter.dueAmount), 'due settled, not falsely positive').to.eq(0)
                  // the customer's running credit is not floored-away — they are square
                  if (invAfter.customer) {
                    expect(Number(invAfter.customer.dueAmount || 0), 'customer square after refund').to.eq(0)
                  }
                })
              })
            })
          })
        })
      })
    })
  })

  it('UNPAID credit sale, return one line → no phantom payment on the header, remaining due correct', () => {
    const custName = 'RETC_' + Date.now()
    cy.seedProduct({ name: 'RCA_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId: prodA }) => {
      cy.seedProduct({ name: 'RCB_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId: prodB }) => {
        cy.request({
          method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
          body: {
            customer: { name: custName, contact: '0300RTC', paidAmount: 0, dueAmount: 0 },
            sales: [
              { productId: prodA, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 },
              { productId: prodB, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 },
            ],
            paidAmount: 0, dueAmount: 0, grandTotal: 200,
          }, failOnStatusCode: false,
        }).then((r) => {
          expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
          const invoiceNo = r.body.object

          sellIdOfProduct(prodA).then((sellId) => {
            cy.request({
              method: 'POST', url: '/saleReturn', form: true,
              body: { sellId, quantity: 1 }, failOnStatusCode: false,
            }).then((rr) => expect(rr.body.status, JSON.stringify(rr.body)).to.eq('SUCCESS'))

            receiptOf(invoiceNo).then((invAfter) => {
              const grandAfter = Number(invAfter.grandTotal)
              // an unpaid return refunds NOTHING onto the header (no cash was ever taken)
              expect(Number(invAfter.paidAmount || 0), 'no phantom paid on an unpaid return').to.eq(0)
              // customer still owes exactly the surviving line (return reduced the debt, didn't refund cash)
              expect(Number(invAfter.dueAmount), 'due = −(remaining bill)').to.eq(-grandAfter)
              if (invAfter.customer) {
                expect(Number(invAfter.customer.dueAmount || 0), 'customer owes the surviving line').to.eq(grandAfter)
              }
            })
          })
        })
      })
    })
  })
})
