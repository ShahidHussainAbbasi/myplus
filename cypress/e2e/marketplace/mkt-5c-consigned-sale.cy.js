/**
 * MKT-5c — the MaxTheService warehouse sells consigned stock; the owner is paid on sell-through.
 * Source R2.1 (consignee = holder/seller; ownership and payment change on sale per agreement), R4.5 (CONSIGNMENT: owner =
 * consignor, custodian = consignee), R15.4 (consignment settles on sell-through, after the return period), R20.5.
 * Contract: microservices/docs/slices/mkt-5c-consigned-sale.md
 *
 * The listing is a CONSIGNMENT offer of the warehouse, owned by the consignor, priced by the party the agreement names
 * (here the consignor: priceSetBy CONSIGNOR). Shoppers see MaxTheService. When the warehouse accepts an order, the units
 * leave the owner's books (its sale to MaxTheService at the price less the agreement's commission) and enter the
 * warehouse's (its purchase), then the warehouse's sale to the shopper takes them. Delivered, the line settles to the
 * OWNER after the return window.
 *
 * Mobile Distributor (owner.mobile@) is the consignor, as in 5b. Each run lists its own product, consigns 3 of it and
 * sells from those 3, so the counts a run asserts on are its own.
 */
const { gate, uniq, SELLER_B, API, UI, ok, data, list, post, get, expectRefused, seedPolicies, publishOffer, makeSeller,
  openMarketplace } = require('./mkt-helpers')

const WAREHOUSE = 'owner.warehouse@myplus.com'
const OWNER = 'Mobile Distributor'
const PRICE = 52000
const T = { view: '/mkt/transfers', offer: '/mkt/transferOffer', receive: '/mkt/transferReceive', list: '/mkt/consignedList' }
const CA = { list: '/platform/mkt/consignments', offer: '/platform/mkt/consignmentOffer', opDecide: '/platform/mkt/consignmentDecision',
  decide: '/mkt/consignmentDecision' }
const TERMS = { ownershipTransfer: 'ON_SALE', priceSetBy: 'CONSIGNOR', commissionPct: 10, settleEveryDays: 7, reconcileEveryDays: 30,
  countEveryDays: 30, shrinkageTolerancePct: 1, damageBearer: 'CONSIGNEE', expiryBearer: 'CONSIGNOR', insuredBy: 'CONSIGNEE',
  claimWindowDays: 14, proofRequired: true, unsoldReturnDays: 180, unsoldReturnBearer: 'CONSIGNOR' }

gate('5c')('MKT-5c — MaxTheService sells consigned stock; the owner is paid on sell-through', () => {
  const run = uniq()
  const S = { placed: [] }

  const as = (email) => cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')
  const view = () => get(T.view).then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body) })
  const held = () => view().then((v) => (v.held || []).find((h) => h.mktProductId === S.mkt))
  const heldRow = () => `#mktHeld tr.mkt-held[data-product="${S.mkt}"]`
  const level = () => cy.request('/productStockLevels').then((r) => {
    expect(r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)
    const l = (r.body.levels || {})[String(S.ownerProduct)]
    expect(l, 'inventory reports a level for the owner\'s product').to.exist
    return { onHand: Number(l.onHand), sellable: Number(l.sellable != null ? l.sellable : l.onHand) }
  })
  const purchases = () => get('/getAllPurchase').then((r) => {
    const rows = (r.body && (r.body.collection || r.body.data)) || []
    return (Array.isArray(rows) ? rows : []).length
  })
  const newPhone = () => `0315${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`
  const order = (qty) => {
    const phone = newPhone()
    cy.clearCookies()
    cy.visit(UI.publicPage)
    return post(API.checkout, { offerId: S.listing, quantity: qty, expectedPrice: PRICE, customerName: 'Sana', customerPhone: phone,
      address: '9 Clifton', city: 'Karachi', idempotencyKey: `m5c-${run}-${Math.random()}` })
      .then((r) => Object.assign({ status: r.status, body: r.body }, ok(r.body) ? data(r.body) : {}, { phone }))
  }
  const part = (orderNo, status) => {
    as(WAREHOUSE)
    return get(`${API.incomingOrders}?status=${status}&size=100`).then((r) => list(r.body).find((x) => x.orderNo === orderNo))
  }
  const publicOffer = () => get(`${API.publicOffers(S.mkt)}?city=Karachi&qty=1`)
    .then((r) => list(r.body).find((o) => o.offerId === S.listing))

  before(() => {
    makeSeller(SELLER_B)
    makeSeller(WAREHOUSE, 'Central Warehouse')
    cy.loginAsOperator()
    cy.orgOf(WAREHOUSE).then((o) => { S.warehouse = o.id })
    cy.then(() => get('/platform/mkt/warehouse')).then((r) => {
      if (data(r.body).organizationId !== S.warehouse) post('/platform/mkt/warehouse', { organizationId: S.warehouse })
    })
    cy.orgOf(SELLER_B).then((o) => { S.owner = o.id })
    // the owner's active agreement, on terms where it sets the price and ownership passes on sale
    cy.then(() => get(`${CA.list}?status=ACTIVE`)).then((r) => {
      const mine = data(r.body).filter((a) => a.consignorOrganizationId === S.owner)
      const fits = mine.find((a) => a.terms ? a.terms.priceSetBy === 'CONSIGNOR' && a.terms.ownershipTransfer === 'ON_SALE'
        : a.priceSetBy === 'CONSIGNOR' && a.ownershipTransfer === 'ON_SALE')
      if (fits) { S.agreement = fits.agreementNo; return }
      cy.then(() => post(CA.offer, { consignorOrganizationId: S.owner, terms: TERMS, note: `gate 5c ${run}` })).then((o) => {
        expect(ok(o.body), JSON.stringify(o.body)).to.eq(true)
        const a = data(o.body)
        S.agreement = a.agreementNo
        as(SELLER_B)
        post(CA.decide, { id: a.id, action: 'ACCEPT', version: a.version }).then((d) => expect(ok(d.body), JSON.stringify(d.body)).to.eq(true))
      })
    })
    // a phone both carry; the owner has 5 and consigns 3, which the warehouse receives
    seedPolicies(run).then((p) => {
      S.policies = p
      cy.loginAsOperator()
      cy.then(() => publishOffer(WAREHOUSE, { run, price: PRICE, qty: 1, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))
        .then((o) => { S.mkt = o.mktProductId })
      cy.then(() => publishOffer(SELLER_B, { run, price: PRICE, qty: 5, mktProductId: S.mkt, warrantyPolicyId: p.warranty,
        returnPolicyId: p.returns })).then((o) => { S.ownerProduct = o.sourceProductId })
    })
    as(SELLER_B)
    cy.then(() => post(T.offer, { mktProductId: S.mkt, quantity: 3, unitPrice: 40000, kind: 'CONSIGN', note: `5c ${run}` })).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      S.transfer = data(r.body)
    })
    as(WAREHOUSE)
    cy.then(() => post(T.receive, { transferNo: S.transfer.transferNo, version: S.transfer.version }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  })

  after(() => {
    as(WAREHOUSE)
    get(`${API.incomingOrders}?status=OFFERED&size=100`).then((r) => list(r.body)
      .filter((x) => S.placed.includes(x.orderNo))
      .forEach((x) => post(API.rejectOrder, { id: x.id, version: x.version, reason: 'gate cleanup' })))
  })

  it('MKT-5c-01 [MKT-R2.1] under terms where the owner sets the price, the warehouse cannot list it; nobody lists consigned stock from the offer form', () => {
    as(WAREHOUSE)
    openMarketplace()
    cy.then(() => cy.get(heldRow()).should('contain', OWNER).find('.mkt-held-listing').should('have.text', 'Not listed'))
    cy.then(() => cy.get(heldRow()).find('.mkt-held-list').should('not.exist'))
    cy.then(() => post(T.list, { consignorOrgId: S.owner, mktProductId: S.mkt, price: PRICE, deliveryAreas: 'Karachi', promiseHours: 48,
      warrantyPolicyId: S.policies.warranty, returnPolicyId: S.policies.returns }))
      .then((r) => expectRefused(r, `Under ${S.agreement}, ${OWNER} sets the selling price.`))
    as(SELLER_B)
    cy.then(() => post(API.saveOffer, { mktProductId: S.mkt, stockSourceType: 'CONSIGNMENT', marketplacePrice: PRICE,
      deliveryAreas: 'Karachi', promiseHours: 48 }))
      .then((r) => expectRefused(r, 'Consigned stock is listed and priced under Consigned stock at MaxTheService, not on this form.'))
  })

  it('MKT-5c-02 [MKT-R2.1] [MKT-R4.5] the owner lists it in the real form; shoppers see MaxTheService selling 3, never the owner', () => {
    as(SELLER_B)
    openMarketplace()
    cy.then(() => cy.get(heldRow()).find('.mkt-held-list').should('have.text', 'List for sale').click())
    cy.get('#mktListForm').should('be.visible')
    cy.get('#mktListPrice').clear().type(String(PRICE))
    cy.get('#mktListArea').clear().type('Karachi')
    cy.get('#mktListHours').clear().type('48')
    cy.then(() => {
      cy.get('#mktListWarranty').select(String(S.policies.warranty), { force: true })
      cy.get('#mktListReturn').select(String(S.policies.returns), { force: true })
    })
    cy.get('#mktListSaveBtn').click()
    cy.then(() => cy.get('#mktTransferMsg').should('have.text',
      `Listed for sale by MaxTheService at Rs 52,000.00, under ${S.agreement}. Shoppers see 3 available.`))
    cy.get('#mktListForm').should('not.be.visible')
    cy.then(() => cy.get(heldRow()).find('.mkt-held-listing').should('contain', '52,000').and('contain', 'a unit, 3 available'))
    cy.then(() => held()).then((h) => {
      expect(h.listing.live, 'live at once: the agreement is MaxTheService\'s approval').to.eq(true)
      S.listing = h.listing.offerId
    })
    cy.clearCookies()
    cy.then(() => publicOffer()).then((o) => {
      expect(o, 'the listing is in the public offers').to.exist
      expect(o.sellerName).to.eq('MaxTheService')
      expect(o.soldByMaxTheService).to.eq(true)
      expect(Number(o.availableQty)).to.eq(3)
      expect(JSON.stringify(o), 'the owner is never a public field').not.to.contain(OWNER)
    })
  })

  it('MKT-5c-03 [MKT-R20.5] a shopper buys 1: the listing can promise 2 more, and no more than that', () => {
    order(1).then((o) => {
      expect(ok(o.body), JSON.stringify(o.body)).to.eq(true)
      S.first = o
      S.placed.push(o.orderNo)
      expect(o.lines[0].stockSourceType).to.eq('CONSIGNMENT')
    })
    cy.then(() => publicOffer()).then((o) => expect(Number(o.availableQty), 'held 3, promised 1').to.eq(2))
    cy.then(() => order(3)).then((o) => expectRefused({ status: o.status, body: o.body }, 'cannot deliver 3 to Karachi right now'))
    cy.then(() => part(S.first.orderNo, 'OFFERED')).then((p) => {
      expect(p.lines[0].commissionBasis, 'the agreement\'s commission').to.eq('ITEMS')
      expect(Number(p.lines[0].commissionRate)).to.eq(0.1)
    })
  })

  it('MKT-5c-04 [MKT-R2.1] [MKT-R15.4] the warehouse accepts (real UI): the unit leaves the owner\'s books and enters the warehouse\'s, then is sold', () => {
    as(SELLER_B)
    cy.then(() => level()).then((l) => { S.before = l })
    as(WAREHOUSE)
    cy.then(() => purchases()).then((n) => { S.purchasesBefore = n })
    cy.visit('/businessDashboard')
    cy.get('#snavSell .snav-btn').click()
    cy.get(UI.sellerNav).click()
    cy.then(() => cy.contains(`${UI.incoming} tr`, S.first.orderNo).find(UI.acceptBtn).click())
    cy.then(() => cy.contains(`${UI.incoming} tr`, S.first.orderNo).find(UI.acceptBtn).should('not.exist'))
    cy.then(() => part(S.first.orderNo, 'ACCEPTED')).then((p) => {
      expect(p, 'accepted').to.exist
      expect(p.invoiceNo, 'the warehouse\'s own sale to the shopper').to.match(/\S/)
      S.accepted = p
    })
    cy.then(() => purchases()).then((n) => expect(n, 'the warehouse bought the unit from the owner').to.eq(S.purchasesBefore + 1))
    as(SELLER_B)
    cy.then(() => level()).then((l) => {
      expect(l.onHand, 'sold out of the owner\'s books').to.eq(S.before.onHand - 1)
      expect(l.sellable, 'the other 2 stay set aside; the shop\'s free stock is unchanged').to.eq(S.before.sellable)
    })
    cy.then(() => held()).then((h) => {
      expect(h.units, 'held at the warehouse').to.eq(2)
      expect(h.sold).to.eq(1)
      expect(h.listing.available).to.eq(2)
    })
  })

  it('MKT-5c-05 [MKT-R15.4] [MKT-R15.3] delivered, the line settles to the OWNER after the return window: price, 10% commission, collected by MaxTheService', () => {
    as(WAREHOUSE)
    cy.then(() => post('/updateOrderStatus', { id: S.accepted.storeOrderId, status: 'PACKED' }))
    cy.then(() => get(`/getOrder?id=${S.accepted.storeOrderId}`)).then((r) => {
      const line = data(r.body).items[0]
      post('/shipOrder', { id: S.accepted.storeOrderId, lines: [{ orderItemId: line.id, quantity: line.quantity }], carrier: 'Own rider',
        trackingNumber: `RID-${run}` })
    })
    cy.then(() => post('/updateOrderStatus', { id: S.accepted.storeOrderId, status: 'DELIVERED' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.loginAsOperator()
    post('/platform/mkt/runSettlement', {}).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    as(SELLER_B)
    cy.then(() => get(`${API.statement}?size=100`)).then((r) => {
      const l = list(r.body).find((x) => x.orderNo === S.first.orderNo)
      expect(l, 'on the owner\'s statement').to.exist
      expect(l.status, 'waiting for the return window').to.eq('PENDING_RETURN_WINDOW')
      expect(Number(l.customerAmount)).to.eq(PRICE)
      expect(Number(l.commission), '10% under the agreement').to.eq(5200)
      expect(Number(l.payable)).to.eq(46800)
      expect(l.collectedBy, 'MaxTheService collected the cash').to.eq('PLATFORM')
    })
    as(WAREHOUSE)
    cy.then(() => get(`${API.statement}?size=100`)).then((r) => expect(list(r.body).map((x) => x.orderNo),
      'nothing on the warehouse\'s own statement').not.to.include(S.first.orderNo))
  })

  it('MKT-5c-06 [MKT-R20.5] an order the warehouse rejects moves nothing: the unit is free to promise again', () => {
    order(1).then((o) => {
      expect(ok(o.body), JSON.stringify(o.body)).to.eq(true)
      S.second = o
      S.placed.push(o.orderNo)
    })
    cy.then(() => publicOffer()).then((o) => expect(Number(o.availableQty)).to.eq(1))
    cy.then(() => part(S.second.orderNo, 'OFFERED')).then((p) =>
      post(API.rejectOrder, { id: p.id, version: p.version, reason: 'gate 5c: not sent' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => publicOffer()).then((o) => expect(Number(o.availableQty), 'back to 2').to.eq(2))
    as(SELLER_B)
    cy.then(() => held()).then((h) => {
      expect(h.units).to.eq(2)
      expect(h.sold, 'still only the one sold').to.eq(1)
    })
  })
})
