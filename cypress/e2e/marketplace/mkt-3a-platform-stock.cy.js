/**
 * MKT-3a — the MaxTheService warehouse sells its own stock. Source R4.2 (PLATFORM: owner MaxTheService, custodian and
 * fulfiller the platform warehouse), R20.4 (platform stock), R10.5 (platform: immediate / until pick), R22.1.
 * Contract: microservices/docs/slices/mkt-3a-platform-stock.md
 *
 * The warehouse is its own seeded tenant (owner.warehouse@), named by the operator. It stays named across runs (it is
 * the stack's warehouse); each run lists a new product of its own, and every order placed here is finished or
 * rejected before the gate ends.
 */
const { gate, uniq, SELLER_A, API, UI, ok, data, list, post, get, expectRefused, seedPolicies, publishOffer, makeSeller } =
  require('./mkt-helpers')

const WAREHOUSE = 'owner.warehouse@myplus.com'
const W = {
  get: '/platform/mkt/warehouse',                 // GET → {organizationId, organizationName, liveOffers, candidates}
  set: '/platform/mkt/warehouse',                 // POST {organizationId | null}
  run: '/platform/mkt/runSettlement',
  accounts: '/platform/mkt/settlementAccounts',
  report: '/platform/mkt/settlementReport',
}
const PRICE = 52000
const DAY = 24 * 60

gate('3a')('MKT-3a — the MaxTheService warehouse sells its own stock', () => {
  const run = uniq()
  let offer, shopOffer, warehouseOrg, sellerAOrg, policies
  const placed = []

  const newPhone = () => `0314${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`
  const asWarehouse = () => cy.loginAs(WAREHOUSE, 'Demo@2025!', '/getBusinessDashboardStats')
  const warehouse = () => {
    cy.loginAsOperator()
    return get(W.get).then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body) })
  }
  const order = (qty = 1, of = offer) => {
    const phone = newPhone()
    cy.clearCookies()
    cy.visit(UI.publicPage)
    return post(API.checkout, { offerId: of.offerId, quantity: qty, expectedPrice: PRICE, customerName: 'Ali',
      customerPhone: phone, address: '1 Clifton', city: 'Karachi', idempotencyKey: `m3a-${run}-${Math.random()}` })
      .then((r) => {
        expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
        placed.push(data(r.body).orderNo)
        return Object.assign(data(r.body), { phone })
      })
  }
  const part = (orderNo, status = 'OFFERED', as = asWarehouse) => {
    as()
    return get(`${API.incomingOrders}?status=${status}&size=100`).then((r) => list(r.body).find((x) => x.orderNo === orderNo))
  }

  before(() => {
    seedPolicies(run).then((p) => { policies = p })
    makeSeller(WAREHOUSE, 'Central Warehouse')
    cy.loginAsOperator()
    cy.orgOf(WAREHOUSE).then((o) => { warehouseOrg = o.id })
    cy.orgOf(SELLER_A).then((o) => { sellerAOrg = o.id })
    // a shop's offer: the control for "the run settles a delivered line", and a product the shop has matched
    cy.then(() => publishOffer(SELLER_A, { run: `${run}s`, price: PRICE, promiseHours: 4, qty: 5,
      warrantyPolicyId: policies.warranty, returnPolicyId: policies.returns })).then((o) => { shopOffer = o })
  })

  after(() => {
    asWarehouse()
    get(`${API.incomingOrders}?status=OFFERED&size=100`).then((r) => list(r.body)
      .filter((x) => placed.includes(x.orderNo))
      .forEach((x) => post(API.rejectOrder, { id: x.id, version: x.version, reason: 'gate cleanup' })))
  })

  it('MKT-3a-01 [MKT-R4.2] [MKT-R20.4] the operator names the warehouse in Marketplace policies (real UI); a shop with offers is refused', () => {
    cy.loginAsOperator()
    post(W.set, { organizationId: sellerAOrg }).then((r) =>
      expectRefused(r, 'This organisation already has offers of its own. Choose one with none: its offers would become MaxTheService\'s.'))
    cy.visit('/platformDashboard')
    cy.get('#platMktPoliciesBtn').click()
    cy.get('#mktWarehouseOrg option').should('have.length.at.least', 2)
    cy.get('#mktWarehouseOrg').select(String(warehouseOrg))
    cy.get('#mktWarehouseSave').click()
    cy.get('#mktWarehouseMsg').should('contain', 'is the MaxTheService warehouse. Its offers read "Sold and shipped by MaxTheService".')
    warehouse().then((w) => {
      expect(w.organizationId).to.eq(warehouseOrg)
      expect(w.candidates.map((c) => c.organizationId), 'a shop with offers is never offered').not.to.include(sellerAOrg)
    })
  })

  it('MKT-3a-02 [MKT-R4.2] [MKT-R5.3] the warehouse\'s offer is PLATFORM stock and reads "Sold and shipped by MaxTheService" (real UI)', () => {
    publishOffer(WAREHOUSE, { run, price: PRICE, promiseHours: 24, qty: 20, warrantyPolicyId: policies.warranty,
      returnPolicyId: policies.returns }).then((o) => { offer = o })
    asWarehouse()
    cy.then(() => get(API.getOffer(offer.offerId))).then((r) => expect(data(r.body).stockSourceType).to.eq('PLATFORM'))
    cy.then(() => get(`${API.publicOffers(offer.mktProductId)}?city=Karachi`)).then((r) => {
      const o = list(r.body).find((x) => x.offerId === offer.offerId)
      expect(o.sellerName, 'never the warehouse tenant\'s own name').to.eq('MaxTheService')
      expect(o.soldByMaxTheService).to.eq(true)
    })
    cy.clearCookies()
    cy.then(() => cy.visit(`${UI.publicPage}?product=${offer.mktProductId}&city=Karachi`))
    cy.then(() => cy.get(`${UI.offerRow}[data-offer-id="${offer.offerId}"]`)
      .should('contain', 'MaxTheService').and('contain', 'Sold and shipped by MaxTheService'))
  })

  it('MKT-3a-03 [MKT-R4.2] [MKT-R22.1] no other seller can list MaxTheService\'s own stock; a seller cannot name the warehouse', () => {
    // its OWN offer, for a product it has matched: the refusal is about the stock source, nothing else
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.saveOffer, { id: shopOffer.offerId, stockSourceType: 'PLATFORM' })
      .then((r) => expectRefused(r, 'Only the MaxTheService warehouse sells MaxTheService\'s own stock.'))
    get(API.getOffer(shopOffer.offerId)).then((r) => expect(data(r.body).stockSourceType).to.eq('MERCHANT'))
    get(W.get).then((r) => expectRefused(r))
    post(W.set, { organizationId: null }).then((r) => expectRefused(r))
    warehouse().then((w) => expect(w.organizationId).to.eq(warehouseOrg))
  })

  it('MKT-3a-04 [MKT-R10.5] [MKT-R4.2] the shopper is told MaxTheService is packing it; the warehouse has 24 hours to pick (real UI)', () => {
    order(1).then((o) => {
      part(o.orderNo).then((p) => {
        expect(Math.round((new Date(p.acceptBy) - new Date(p.createdAt)) / 60000), 'the PLATFORM hold').to.eq(DAY)
      })
      cy.clearCookies()
      cy.visit(`${UI.publicPage}?order=${encodeURIComponent(o.orderNo)}&phone=${o.phone}`)
      cy.get(UI.checkoutStatus).should('contain', 'MaxTheService is packing your order')
      cy.get('#mktOrderDetail').invoke('text')
        .should('match', /In stock at MaxTheService and set aside for you\. Packed within (24:00:00|23:[0-5]\d:[0-5]\d)\./)
      // the warehouse's screen: the same Incoming orders, with hours on the clock
      asWarehouse()
      cy.visit('/businessDashboard')
      cy.get('#snavSell .snav-btn').click()
      cy.get(UI.sellerNav).click()
      cy.contains(`${UI.incoming} tr`, o.orderNo).find(UI.countdown).invoke('text').should('match', /^(24:00:00|23:[0-5]\d:[0-5]\d)$/)
    })
  })

  it('MKT-3a-05 [MKT-R4.2] [MKT-R20.4] picked: the sale is in the warehouse\'s books; delivered, it is never settled (no commission, no payout)', () => {
    const asShop = () => cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    /** Accept (the pick), pack, ship and deliver, as the part's own seller; yields the accepted part. */
    const fulfil = (o, as) => {
      let p
      as()
      cy.then(() => post(API.acceptOrder, { id: o.sellerOrderId, version: o.sellerOrderVersion }))
        .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
      cy.then(() => part(o.orderNo, 'ACCEPTED', as)).then((x) => { p = x })
      cy.then(() => post('/updateOrderStatus', { id: p.storeOrderId, status: 'PACKED' }))
      cy.then(() => get(`/getOrder?id=${p.storeOrderId}`)).then((r) => {
        const line = data(r.body).items[0]
        post('/shipOrder', { id: p.storeOrderId, lines: [{ orderItemId: line.id, quantity: line.quantity }], carrier: 'Own rider',
          trackingNumber: `RID-${run}` })
      })
      cy.then(() => post('/updateOrderStatus', { id: p.storeOrderId, status: 'DELIVERED' }))
        .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
      return cy.then(() => p)
    }
    let own, shop
    order(1).then((x) => { own = x })
    cy.then(() => fulfil(own, asWarehouse)).then((p) => expect(p.invoiceNo, 'the sale is in the warehouse\'s own books').to.match(/\S/))
    cy.then(() => get(API.trackOrder(own.orderNo, own.phone))).then((r) => expect(data(r.body).status).to.eq('CONFIRMED'))
    cy.then(() => order(1, shopOffer)).then((x) => { shop = x })
    cy.then(() => fulfil(shop, asShop))
    cy.loginAsOperator()
    post(W.run, {}).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    // the control: the same run moved the shop's delivered line on; the warehouse's line it never touched
    cy.then(() => part(shop.orderNo, 'ACCEPTED', asShop)).then((p) =>
      expect(p.lines[0].settlementStatus, 'the run reached delivered lines').to.eq('PENDING_RETURN_WINDOW'))
    cy.then(() => part(own.orderNo, 'ACCEPTED')).then((p) =>
      expect(p.lines[0].settlementStatus, 'MaxTheService\'s own sale is not settled').to.eq('NOT_ELIGIBLE'))
    cy.loginAsOperator()
    get(W.accounts).then((r) => expect(list(r.body).map((a) => a.organizationId), 'no ledger account for the warehouse')
      .not.to.include(warehouseOrg))
    get(W.report).then((r) => expect(data(r.body).rows.map((x) => x.organizationId)).not.to.include(warehouseOrg))
    asWarehouse()
    cy.then(() => get(`${API.statement}?size=100`)).then((r) => expect(list(r.body).map((l) => l.orderNo),
      'nothing owed to or by itself').not.to.include(own.orderNo))
  })

  it('MKT-3a-06 [MKT-R4.2] the warehouse cannot be changed or removed while its offer is live', () => {
    cy.loginAsOperator()
    post(W.set, { organizationId: null }).then((r) =>
      expectRefused(r, 'The warehouse has offers live or waiting for approval. Suspend them before removing the warehouse.'))
    warehouse().then((w) => {
      expect(w.organizationId).to.eq(warehouseOrg)
      expect(w.liveOffers).to.be.at.least(1)
    })
  })
})
