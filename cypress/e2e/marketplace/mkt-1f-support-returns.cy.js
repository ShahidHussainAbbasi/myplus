/**
 * MKT-1f — support cases and marketplace returns, with the cost bearer.
 * Contract: microservices/docs/slices/mkt-1f-support-returns.md. Run headed: --env '{"mkt":"1f"}'
 *
 * The customer talks to MaxTheService only (R8.2). A return's cost lands on the party whose fault it was, read from
 * what the order line recorded when it was placed (R13.1, R13.3) — never from today's policies. Rulings:
 * R-MKT-12 a cash-on-delivery return is refunded in cash by the seller's rider at pickup; R-MKT-13 the seller's
 * rider collects; R-MKT-14 change of mind follows the offer's return policy and the customer bears the cost.
 *
 * Every case builds its own DELIVERED order through the seller's real steps (Accept → Packed → parcel → Delivered).
 */
const { gate, uniq, SELLER_A, SELLER_B, API, UI, ok, data, list, msg, post, get, expectRefused, seedPolicies, publishOffer } =
  require('./mkt-helpers')

const ACC = {
  login: '/marketplace/account/login',
  register: '/marketplace/account/register',
  logout: '/marketplace/account/logout',
  orders: '/marketplace/account/orders',
  openCase: (no) => `/marketplace/account/orders/${encodeURIComponent(no)}/cases`,
  cases: '/marketplace/account/cases',
  caseView: (no) => `/marketplace/account/cases/${encodeURIComponent(no)}`,
  caseMessage: (no) => `/marketplace/account/cases/${encodeURIComponent(no)}/messages`,
}
const OPS = {
  cases: '/platform/mkt/cases',                 // ?status=OPEN|WAITING_SELLER|…  (urgent first, then oldest)
  caseView: (no) => `/platform/mkt/caseView?caseNo=${encodeURIComponent(no)}`,
  reply: '/platform/mkt/caseReply',             // {caseNo, body, internal}
  task: '/platform/mkt/caseTask',               // {caseNo, note}
  decide: '/platform/mkt/returnDecision',       // {returnNo, decision: APPROVED|REJECTED, note}
  resolve: '/platform/mkt/caseResolve',         // {caseNo, note}
  returnPolicy: '/platform/mkt/returnPolicyDays', // {policyId, returnDays} — edits the policy, never old orders
  fee: '/platform/mkt/settings/changeOfMindFee',  // {amount}
}
const SELL = {
  tasks: '/mkt/sellerTasks',
  taskReply: '/mkt/sellerTaskReply',            // {caseNo, body}
  received: '/mkt/returnReceived',              // {returnNo, outcome: RESTOCK|QUARANTINE|WRITE_OFF, cashHandedBack}
}
const PW = 'Shop!ng2026'
const PRICE = 52000

gate('1f')('MKT-1f — support cases and returns', () => {
  const run = uniq()
  const phone = (k) => `0312${String(run).slice(-6)}${k}`
  let offer, policies

  const asCustomer = (ph) => {
    cy.clearCookies()
    cy.visit(UI.publicPage)
    post(ACC.login, { phone: ph, password: PW }).then((r) => {
      if (ok(r.body)) return
      post(ACC.register, { phone: ph, name: 'Ali Raza', password: PW }).then((x) => expect(ok(x.body), JSON.stringify(x.body)).to.eq(true))
    })
  }
  const asSeller = (email = SELLER_A) => cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')

  /** Signed in as `ph`: order, the seller accepts and delivers. Yields {orderNo, lineId, storeOrderId}. */
  const delivered = (ph, { mode = 'COD', offerId } = {}) => {
    const out = {}
    asCustomer(ph)
    post(API.checkout, { offerId: offerId || offer.offerId, quantity: 1, expectedPrice: PRICE, customerName: 'Ali Raza',
      customerPhone: ph, address: '1 Clifton', city: 'Karachi', idempotencyKey: `f-${run}-${Math.random()}`,
      paymentMode: mode, cardToken: mode === 'CARD' ? 'tok_ok' : undefined }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      Object.assign(out, { orderNo: data(r.body).orderNo, so: data(r.body).sellerOrderId, v: data(r.body).sellerOrderVersion })
    })
    asSeller()
    cy.then(() => post(API.acceptOrder, { id: out.so, version: out.v }).then((a) => expect(ok(a.body), JSON.stringify(a.body)).to.eq(true)))
    cy.then(() => get(`${API.incomingOrders}?status=ACCEPTED&size=100`).then((s) => {
      const row = list(s.body).find((x) => x.orderNo === out.orderNo)
      expect(row && row.storeOrderId, `storeOrderId on the seller's row: ${JSON.stringify(row)}`).to.be.a('number')
      out.storeOrderId = row.storeOrderId
    }))
    cy.then(() => post('/updateOrderStatus', { id: out.storeOrderId, status: 'PACKED' }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)))
    cy.then(() => get(`/getOrder?id=${out.storeOrderId}`).then((r) => {
      const line = data(r.body).items[0]
      post('/shipOrder', { id: out.storeOrderId, lines: [{ orderItemId: line.id, quantity: line.quantity }], carrier: 'Own rider',
        trackingNumber: `RID-${run}` }).then((s) => expect(ok(s.body), JSON.stringify(s.body)).to.eq(true))
    }))
    cy.then(() => post('/updateOrderStatus', { id: out.storeOrderId, status: 'DELIVERED' }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)))
    asCustomer(ph)
    cy.then(() => get(`${ACC.orders}?size=50`).then((r) => {
      const o = list(r.body).find((x) => x.orderNo === out.orderNo)
      expect(o.status, 'My orders follows the delivery').to.eq('DELIVERED')
      out.lineId = o.lines[0].id
    }))
    return cy.wrap(out)
  }
  const openReturn = (o, reason, note = 'see photos') =>
    post(ACC.openCase(o.orderNo), { topic: 'RETURN', note, lineId: o.lineId, quantity: 1, reason })

  before(() => {
    seedPolicies(`${run}f`).then((p) => { policies = p; return cy.then(() => publishOffer(SELLER_A, { run: `${run}f`, price: PRICE, qty: 40,
      warrantyPolicyId: p.warranty, returnPolicyId: p.returns })) }).then((o) => { offer = o })
    cy.loginAsOperator()
    post(API.acceptWindow, { minutes: 5 })
    post(OPS.fee, { amount: 250 }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  })

  it('MKT-1f-01 [MKT-R13.2] My orders follows the seller\'s delivery; "Get help" exists only once delivered (real UI)', () => {
    const ph = phone(1)
    asCustomer(ph)
    cy.visit(`${UI.publicPage}?account=orders`)
    delivered(ph).then((o) => {
      cy.visit(`${UI.publicPage}?account=orders`)
      cy.get(`#mktMyOrders li[data-order-no="${o.orderNo}"]`).should('contain', 'Delivered').find('.mkt-help').should('be.visible')
    })
  })

  it('MKT-1f-02 [MKT-R8.2] the customer opens ONE case with MaxTheService; the seller\'s phone is never shown to them', () => {
    const ph = phone(2)
    delivered(ph).then((o) => {
      post(ACC.openCase(o.orderNo), { topic: 'ORDER_PROBLEM', note: 'Box was open' }).then((r) => {
        expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
        expect(data(r.body).caseNo).to.match(/^SC-\d+$/)
        expect(msg(r.body)).to.contain('MaxTheService support')
        expect(JSON.stringify(r.body)).not.to.match(/sellerPhone|supportPhone/)
        post(ACC.openCase(o.orderNo), { topic: 'ORDER_PROBLEM', note: 'again' }).then((x) =>
          expect(data(x.body).caseNo, 'one open case per order: the same case').to.eq(data(r.body).caseNo))
      })
    })
  })

  it('MKT-1f-03 [MKT-R8.2] [MKT-R22.1] the operator tasks the seller; only that seller sees it; an internal note never reaches the customer', () => {
    const ph = phone(3)
    delivered(ph).then((o) => {
      post(ACC.openCase(o.orderNo), { topic: 'ORDER_PROBLEM', note: 'No charger in the box' }).then((r) => {
        const caseNo = data(r.body).caseNo
        cy.loginAsOperator()
        post(OPS.reply, { caseNo, body: 'seller has a history of this', internal: true })
        post(OPS.task, { caseNo, note: 'Check the box and call the customer' }).then((t) => expect(ok(t.body), JSON.stringify(t.body)).to.eq(true))
        asSeller(SELLER_B)
        get(SELL.tasks).then((t) => expect(list(t.body).map((x) => x.caseNo)).not.to.include(caseNo))
        post(SELL.taskReply, { caseNo, body: 'not mine' }).then((t) => expectRefused(t, 'No such case'))
        asSeller()
        get(SELL.tasks).then((t) => expect(list(t.body).map((x) => x.caseNo)).to.include(caseNo))      // positive control
        post(SELL.taskReply, { caseNo, body: 'Charger sent with our rider today' }).then((t) => expect(ok(t.body), JSON.stringify(t.body)).to.eq(true))
        asCustomer(ph)
        get(ACC.caseView(caseNo)).then((v) => {
          const text = JSON.stringify(data(v.body))
          expect(text).not.to.contain('history of this')
          expect(text).to.contain('Charger sent with our rider today')                                    // relayed
        })
      })
    })
  })

  it('MKT-1f-04 [MKT-R13.1] [MKT-R13.2] wrong product, paid online: the fulfiller bears it; refunded exactly once; the seller\'s books reverse', () => {
    const ph = phone(4)
    delivered(ph, { mode: 'CARD' }).then((o) => {
      openReturn(o, 'WRONG_PRODUCT').then((r) => {
        expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
        const ret = data(r.body).returns[0]
        expect(ret.bearerRole).to.eq('FULFILLER')
        expect(ret.bearerOrgName, 'resolved from the line snapshot').to.contain('Shahzad')
        cy.loginAsOperator()
        post(OPS.decide, { returnNo: ret.returnNo, decision: 'APPROVED', note: 'pickup tomorrow' })
        asSeller()
        post(SELL.received, { returnNo: ret.returnNo, outcome: 'RESTOCK' }).then((x) => expect(ok(x.body), JSON.stringify(x.body)).to.eq(true))
        post(SELL.received, { returnNo: ret.returnNo, outcome: 'RESTOCK' }).then((x) => expect(ok(x.body), 'a second press changes nothing').to.eq(true))
        get(`/getOrder?id=${o.storeOrderId}`).then((s) => expect(data(s.body).fulfilmentStatus).to.eq('RETURNED'))
        asCustomer(ph)
        get(`${ACC.orders}?size=50`).then((m) => {
          const x = list(m.body).find((y) => y.orderNo === o.orderNo)
          expect(x.payments.filter((p) => p.kind === 'REFUND' && p.status === 'SUCCEEDED'), 'one refund').to.have.length(1)
          expect(x.payments.find((p) => p.kind === 'REFUND').amount).to.eq(PRICE)
        })
      })
    })
  })

  it('MKT-1f-05 [MKT-R13.1] change of mind within the return days: allowed; the customer bears the pickup fee', () => {
    const ph = phone(5)
    delivered(ph, { mode: 'CARD' }).then((o) => {
      openReturn(o, 'CHANGE_OF_MIND', 'do not want it').then((r) => {
        const ret = data(r.body).returns[0]
        expect(ret.bearerRole).to.eq('CUSTOMER')
        expect(ret.deduction).to.eq(250)
        expect(ret.refundAmount).to.eq(PRICE - 250)
      })
    })
  })

  it('MKT-1f-06 [MKT-R13.3] a policy changed after the order never changes that order: its snapshot decides', () => {
    const ph = phone(6)
    delivered(ph).then((o) => {
      cy.loginAsOperator()
      post(OPS.returnPolicy, { policyId: policies.returns, returnDays: 0 }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
      asCustomer(ph)
      openReturn(o, 'CHANGE_OF_MIND').then((r) => expect(ok(r.body), `the order's own ${7} days still apply: ${JSON.stringify(r.body)}`).to.eq(true))
      cy.loginAsOperator()
      post(OPS.returnPolicy, { policyId: policies.returns, returnDays: 7 })
    })
  })

  it('MKT-1f-07 [MKT-R13.2] past the return days a change of mind is refused in words (positive control: a fault is still taken)', () => {
    const ph = phone(7)
    cy.loginAsOperator()
    post(OPS.returnPolicy, { policyId: policies.returns, returnDays: 0 })
    delivered(ph).then((o) => {
      openReturn(o, 'CHANGE_OF_MIND').then((r) => expectRefused(r, 'The return period for this item'))
      openReturn(o, 'NOT_AS_DESCRIBED').then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
      cy.loginAsOperator()
      post(OPS.returnPolicy, { policyId: policies.returns, returnDays: 7 })
    })
  })

  it('MKT-1f-08 [MKT-R13.4] expired or unsafe goods: the case is URGENT and first in the operator\'s queue', () => {
    const ph = phone(8)
    delivered(ph).then((o) => {
      openReturn(o, 'EXPIRED_OR_UNSAFE', 'battery swollen').then((r) => {
        const caseNo = data(r.body).caseNo
        cy.loginAsOperator()
        get(`${OPS.cases}?size=50`).then((q) => {
          const rows = list(q.body)
          expect(rows[0].urgent, 'urgent first').to.eq(true)
          expect(rows.find((x) => x.caseNo === caseNo).urgent).to.eq(true)
        })
      })
    })
  })

  it('MKT-1f-09 [MKT-R8.2] the store order\'s own return paths are closed for a marketplace order (no bypass, no unrefunded reversal)', () => {
    const ph = phone(9)
    delivered(ph, { mode: 'CARD' }).then((o) => {
      cy.clearCookies()
      post('/storefront/return', { ref: o.storeOrderId, contact: ph, reason: 'bypass' }).then((r) => expectRefused(r, 'MaxTheService'))
      asSeller()
      post('/processReturn', { id: o.storeOrderId }).then((r) => expectRefused(r, 'through MaxTheService'))
      get(`/getOrder?id=${o.storeOrderId}`).then((s) => expect(data(s.body).fulfilmentStatus, 'untouched').to.eq('DELIVERED'))
    })
  })

  it('MKT-1f-10 [MKT-R13.2] a cash-on-delivery return: the rider hands the cash back at pickup; no card refund is attempted', () => {
    const ph = phone(10)
    delivered(ph).then((o) => {
      openReturn(o, 'DEFECTIVE', 'screen flickers').then((r) => {
        const ret = data(r.body).returns[0]
        expect(ret.bearerRole).to.eq('STOCK_OWNER')
        cy.loginAsOperator()
        post(OPS.decide, { returnNo: ret.returnNo, decision: 'APPROVED' })
        asSeller()
        post(SELL.received, { returnNo: ret.returnNo, outcome: 'QUARANTINE' }).then((x) => expectRefused(x, 'cash'))
        post(SELL.received, { returnNo: ret.returnNo, outcome: 'QUARANTINE', cashHandedBack: true }).then((x) => {
          expect(ok(x.body), JSON.stringify(x.body)).to.eq(true)
          expect(data(x.body).refundChannel).to.eq('CASH_AT_PICKUP')
        })
        asCustomer(ph)
        get(`${ACC.orders}?size=50`).then((m) => {
          const x = list(m.body).find((y) => y.orderNo === o.orderNo)
          expect((x.payments || []).filter((p) => p.kind === 'REFUND'), 'no provider refund for cash').to.have.length(0)
        })
      })
    })
  })

  it('MKT-1f-11 [MKT-R22.1] another customer can neither open a case on my order nor read my case', () => {
    const ph = phone(11)
    delivered(ph).then((o) => {
      post(ACC.openCase(o.orderNo), { topic: 'OTHER', note: 'question' }).then((r) => {
        const caseNo = data(r.body).caseNo
        asCustomer(phone(12))
        post(ACC.openCase(o.orderNo), { topic: 'OTHER', note: 'not mine' }).then((x) => expectRefused(x, 'No such order'))
        get(ACC.caseView(caseNo)).then((x) => expectRefused(x, 'No such case'))
      })
    })
  })

  it('MKT-1f-12 [MKT-R22.4] support and return actions leave audit rows in the SELLER\'s trail (a refused action leaves none)', () => {
    const ph = phone(13)
    delivered(ph).then((o) => {
      openReturn(o, 'WRONG_PRODUCT').then((r) => {
        const ret = data(r.body).returns[0]
        cy.loginAsOperator()
        post(OPS.decide, { returnNo: ret.returnNo, decision: 'MAYBE' }).then((x) => expectRefused(x, 'Choose'))   // refused: no row
        post(OPS.decide, { returnNo: ret.returnNo, decision: 'APPROVED', note: 'audit me' })
        asSeller()
        cy.findAudit((a) => a.action === 'MKT_RETURN_DECIDED' && String(a.entityRef) === ret.returnNo
          && String(a.afterValue) === 'APPROVED' && a.actorType === 'PLATFORM_OPERATOR', 'the operator\'s decision, in the seller\'s trail')
        cy.auditLog().then((rows) => expect(rows.filter((a) => a.action === 'MKT_RETURN_DECIDED' && String(a.entityRef) === ret.returnNo),
          'exactly one: the refused decision recorded nothing').to.have.length(1))
      })
    })
  })
})
