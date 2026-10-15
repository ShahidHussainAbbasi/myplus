/**
 * MKT-4a — dropship suppliers: the operator marks an approved seller as a supplier, whose offers are then supplier stock.
 * Source R4.3 (SUPPLIER: owner = custodian = the supplier, which ships), R4.4 (never shown as immediately available unless
 * the supplier's availability is reliable). Ruling 2026-10-15: a supplier is a MyPlus business the operator marks.
 * Contract: microservices/docs/slices/mkt-4a-supplier-offers.md
 *
 * The supplier is its own seeded tenant (owner.supplier@). Each run lists a new phone of its own; the gate unmarks the
 * supplier at the end, so no other gate sees supplier stock, and rejects any order it placed.
 */
const { gate, uniq, SELLER_A, API, UI, ok, data, list, msg, post, get, expectRefused, seedPolicies, publishOffer } =
  require('./mkt-helpers')

const SUPPLIER = 'owner.supplier@myplus.com'
const WAREHOUSE = 'owner.warehouse@myplus.com'
const S_API = { mark: '/platform/mkt/sellerSupplier', warehouse: '/platform/mkt/warehouse' }
const PRICE = 48000

gate('4a')('MKT-4a — a supplier\'s offers are supplier stock, never shown as checked until its counts are trusted', () => {
  const run = uniq()
  const S = {}
  const placed = []

  const asSupplier = () => cy.loginAs(SUPPLIER, 'Demo@2025!', '/getBusinessDashboardStats')
  const account = () => {
    cy.loginAsOperator()
    return get(`${API.sellers}?status=APPROVED&size=100`).then((r) => data(r.body).content.find((a) => a.organizationId === S.org))
  }
  const publicOffer = () => get(`${API.publicOffers(S.offer.mktProductId)}?city=Karachi`)
    .then((r) => list(r.body).find((x) => x.offerId === S.offer.offerId))
  const offerRow = () => `${UI.offerRow}[data-offer-id="${S.offer.offerId}"]`
  const openProduct = () => {
    cy.clearCookies()
    cy.visit(`${UI.publicPage}?product=${S.offer.mktProductId}&city=Karachi`)
    return cy.get(offerRow()).should('be.visible')
  }
  const operatorRow = () => {
    cy.loginAsOperator()
    cy.visit('/platformDashboard')
    cy.get('#platMktSellersBtn').click()
    cy.get('#platMktStatus button[data-status="APPROVED"]').click()
    return cy.get(`#platMktSellerList .plat-row[data-org="${S.org}"]`).should('be.visible')
  }

  before(() => {
    seedPolicies(run).then((p) => {
      // listed as a seller first: marking it is what turns its offers into supplier stock
      cy.then(() => publishOffer(SUPPLIER, { run, price: PRICE, qty: 5, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))
        .then((o) => { S.offer = o })
    })
    cy.loginAsOperator()
    cy.orgOf(SUPPLIER).then((o) => { S.org = o.id })
    cy.orgOf(WAREHOUSE).then((o) => { S.warehouse = o.id })
    // an earlier run that failed half-way may have left it marked
    cy.then(() => post(S_API.mark, { organizationId: S.org, supplier: false }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  })

  after(() => {
    asSupplier()
    get(`${API.incomingOrders}?status=OFFERED&size=100`).then((r) => list(r.body)
      .filter((x) => placed.includes(x.orderNo))
      .forEach((x) => post(API.rejectOrder, { id: x.id, version: x.version, reason: 'gate cleanup' })))
    cy.loginAsOperator()
    cy.then(() => post(S_API.mark, { organizationId: S.org, supplier: false }))
  })

  it('MKT-4a-01 [MKT-R4.3] the operator marks an approved seller as a supplier in Marketplace sellers (real UI)', () => {
    cy.then(() => publicOffer()).then((o) => {
      expect(o.dropship, 'a seller until marked').to.eq(false)
      expect(o.stockConfirmed).to.eq(true)
    })
    operatorRow().within(() => {
      cy.get('.mkt-supplier-tag').should('not.exist')
      cy.get('.mkt-supplier-reliable').should('be.disabled')
      cy.get('.mkt-supplier-on').check()
      cy.get('.mkt-supplier-reliable').should('be.enabled').and('not.be.checked')
      cy.get('.mkt-supplier-save').click()
      cy.get('.mkt-supplier-msg').should('have.text', 'Phone Supplier is a supplier. Customers are told it confirms the stock within 20 minutes of an order.')
    })
    operatorRow().find('.mkt-supplier-tag').should('have.text', 'Supplier')
    account().then((a) => {
      expect(a.supplier).to.eq(true)
      expect(a.supplierStockReliable).to.eq(false)
    })
  })

  it('MKT-4a-02 [MKT-R4.3] [MKT-R4.4] its offer is supplier stock: "Shipped by the supplier", and the stock is not shown as checked (real UI)', () => {
    cy.then(() => publicOffer()).then((o) => {
      expect(o.dropship).to.eq(true)
      expect(o.stockConfirmed, 'counts not trusted yet').to.eq(false)
      expect(o.sellerName).to.eq('Phone Supplier')
    })
    openProduct()
    cy.get(offerRow()).should('contain', 'Phone Supplier').and('contain', 'Shipped by the supplier')
      .and('contain', 'The supplier confirms the stock within 20 minutes of your order')
    cy.get(offerRow()).find('.mkt-stock-checked').should('not.exist')
    asSupplier()
    cy.then(() => get(API.myOffers)).then((r) => {
      const mine = list(r.body).find((x) => x.id === S.offer.offerId)
      expect(mine.stockSourceType).to.eq('SUPPLIER')
    })
  })

  it('MKT-4a-03 [MKT-R4.3] [MKT-R4.4] a customer orders from the supplier; the supplier has 20 minutes to accept it', () => {
    cy.clearCookies()
    cy.visit(UI.publicPage)
    const phone = `0315${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`
    cy.then(() => post(API.checkout, { offerId: S.offer.offerId, quantity: 1, expectedPrice: PRICE, customerName: 'Ali',
      customerPhone: phone, address: '1 Clifton', city: 'Karachi', idempotencyKey: `m4a-${run}` })).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      S.orderNo = data(r.body).orderNo
      placed.push(S.orderNo)
      // R4.4: the supplier's own defined window, 20 minutes, whatever the operator's window for shops
      expect(data(r.body).secondsToAccept, 'the supplier confirms within 20 minutes').to.be.within(19 * 60, 20 * 60)
    })
    cy.then(() => get(API.trackOrder(S.orderNo, phone))).then((r) => {
      const lines = (data(r.body).parts || []).flatMap((p) => p.lines || [])
      const line = lines.length ? lines[0] : (data(r.body).lines || [])[0]
      expect(line && line.stockSourceType, `order line: ${JSON.stringify(data(r.body))}`).to.eq('SUPPLIER')
    })
    asSupplier()
    cy.then(() => get(`${API.incomingOrders}?status=OFFERED&size=100`))
      .then((r) => expect(list(r.body).map((x) => x.orderNo), 'waiting for the supplier to accept').to.include(S.orderNo))
  })

  it('MKT-4a-04 [MKT-R4.4] once the operator trusts its counts, the stock reads as checked (real UI)', () => {
    operatorRow().within(() => {
      cy.get('.mkt-supplier-tag').should('exist')
      cy.get('.mkt-supplier-reliable').check()
      cy.get('.mkt-supplier-save').click()
      cy.get('.mkt-supplier-msg').should('have.text', 'Phone Supplier is a supplier. Customers see its stock as checked.')
    })
    cy.then(() => publicOffer()).then((o) => {
      expect(o.dropship).to.eq(true)
      expect(o.stockConfirmed).to.eq(true)
    })
    openProduct()
    cy.get(offerRow()).should('contain', 'Shipped by the supplier').and('not.contain', 'The supplier confirms the stock')
    cy.get(offerRow()).find('.mkt-stock-checked').should('exist')
  })

  it('MKT-4a-05 [MKT-R4.3] refused in a sentence: the warehouse as a supplier, a supplier as the warehouse, a shop claiming supplier stock, a seller marking itself', () => {
    cy.loginAsOperator()
    cy.then(() => post(S_API.mark, { organizationId: S.warehouse, supplier: true }))
      .then((r) => expectRefused(r, 'The MaxTheService warehouse sells MaxTheService\'s own stock, not a supplier\'s.'))
    cy.then(() => post(S_API.warehouse, { organizationId: S.org }))
      .then((r) => expectRefused(r, 'A supplier sells its own stock, so it cannot be the MaxTheService warehouse.'))
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    cy.then(() => get(API.myOffers)).then((r) => {
      const any = list(r.body)[0]
      expect(any, 'Shahzad Mobile Shop has an offer').to.exist
      return post(API.saveOffer, { id: any.id, mktProductId: any.mktProductId, stockSourceType: 'SUPPLIER', version: any.version })
    }).then((r) => expectRefused(r, 'Only a business MaxTheService has marked as a supplier sells supplier stock.'))
    cy.request({ method: 'POST', url: S_API.mark, body: { organizationId: S.org, supplier: false }, failOnStatusCode: false })
      .then((r) => expect(ok(r.body), `a shop owner cannot reach the operator's mark: ${r.status}`).to.not.eq(true))
  })

  it('MKT-4a-06 [MKT-R4.3] unmarked, its offers are its own stock again (real UI)', () => {
    operatorRow().within(() => {
      cy.get('.mkt-supplier-on').uncheck()
      cy.get('.mkt-supplier-reliable').should('be.disabled').and('not.be.checked')
      cy.get('.mkt-supplier-save').click()
      cy.get('.mkt-supplier-msg').should('have.text', 'Phone Supplier sells its own stock as a seller.')
    })
    cy.then(() => publicOffer()).then((o) => {
      expect(o.dropship).to.eq(false)
      expect(o.stockConfirmed).to.eq(true)
    })
    openProduct()
    cy.get(offerRow()).should('not.contain', 'Shipped by the supplier')
  })
})
