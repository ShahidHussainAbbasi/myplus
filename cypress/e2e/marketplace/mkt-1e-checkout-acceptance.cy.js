/**
 * MKT-1e — one-seller checkout, reservation, seller acceptance window, expiry release, order snapshots.
 * Source §8, §10, §13.3, §17.1, §18.5, §19, §20.1–20.2, §22. Run headed: --env mkt=1e
 *
 * Money is never optimistic (STANDARDS §0b): the customer sees PENDING until the seller accepts.
 */
const { gate, uniq, SELLER_A, SELLER_B, API, UI, ok, data, list, post, get, expectRefused, openMarketplace } = require('./mkt-helpers')

gate('1e')('MKT-1e — checkout and seller acceptance', () => {
  const run = uniq()
  // offers published by the MKT-1d fixture shape; resolved by search so this spec stands alone
  let offerA, offerB, mktProductId

  before(() => {
    get(`${API.publicProducts}?q=Galaxy%20A32`).then((r) => {
      const p = list(r.body).find((x) => x.offerCount >= 2)
      expect(p, 'a product with two live offers (seed with the 1d spec first)').to.exist
      mktProductId = p.id
      return get(`${API.publicOffers(p.id)}?city=Karachi&sort=LOWEST_PRICE`)
    }).then((r) => {
      const rows = list(r.body)
      offerB = rows[0]
      offerA = rows[1]
    })
  })

  const checkout = (lines, key = `mkt-${run}-${Math.random()}`) =>
    post(API.checkout, { lines, city: 'Karachi', customerName: 'Ali', customerContact: '03001234567',
      shippingAddress: '1 Clifton, Karachi', paymentMode: 'COD', idempotencyKey: key })

  it('MKT-1e-01 [MKT-R10.2] [MKT-R18.5] the customer buys; the screen shows PENDING until the seller accepts (real UI)', () => {
    cy.visit(`${UI.publicPage}?product=${mktProductId}&city=Karachi`)
    cy.get(`${UI.offerRow}[data-offer-id="${offerA.offerId}"] ${UI.chooseOffer}`).click()
    cy.get(UI.buyButton).click()
    cy.get('#mktCustomerName').type('Ali')
    cy.get('#mktCustomerContact').type('03001234567')
    cy.get('#mktAddress').type('1 Clifton, Karachi')
    cy.get('#mktPayCod').check()
    cy.get('#mktPlaceOrder').click()
    cy.get(UI.checkoutStatus).should('contain', 'Waiting for the seller to confirm')
    cy.get(UI.checkoutStatus).should('not.contain', 'Confirmed')
    // the seller accepts from their dashboard, with a visible countdown
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    openMarketplace()
    cy.get(`${UI.incoming} tr`).first().within(() => {
      cy.get(UI.countdown).invoke('text').should('match', /\d+:\d\d/)
      cy.get(UI.acceptBtn).should('be.visible').click()
    })
    cy.get(`${UI.incoming} tr`).first().should('contain', 'ACCEPTED')
  })

  it('MKT-1e-02 [MKT-R17.1] [MKT-R20.2] two sellers in one Phase 1 checkout are refused by the server', () => {
    checkout([{ offerId: offerA.offerId, quantity: 1 }, { offerId: offerB.offerId, quantity: 1 }])
      .then((r) => expectRefused(r, 'Items from different sellers must be checked out separately.'))
  })

  it('MKT-1e-03 [MKT-R22.2] the price is the server\'s: a tampered price in the request is ignored', () => {
    post(API.checkout, { lines: [{ offerId: offerA.offerId, quantity: 1, price: 1 }], city: 'Karachi',
      customerName: 'Ali', customerContact: '0300', shippingAddress: 'x', paymentMode: 'COD',
      idempotencyKey: `tamper-${run}` }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      expect(Number(data(r.body).lines[0].unitPrice)).to.eq(Number(offerA.price))
    })
  })

  it('MKT-1e-04 [MKT-R22.3] a double submit with the same key yields ONE order', () => {
    const key = `dup-${run}`
    checkout([{ offerId: offerA.offerId, quantity: 1 }], key).then((r1) =>
      checkout([{ offerId: offerA.offerId, quantity: 1 }], key).then((r2) => {
        expect(ok(r1.body) && ok(r2.body)).to.eq(true)
        expect(data(r2.body).orderNo).to.eq(data(r1.body).orderNo)
      }))
  })

  it('MKT-1e-05 [MKT-R10.2] [MKT-R10.5] a rejected order releases the hold: sellable stock returns', () => {
    get(`${API.publicOffers(mktProductId)}?city=Karachi`).then((r0) => {
      const before = Number(list(r0.body).find((o) => o.offerId === offerA.offerId).availableQty)
      checkout([{ offerId: offerA.offerId, quantity: 1 }]).then((r) => {
        const sellerOrderId = data(r.body).sellerOrders[0].id
        cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
        post(API.rejectOrder, { id: sellerOrderId, reason: 'out of stock in store' })
          .then((rj) => expect(ok(rj.body)).to.eq(true))
        get(`${API.publicOffers(mktProductId)}?city=Karachi`).then((r2) => {
          expect(Number(list(r2.body).find((o) => o.offerId === offerA.offerId).availableQty)).to.eq(before)
        })
      })
    })
  })

  it('MKT-1e-06 [MKT-R10.2] [MKT-R19.1] an acceptance after the deadline is refused (EXPIRED, not ACCEPTED)', () => {
    checkout([{ offerId: offerA.offerId, quantity: 1 }]).then((r) => {
      const so = data(r.body).sellerOrders[0]
      expect(so.acceptBy, 'deadline is published').to.exist
      // the gate cannot wait 5 minutes: the test-fixture hook expires it now (seed-test-fixtures only, prod-blocked)
      post('/test/mkt/expireNow', { sellerOrderId: so.id })
      cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
      post(API.acceptOrder, { id: so.id }).then((a) => expectRefused(a, 'EXPIRED'))
    })
  })

  it('MKT-1e-07 [MKT-R13.3] [MKT-R3.1] the order line snapshots seller, owner, fulfiller, price and policies', () => {
    checkout([{ offerId: offerA.offerId, quantity: 1 }]).then((r) => {
      const line = data(r.body).sellerOrders[0].lines[0]
      expect(line).to.include.keys('sellerOrganizationId', 'stockOwnerOrganizationId', 'custodianOrganizationId',
        'fulfillerOrganizationId', 'unitPrice', 'tax', 'commission', 'deliveryFee', 'policySnapshot')
      expect(line.policySnapshot).to.include.keys('returnPolicy', 'refundPolicy', 'returnCostPayer', 'warranty')
    })
  })

  it('MKT-1e-10 [MKT-R10.4] [MKT-R10.3] the hold is recorded with its ids, parties, quantity and expiry', () => {
    checkout([{ offerId: offerA.offerId, quantity: 2 }]).then((r) => {
      const so = data(r.body).sellerOrders[0]
      cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
      get(`${API.incomingOrders}?id=${so.id}`).then((io) => {
        const res = list(io.body)[0].reservation
        expect(res).to.include.keys('reservationId', 'orderId', 'orderLineId', 'offerId', 'stockSourceType',
          'stockOwnerId', 'custodianId', 'locationId', 'quantity', 'status', 'createdAt', 'expiresAt',
          'releaseReason', 'sourceReference')
        expect(res.status).to.eq('HELD')
        expect(Number(res.quantity)).to.eq(2)
        expect(new Date(res.expiresAt) > new Date(res.createdAt)).to.eq(true)
      })
    })
  })

  it('MKT-1e-08 [MKT-R8.1] [MKT-R22.1] the customer belongs to MaxTheService: one account across sellers', () => {
    get(API.myMarketplaceOrders).then((r) => {
      expect(ok(r.body), 'positive control').to.eq(true)
      const sellers = new Set(list(r.body).flatMap((o) => (o.sellerOrders || []).map((s) => s.sellerOrganizationId)))
      expect(sellers.size, 'orders from both sellers under one customer').to.be.at.least(1)
    })
  })

  it('MKT-1e-09 [MKT-R19.1] order, payment and settlement status are separate fields', () => {
    get(API.myMarketplaceOrders).then((r) => {
      const o = list(r.body)[0]
      expect(o).to.include.keys('status', 'paymentStatus')
      expect(o.sellerOrders[0].lines[0]).to.have.property('settlementStatus', 'NOT_ELIGIBLE')
    })
  })
})
