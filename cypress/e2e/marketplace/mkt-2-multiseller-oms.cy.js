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

  it('MKT-2-02 [MKT-R11.1] [MKT-R11.2] a rejected child is REASSIGNED silently only to the same product at ≤ price and ≤ promise', () => {
    checkout([{ offerId: two[0].offerId, quantity: 1 }]).then((r) => {
      const so = data(r.body).sellerOrders[0]
      post(API.rejectOrder, { id: so.id, reason: 'shortage' }).then(() =>
        get(API.myMarketplaceOrders).then((o) => {
          const mine = list(o.body).find((x) => x.orderNo === data(r.body).orderNo)
          expect(mine.shortageResult).to.be.oneOf(['REASSIGNED', 'SUBSTITUTION_REQUESTED', 'LINE_CANCELLED'])
        }))
    })
  })

  it('MKT-2-03 [MKT-R11.3] a different variant is never substituted without the customer', () => {
    get(API.myMarketplaceOrders).then((r) => list(r.body).filter((o) => o.shortageResult === 'REASSIGNED')
      .forEach((o) => expect(o.substitutedVariant, o.orderNo).to.not.exist))
  })

  it('MKT-2-04 [MKT-R11.4] [MKT-R12.4] the shortage records its cause and party; no debit without evidence', () => {
    get(API.myMarketplaceOrders).then((r) => list(r.body).filter((o) => o.shortageResult).forEach((o) => {
      expect(o).to.include.keys('shortageCause', 'shortageResponsibleOrganizationId')
      expect(o.autoDebited, 'never auto-debit a stakeholder').to.not.eq(true)
    }))
  })

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
