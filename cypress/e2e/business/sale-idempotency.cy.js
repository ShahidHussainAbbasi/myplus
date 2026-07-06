/**
 * SF-3 — idempotent sale submission. Re-submitting addSell with the SAME idempotency key (a double-click /
 * network retry) must record ONE invoice: the replay returns the same invoiceNo and the customer's due is not
 * doubled. A DIFFERENT key records a new sale. Tax-agnostic (compares dues relatively). Run headed.
 */
describe('SF-3 — idempotent sale submission', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('same key → one invoice (due not doubled); different key → a new invoice', () => {
    const key = 'cyk-' + Date.now()
    const custName = 'IDEM_' + Date.now()
    cy.seedProduct({ name: 'ID_' + Date.now(), sellingPrice: 50, stock: 20 }).then(({ productId }) => {
      const body = (k) => ({
        customer: { name: custName, contact: '0300IDEM', paidAmount: 0, dueAmount: 0 },
        sales: [{ productId, quantity: 1, sellRate: 50, totalAmount: 50, netAmount: 50 }],
        tenders: [{ method: 'CREDIT', amount: 0 }],   // on account → the whole bill is due
        idempotencyKey: k,
      })
      const post = (k) => cy.request({ method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, body: body(k), failOnStatusCode: false })
      const dueOf = () => cy.request('/getUserCustomer?q=-1').then((r) => {
        const c = (r.body.collection || []).find((x) => x.name === custName)
        return c ? Number(c.dueAmount || 0) : 0
      })

      let invoice1
      let d1
      // 1) first submission
      post(key).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        invoice1 = r.body.object
        expect(invoice1, 'invoice number').to.be.ok
      })
      dueOf().then((d) => { d1 = d; expect(d1, 'one sale is owed').to.be.greaterThan(0) })

      // 2) REPLAY same key → same invoice, due unchanged (not doubled)
      post(key).then((r) => {
        expect(r.body.status).to.eq('SUCCESS')
        expect(r.body.object, 'replay returns the SAME invoice').to.eq(invoice1)
      })
      dueOf().then((d) => expect(d, 'replay did not double-charge').to.be.closeTo(d1, 0.01))

      // 3) DIFFERENT key → a new invoice, due now ~doubled (a genuine second sale)
      post(key + '-2').then((r) => {
        expect(r.body.status).to.eq('SUCCESS')
        expect(r.body.object, 'new key → new invoice').to.not.eq(invoice1)
      })
      dueOf().then((d) => expect(d, 'second real sale roughly doubles the due').to.be.closeTo(d1 * 2, 0.01))
    })
  })
})
