/**
 * MKT-3d — returns of MaxTheService's own stock. Source R4.2 (the platform warehouse is custodian and fulfiller),
 * R20.4 (central returns), R13.2 (the return path). Contract: microservices/docs/slices/mkt-3d-central-returns.md
 *
 * A return of a warehouse line already lands at the warehouse (its seller): the credit note in its books, the item in
 * its stock. This gate proves what the shopper is told (MaxTheService collects, not "the seller's rider"), and that a
 * written-off item is where the warehouse can see it and dispose of it (Register → Quarantine). And (3d-04) that the
 * operator can page past the first 100 waiting cases, where the newest one sits.
 */
const { gate, uniq, SELLER_A, API, UI, ok, data, list, post, get, seedPolicies, publishOffer, makeSeller } =
  require('./mkt-helpers')

const WAREHOUSE = 'owner.warehouse@myplus.com'
const PW = 'Shop!ng2026'
const PRICE = 52000
const ACC = {
  login: '/marketplace/account/login',
  register: '/marketplace/account/register',
  orders: '/marketplace/account/orders',
  openCase: (no) => `/marketplace/account/orders/${encodeURIComponent(no)}/cases`,
  caseView: (no) => `/marketplace/account/cases/${encodeURIComponent(no)}`,
}
const OPS = { decide: '/platform/mkt/returnDecision' }

gate('3d')('MKT-3d — returns of MaxTheService\'s own stock', () => {
  const run = uniq()
  const phone = (k) => `0316${String(run).slice(-6)}${k}`
  let offer, shopOffer
  const R = {}

  const asCustomer = (ph) => {
    cy.clearCookies()
    cy.visit(UI.publicPage)
    post(ACC.login, { phone: ph, password: PW }).then((r) => {
      if (ok(r.body)) return
      post(ACC.register, { phone: ph, name: 'Ali Raza', password: PW }).then((x) => expect(ok(x.body), JSON.stringify(x.body)).to.eq(true))
    })
  }
  const asSeller = (email) => cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')

  /** Signed in as `ph`: order `of`, its seller accepts, packs, sends and delivers. Yields {orderNo, lineId}. */
  const delivered = (ph, of, seller) => {
    const out = {}
    asCustomer(ph)
    post(API.checkout, { offerId: of.offerId, quantity: 1, expectedPrice: PRICE, customerName: 'Ali Raza', customerPhone: ph,
      address: '1 Clifton', city: 'Karachi', idempotencyKey: `d-${run}-${Math.random()}` }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      Object.assign(out, { orderNo: data(r.body).orderNo, so: data(r.body).sellerOrderId, v: data(r.body).sellerOrderVersion })
    })
    asSeller(seller)
    cy.then(() => post(API.acceptOrder, { id: out.so, version: out.v }).then((a) => expect(ok(a.body), JSON.stringify(a.body)).to.eq(true)))
    cy.then(() => get(`${API.incomingOrders}?status=ACCEPTED&size=100`).then((s) => {
      out.storeOrderId = list(s.body).find((x) => x.orderNo === out.orderNo).storeOrderId
    }))
    cy.then(() => post('/updateOrderStatus', { id: out.storeOrderId, status: 'PACKED' }))
    cy.then(() => get(`/getOrder?id=${out.storeOrderId}`).then((r) => {
      const line = data(r.body).items[0]
      post('/shipOrder', { id: out.storeOrderId, lines: [{ orderItemId: line.id, quantity: line.quantity }], carrier: 'TCS',
        trackingNumber: `D-${run}` })
    }))
    cy.then(() => post('/updateOrderStatus', { id: out.storeOrderId, status: 'DELIVERED' })
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)))
    asCustomer(ph)
    cy.then(() => get(`${ACC.orders}?size=50`).then((r) => {
      out.lineId = list(r.body).find((x) => x.orderNo === out.orderNo).lines[0].id
    }))
    return cy.wrap(out)
  }
  /** The customer asks to return it as faulty; the operator approves. Yields {caseNo, returnNo}. */
  const approvedReturn = (o, ph) => {
    const out = {}
    asCustomer(ph)
    post(ACC.openCase(o.orderNo), { topic: 'RETURN', note: 'screen flickers', lineId: o.lineId, quantity: 1, reason: 'DEFECTIVE' })
      .then((r) => {
        expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
        Object.assign(out, { caseNo: data(r.body).caseNo, returnNo: data(r.body).returns[0].returnNo })
      })
    cy.loginAsOperator()
    cy.then(() => post(OPS.decide, { returnNo: out.returnNo, decision: 'APPROVED' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    return cy.wrap(out)
  }

  before(() => {
    seedPolicies(run).then((p) => {
      makeSeller(WAREHOUSE, 'Central Warehouse')
      cy.loginAsOperator()
      cy.orgOf(WAREHOUSE).then((o) => post('/platform/mkt/warehouse', { organizationId: o.id }))
      cy.then(() => publishOffer(WAREHOUSE, { run, price: PRICE, qty: 10, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))
        .then((o) => { offer = o })
      cy.then(() => publishOffer(SELLER_A, { run: `${run}s`, price: PRICE, qty: 5, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))
        .then((o) => { shopOffer = o })
    })
  })

  it('MKT-3d-01 [MKT-R4.2] [MKT-R20.4] approved, the shopper is told MaxTheService collects its own stock; a shop\'s rider collects the shop\'s', () => {
    delivered(phone(1), offer, WAREHOUSE).then((o) => { R.own = o })
    cy.then(() => approvedReturn(R.own, phone(1))).then((r) => { R.ownRet = r })
    asCustomer(phone(1))
    cy.then(() => get(ACC.caseView(R.ownRet.caseNo))).then((r) => {
      const said = data(r.body).messages.map((m) => m.body).join(' | ')
      expect(said).to.contain(`Your return ${R.ownRet.returnNo} is approved. MaxTheService will collect it from your address.`)
      expect(said).not.to.contain('seller\'s rider')
    })
    delivered(phone(2), shopOffer, SELLER_A).then((o) => { R.shop = o })
    cy.then(() => approvedReturn(R.shop, phone(2))).then((r) => { R.shopRet = r })
    asCustomer(phone(2))
    cy.then(() => get(ACC.caseView(R.shopRet.caseNo))).then((r) => expect(data(r.body).messages.map((m) => m.body).join(' | '))
      .to.contain(`Your return ${R.shopRet.returnNo} is approved. The seller's rider will collect it from your address.`))
  })

  it('MKT-3d-02 [MKT-R20.4] [MKT-R13.2] the warehouse receives it and writes it off: refunded; the item waits in Quarantine, where it is disposed of (real UI)', () => {
    asSeller(WAREHOUSE)
    cy.then(() => get('/quarantineList')).then((r) => { R.before = (data(r.body) || r.body).items.filter((q) => q.productId === offer.sourceProductId).length })
    cy.visit('/businessDashboard')
    cy.get('#snavSell .snav-btn').click()
    cy.get(UI.sellerNav).click()
    cy.get(`.mkt-return[data-return-no="${R.ownRet.returnNo}"] .mkt-outcome`)
      .should('contain', 'Write off: unusable (goes to Quarantine, where you dispose of it)').select('WRITE_OFF')
    cy.get(`.mkt-return[data-return-no="${R.ownRet.returnNo}"] .mkt-cash`).check()
    cy.get(`.mkt-return[data-return-no="${R.ownRet.returnNo}"] .mkt-received`).click()
    cy.get(`.mkt-return[data-return-no="${R.ownRet.returnNo}"]`).should('not.exist')       // a finished return leaves the list
    asCustomer(phone(1))
    cy.then(() => get(ACC.caseView(R.ownRet.caseNo))).then((r) => {
      const ret = data(r.body).returns.find((x) => x.returnNo === R.ownRet.returnNo)
      expect(ret.status).to.eq('REFUNDED')
    })
    asSeller(WAREHOUSE)
    cy.then(() => get('/quarantineList')).then((r) => {
      const now = (data(r.body) || r.body).items.filter((q) => q.productId === offer.sourceProductId)
      expect(now.length, 'the written-off unit is in quarantine').to.eq(R.before + 1)
      R.lot = now.map((q) => q.id).sort((x, y) => y - x)[0]
    })
    // the screen it is disposed from, which a shop without batch or expiry tracking never had
    cy.visit('/businessDashboard')
    cy.get('#snavRegister .snav-btn').click()
    cy.get('#navQuarantine').should('be.visible').click()
    cy.get('#QuarantineDiv').should('be.visible')
    cy.then(() => cy.get(`#disp_${R.lot}`).click())
    cy.get('.uiC-ok').click()
    cy.then(() => cy.get(`#disp_${R.lot}`).should('not.exist'))
    cy.then(() => get('/quarantineList')).then((r) => expect((data(r.body) || r.body).items.map((q) => q.id), 'disposed')
      .not.to.include(R.lot))
  })

  it('MKT-3d-03 [MKT-R13.2] the shop\'s return closes the same way (control)', () => {
    asSeller(SELLER_A)
    cy.then(() => post('/mkt/returnReceived', { returnNo: R.shopRet.returnNo, outcome: 'RESTOCK', cashHandedBack: true }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  })

  it('MKT-3d-04 [MKT-R22.1] the operator reaches every waiting case, not only the first 100 (real UI)', () => {
    cy.loginAsOperator()
    cy.then(() => get('/platform/mkt/cases?size=100&page=0')).then((r) => { R.q = data(r.body) })
    cy.then(() => get(`/platform/mkt/cases?size=100&page=${Math.max(0, R.q.totalPages - 1)}`)).then((r) => {
      const rows = data(r.body).content
      R.newest = rows[rows.length - 1].caseNo     // urgent first, then the longest waiting: the last row of the last page
    })
    cy.visit('/platformDashboard')
    cy.get('#platMktCasesBtn').click()
    cy.get('#mktCaseList tbody tr').should('exist')
    cy.then(() => {
      if (R.q.totalElements <= 100) {
        cy.get('#mktCasePager').should('be.empty')
        cy.get(`#mktCaseList tr[data-case-no="${R.newest}"]`).should('exist')
        return
      }
      cy.get('#mktCasePager span').should('have.text', `1–100 of ${R.q.totalElements}`)
      cy.get('#mktCasePrev').should('be.disabled')
      cy.get(`#mktCaseList tr[data-case-no="${R.newest}"]`).should('not.exist')
      for (let p = 1; p < R.q.totalPages; p++) {
        cy.get('#mktCaseNext').click()
        cy.get('#mktCasePager span').should('have.text',
          `${p * 100 + 1}–${Math.min((p + 1) * 100, R.q.totalElements)} of ${R.q.totalElements}`)
      }
      cy.get('#mktCaseNext').should('be.disabled')
      cy.get(`#mktCaseList tr[data-case-no="${R.newest}"]`).click()
      cy.get(`#mktCaseDetail [data-case-no="${R.newest}"]`).should('be.visible')
      cy.get('#mktCasePrev').click()
      cy.get('#mktCasePager span').should('contain', `of ${R.q.totalElements}`)
    })
  })
})
