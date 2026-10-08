/**
 * MKT-2b — a part its seller does not fulfil: the cause is recorded against a party (never a debit), and with the
 * operator's switch on the part is moved to another seller of the SAME product — silently when it costs no more and
 * arrives no later, otherwise the shopper chooses. Source §11, §12.4. Run headed: --env '{"mkt":"2b"}'
 * Contract: microservices/docs/slices/mkt-2b-shortage-reroute.md
 *
 * The switch (Platform → Marketplace policies → "When a seller cannot fulfil a part, find another seller") is OFF by
 * default. Every case that needs it on switches it on itself, and the gate switches it back off at the end.
 *
 * Three canonical products, each sold by Seller A and Seller B, so each case has exactly one candidate:
 *   later   A 52,000 in 4 h   B 51,500 in 24 h   cheaper but later    → the shopper is asked
 *   quiet   A 52,000 in 24 h  B 51,500 in 4 h    cheaper and sooner   → moved without asking
 *   dearer  A 51,000 in 4 h   B 51,500 in 4 h    dearer               → asked (cash), never offered (card)
 */
const { gate, uniq, SELLER_A, SELLER_B, API, UI, ok, data, list, post, get, expectRefused, seedPolicies, publishOffer } =
  require('./mkt-helpers')

const ACC = {
  register: '/marketplace/account/register',
  login: '/marketplace/account/login',
  orders: '/marketplace/account/orders',
  cancel: (no) => `/marketplace/account/orders/${encodeURIComponent(no)}/cancel`,
}
const decision = (no, id) => `/marketplace/public/orders/${encodeURIComponent(no)}/shortages/${id}/decision`
const PW = 'Shop!ng2026'

gate('2b')('MKT-2b — shortage, reroute and the shopper\'s approval', () => {
  const run = uniq()
  const p = {}
  let aName, bName

  const newPhone = () => `0313${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`
  const reroute = (on) => {
    cy.loginAsOperator()
    return post(API.acceptWindow, { reroute: on }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      expect(data(r.body).reroute).to.eq(on)
    })
  }
  /** An anonymous one-seller order from Seller A (COD unless told otherwise). Yields {orderNo, phone}. */
  const order = (pair, over = {}, keepCookies = false) => {
    const phone = over.customerPhone || newPhone()
    if (!keepCookies) { cy.clearCookies(); cy.visit(UI.publicPage) }
    return post(API.checkout, Object.assign({ lines: [{ offerId: pair.a.offerId, quantity: 1, expectedPrice: pair.a.price }],
      customerName: 'Ali', customerPhone: phone, address: '1 Clifton', city: 'Karachi',
      idempotencyKey: `m2b-${run}-${Math.random()}` }, over)).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      return { orderNo: data(r.body).orderNo, phone }
    })
  }
  const partOf = (email, orderNo, status = 'OFFERED') => {
    cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')
    return get(`${API.incomingOrders}?status=${status}&size=100`).then((r) => list(r.body).find((x) => x.orderNo === orderNo))
  }
  /** Seller A rejects its part of `no`, naming `cause`. */
  const rejectA = (no, cause) => partOf(SELLER_A, no).then((so) =>
    post(API.rejectOrder, { id: so.id, version: so.version, reason: 'none left on the shelf', cause }))
    .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  const track = (o) => get(API.trackOrder(o.orderNo, o.phone)).then((r) => data(r.body))
  const shortPart = (view) => view.sellerOrders.find((s) => s.sellerName === aName)
  /** The shopper's own order page, opened the way the checkout leaves it: the phone remembered for this tab. */
  const openOrderPage = (o) => cy.visit(`${UI.publicPage}?order=${o.orderNo}`,
    { onBeforeLoad: (w) => w.sessionStorage.setItem(`mkt.ph.${o.orderNo}`, o.phone) })

  before(() => {
    seedPolicies(run).then((pol) => {
      const pair = (key, aPrice, aHours, bPrice, bHours) => {
        p[key] = {}
        publishOffer(SELLER_A, { run: `${run}${key}`, price: aPrice, promiseHours: aHours, qty: 40,
          warrantyPolicyId: pol.warranty, returnPolicyId: pol.returns }).then((o) => { p[key].a = Object.assign(o, { price: aPrice }) })
        cy.then(() => publishOffer(SELLER_B, { run: `${run}${key}b`, price: bPrice, promiseHours: bHours, qty: 40,
          mktProductId: p[key].a.mktProductId, warrantyPolicyId: pol.warranty, returnPolicyId: pol.returns }))
          .then((o) => { p[key].b = Object.assign(o, { price: bPrice }) })
      }
      pair('later', 52000, 4, 51500, 24)
      pair('quiet', 52000, 24, 51500, 4)
      pair('dearer', 51000, 4, 51500, 4)
    })
    cy.loginAsOperator()
    post(API.acceptWindow, { minutes: 5, multiSeller: false })
    cy.then(() => get(`${API.publicOffers(p.later.a.mktProductId)}?city=Karachi`)).then((r) => {
      aName = list(r.body).find((x) => x.offerId === p.later.a.offerId).sellerName
      bName = list(r.body).find((x) => x.offerId === p.later.b.offerId).sellerName
    })
  })

  after(() => reroute(false))

  it('MKT-2b-01 [MKT-R11.4] [MKT-R12.4] switch off: the rejection is recorded with its cause and party, and the order ends as before', () => {
    reroute(false)
    let o
    order(p.quiet).then((x) => { o = x })
    cy.then(() => rejectA(o.orderNo, 'SUPPLIER_STALE_STOCK'))
    cy.then(() => track(o)).then((v) => {
      expect(v.status).to.eq('CANCELLED')
      expect(v.sellerOrders, 'no second seller was tried').to.have.length(1)
      expect(shortPart(v).shortage, 'the cause is between the seller and MaxTheService').to.eq(null)
    })
    cy.then(() => partOf(SELLER_A, o.orderNo, 'REJECTED')).then((so) => {
      expect(so.shortage.cause).to.eq('SUPPLIER_STALE_STOCK')
      expect(so.shortage.responsibleRole).to.eq('SUPPLIER')
      expect(so.shortage.status).to.eq('RECORDED')
      expect(so.shortage.canDispute).to.eq(true)
    })
  })

  it('MKT-2b-02 [MKT-R11.1] [MKT-R11.3] same product, cheaper and sooner: moved to the other seller without asking; the order page says so (real UI)', () => {
    reroute(true)
    let o
    order(p.quiet).then((x) => { o = x })
    cy.then(() => rejectA(o.orderNo, 'MERCHANT_STALE_STOCK'))
    cy.then(() => track(o)).then((v) => {
      expect(v.status, 'the order goes on').to.eq('SUBMITTED')
      expect(Number(v.total)).to.eq(51500)
      const old = shortPart(v)
      expect(old.status).to.eq('REJECTED')
      expect(old.shortage.result).to.eq('REASSIGNED')
      expect(old.shortage.movedTo).to.eq(bName)
      expect(Number(old.shortage.priceDifference)).to.eq(-500)
      const now = v.sellerOrders.find((s) => s.sellerName === bName)
      expect(now.status).to.eq('OFFERED')
      expect(now.lines[0].offerId).to.eq(p.quiet.b.offerId)
    })
    cy.then(() => partOf(SELLER_B, o.orderNo)).then((so) => expect(Number(so.total), 'B sees its own part to accept').to.eq(51500))
    cy.clearCookies()
    cy.then(() => openOrderPage(o))
    cy.get(`#mktOrderParts .mkt-part[data-seller="${aName}"]`).should('contain', `Moved to ${bName} at the same or a lower price`)
    cy.get(`#mktOrderParts .mkt-part[data-seller="${bName}"]`).should('contain', 'Rs. 51,500').and('contain', 'left')
  })

  it('MKT-2b-03 [MKT-R11.2] a later promise needs the shopper: they see the alternative and accept it on the order page (real UI)', () => {
    reroute(true)
    let o
    order(p.later).then((x) => { o = x })
    cy.then(() => rejectA(o.orderNo))
    cy.then(() => track(o)).then((v) => {
      expect(v.status, 'the order waits for the shopper').to.eq('SUBMITTED')
      expect(v.sellerOrders, 'no new part until they say yes').to.have.length(1)
      const sh = shortPart(v).shortage
      expect(sh.result).to.eq('SUBSTITUTION_REQUESTED')
      expect(sh.proposalSeller).to.eq(bName)
      expect(sh.proposalPromiseHours).to.eq(24)
      expect(sh.secondsToDecide).to.be.within(1700, 1800)
    })
    cy.clearCookies()
    cy.then(() => openOrderPage(o))
    cy.get(`#mktOrderParts .mkt-part[data-seller="${aName}"] .mkt-sh-offer`)
      .should('contain', `${bName} can deliver them for Rs. 51,500`).and('contain', 'Rs. 500 less').and('contain', 'within 24 hours')
    cy.get('.mkt-sh-left').should('contain', 'Answer within')
    cy.get('.mkt-sh-accept').should('contain', `Accept ${bName}`).click()
    cy.get(`#mktOrderParts .mkt-part[data-seller="${aName}"]`).should('contain', `You chose ${bName} for these items`)
    cy.get(`#mktOrderParts .mkt-part[data-seller="${bName}"]`).should('contain', 'Rs. 51,500')
    cy.then(() => track(o)).then((v) => {
      expect(shortPart(v).shortage.decision).to.eq('ACCEPTED')
      expect(v.sellerOrders.find((s) => s.sellerName === bName).status).to.eq('OFFERED')
      expect(Number(v.total)).to.eq(51500)
    })
  })

  it('MKT-2b-04 [MKT-R11.2] declining ends the part: the order is cancelled with the reason; another phone cannot answer', () => {
    reroute(true)
    let o, id
    order(p.later).then((x) => { o = x })
    cy.then(() => rejectA(o.orderNo))
    cy.then(() => track(o)).then((v) => { id = shortPart(v).shortage.id })
    cy.then(() => post(decision(o.orderNo, id), { phone: '03000000000', accept: true })).then((r) => expectRefused(r, 'No such order.'))
    cy.then(() => post(decision(o.orderNo, id), { phone: o.phone, accept: false })).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      expect(data(r.body).status).to.eq('CANCELLED')
      expect(data(r.body).cancelReason).to.eq('You declined the alternative offered for this order.')
    })
    cy.then(() => post(decision(o.orderNo, id), { phone: o.phone, accept: true }))
      .then((r) => expectRefused(r, 'This alternative is no longer open.'))
  })

  it('MKT-2b-05 [MKT-R11.2] a dearer alternative: a cash order is asked with the difference shown; a card order is never asked to pay more', () => {
    reroute(true)
    let cash, card
    order(p.dearer).then((x) => { cash = x })
    cy.then(() => rejectA(cash.orderNo))
    cy.then(() => track(cash)).then((v) => {
      expect(shortPart(v).shortage.result).to.eq('SUBSTITUTION_REQUESTED')
      expect(Number(shortPart(v).shortage.priceDifference)).to.eq(500)
    })
    const phone = newPhone()
    cy.clearCookies()
    cy.visit(UI.publicPage)
    post(ACC.register, { phone, name: 'Sana Shortage', password: PW })
    order(p.dearer, { customerPhone: phone, customerName: 'Sana Shortage', paymentMode: 'CARD', cardToken: 'tok_ok' }, true)
      .then((x) => { card = x })
    cy.then(() => rejectA(card.orderNo))
    cy.clearCookies()
    cy.visit(UI.publicPage)
    post(ACC.login, { phone, password: PW })
    get(ACC.orders).then((r) => {
      const o = list(r.body).find((x) => x.orderNo === card.orderNo)
      expect(o.status, 'no alternative was offered').to.eq('CANCELLED')
      expect(o.sellerOrders).to.have.length(1)
      expect(o.sellerOrders[0].shortage.result).to.eq('ORDER_CANCELLED')
      expect(o.paymentStatus, 'the card is refunded').to.eq('REFUNDED')
    })
  })

  it('MKT-2b-06 [MKT-R10.5] the shopper cancels while an alternative waits for them: the alternative ends with the order', () => {
    reroute(true)
    const phone = newPhone()
    let o
    cy.clearCookies()
    cy.visit(UI.publicPage)
    post(ACC.register, { phone, name: 'Bilal Shortage', password: PW })
    order(p.later, { customerPhone: phone, customerName: 'Bilal Shortage' }, true).then((x) => { o = x })
    cy.then(() => rejectA(o.orderNo))
    cy.clearCookies()
    cy.visit(UI.publicPage)
    post(ACC.login, { phone, password: PW })
    get(ACC.orders).then((r) => {
      const mine = list(r.body).find((x) => x.orderNo === o.orderNo)
      expect(mine.canCancel, 'still the shopper\'s to cancel').to.eq(true)
    })
    cy.then(() => post(ACC.cancel(o.orderNo), { reason: 'found it elsewhere' })).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      expect(data(r.body).status).to.eq('CANCELLED')
      expect(data(r.body).sellerOrders[0].shortage.decision).to.eq('CANCELLED')
    })
  })

  it('MKT-2b-07 [MKT-R11.4] [MKT-R12.4] the seller disputes the cause on Incoming orders and the operator overturns it; no money moves (real UI)', () => {
    reroute(false)
    const note = `Listing showed stock ${run}`
    let o, payments
    order(p.quiet).then((x) => { o = x })
    cy.then(() => rejectA(o.orderNo, 'PLATFORM_SYNC_DEFECT'))
    cy.then(() => track(o)).then((v) => { payments = JSON.stringify([v.paymentStatus, v.total]) })
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    cy.visit('/businessDashboard')
    cy.get('#snavSell .snav-btn').click()
    cy.get(UI.sellerNav).should('be.visible').click()
    cy.intercept('GET', '**/mkt/incomingOrders*status=REJECTED*').as('rejectedList')
    cy.get('#mktIncomingStatus').select('REJECTED', { force: true })
    cy.wait('@rejectedList')                                   // the filtered list has replaced the old one
    cy.then(() => cy.contains('#mktIncomingOrders tbody tr', o.orderNo)).within(() => {
      cy.get('.mkt-so-shortage').should('contain', 'MaxTheService showed the wrong stock').and('contain', 'Recorded')
      cy.get('.mkt-dispute').click()
      cy.get('.mkt-dispute-note').type(note)
      cy.get('.mkt-dispute-send').click()
      cy.get('.mkt-so-shortage').should('contain', 'Disputed: MaxTheService is reviewing it').and('contain', `You said: ${note}`)
    })
    cy.loginAsOperator()
    cy.visit('/platformDashboard')
    cy.get('#platMktShortagesBtn').click()
    cy.then(() => cy.contains('#mktShortageList .mkt-shortage-row', o.orderNo)).within(() => {
      cy.root().should('contain', `Seller: ${note}`)
      cy.get('.mkt-shortage-overturn').click()
      cy.get('[role=status]').should('contain', 'Write the reason for the seller.')
      cy.get('.mkt-shortage-note').type('The sync was late; not the seller\'s fault.')
      cy.get('.mkt-shortage-overturn').click()
    })
    cy.then(() => cy.contains('#mktShortageList .mkt-shortage-row', o.orderNo)).find('.mkt-shortage-status').should('have.attr', 'data-status', 'OVERTURNED')
    cy.then(() => partOf(SELLER_A, o.orderNo, 'REJECTED')).then((so) => {
      expect(so.shortage.status).to.eq('OVERTURNED')
      expect(so.shortage.canDispute).to.eq(false)
    })
    cy.then(() => track(o)).then((v) => expect(JSON.stringify([v.paymentStatus, v.total]), 'no money moved').to.eq(payments))
  })

  it('MKT-2b-08 [MKT-R11.1] the operator\'s switch: off by default, saved from Marketplace policies (real UI)', () => {
    reroute(false)
    cy.visit('/platformDashboard')
    cy.get('#platMktPoliciesBtn').click()
    cy.get('#mktReroute').should('be.enabled').and('not.be.checked').check()
    cy.get('#mktRerouteSave').click()
    cy.get('#mktRerouteMsg').should('contain', 'Order settings saved.')
    get(API.acceptWindow).then((r) => expect(data(r.body).reroute).to.eq(true))
    cy.get('#mktReroute').uncheck()
    cy.get('#mktRerouteSave').click()
    cy.get('#mktRerouteMsg').should('contain', 'Order settings saved.')
    get(API.acceptWindow).then((r) => expect(data(r.body).reroute).to.eq(false))
  })
})
