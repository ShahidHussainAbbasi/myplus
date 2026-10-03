/**
 * MKT-1e — one-seller COD checkout, stock hold, seller acceptance window, the sale in the seller's books, expiry.
 * Source §8, §10, §13.3, §17.1, §18.5, §19, §20.1–20.2, §22. Run headed: --env mkt=1e
 * Contract: microservices/docs/slices/mkt-1e-checkout-acceptance.md
 *
 * Money is never optimistic (STANDARDS §0b): the shopper sees "Waiting for <seller> to confirm" until the seller
 * accepts. Platform customer account, online payment and customer cancellation are MKT-1e2.
 */
const { gate, uniq, SELLER_A, SELLER_B, API, UI, ok, data, list, msg, post, get, expectRefused, seedPolicies,
  publishOffer } = require('./mkt-helpers')

gate('1e')('MKT-1e — checkout and seller acceptance', () => {
  const run = uniq()
  const PHONE = `0301${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`   // case 01's shopper; fresh per run
  let offer, sellerName, policies

  /** A fresh shopper phone per checkout: the server allows 3 waiting orders per phone (the abuse guard). */
  const newPhone = () => `0300${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`

  /**
   * Anonymous checkout. The page visit sets the XSRF-TOKEN cookie the request layer turns into the header.
   * Yields the response with `phone` attached, so a case can track the order it placed.
   */
  const checkout = (over = {}) => {
    const phone = over.customerPhone || newPhone()
    cy.clearCookies()
    cy.visit(UI.publicPage)
    return post(API.checkout, Object.assign({ offerId: offer.offerId, quantity: 1, expectedPrice: 52000,
      customerName: 'Ali', customerPhone: phone, address: '1 Clifton', city: 'Karachi',
      idempotencyKey: `mkt-${run}-${Math.random()}` }, over)).then((r) => Object.assign(r, { phone }))
  }
  const incoming = (status) => get(`${API.incomingOrders}?status=${status}&size=100`)

  before(() => {
    seedPolicies(run).then((p) => { policies = p })
    cy.loginAsOperator()
    post(API.acceptWindow, { minutes: 5 })
    // enough stock for every case's one-unit checkout; case 04 exhausts its OWN two-unit offer
    cy.then(() => publishOffer(SELLER_A, { run, price: 52000, promiseHours: 4, qty: 50,
      warrantyPolicyId: policies.warranty, returnPolicyId: policies.returns })).then((o) => { offer = o })
    cy.then(() => get(`${API.publicOffers(offer.mktProductId)}?city=Karachi`))
      .then((r) => { sellerName = list(r.body).find((x) => x.offerId === offer.offerId).sellerName })
  })

  it('MKT-1e-01 [MKT-R10.2] [MKT-R18.5] [MKT-R10.1] the shopper orders; PENDING until the seller accepts in the UI; the sale is in the seller\'s books (real UI)', () => {
    cy.clearCookies()
    cy.visit(`${UI.publicPage}?product=${offer.mktProductId}&city=Karachi`)
    cy.get(`${UI.offerRow}[data-offer-id="${offer.offerId}"] ${UI.chooseOffer}`).check()
    cy.get(UI.buyButton).click()
    cy.get('#mktCoName').type('Ali')
    cy.get('#mktCoPhone').type(PHONE)
    cy.get('#mktCoAddress').type('1 Clifton')
    cy.get('#mktCoTotal').should('contain', 'Rs. 52,000')
    cy.get('#mktCoPlace').click()
    cy.get(UI.checkoutStatus).should('contain', `Waiting for ${sellerName} to confirm`)
    cy.get('#mktCoOrderNo').invoke('text').should('match', /^MKT-\d+/).then((orderNo) => {
      cy.url().should('include', `order=${encodeURIComponent(orderNo)}`)
      // the seller accepts from the dashboard, with the countdown running
      cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
      cy.visit('/businessDashboard')
      cy.get('#snavSell .snav-btn').click()
      cy.get(UI.sellerNav).click()
      cy.contains(`${UI.incoming} tr`, orderNo).within(() => {
        cy.get(UI.countdown).invoke('text').should('match', /^[0-4]:[0-5]\d$/)
        cy.get(UI.acceptBtn).click()
      })
      cy.contains(`${UI.incoming} tr`, orderNo).should('contain', 'Accepted').and('contain', 'The sale is in your books')
      incoming('ACCEPTED').then((r) => {
        const so = list(r.body).find((x) => x.orderNo === orderNo)
        expect(so.invoiceNo, 'the sale is in the seller\'s own books').to.match(/\S/)
        expect(so.storeOrderNo, 'and in the seller\'s order list').to.match(/\S/)
      })
      // the shopper's page turns CONFIRMED by itself
      cy.clearCookies()
      cy.visit(`${UI.publicPage}?order=${encodeURIComponent(orderNo)}&phone=${PHONE}`)
      cy.get(UI.checkoutStatus, { timeout: 15000 }).should('contain', `Confirmed by ${sellerName}`)
    })
  })

  it('MKT-1e-02 [MKT-R22.2] the price is the server\'s: a changed price is refused before anything is created', () => {
    checkout({ expectedPrice: 1 }).then((r) => expectRefused(r, 'The price changed to Rs. 52,000'))
    checkout({ expectedPrice: 52000, unitPrice: 1, total: 1 }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      expect(Number(data(r.body).total), 'client money is ignored').to.eq(52000)
    })
  })

  it('MKT-1e-03 [MKT-R22.3] a double submit with the same key is ONE order', () => {
    const key = `dup-${run}`
    let orderNo
    checkout({ idempotencyKey: key }).then((a) => checkout({ idempotencyKey: key }).then((b) => {
      expect(ok(a.body) && ok(b.body)).to.eq(true)
      orderNo = data(a.body).orderNo
      expect(data(b.body).orderNo).to.eq(orderNo)
      expect(data(b.body).sellerOrderId).to.eq(data(a.body).sellerOrderId)
    }))
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    incoming('OFFERED').then((r) => expect(list(r.body).filter((x) => x.orderNo === orderNo), 'one seller order').to.have.length(1))
  })

  it('MKT-1e-04 [MKT-R10.2] [MKT-R10.5] [MKT-R10.3] the hold is real: all stock held refuses the next shopper; a reject gives it back', () => {
    let held, heldPhone, small
    publishOffer(SELLER_A, { run: `${run}h`, price: 52000, qty: 2, warrantyPolicyId: policies.warranty,
      returnPolicyId: policies.returns }).then((o) => { small = o })
    cy.then(() => checkout({ offerId: small.offerId, quantity: 2 }))
      .then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); held = data(r.body); heldPhone = r.phone })
    cy.then(() => checkout({ offerId: small.offerId, quantity: 1 })).then((r) => expectRefused(r, 'enough stock'))
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    cy.then(() => post(API.rejectOrder, { id: held.sellerOrderId, version: held.sellerOrderVersion }))
      .then((r) => expectRefused(r, 'reason'))
    cy.then(() => post(API.rejectOrder, { id: held.sellerOrderId, version: held.sellerOrderVersion, reason: 'out of stock in store' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => checkout({ offerId: small.offerId, quantity: 1 }))
      .then((r) => expect(ok(r.body), 'stock is back after the reject').to.eq(true))
    cy.then(() => get(API.trackOrder(held.orderNo, heldPhone))).then((r) => {
      expect(data(r.body).status).to.eq('CANCELLED')
      expect(data(r.body).cancelReason).to.contain('could not fulfil')
    })
  })

  it('MKT-1e-05 [MKT-R10.2] [MKT-R19.1] [MKT-R18.5] nobody answers: the order EXPIRES, stock comes back, a late accept is refused', () => {
    cy.loginAsOperator()
    post(API.acceptWindow, { minutes: 1 }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    let o, phone
    checkout({ quantity: 1 }).then((r) => { o = data(r.body); phone = r.phone })
    cy.wait(130 * 1000)   // 1 min window + 30 s grace + one 30 s sweep + margin
    cy.then(() => get(API.trackOrder(o.orderNo, phone))).then((r) => {
      expect(data(r.body).status).to.eq('CANCELLED')
      expect(data(r.body).cancelReason).to.contain('did not confirm in time')
    })
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    cy.then(() => post(API.acceptOrder, { id: o.sellerOrderId, version: o.sellerOrderVersion }))
      .then((r) => expectRefused(r, 'expired'))
    cy.loginAsOperator()
    post(API.acceptWindow, { minutes: 5 })
  })

  it('MKT-1e-06 [MKT-R13.3] [MKT-R3.1] [MKT-R3.2] the order keeps its own copy of the terms; a later policy change does not touch it', () => {
    // its own policies and offer: deactivating the shared return policy would take the other cases' offer down with it
    let o, phone, own, mine
    seedPolicies(`${run}p`).then((p) => { own = p })
    cy.then(() => publishOffer(SELLER_A, { run: `${run}p`, price: 52000, promiseHours: 4, warrantyPolicyId: own.warranty,
      returnPolicyId: own.returns })).then((x) => { mine = x })
    cy.then(() => checkout({ offerId: mine.offerId })).then((r) => { o = data(r.body); phone = r.phone })
    cy.loginAsOperator()
    cy.then(() => post(API.deactivatePolicy, { id: own.returns }))
    cy.clearCookies()
    cy.then(() => get(API.trackOrder(o.orderNo, phone))).then((r) => {
      const line = data(r.body).lines[0]
      expect(line.returnDays, 'snapshot, not the live policy').to.eq(7)
      expect(line.warrantyProvider).to.eq('Samsung Pakistan')
      expect(line.unitPrice).to.eq(52000)
      expect(line.promiseHours).to.eq(4)
      expect(line.sellerOrganizationId).to.eq(line.stockOwnerOrganizationId)
      expect(line.fulfillerOrganizationId).to.eq(line.sellerOrganizationId)
      expect(line, 'commission is between MaxTheService and the seller, not the shopper').to.not.have.any.keys('commissionBasis', 'commissionRate')
    })
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    incoming('OFFERED').then((r) => {
      const so = list(r.body).find((x) => x.orderNo === o.orderNo)
      expect(so.lines[0].commissionBasis, 'the seller sees the commission it is charged (snapshot)').to.eq('ITEMS')
    })
  })

  it('MKT-1e-07 [MKT-R22.1] tracking needs the right phone; another seller cannot see or accept the order (positive controls)', () => {
    let o, phone
    checkout().then((r) => { o = data(r.body); phone = r.phone })
    cy.then(() => get(API.trackOrder(o.orderNo, '03009999999'))).then((r) => expectRefused(r, 'No such order'))
    cy.then(() => get(API.trackOrder(o.orderNo, phone))).then((r) => expect(ok(r.body), 'positive control').to.eq(true))
    publishOffer(SELLER_B, { run: `${run}b`, price: 50000, warrantyPolicyId: policies.warranty,      // B is a live seller
      returnPolicyId: policies.returns })
    cy.loginAs(SELLER_B, 'Demo@2025!', '/getBusinessDashboardStats')
    incoming('OFFERED').then((r) => {
      expect(ok(r.body), 'positive control').to.eq(true)
      expect(list(r.body).map((x) => x.orderNo)).to.not.include(o.orderNo)
    })
    cy.then(() => post(API.acceptOrder, { id: o.sellerOrderId, version: o.sellerOrderVersion }))
      .then((r) => expectRefused(r, 'No such order'))
  })

  it('MKT-1e-10 [MKT-R22.3] one phone can have at most 3 orders waiting: the stock cannot be held hostage', () => {
    const phone = newPhone()
    ;[1, 2, 3].forEach(() => checkout({ customerPhone: phone }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)))
    checkout({ customerPhone: phone }).then((r) => expectRefused(r, 'already have 3 orders waiting'))
    checkout({ customerPhone: newPhone() }).then((r) => expect(ok(r.body), 'positive control: another shopper').to.eq(true))
  })

  it('MKT-1e-08 [MKT-R22.1] the anonymous POST carries a CSRF token: a forged one is refused', () => {
    // From INSIDE the page with fetch: the suite's cy.request override swaps any X-XSRF-TOKEN for the real cookie,
    // so a "forged" cy.request would silently carry the valid token and prove nothing.
    const send = (token) => cy.window().then((w) => w.fetch('/marketplace/public/checkout', {
      method: 'POST', redirect: 'manual', credentials: 'same-origin',
      headers: Object.assign({ 'Content-Type': 'application/json', Accept: 'application/json' }, token ? { 'X-XSRF-TOKEN': token } : {}),
      body: JSON.stringify({ offerId: offer.offerId, quantity: 1, expectedPrice: 52000, customerName: 'Ali',
        customerPhone: newPhone(), address: '1 Clifton', city: 'Karachi', idempotencyKey: `csrf-${run}-${Math.random()}` }),
    }).then((r) => r.text().then((body) => ({ status: r.status, type: r.type, body }))))
    cy.clearCookies()
    cy.visit(UI.publicPage)
    send('forged').then((r) => {
      // Spring sends an anonymous CSRF failure to the login page: an opaque redirect (status 0) here, or a 403
      expect(r.type === 'opaqueredirect' || r.status === 403, `refused: ${r.status} ${r.type}`).to.eq(true)
      expect(r.body).to.not.contain('orderNo')
    })
    send(null).then((r) => expect(r.type === 'opaqueredirect' || r.status === 403, 'no token: refused').to.eq(true))
    cy.getCookie('XSRF-TOKEN').then((c) => send(decodeURIComponent(c.value))).then((r) => {
      expect(r.status, 'positive control: the page\'s own token works').to.eq(200)
      expect(JSON.parse(r.body).success, r.body).to.eq(true)
    })
  })

  it('MKT-1e-09 [MKT-R19.1] [MKT-R20.1] order, payment and settlement are separate facts; Phase 1 is cash on delivery', () => {
    checkout().then((r) => {
      const o = data(r.body)
      expect(o.status).to.eq('SUBMITTED')
      expect(o.paymentMode).to.eq('COD')
      expect(o.paymentStatus).to.eq('UNPAID')
      expect(o.sellerOrderStatus).to.eq('OFFERED')
    })
    cy.loginAsOperator()
    get(`${API.operatorOrders}?status=SUBMITTED&size=100`).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      expect(list(r.body).every((x) => x.status === 'SUBMITTED')).to.eq(true)
    })
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    get(`${API.operatorOrders}?status=SUBMITTED`).then((r) => expect(ok(r.body), 'tenant refused').to.eq(false))
  })
})
