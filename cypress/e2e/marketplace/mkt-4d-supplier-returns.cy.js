/**
 * MKT-4d — a supplier's shortages, returns and settlement. Source R11.4 (a rejection is recorded against the party
 * responsible), R20.5 (supplier stock: returns and settlement), R13.1/R13.2 (the bearer of a return, the rider at
 * pickup), R15 (commission and payable lines). Contract: microservices/docs/slices/mkt-4d-supplier-returns.md
 *
 * Ruling 2026-10-15: a supplier is a MyPlus business the operator marks. It owns, holds and sends its stock itself, so:
 *   - it cannot reject naming "My supplier could not deliver": it IS the supplier, and that cause would excuse its own
 *     miss on the scorecard (the one code change in this slice);
 *   - a faulty return is borne by it as the stock owner, its rider collects, and the cash is handed back at pickup;
 *   - its delivered line settles like a shop's: commission taken, payable after the return window.
 *
 * Time is not faked beyond the stack's usual clock: the settlement case sells under a 0-day return policy and T+0 (as
 * MKT-1g), so a line delivered on a business day is payable the same day. The previous settlement setting is restored.
 *
 * Leaves the supplier unmarked; every order it took is rejected, returned or delivered.
 */
const { gate, uniq, SELLER_A, API, UI, ok, data, list, post, get, expectRefused, seedPolicies, publishOffer } = require('./mkt-helpers')

const SUPPLIER = 'owner.supplier@myplus.com'
const NAME = 'Phone Supplier'
const MARK = '/platform/mkt/sellerSupplier'
const ACC = {
  login: '/marketplace/account/login',
  register: '/marketplace/account/register',
  orders: '/marketplace/account/orders',
  openCase: (no) => `/marketplace/account/orders/${encodeURIComponent(no)}/cases`,
  caseView: (no) => `/marketplace/account/cases/${encodeURIComponent(no)}`,
}
const OPS = {
  caseView: (no) => `/platform/mkt/caseView?caseNo=${encodeURIComponent(no)}`,
  decide: '/platform/mkt/returnDecision',
}
const SET = { settings: '/platform/mkt/settlementSettings', run: '/platform/mkt/runSettlement' }
const RECEIVED = '/mkt/returnReceived'
const OWN_CAUSE = 'You are the supplier of these items, so the stock is yours: choose "Out of stock in my shop".'
const PW = 'Shop!ng2026'
const PRICE = 48000

gate('4d')('MKT-4d — a supplier answers for its own shortages, returns and settlement', () => {
  const run = uniq()
  const phone = (k) => `0318${String(run).slice(-6)}${k}`
  const S = {}

  const asSupplier = () => cy.loginAs(SUPPLIER, 'Demo@2025!', '/getBusinessDashboardStats')
  const asCustomer = (ph) => {
    cy.clearCookies()
    cy.visit(UI.publicPage)
    post(ACC.login, { phone: ph, password: PW }).then((r) => {
      if (ok(r.body)) return
      post(ACC.register, { phone: ph, name: 'Ali Raza', password: PW }).then((x) => expect(ok(x.body), JSON.stringify(x.body)).to.eq(true))
    })
  }
  const mark = (on) => {
    cy.loginAsOperator()
    return cy.then(() => post(MARK, { organizationId: S.org, supplier: on }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  }
  /** Signed in as `ph`, the shopper orders `of`. Yields {orderNo, so, v}. */
  const order = (ph, of) => {
    asCustomer(ph)
    return post(API.checkout, { offerId: of.offerId, quantity: 1, expectedPrice: PRICE, customerName: 'Ali Raza',
      customerPhone: ph, address: '1 Clifton', city: 'Karachi', idempotencyKey: `m4d-${run}-${Math.random()}` }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      return { orderNo: data(r.body).orderNo, so: data(r.body).sellerOrderId, v: data(r.body).sellerOrderVersion }
    })
  }
  /** The supplier's real steps: accept → packed → parcel → delivered. Yields the order with storeOrderId and lineId. */
  const delivered = (ph, of) => {
    const out = {}
    order(ph, of).then((o) => Object.assign(out, o))
    asSupplier()
    cy.then(() => post(API.acceptOrder, { id: out.so, version: out.v })).then((a) => expect(ok(a.body), JSON.stringify(a.body)).to.eq(true))
    cy.then(() => get(`${API.incomingOrders}?status=ACCEPTED&size=100`)).then((s) => {
      out.storeOrderId = list(s.body).find((x) => x.orderNo === out.orderNo).storeOrderId
    })
    cy.then(() => post('/updateOrderStatus', { id: out.storeOrderId, status: 'PACKED' }))
    cy.then(() => get(`/getOrder?id=${out.storeOrderId}`)).then((r) => {
      const line = data(r.body).items[0]
      return post('/shipOrder', { id: out.storeOrderId, lines: [{ orderItemId: line.id, quantity: line.quantity }], carrier: 'Own rider',
        trackingNumber: `PS-${run}` }).then((x) => expect(ok(x.body), JSON.stringify(x.body)).to.eq(true))
    })
    cy.then(() => post('/updateOrderStatus', { id: out.storeOrderId, status: 'DELIVERED' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    asCustomer(ph)
    cy.then(() => get(`${ACC.orders}?size=50`)).then((r) => {
      const o = list(r.body).find((x) => x.orderNo === out.orderNo)
      expect(o.status, 'My orders follows the delivery').to.eq('DELIVERED')
      out.lineId = o.lines[0].id
    })
    return cy.wrap(out)
  }
  /** The waiting row for `orderNo` on Sale → Marketplace, signed in as `email`. */
  const offeredRow = (email, orderNo) => {
    cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')
    cy.visit('/businessDashboard')
    cy.get('#snavSell .snav-btn').click()
    cy.intercept('GET', '**/mkt/incomingOrders*').as('incoming')
    cy.get(UI.sellerNav).should('be.visible').click()
    cy.wait('@incoming')
    return cy.contains(`${UI.incoming} tbody tr`, orderNo, { timeout: 10000 })
  }

  before(() => {
    cy.loginAsOperator()
    cy.orgOf(SUPPLIER).then((o) => { S.org = o.id })
    cy.then(() => get(SET.settings)).then((r) => { S.settings = data(r.body) })
    post(API.acceptWindow, { minutes: 5, multiSeller: false, reroute: false })
    seedPolicies(run).then((p) => {
      cy.then(() => publishOffer(SUPPLIER, { run: `${run}r`, price: PRICE, qty: 10, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))
        .then((o) => { S.offer = o })
      cy.then(() => publishOffer(SELLER_A, { run: `${run}s`, price: PRICE, qty: 5, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))
        .then((o) => { S.shop = o })
    })
    seedPolicies(`${run}z`, { returnDays: 0 }).then((p) => cy.then(() => publishOffer(SUPPLIER, { run: `${run}z`, price: PRICE, qty: 5,
      warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))).then((o) => { S.noReturns = o })
    mark(true)
  })

  after(() => {
    mark(false)
    cy.then(() => {
      if (S.settings) post(SET.settings, { tPlusDays: S.settings.tPlusDays })
    })
  })

  it('MKT-4d-01 [MKT-R11.4] [MKT-R20.5] the supplier cannot reject blaming "my supplier": its own cause is recorded against it (real UI)', () => {
    order(phone(1), S.offer).then((o) => { S.a = o })
    cy.then(() => offeredRow(SUPPLIER, S.a.orderNo)).within(() => {
      cy.get('.mkt-reject-cause option').then(($o) => {
        const values = [...$o].map((x) => x.value)
        expect(values, 'no supplier behind a supplier').to.deep.eq(['MERCHANT_STALE_STOCK', 'PLATFORM_SYNC_DEFECT'])
      })
    })
    // the server refuses it too, for a client that sends the cause anyway
    cy.then(() => post(API.rejectOrder, { id: S.a.so, version: S.a.v, reason: 'none left', cause: 'SUPPLIER_STALE_STOCK' }))
      .then((r) => expectRefused(r, OWN_CAUSE))
    cy.then(() => offeredRow(SUPPLIER, S.a.orderNo)).within(() => {
      cy.get('input[placeholder="Why you cannot fulfil it"]').type('none left on the shelf')
      cy.get('.mkt-reject-cause').select('MERCHANT_STALE_STOCK')
      cy.get('.mkt-reject').click()
    })
    cy.then(() => get(`${API.incomingOrders}?status=REJECTED&size=100`)).then((r) => {
      const so = list(r.body).find((x) => x.orderNo === S.a.orderNo)
      expect(so, 'rejected').to.exist
      expect(so.shortage.cause).to.eq('MERCHANT_STALE_STOCK')
      expect(so.shortage.responsibleRole, 'counted against the supplier, not excused').to.eq('MERCHANT')
    })
    // control: a shop still names its supplier
    order(phone(2), S.shop).then((o) => { S.b = o })
    cy.then(() => offeredRow(SELLER_A, S.b.orderNo)).find('.mkt-reject-cause option[value="SUPPLIER_STALE_STOCK"]')
      .should('have.text', 'My supplier could not deliver')
    cy.then(() => post(API.rejectOrder, { id: S.b.so, version: S.b.v, reason: 'gate cleanup', cause: 'SUPPLIER_STALE_STOCK' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  })

  it('MKT-4d-02 [MKT-R13.1] [MKT-R13.2] [MKT-R20.5] a faulty return: the supplier bears it as stock owner, its rider collects and hands the cash back', () => {
    delivered(phone(3), S.offer).then((o) => { S.c = o })
    asCustomer(phone(3))
    cy.then(() => post(ACC.openCase(S.c.orderNo), { topic: 'RETURN', note: 'screen flickers', lineId: S.c.lineId, quantity: 1, reason: 'DEFECTIVE' }))
      .then((r) => {
        expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
        S.c.caseNo = data(r.body).caseNo
        S.c.ret = data(r.body).returns[0]
        expect(S.c.ret.bearerRole).to.eq('STOCK_OWNER')
        expect(S.c.ret.bearerOrgName, 'between the parties, not the shopper\'s to read').to.eq(null)
      })
    cy.loginAsOperator()
    cy.then(() => get(OPS.caseView(S.c.caseNo))).then((v) => expect(data(v.body).returns[0].bearerOrgName).to.eq(NAME))
    cy.then(() => post(OPS.decide, { returnNo: S.c.ret.returnNo, decision: 'APPROVED' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    asCustomer(phone(3))
    cy.then(() => get(ACC.caseView(S.c.caseNo))).then((r) => expect(data(r.body).messages.map((m) => m.body).join(' | '))
      .to.contain(`Your return ${S.c.ret.returnNo} is approved. The seller's rider will collect it from your address.`))
    asSupplier()
    cy.then(() => post(RECEIVED, { returnNo: S.c.ret.returnNo, outcome: 'RESTOCK' })).then((x) => expectRefused(x, 'cash'))
    cy.then(() => post(RECEIVED, { returnNo: S.c.ret.returnNo, outcome: 'RESTOCK', cashHandedBack: true })).then((x) => {
      expect(ok(x.body), JSON.stringify(x.body)).to.eq(true)
      expect(data(x.body).refundChannel).to.eq('CASH_AT_PICKUP')
    })
    cy.then(() => get(`/getOrder?id=${S.c.storeOrderId}`)).then((s) => expect(data(s.body).fulfilmentStatus).to.eq('RETURNED'))
  })

  it('MKT-4d-03 [MKT-R15.5] [MKT-R20.5] a delivered supplier line settles like a shop\'s: commission taken, payable after its return window', () => {
    cy.loginAsOperator()
    post(SET.settings, { tPlusDays: 0, useMyBooks: true }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    delivered(phone(4), S.noReturns).then((o) => { S.d = o })
    cy.loginAsOperator()
    cy.then(() => post(SET.run, {})).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    asSupplier()
    cy.then(() => get(`${API.statement}?size=100`)).then((r) => {
      const l = list(r.body).find((x) => x.orderNo === S.d.orderNo)
      expect(l, 'on the supplier\'s own statement').to.exist
      expect(Number(l.commission), 'MaxTheService earns its commission on supplier stock').to.be.greaterThan(0)
      const parts = ['payable', 'commission', 'delivery', 'fees', 'tax', 'reserve', 'adjustment'].reduce((t, k) => t + Number(l[k] || 0), 0)
      expect(Math.round(parts * 100), 'the line adds up to what the shopper paid').to.eq(Math.round(Number(l.customerAmount) * 100))
      if (l.status === 'PENDING_RETURN_WINDOW') {
        // the server says today is not a business day: T+0 pays on the next one (MKT-R15.1)
        expect(new Date(`${l.eligibleOn}T00:00:00Z`).getUTCDay(), `payable ${l.eligibleOn}`).to.eq(1)
        return
      }
      expect(l.status).to.eq('ELIGIBLE')
    })
  })
})
