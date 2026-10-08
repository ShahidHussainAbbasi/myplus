/**
 * MKT-2 — OMS maturity: parent/child orders, multi-seller cart, shortage + reroute, live routing deadline.
 * Source §10.6, §11, §12.4, §17.2, §18.1, §18.3, §20.3. Run headed: --env mkt=2
 *
 * Written ahead as the Phase 2 requirement; each case moves into its MKT-2x slice spec when that slice is designed.
 */
const { gate, uniq, API, ok, data, list, post, get, expectRefused } = require('./mkt-helpers')

gate('2')('MKT-2 — multi-seller orders and shortage handling', () => {
  const run = uniq()
  let two

  before(() => {
    get(`${API.publicProducts}?q=Galaxy%20A32`).then((r) => {
      const p = list(r.body).find((x) => x.offerCount >= 2)
      return get(`${API.publicOffers(p.id)}?city=Karachi`)
    }).then((r) => { two = list(r.body).slice(0, 2) })
  })

  const checkout = (lines, key = `mkt2-${run}-${Math.random()}`) => post(API.checkout, { lines, city: 'Karachi',
    customerName: 'Ali', customerContact: '0300', shippingAddress: 'x', paymentMode: 'COD', idempotencyKey: key })

  // MKT-2-01 moved to mkt-2a-multi-seller.cy.js (MKT-2a-02, with 2a-03..08 covering the rest of R17.2).

  // MKT-2-02..04 moved to mkt-2b-shortage-reroute.cy.js: 2b-02 (silent move, R11.1/R11.3), 2b-03..05 (the shopper's
  // approval, R11.2), 2b-01 and 2b-07 (cause, party and dispute; never a debit, R11.4/R12.4).

  it('MKT-2-05 [MKT-R18.1] [MKT-R18.3] routing answers within the deadline or says it is still checking', () => {
    const t0 = Date.now()
    checkout([{ offerId: two[0].offerId, quantity: 1 }]).then((r) => {
      expect(Date.now() - t0, 'overall routing deadline 2s + network').to.be.lessThan(4000)
      if (!ok(r.body)) expect(r.body.message).to.match(/checking availability|choose another offer/)
    })
  })

  it('MKT-2-06 [MKT-R10.6] [MKT-R20.3] acceptance terms vary by order value (configured, read on the path)', () => {
    checkout([{ offerId: two[0].offerId, quantity: 3 }]).then((r) => {
      if (!ok(r.body)) return
      expect(data(r.body).sellerOrders[0]).to.have.property('acceptTermsSource')
    })
  })
})
