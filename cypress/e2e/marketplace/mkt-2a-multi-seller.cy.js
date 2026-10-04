/**
 * MKT-2a — one checkout from several sellers: a parent order with one part (seller order) per seller, each held,
 * accepted or rejected, refunded and supported on its own. Source §17.2, §20.3. Run headed: --env '{"mkt":"2a"}'
 * Contract: microservices/docs/slices/mkt-2a-multi-seller-orders.md
 *
 * The operator's switch (Platform → Marketplace policies → "Customers can buy from several sellers in one order") is
 * OFF by default, which is Phase 1's one-seller rule (R17.1). Every case that needs it on switches it on itself, and
 * the gate switches it back off at the end.
 */
const { gate, uniq, SELLER_A, SELLER_B, API, UI, ok, data, list, post, get, expectRefused, seedPolicies, publishOffer } =
  require('./mkt-helpers')

const ACC = {
  register: '/marketplace/account/register',
  orders: '/marketplace/account/orders',
  cancel: (no) => `/marketplace/account/orders/${encodeURIComponent(no)}/cancel`,
  cases: (no) => `/marketplace/account/orders/${encodeURIComponent(no)}/cases`,
}
const PW = 'Shop!ng2026'

gate('2a')('MKT-2a — multi-seller basket, parent and child orders', () => {
  const run = uniq()
  let a, b, aName, bName

  const newPhone = () => `0312${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`
  const switchTo = (on) => {
    cy.loginAsOperator()
    return post(API.acceptWindow, { multiSeller: on }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      expect(data(r.body).multiSeller).to.eq(on)
    })
  }
  /** An anonymous basket checkout (COD unless told otherwise). Yields the response with `phone` attached. */
  const basket = (lines, over = {}, keepCookies = false) => {
    const phone = over.customerPhone || newPhone()
    if (!keepCookies) { cy.clearCookies(); cy.visit(UI.publicPage) }
    return post(API.checkout, Object.assign({ lines, customerName: 'Ali', customerPhone: phone, address: '1 Clifton',
      city: 'Karachi', idempotencyKey: `m2a-${run}-${Math.random()}` }, over)).then((r) => Object.assign(r, { phone }))
  }
  const lineOf = (o, qty = 1) => ({ offerId: o.offerId, quantity: qty, expectedPrice: o.price })
  const incoming = (email, status) => {
    cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')
    return get(`${API.incomingOrders}?status=${status}&size=100`)
  }
  const partOf = (email, orderNo, status = 'OFFERED') => incoming(email, status)
    .then((r) => list(r.body).find((x) => x.orderNo === orderNo))

  before(() => {
    let p
    seedPolicies(run).then((x) => { p = x })
    cy.loginAsOperator()
    post(API.acceptWindow, { minutes: 5 })
    // two sellers, ONE canonical product: A at 52,000 (4 h), B at 51,500 (24 h)
    cy.then(() => publishOffer(SELLER_A, { run, price: 52000, promiseHours: 4, qty: 40, warrantyPolicyId: p.warranty,
      returnPolicyId: p.returns })).then((o) => { a = Object.assign(o, { price: 52000 }) })
    cy.then(() => publishOffer(SELLER_B, { run: `${run}b`, price: 51500, promiseHours: 24, qty: 40, mktProductId: a.mktProductId,
      warrantyPolicyId: p.warranty, returnPolicyId: p.returns })).then((o) => { b = Object.assign(o, { price: 51500 }) })
    cy.then(() => get(`${API.publicOffers(a.mktProductId)}?city=Karachi`)).then((r) => {
      aName = list(r.body).find((x) => x.offerId === a.offerId).sellerName
      bName = list(r.body).find((x) => x.offerId === b.offerId).sellerName
    })
  })

  after(() => switchTo(false))

  it('MKT-2a-01 [MKT-R17.1] the switch is off by default: a basket from two sellers is refused before anything is held', () => {
    switchTo(false)
    basket([lineOf(a), lineOf(b)]).then((r) => expectRefused(r, 'Items from different sellers must be checked out separately.'))
    basket([lineOf(a, 2)]).then((r) => {
      expect(ok(r.body), 'one seller is still fine').to.eq(true)
      expect(data(r.body).sellerOrders).to.have.length(1)
    })
  })

  it('MKT-2a-02 [MKT-R17.2] [MKT-R20.3] the shopper fills a basket from two sellers and checks out once; one part per seller, each waiting on its own clock (real UI)', () => {
    switchTo(true)
    const phone = newPhone()
    cy.clearCookies()
    cy.visit(`${UI.publicPage}?product=${a.mktProductId}&city=Karachi`, { onBeforeLoad: (w) => w.localStorage.removeItem('mkt.basket') })
    cy.get(`${UI.offerRow}[data-offer-id="${a.offerId}"] ${UI.chooseOffer}`).check()
    cy.get('#mktAddBtn').click()
    cy.get('#mktBasketMsg').should('contain', `from ${aName}`)
    cy.get(`${UI.offerRow}[data-offer-id="${b.offerId}"] ${UI.chooseOffer}`).check()
    cy.get('#mktAddBtn').click()
    cy.get('#mktBasketBtn').should('be.visible').and('contain', 'Basket (2)').click()
    cy.get('.mkt-basket-group').should('have.length', 2)
    cy.contains('.mkt-basket-group', bName).find('.mkt-basket-qty').select('2')
    cy.get('#mktCoTotal').should('contain', 'Rs. 155,000')
    cy.get('#mktCoName').type('Ali')
    cy.get('#mktCoPhone').type(phone)
    cy.get('#mktCoAddress').type('1 Clifton')
    cy.get('#mktCoPlace').click()
    cy.get(UI.checkoutStatus).should('contain', 'Waiting for the sellers to confirm')
    cy.get('#mktOrderParts .mkt-part').should('have.length', 2)
    cy.get(`#mktOrderParts .mkt-part[data-seller="${aName}"]`).should('contain', 'Rs. 52,000').and('contain', 'left')
    cy.get(`#mktOrderParts .mkt-part[data-seller="${bName}"]`).should('contain', 'Rs. 103,000').and('contain', 'left')
    cy.get('#mktBasketBtn').should('not.be.visible')                         // placed: the basket is now an order
    cy.get('#mktCoOrderNo').invoke('text').then((no) => get(API.trackOrder(no, phone))).then((r) => {
      const o = data(r.body)
      expect(o.status).to.eq('SUBMITTED')
      expect(Number(o.total)).to.eq(155000)
      expect(o.sellerOrders).to.have.length(2)
      expect(new Set(o.sellerOrders.map((s) => s.sellerOrganizationId)).size, 'one part per seller').to.eq(2)
      o.sellerOrders.forEach((s) => {
        expect(s).to.include.keys('promisedBy', 'deliveryFee', 'secondsToAccept')
        expect(s.status).to.eq('OFFERED')
      })
    })
  })

  it('MKT-2a-03 [MKT-R17.2] [MKT-R22.1] each seller sees only its own part: its lines, its total', () => {
    switchTo(true)
    basket([lineOf(a), lineOf(b, 2)]).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      const no = data(r.body).orderNo
      partOf(SELLER_A, no).then((so) => {
        expect(Number(so.total), 'A sees 52,000, never the basket').to.eq(52000)
        expect(so.lines.map((l) => l.line.offerId)).to.deep.eq([a.offerId])
      })
      partOf(SELLER_B, no).then((so) => {
        expect(Number(so.total)).to.eq(103000)
        expect(so.lines.map((l) => l.line.offerId)).to.deep.eq([b.offerId])
      })
    })
  })

  it('MKT-2a-04 [MKT-R17.2] one seller accepts and the other rejects: the order is CONFIRMED, only the rejected part ends and its stock goes back', () => {
    switchTo(true)
    let no, phone
    basket([lineOf(a), lineOf(b)]).then((r) => { no = data(r.body).orderNo; phone = r.phone })
    cy.then(() => partOf(SELLER_A, no)).then((so) => post(API.acceptOrder, { id: so.id, version: so.version }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => partOf(SELLER_B, no)).then((so) => post(API.rejectOrder, { id: so.id, version: so.version, reason: 'no stock' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => get(API.trackOrder(no, phone))).then((r) => {
      const o = data(r.body)
      expect(o.status, 'the accepted part goes ahead').to.eq('CONFIRMED')
      expect(o.sellerOrders.find((s) => s.sellerName === aName).status).to.eq('ACCEPTED')
      expect(o.sellerOrders.find((s) => s.sellerName === bName).status).to.eq('REJECTED')
    })
  })

  it('MKT-2a-05 [MKT-R17.2] [MKT-R10.2] all or nothing: when one seller cannot hold, nothing is ordered and every hold is released', () => {
    switchTo(true)
    let smallA, smallB
    seedPolicies(`${run}s`).then((p) => {
      publishOffer(SELLER_A, { run: `${run}sa`, price: 52000, qty: 1, warrantyPolicyId: p.warranty, returnPolicyId: p.returns })
        .then((o) => { smallA = Object.assign(o, { price: 52000 }) })
      cy.then(() => publishOffer(SELLER_B, { run: `${run}sb`, price: 51500, qty: 1, mktProductId: smallA.mktProductId,
        warrantyPolicyId: p.warranty, returnPolicyId: p.returns })).then((o) => { smallB = Object.assign(o, { price: 51500 }) })
    })
    cy.then(() => basket([lineOf(smallB)])).then((r) => expect(ok(r.body), 'B\'s only unit is now held').to.eq(true))
    cy.then(() => basket([lineOf(smallA), lineOf(smallB)])).then((r) => expectRefused(r, `${bName} no longer has enough stock`))
    cy.then(() => basket([lineOf(smallA)])).then((r) => expect(ok(r.body), 'A\'s unit was given back').to.eq(true))
  })

  it('MKT-2a-06 [MKT-R17.2] [MKT-R13.1] paid online: ONE charge for the basket; a rejected part is refunded on its own, once', () => {
    switchTo(true)
    const phone = newPhone()
    let no
    cy.clearCookies()
    cy.visit(UI.publicPage)
    post(ACC.register, { phone, name: 'Sana Basket', password: PW }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    basket([lineOf(a), lineOf(b)], { customerPhone: phone, customerName: 'Sana Basket', paymentMode: 'CARD', cardToken: 'tok_ok' }, true)
      .then((r) => {
        expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
        no = data(r.body).orderNo
      })
    cy.then(() => partOf(SELLER_B, no)).then((so) => post(API.rejectOrder, { id: so.id, version: so.version, reason: 'no stock' }))
    cy.then(() => partOf(SELLER_B, no, 'REJECTED')).then((so) => post(API.rejectOrder, { id: so.id, version: so.version, reason: 'again' }))
    cy.clearCookies()
    cy.visit(UI.publicPage)
    post('/marketplace/account/login', { phone, password: PW })
    get(ACC.orders).then((r) => {
      const o = list(r.body).find((x) => x.orderNo === no)
      const charges = o.payments.filter((p) => p.kind === 'CHARGE')
      const refunds = o.payments.filter((p) => p.kind === 'REFUND')
      expect(charges, 'one charge').to.have.length(1)
      expect(Number(charges[0].amount)).to.eq(103500)
      expect(refunds, 'one refund, for B\'s part').to.have.length(1)
      expect(Number(refunds[0].amount)).to.eq(51500)
      expect(o.paymentStatus).to.eq('PARTIALLY_REFUNDED')
      expect(o.status, 'A\'s part still waits').to.eq('SUBMITTED')
    })
  })

  it('MKT-2a-07 [MKT-R17.2] [MKT-R10.5] the customer cancels a basket no seller has accepted: every part ends together', () => {
    switchTo(true)
    const phone = newPhone()
    let no
    cy.clearCookies()
    cy.visit(UI.publicPage)
    post(ACC.register, { phone, name: 'Bilal Basket', password: PW })
    basket([lineOf(a), lineOf(b)], { customerPhone: phone, customerName: 'Bilal Basket' }, true).then((r) => { no = data(r.body).orderNo })
    cy.then(() => post(ACC.cancel(no), { reason: 'ordered twice' })).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      expect(data(r.body).status).to.eq('CANCELLED')
      expect(data(r.body).sellerOrders.map((s) => s.status)).to.deep.eq(['CANCELLED', 'CANCELLED'])
    })
  })

  it('MKT-2a-08 [MKT-R8.2] [MKT-R17.2] help is per seller: a case on one seller\'s item goes to that seller only', () => {
    switchTo(true)
    const phone = newPhone()
    let no, order
    cy.clearCookies()
    cy.visit(UI.publicPage)
    post(ACC.register, { phone, name: 'Hina Basket', password: PW })
    basket([lineOf(a), lineOf(b)], { customerPhone: phone, customerName: 'Hina Basket' }, true).then((r) => { no = data(r.body).orderNo })
    cy.then(() => partOf(SELLER_A, no)).then((so) => post(API.acceptOrder, { id: so.id, version: so.version }))
    cy.then(() => partOf(SELLER_B, no)).then((so) => post(API.acceptOrder, { id: so.id, version: so.version }))
    cy.clearCookies()
    cy.visit(UI.publicPage)
    post('/marketplace/account/login', { phone, password: PW })
    get(ACC.orders).then((r) => { order = list(r.body).find((x) => x.orderNo === no) })
    cy.then(() => post(ACC.cases(no), { topic: 'ORDER_PROBLEM', note: 'Where is it?' }))
      .then((r) => expectRefused(r, 'Choose the item you need help with.'))
    cy.then(() => {
      const bLine = order.sellerOrders.find((s) => s.sellerName === bName).lines[0]
      return post(ACC.cases(no), { topic: 'ORDER_PROBLEM', note: 'Where is the second phone?', lineId: bLine.id })
    }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      expect(data(r.body).sellerName).to.eq(bName)
    })
  })
})
