/**
 * MKT-3b — the shopper sees where the delivery stands: packed, sent (carrier and tracking), delivered. Source R20.4
 * (platform pick/pack and delivery), R18.5 (the shopper tracks the order). Contract:
 * microservices/docs/slices/mkt-3b-delivery-progress.md
 *
 * Read, never copied: the part's store order status and its parcels (O5b), through the same anonymous tracking proof
 * (number AND phone). The warehouse (MKT-3a) is the stack's; each run lists its own product, and every order placed
 * here is delivered or rejected before the gate ends.
 */
const { gate, uniq, SELLER_A, API, UI, ok, data, list, post, get, seedPolicies, publishOffer, makeSeller } =
  require('./mkt-helpers')

const WAREHOUSE = 'owner.warehouse@myplus.com'
const PRICE = 52000

gate('3b')('MKT-3b — the shopper sees packed, sent and delivered', () => {
  const run = uniq()
  let offer, shopOffer, policies
  const placed = []
  const W = {}
  const S = {}

  const newPhone = () => `0315${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`
  const asWarehouse = () => cy.loginAs(WAREHOUSE, 'Demo@2025!', '/getBusinessDashboardStats')
  const asShop = () => cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
  const order = (of) => {
    const phone = newPhone()
    cy.clearCookies()
    cy.visit(UI.publicPage)
    return post(API.checkout, { offerId: of.offerId, quantity: 1, expectedPrice: PRICE, customerName: 'Ali',
      customerPhone: phone, address: '1 Clifton', city: 'Karachi', idempotencyKey: `m3b-${run}-${Math.random()}` })
      .then((r) => {
        expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
        placed.push(data(r.body).orderNo)
        return Object.assign(data(r.body), { phone })
      })
  }
  const part = (orderNo, status, as) => {
    as()
    return get(`${API.incomingOrders}?status=${status}&size=100`).then((r) => list(r.body).find((x) => x.orderNo === orderNo))
  }
  /** The anonymous tracking view's one part, proven by number and phone (cookies cleared: no session at all). */
  const tracked = (o) => {
    cy.clearCookies()
    return get(API.trackOrder(o.orderNo, o.phone)).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      return data(r.body).sellerOrders[0]
    })
  }
  const page = (o) => {
    cy.clearCookies()
    cy.visit(`${UI.publicPage}?order=${encodeURIComponent(o.orderNo)}&phone=${o.phone}`)
  }
  const accept = (o, as) => {
    as()
    cy.then(() => post(API.acceptOrder, { id: o.sellerOrderId, version: o.sellerOrderVersion }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    return cy.then(() => part(o.orderNo, 'ACCEPTED', as))
  }
  const ship = (p, carrier, tracking) => cy.then(() => get(`/getOrder?id=${p.storeOrderId}`)).then((r) => {
    const line = data(r.body).items[0]
    return post('/shipOrder', { id: p.storeOrderId, lines: [{ orderItemId: line.id, quantity: line.quantity }], carrier,
      trackingNumber: tracking, note: 'back door, fragile' }).then((x) => expect(ok(x.body), JSON.stringify(x.body)).to.eq(true))
  })

  before(() => {
    seedPolicies(run).then((p) => { policies = p })
    makeSeller(WAREHOUSE, 'Central Warehouse')
    cy.loginAsOperator()
    cy.orgOf(WAREHOUSE).then((o) => post('/platform/mkt/warehouse', { organizationId: o.id }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => publishOffer(WAREHOUSE, { run, price: PRICE, promiseHours: 24, qty: 10, warrantyPolicyId: policies.warranty,
      returnPolicyId: policies.returns })).then((o) => { offer = o })
    cy.then(() => publishOffer(SELLER_A, { run: `${run}s`, price: PRICE, promiseHours: 4, qty: 5,
      warrantyPolicyId: policies.warranty, returnPolicyId: policies.returns })).then((o) => { shopOffer = o })
  })

  after(() => {
    ;[[asWarehouse], [asShop]].forEach(([as]) => {
      as()
      get(`${API.incomingOrders}?status=OFFERED&size=100`).then((r) => list(r.body)
        .filter((x) => placed.includes(x.orderNo))
        .forEach((x) => post(API.rejectOrder, { id: x.id, version: x.version, reason: 'gate cleanup' })))
    })
  })

  it('MKT-3b-01 [MKT-R18.5] before its seller accepts, a part carries no delivery facts', () => {
    order(shopOffer).then((o) => { S.waiting = o })
    cy.then(() => tracked(S.waiting)).then((p) => {
      expect(p.status).to.eq('OFFERED')
      expect(p.fulfilment, 'no store order yet').to.eq(null)
      expect(p.parcels).to.deep.eq([])
    })
  })

  it('MKT-3b-02 [MKT-R20.4] [MKT-R18.5] picked at the warehouse: "MaxTheService is packing your order" (real UI)', () => {
    order(offer).then((o) => { W.o = o })
    cy.then(() => accept(W.o, asWarehouse)).then((p) => { W.p = p })
    cy.then(() => tracked(W.o)).then((p) => {
      expect(p.fulfilment).to.eq('NEW')
      expect(p.parcels).to.deep.eq([])
    })
    cy.then(() => page(W.o))
    cy.get(UI.checkoutStatus).should('have.text', 'MaxTheService is packing your order')
    cy.get('#mktOrderDetail').should('have.text', 'Picked and set aside for you. Packing is next. Pay Rs. 52,000 in cash when it arrives.')
  })

  it('MKT-3b-03 [MKT-R20.4] packed: "Packed by MaxTheService" (real UI)', () => {
    asWarehouse()
    cy.then(() => post('/updateOrderStatus', { id: W.p.storeOrderId, status: 'PACKED' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => tracked(W.o)).then((p) => expect(p.fulfilment).to.eq('PACKED'))
    cy.then(() => page(W.o))
    cy.get(UI.checkoutStatus).should('have.text', 'Packed by MaxTheService')
    cy.get('#mktOrderDetail').should('have.text', 'It leaves soon. Pay Rs. 52,000 in cash when it arrives.')
  })

  it('MKT-3b-04 [MKT-R20.4] [MKT-R18.5] sent: the carrier and tracking number, never the invoice or the packer\'s note (real UI)', () => {
    asWarehouse()
    ship(W.p, 'TCS', `TCS-${run}`)
    cy.then(() => tracked(W.o)).then((p) => {
      expect(p.fulfilment).to.eq('SHIPPED')
      expect(p.parcels).to.have.length(1)
      expect(p.parcels[0]).to.include({ carrier: 'TCS', trackingNumber: `TCS-${run}`, status: 'DISPATCHED' })
      expect(p.parcels[0].shipmentNo).to.match(/\S/)
      expect(Object.keys(p.parcels[0]).sort(), 'the shopper\'s fields only')
        .to.deep.eq(['carrier', 'shipmentNo', 'shippedAt', 'status', 'trackingNumber'])
    })
    cy.then(() => page(W.o))
    cy.get(UI.checkoutStatus).should('have.text', 'Shipped by MaxTheService')
    cy.get('#mktOrderDetail').should('have.text', `TCS · tracking TCS-${run}. Pay Rs. 52,000 in cash when it arrives.`)
  })

  it('MKT-3b-05 [MKT-R18.5] delivered: "Delivered" (real UI)', () => {
    asWarehouse()
    cy.then(() => post('/updateOrderStatus', { id: W.p.storeOrderId, status: 'DELIVERED' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => tracked(W.o)).then((p) => {
      expect(p.fulfilment).to.eq('DELIVERED')
      expect(p.deliveredAt).to.match(/\S/)
    })
    cy.then(() => page(W.o))
    cy.get(UI.checkoutStatus).should('have.text', 'Delivered')
    cy.get('#mktOrderDetail').should('have.text', '')        // never "will deliver and collect" once it is delivered
  })

  it('MKT-3b-06 [MKT-R18.5] a shop\'s order: "Confirmed by" as before, then "On its way from" the shop with its carrier (real UI)', () => {
    order(shopOffer).then((o) => { S.o = o })
    cy.then(() => accept(S.o, asShop)).then((p) => { S.p = p })
    cy.then(() => page(S.o))
    cy.get(UI.checkoutStatus).should('have.text', 'Confirmed by Shahzad Mobile Shop')
    asShop()
    cy.then(() => ship(S.p, 'Own rider', ''))           // S.p is set by the accept above, when the chain runs
    cy.then(() => page(S.o))
    cy.get(UI.checkoutStatus).should('have.text', 'On its way from Shahzad Mobile Shop')
    cy.get('#mktOrderDetail').should('have.text', 'Own rider. Pay Rs. 52,000 in cash when it arrives.')
    asShop()
    cy.then(() => post('/updateOrderStatus', { id: S.p.storeOrderId, status: 'DELIVERED' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  })
})
