/**
 * Store credit (SF-5 Model B). A return refunded "as store credit" raises the customer's credit balance (and credits
 * GL 2200, a liability). A later sale can redeem it via a STORE_CREDIT tender (balance falls, GL 2200 debited). The
 * server caps redemption at the balance (never negative). Requires finance (GL) + business + gateway up. Run headed.
 */
describe('Store credit (SF-5 Model B)', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const tb = () => cy.request('/gl/trialBalance').then((r) => parse(r.body))
  const net2200 = (t) => { const r = (t.rows || []).find((x) => x.code === '2200'); return r ? Number(r.debit || 0) - Number(r.credit || 0) : 0 }
  const credit = (cid) => cy.request(`/customerCredit?customerId=${cid}`).then((r) => Number(parse(r.body).object || 0))

  it('return issues credit; a sale redeems it; over-redeem is capped at the balance', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    const stamp = Date.now()
    const cname = 'SCC_' + stamp

    // 1) A fully-paid sale that also creates the customer.
    cy.seedProduct({ name: 'SCP_' + stamp, sellingPrice: 100, stock: 10 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: cname, contact: '0300' + stamp },
          sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
          tenders: [{ method: 'CASH', amount: 100 }], grandTotal: 100,
        }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        const inv1 = r.body.object

        cy.request('/getUserCustomer').then((cr) => {
          const c = (cr.body.collection || cr.body.data || []).find((c) => c.name === cname)
          const cid = c && (c.customerId != null ? c.customerId : c.id)   // customer DTO id field is customerId
          expect(cid, 'customer id').to.be.a('number')

          cy.request('/getUserSell').then((gs) => {
            const line = (gs.body.collection || gs.body.data || [])
              .find((s) => s.productId === productId && s.customerHistory && s.customerHistory.invoiceNo === inv1)
            expect(line, 'sell line for inv1').to.exist

            // 2) Return the line, refunded AS STORE CREDIT → +100 credit, GL 2200 credited.
            tb().then((before) => {
              cy.request({ method: 'POST', url: '/saleReturn', form: true, body: { sellId: line.sellId, quantity: 1, reason: 'SC', refundAs: 'CREDIT' }, failOnStatusCode: false })
                .then((rr) => expect(rr.body.status, JSON.stringify(rr.body)).to.eq('SUCCESS'))
              credit(cid).then((b) => expect(b, 'credit issued').to.eq(100))
              tb().then((after) => {
                expect(after.balanced, 'GL balanced after issue').to.eq(true)
                expect(net2200(after) - net2200(before), 'store-credit liability grew (net fell)').to.be.lessThan(0)
              })
            })

            // 3) Redeem 60 on a new sale (STORE_CREDIT tender) → balance 40, GL 2200 debited.
            cy.seedProduct({ name: 'SCP2_' + stamp, sellingPrice: 60, stock: 10 }).then(({ productId: p2 }) => {
              tb().then((before) => {
                cy.request({
                  method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
                  body: {
                    customer: { customerId: cid, name: cname, contact: '0300' + stamp },
                    sales: [{ productId: p2, quantity: 1, sellRate: 60, totalAmount: 60, netAmount: 60 }],
                    tenders: [{ method: 'STORE_CREDIT', amount: 60 }], grandTotal: 60,
                  }, failOnStatusCode: false,
                }).then((r2) => expect(r2.body.status, JSON.stringify(r2.body)).to.eq('SUCCESS'))
                credit(cid).then((b) => expect(b, 'balance after redeeming 60').to.eq(40))
                tb().then((after) => {
                  expect(after.balanced, 'GL balanced after redeem').to.eq(true)
                  expect(net2200(after) - net2200(before), 'liability reduced (net rose)').to.be.greaterThan(0)
                })
              })

              // 4) Over-redeem: apply 999 credit on a 100 sale → capped to the 40 balance (never negative).
              cy.seedProduct({ name: 'SCP3_' + stamp, sellingPrice: 100, stock: 10 }).then(({ productId: p3 }) => {
                cy.request({
                  method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
                  body: {
                    customer: { customerId: cid, name: cname, contact: '0300' + stamp },
                    sales: [{ productId: p3, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
                    tenders: [{ method: 'STORE_CREDIT', amount: 999 }], grandTotal: 100,
                  }, failOnStatusCode: false,
                }).then((r3) => expect(r3.body.status, JSON.stringify(r3.body)).to.eq('SUCCESS'))
                credit(cid).then((b) => expect(b, 'over-redeem capped to 0, never negative').to.eq(0))
              })
            })
          })
        })
      })
    })
  })
})
