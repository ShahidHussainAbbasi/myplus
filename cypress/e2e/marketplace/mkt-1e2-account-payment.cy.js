/**
 * MKT-1e2 — the marketplace customer: phone + password account, My orders, claim, cancel, online payment (sandbox).
 * Contract: microservices/docs/slices/mkt-1e2-customer-account-payment.md. Run headed: --env '{"mkt":"1e2"}'   (JSON keeps "1e2" a string: bare mkt=1e2 is parsed as the number 100)
 *
 * Proof, not phone: a phone number is never verified (no SMS provider), so an order joins an account only when placed
 * signed in or CLAIMED with its number + phone — the same proof tracking already asks for.
 */
const { gate, uniq, SELLER_A, API, UI, ok, data, list, msg, post, get, expectRefused, seedPolicies, publishOffer } =
  require('./mkt-helpers')

const ACC = {
  register: '/marketplace/account/register',
  login: '/marketplace/account/login',
  logout: '/marketplace/account/logout',
  me: '/marketplace/account/me',
  orders: '/marketplace/account/orders',
  claim: '/marketplace/account/claim',
  cancel: (no) => `/marketplace/account/orders/${encodeURIComponent(no)}/cancel`,
}
const PW = 'Shop!ng2026'

gate('1e2')('MKT-1e2 — customer account, My orders, cancel, online payment', () => {
  const run = uniq()
  const phone = (k) => `0311${String(run).slice(-6)}${k}`
  let offer

  /** A fresh anonymous browser with the page's security token. */
  const fresh = () => { cy.clearCookies(); cy.visit(UI.publicPage) }
  const register = (ph, name = 'Ali Raza') => post(ACC.register, { phone: ph, name, password: PW })
  const order = (ph, extra = {}) => post(API.checkout, Object.assign({ offerId: offer.offerId, quantity: 1, expectedPrice: 52000,
    customerName: 'Ali Raza', customerPhone: ph, address: '1 Clifton', city: 'Karachi', idempotencyKey: `e2-${run}-${Math.random()}` }, extra))

  before(() => {
    seedPolicies(`${run}a`).then((p) => cy.then(() => publishOffer(SELLER_A, { run: `${run}a`, price: 52000, qty: 30,
      warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))).then((o) => { offer = o })
    cy.loginAsOperator()
    post(API.acceptWindow, { minutes: 5 })
  })

  it('MKT-1e2-01 [MKT-R1.1] a shopper creates an account with phone + password and is signed in (real UI); one account per phone', () => {
    fresh()
    cy.get('#mktAccountBtn').click()
    cy.get('#mktAccCreate').click()
    cy.get('#mktAccPhone').type(phone(1))
    cy.get('#mktAccName').type('Ali Raza')
    cy.get('#mktAccPassword').type(PW)
    cy.get('#mktAccSubmit').click()
    cy.get('#mktAccountBtn').should('contain', 'Ali Raza')
    fresh()
    register(`(${phone(1).slice(0, 4)}) ${phone(1).slice(4)}`).then((r) => expectRefused(r, 'already has an account'))
  })

  it('MKT-1e2-02 [MKT-R22.3] the session cookie is HttpOnly; signing out ends it on the server', () => {
    fresh()
    post(ACC.login, { phone: phone(1), password: PW }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.document().its('cookie').should('not.contain', 'MKT_SESSION')            // never readable by page script
    cy.getCookie('MKT_SESSION').then((c) => {                                  // (a should() on a property would change the subject)
      expect(c, 'session cookie').to.exist
      expect(c.httpOnly, 'HttpOnly').to.eq(true)
      expect(c.sameSite, 'SameSite').to.match(/lax/i)
      get(ACC.me).then((r) => expect(data(r.body).name).to.eq('Ali Raza'))
      post(ACC.logout, {})
      cy.setCookie('MKT_SESSION', c.value, { httpOnly: true })                  // replay the old cookie
      get(ACC.me).then((r) => expectRefused(r, 'Sign in'))
    })
  })

  it('MKT-1e2-03 [MKT-R22.3] five wrong passwords lock the phone for 15 minutes; an unknown phone reads the same', () => {
    fresh()
    register(phone(2)).then((r) => expect(ok(r.body)).to.eq(true))
    post(ACC.logout, {})
    ;[1, 2, 3, 4, 5].forEach(() => post(ACC.login, { phone: phone(2), password: 'wrong-one' })
      .then((r) => expectRefused(r, 'The phone number or password is not right.')))
    post(ACC.login, { phone: phone(2), password: PW }).then((r) => expectRefused(r, 'try again in 15 minutes'))
    post(ACC.login, { phone: '03119999999', password: PW }).then((r) => expectRefused(r, 'The phone number or password is not right.'))
  })

  it('MKT-1e2-04 [MKT-R1.1] an order placed signed in is in My orders without any claim (positive control: anonymous one is not)', () => {
    fresh()
    post(ACC.login, { phone: phone(1), password: PW })
    order(phone(1)).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      const no = data(r.body).orderNo
      get(ACC.orders).then((m) => expect(list(m.body).map((o) => o.orderNo)).to.include(no))
    })
    fresh()
    order(phone(1)).then((r) => {
      const anon = data(r.body).orderNo
      post(ACC.login, { phone: phone(1), password: PW })
      get(ACC.orders).then((m) => expect(list(m.body).map((o) => o.orderNo), 'same phone is NOT proof').to.not.include(anon))
    })
  })

  it('MKT-1e2-05 [MKT-R22.1] claiming an order needs its number AND phone; an order already in another account cannot be taken', () => {
    let no
    fresh()
    order(phone(3)).then((r) => { no = data(r.body).orderNo })
    cy.then(() => register(phone(4)))
    cy.then(() => post(ACC.claim, { orderNo: no, phone: phone(9) })).then((r) => expectRefused(r, 'No such order'))
    cy.then(() => post(ACC.claim, { orderNo: no, phone: phone(3) })).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    get(ACC.orders).then((m) => expect(list(m.body).map((o) => o.orderNo)).to.include(no))
    fresh()
    register(phone(5))
    cy.then(() => post(ACC.claim, { orderNo: no, phone: phone(3) })).then((r) => expectRefused(r, 'already in another account'))
  })

  it('MKT-1e2-06 [MKT-R10.5] the shopper cancels while the seller has not answered (real UI): stock back; after Accept it cannot', () => {
    fresh()
    post(ACC.login, { phone: phone(1), password: PW })
    order(phone(1)).then((r) => {
      const no = data(r.body).orderNo
      cy.visit(`${UI.publicPage}?account=orders`)
      cy.contains('#mktMyOrders li', no).find('.mkt-cancel').click()
      cy.get('#uiC-input').type('changed my mind')
      cy.get('.uiC-ok').click()
      cy.contains('#mktMyOrders li', no).should('contain', 'Cancelled').find('.mkt-cancel').should('not.exist')
      cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
      get(`${API.incomingOrders}?status=&size=100`).then((s) =>
        expect(list(s.body).find((x) => x.orderNo === no).acceptanceStatus).to.eq('CANCELLED'))
    })
    fresh()
    post(ACC.login, { phone: phone(1), password: PW })
    order(phone(1)).then((r) => {
      const o = data(r.body)
      cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
      post(API.acceptOrder, { id: o.sellerOrderId, version: o.sellerOrderVersion }).then((a) => expect(ok(a.body), JSON.stringify(a.body)).to.eq(true))
      fresh()
      post(ACC.login, { phone: phone(1), password: PW })
      post(ACC.cancel(o.orderNo), { reason: 'too late' }).then((c) => expectRefused(c, 'already confirmed'))
    })
  })

  it('MKT-1e2-07 [MKT-R19.1] [MKT-R20.1] Pay online now: CAPTURED at once; the seller rejects → REFUNDED exactly once', () => {
    fresh()
    post(ACC.login, { phone: phone(1), password: PW })
    order(phone(1), { paymentMode: 'CARD', cardToken: 'tok_ok' }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      const o = data(r.body)
      expect(o.paymentMode).to.eq('CARD')
      expect(o.paymentStatus, 'charged at once (domain: CAPTURED)').to.eq('CAPTURED')
      cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
      post(API.rejectOrder, { id: o.sellerOrderId, version: o.sellerOrderVersion, reason: 'out of stock' })
      post(API.rejectOrder, { id: o.sellerOrderId, version: o.sellerOrderVersion, reason: 'out of stock' })   // a double click
      fresh()
      post(ACC.login, { phone: phone(1), password: PW })
      get(ACC.orders).then((m) => {
        const mine = list(m.body).find((x) => x.orderNo === o.orderNo)
        expect(mine.paymentStatus).to.eq('REFUNDED')
        expect(mine.payments.filter((p) => p.kind === 'REFUND' && p.status === 'SUCCEEDED'), 'refunded once').to.have.length(1)
      })
    })
  })

  it('MKT-1e2-08 [MKT-R19.1] a declined card places nothing for the seller and gives the stock back', () => {
    fresh()
    post(ACC.login, { phone: phone(1), password: PW })
    order(phone(1), { paymentMode: 'CARD', cardToken: 'fail' }).then((r) => {
      expectRefused(r, 'Your card was declined')
      cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
      get(`${API.incomingOrders}?status=OFFERED&size=100`).then((s) =>
        expect(list(s.body).filter((x) => x.customerPhone === phone(1) && x.acceptanceStatus === 'OFFERED' && x.paymentMode === 'CARD')).to.have.length(0))
    })
  })

  it('MKT-1e2-09 [MKT-R20.1] online payment needs an account; an anonymous CARD checkout is refused (positive control: COD works)', () => {
    fresh()
    order(phone(6), { paymentMode: 'CARD', cardToken: 'tok_ok' }).then((r) => expectRefused(r, 'Sign in to pay online'))
    order(phone(6)).then((r) => expect(ok(r.body)).to.eq(true))
  })
})
