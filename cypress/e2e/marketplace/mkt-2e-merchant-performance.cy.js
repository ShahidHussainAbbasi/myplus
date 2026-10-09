/**
 * MKT-2e — merchant performance: how each seller handles its orders, read from the orders themselves; the operator's
 * scorecard, the seller's own, and the ranking's "acceptance history" tie-break. Source R20.3 ("merchant performance"),
 * §18 (acceptance history in the ranking), R11.4 / R12.4 (a cause is a record; a disputed one is not used).
 * Contract: microservices/docs/slices/mkt-2e-merchant-performance.md
 *
 * Every figure is checked as a DIFFERENCE from the figures read just before the action, because the stack's sellers
 * carry the history of every earlier gate. Before the first case, both sellers' waiting orders are answered, so the
 * sweeper cannot expire one of them in the middle of a case.
 */
const { gate, uniq, SELLER_A, SELLER_B, API, UI, ok, data, list, post, get, expectRefused, seedPolicies, publishOffer } =
  require('./mkt-helpers')

const PERF = {
  operator: (days = 30) => `/platform/mkt/sellerPerformance?days=${days}`,
  mine: (days = 30) => `/mkt/myPerformance?days=${days}`,
  dispute: '/mkt/shortageDispute',          // {id, note}
  decide: '/platform/mkt/shortageDecide',   // {id, outcome, note}
}
const PRICE = 52000

gate('2e')('MKT-2e — merchant performance: the scorecard and the ranking', () => {
  const run = uniq()
  let a, b, orgA, orgB, aName, bName, bFirst
  const newPhone = () => `0316${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`

  const asSeller = (email) => cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')
  const reroute = (on) => {
    cy.loginAsOperator()
    return post(API.acceptWindow, { reroute: on }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  }
  /** An anonymous cash order for one unit of `o`. Yields {orderNo, phone}. */
  const order = (o) => {
    const phone = newPhone()
    cy.clearCookies()
    cy.visit(UI.publicPage)
    return post(API.checkout, { lines: [{ offerId: o.offerId, quantity: 1, expectedPrice: PRICE }], customerName: 'Ali',
      customerPhone: phone, address: '1 Clifton', city: 'Karachi', idempotencyKey: `m2e-${run}-${Math.random()}` }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      return { orderNo: data(r.body).orderNo, phone }
    })
  }
  const partOf = (email, orderNo, status = 'OFFERED') => {
    asSeller(email)
    return get(`${API.incomingOrders}?status=${status}&size=100`).then((r) => list(r.body).find((x) => x.orderNo === orderNo))
  }
  const accept = (no) => partOf(SELLER_A, no).then((so) => post(API.acceptOrder, { id: so.id, version: so.version }))
    .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  const reject = (no, cause) => partOf(SELLER_A, no).then((so) =>
    post(API.rejectOrder, { id: so.id, version: so.version, reason: 'none left on the shelf', cause }))
    .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  /** The seller's store order behind `no` moves PACKED → shipped → DELIVERED, by the seller's real steps. */
  const deliver = (no) => partOf(SELLER_A, no, 'ACCEPTED').then((so) => {
    const store = so.storeOrderId
    post('/updateOrderStatus', { id: store, status: 'PACKED' })
    get(`/getOrder?id=${store}`).then((r) => {
      const l = data(r.body).items[0]
      post('/shipOrder', { id: store, lines: [{ orderItemId: l.id, quantity: l.quantity }], carrier: 'Own rider', trackingNumber: `RD-${run}` })
    })
    return post('/updateOrderStatus', { id: store, status: 'DELIVERED' }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  })
  /** The operator's row for a seller (30 days unless told). */
  const score = (org, days = 30) => {
    cy.loginAsOperator()
    return get(PERF.operator(days)).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      return data(r.body).sellers.find((x) => x.organizationId === org)
    })
  }
  /** Answers every order still waiting for `email`, so the sweeper cannot expire one mid-case. */
  const answerWaiting = (email) => {
    asSeller(email)
    return get(`${API.incomingOrders}?status=OFFERED&size=100`).then((r) => {
      list(r.body).forEach((so) => post(API.rejectOrder, { id: so.id, version: so.version, reason: 'gate cleanup',
        cause: 'PLATFORM_SYNC_DEFECT' }))
    })
  }
  const pct = (part, whole) => `${Math.round((part / whole) * 100)}% (${part} of ${whole})`

  before(() => {
    let p
    seedPolicies(run).then((x) => { p = x })
    cy.loginAsOperator()
    post(API.acceptWindow, { minutes: 5 })
    reroute(false)
    // two sellers, ONE product, everything equal: price, promise, policies. Only acceptance history can order them.
    // The seller that accepts more publishes SECOND, so its offer id is the larger: the id alone would list it last.
    cy.loginAsOperator()
    get(PERF.operator(30)).then((r) => {
      const rate = (n) => {
        const s = data(r.body).sellers.find((x) => x.sellerName === n)
        return s && s.acceptanceRate !== null ? s.acceptanceRate : 1
      }
      bFirst = rate('Mobile Distributor') < rate('Shahzad Mobile Shop')
    })
    const pubA = () => publishOffer(SELLER_A, { run, price: PRICE, promiseHours: 24, qty: 40, warrantyPolicyId: p.warranty,
      returnPolicyId: p.returns, mktProductId: b && b.mktProductId }).then((o) => { a = o })
    const pubB = () => publishOffer(SELLER_B, { run: `${run}b`, price: PRICE, promiseHours: 24, qty: 40, mktProductId: a && a.mktProductId,
      warrantyPolicyId: p.warranty, returnPolicyId: p.returns }).then((o) => { b = o })
    cy.then(() => (bFirst ? pubB() : pubA()))
    cy.then(() => (bFirst ? pubA() : pubB()))
    cy.then(() => get(`${API.publicOffers((a || b).mktProductId)}?city=Karachi`)).then((r) => {
      const ra = list(r.body).find((x) => x.offerId === a.offerId)
      const rb = list(r.body).find((x) => x.offerId === b.offerId)
      Object.assign(a, { org: ra.sellerOrganizationId }); aName = ra.sellerName
      Object.assign(b, { org: rb.sellerOrganizationId }); bName = rb.sellerName
      orgA = a.org; orgB = b.org
    })
    answerWaiting(SELLER_A)
    answerWaiting(SELLER_B)
  })

  after(() => reroute(false))

  it('MKT-2e-01 [MKT-R20.3] the operator opens Seller performance: every seller with orders, the same figures as the service (real UI)', () => {
    let api
    score(orgA).then((s) => { api = s })
    cy.visit(UI.operatorPage)
    cy.intercept('GET', '**/platform/mkt/sellerPerformance*').as('perf')
    cy.get('#platMktPerformanceBtn').should('be.visible').click()
    cy.wait('@perf').its('response.body.data.days').should('eq', 30)
    cy.get('#platMktPerformance').should('be.visible')
    cy.get('#platMktPerfDays button.is-on').should('have.attr', 'data-days', '30')
    cy.then(() => cy.get(`#mktPerfList .mkt-perf-row[data-org="${orgA}"]`)).within(() => {
      cy.root().should('contain', aName)
      cy.then(() => {
        const decided = api.accepted + api.missed
        cy.get('.mkt-perf-accept').should('have.text', decided >= 5 ? pct(api.accepted, decided) : 'Not enough orders yet')
        cy.get('.mkt-perf-missed').should('contain', String(api.missed))
        cy.get('.mkt-perf-returns').should('have.text', String(api.sellerFaultReturns))
        if (api.flags.length) cy.get('.mkt-perf-flag').should('have.length', api.flags.length)
        else cy.get('.mkt-perf-flags').should('have.text', 'Nothing')
      })
    })
    cy.then(() => cy.get(`#mktPerfList .mkt-perf-row[data-org="${orgB}"]`)).should('contain', bName)
    // flagged sellers come first
    cy.get('#mktPerfList .mkt-perf-row').then(($rows) => {
      const flagged = [...$rows].map((r) => (r.getAttribute('data-flags') || '') !== '')
      expect(flagged.indexOf(false) === -1 || flagged.slice(flagged.indexOf(false)).every((f) => !f), 'flagged first').to.eq(true)
    })
    cy.intercept('GET', '**/platform/mkt/sellerPerformance?days=7').as('perf7')
    cy.get('#platMktPerfDays button[data-days="7"]').click()
    cy.wait('@perf7').its('response.statusCode').should('eq', 200)
    cy.get('#platMktPerfDays button.is-on').should('have.attr', 'data-days', '7')
  })

  it('MKT-2e-02 [MKT-R20.3] an accepted order counts as accepted; a rejection the seller caused counts as missed', () => {
    let before, o1, o2
    order(a).then((x) => { o1 = x })
    order(a).then((x) => { o2 = x })
    cy.then(() => score(orgA)).then((s) => { before = s })
    cy.then(() => accept(o1.orderNo))
    cy.then(() => reject(o2.orderNo, 'MERCHANT_STALE_STOCK'))
    cy.then(() => score(orgA)).then((s) => {
      expect(s.accepted - before.accepted, 'accepted +1').to.eq(1)
      expect(s.missed - before.missed, 'missed +1').to.eq(1)
      expect(s.excused - before.excused, 'nothing excused').to.eq(0)
      expect(s.avgMinutesToAccept, 'time to accept is measured').to.be.a('number')
    })
    cy.then(() => Cypress.env('m2e', { delivered: o1, missed: o2 }))
  })

  it('MKT-2e-03 [MKT-R11.4] [MKT-R20.3] a rejection someone else caused is not counted against the seller', () => {
    let before, o
    order(a).then((x) => { o = x })
    cy.then(() => score(orgA)).then((s) => { before = s })
    cy.then(() => reject(o.orderNo, 'SUPPLIER_STALE_STOCK'))
    cy.then(() => score(orgA)).then((s) => {
      expect(s.missed - before.missed, 'not a miss').to.eq(0)
      expect(s.excused - before.excused, 'excused +1').to.eq(1)
      expect(s.accepted - before.accepted).to.eq(0)
    })
  })

  it('MKT-2e-04 [MKT-R12.4] [MKT-R11.4] a disputed miss is not used until decided; overturned, it is excused; no money moves', () => {
    const { missed: o } = Cypress.env('m2e')
    let before, sh, balance
    cy.loginAsOperator()
    get(`/platform/mkt/settlementAccount?organizationId=${orgA}`).then((r) => { balance = data(r.body).balance })
    cy.then(() => score(orgA)).then((s) => { before = s })
    cy.then(() => partOf(SELLER_A, o.orderNo, 'REJECTED')).then((so) => {
      sh = so.shortage
      expect(sh.status).to.eq('RECORDED')
      return post(PERF.dispute, { id: sh.id, note: `Stock was there ${run}` })
    }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => score(orgA)).then((s) => {
      expect(s.missed - before.missed, 'disputed: no longer a miss').to.eq(-1)
      expect(s.disputed - before.disputed, 'shown as under dispute').to.eq(1)
    })
    cy.loginAsOperator()
    cy.then(() => post(PERF.decide, { id: sh.id, outcome: 'OVERTURNED', note: 'The listing was late; not the seller\'s fault.' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => score(orgA)).then((s) => {
      expect(s.missed - before.missed).to.eq(-1)
      expect(s.disputed - before.disputed, 'decided').to.eq(0)
      expect(s.excused - before.excused, 'overturned: excused').to.eq(1)
    })
    cy.loginAsOperator()
    cy.then(() => get(`/platform/mkt/settlementAccount?organizationId=${orgA}`)).then((r) =>
      expect(data(r.body).balance, 'a record, never a charge (R12.4)').to.eq(balance))
  })

  it('MKT-2e-05 [MKT-R20.3] delivered inside its promise counts as on time', () => {
    const { delivered: o } = Cypress.env('m2e')
    let before
    score(orgA).then((s) => { before = s })
    cy.then(() => deliver(o.orderNo))
    cy.then(() => score(orgA)).then((s) => {
      expect(s.due - before.due, 'due +1').to.eq(1)
      expect(s.onTime - before.onTime, 'on time +1').to.eq(1)
    })
  })

  it('MKT-2e-06 [MKT-R20.3] [MKT-R22.1] 7, 30 or 90 days; the operator\'s scorecard is the operator\'s; a seller sees only its own', () => {
    cy.loginAsOperator()
    ;[7, 30, 90].forEach((d) => get(PERF.operator(d)).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      expect(data(r.body).days).to.eq(d)
    }))
    get(PERF.operator(365)).then((r) => expectRefused(r, 'Choose 7, 30 or 90 days.'))
    let op
    score(orgA).then((s) => { op = s })
    asSeller(SELLER_A)
    get(PERF.operator(30)).then((r) => expect(ok(r.body), 'a seller cannot read every seller').to.eq(false))
    get(PERF.mine(30)).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      const rows = data(r.body).sellers
      expect(rows.map((x) => x.organizationId), 'its own row only').to.deep.eq([orgA])
      const mine = rows[0]
      ;['accepted', 'missed', 'disputed', 'excused', 'due', 'onTime', 'sellerFaultReturns', 'acceptanceRate', 'onTimeRate']
        .forEach((k) => expect(mine[k], `${k}: the operator's figure`).to.eq(op[k]))
    })
    asSeller(SELLER_B)
    get(PERF.mine(30)).then((r) => expect(data(r.body).sellers.map((x) => x.organizationId), 'positive control: B sees B').to.deep.eq([orgB]))
  })

  it('MKT-2e-07 [MKT-R20.3] the seller reads its own scorecard on the Marketplace page (real UI)', () => {
    let mine
    asSeller(SELLER_A)
    get(PERF.mine(30)).then((r) => { mine = data(r.body).sellers[0] })
    cy.visit('/businessDashboard')
    cy.intercept('GET', '**/mkt/myPerformance*').as('mine')
    cy.get('#snavSell .snav-btn').click()
    cy.get(UI.sellerNav).should('be.visible').click()
    cy.wait('@mine')
    cy.get('#mktPerfBox').should('be.visible').and('contain', 'Your performance')
    cy.then(() => {
      const decided = mine.accepted + mine.missed
      cy.get('#mktPerfAccept').should('have.text', mine.acceptanceRate === null ? 'Not enough orders yet' : pct(mine.accepted, decided))
      cy.get('#mktPerfOnTime').should('have.text', mine.onTimeRate === null ? 'Not enough orders yet' : pct(mine.onTime, mine.due))
      cy.get('#mktPerfReturns').should('have.text', String(mine.sellerFaultReturns))
      cy.get('#mktPerfFlags .mkt-perf-flag').should('have.length', mine.flags.length)
      if (mine.flags.includes('LOW_ACCEPTANCE')) cy.get('#mktPerfFlags').should('contain', 'MaxTheService has noted: Accepts fewer than 80% of its orders.')
    })
  })

  it('MKT-2e-08 [MKT-R20.3] two offers equal in everything else: the seller that accepts more of its orders is listed first', () => {
    let ra, rb
    score(orgA).then((s) => { ra = s.acceptanceRate === null ? 1 : s.acceptanceRate })   // opening the 30-day scorecard refreshes the ranking
    score(orgB).then((s) => { rb = s.acceptanceRate === null ? 1 : s.acceptanceRate })
    cy.then(() => {
      expect(ra, 'positive control: the two sellers\' acceptance differs').not.to.eq(rb)
      const first = ra > rb ? a.offerId : b.offerId
      const second = ra > rb ? b.offerId : a.offerId
      expect(first, 'positive control: by offer id alone the better seller would be listed second').to.be.greaterThan(second)
      get(`${API.publicOffers(a.mktProductId)}?city=Karachi`).then((r) => {
        const ids = list(r.body).map((x) => x.offerId).filter((id) => id === a.offerId || id === b.offerId)
        expect(ids, `${ra > rb ? aName : bName} accepts more (${Math.round(Math.max(ra, rb) * 100)}% vs ${Math.round(Math.min(ra, rb) * 100)}%)`)
          .to.deep.eq([first, second])
      })
      get(`${API.publicOffers(a.mktProductId)}?city=Karachi&sort=LOWEST_PRICE`).then((r) => {
        const ids = list(r.body).map((x) => x.offerId).filter((id) => id === a.offerId || id === b.offerId)
        expect(ids, 'the same price: the tie-break still decides').to.deep.eq([first, second])
      })
    })
  })
})
