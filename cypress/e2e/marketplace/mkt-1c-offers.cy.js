/**
 * MKT-1c — offers with party roles, policies and approval; the published projection.
 * Source §3, §4.1, §5.3, §7.4–7.5, §9.2–9.3, §14. Run headed: --env mkt=1b,1c
 */
const { gate, uniq, SELLER_A, SELLER_B, API, UI, ok, data, list, post, get, expectRefused, makeSeller, openMarketplace, seedProduct,
  a32 } = require('./mkt-helpers')

gate('1c')('MKT-1c — offers, roles, policies, approval and projection', () => {
  const run = uniq()
  let mktProductId, aProduct, rxProduct, offerId

  before(() => {
    makeSeller(SELLER_A)
    seedProduct({ name: `Offer phone ${run}` }).then((id) => { aProduct = id })
    seedProduct({ name: `Rx ${run}`, manufacturer: 'GSK', rx: true }).then((id) => { rxProduct = id })
    cy.then(() => post(API.proposeProduct, { sourceProductId: aProduct, ...a32(run) })).then((r) => {
      const prop = data(r.body)
      cy.loginAsOperator()
      post(API.decideMatch, { id: prop.id, decision: 'MATCHED' }).then((d) => {
        mktProductId = data(d.body).mktProductId
        expect(mktProductId, 'matched canonical product').to.exist
      })
    })
  })

  it('MKT-1c-01 [MKT-R5.3] [MKT-R7.5] [MKT-R14.1] a seller creates and submits an offer from the screen (real UI)', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    openMarketplace()
    cy.get('#mktNewOfferBtn').should('be.visible').click()
    cy.get('#mktOfferProduct').select(String(mktProductId))
    cy.get('#mktOfferPrice').clear().type('52000')
    cy.get('#mktOfferArea').clear().type('Karachi')
    cy.get('#mktOfferWarranty').select('12 months — authorised distributor')
    cy.get('#mktOfferReturn').select('7 days')
    cy.get('#mktOfferSave').click()
    cy.get('#mktOfferSubmit').click()
    cy.contains(`${UI.offersTable} tr`, '52,000').should('contain', 'PENDING_REVIEW').invoke('attr', 'data-offer-id')
      .then((id) => { offerId = Number(id) })
  })

  it('MKT-1c-02 [MKT-R3.1] [MKT-R4.1] [MKT-R22.1] party roles come from the token, not the JSON', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.saveOffer, { mktProductId, sourceProductId: aProduct, marketplacePrice: 52000, deliveryArea: 'Karachi',
      stockSourceType: 'MERCHANT', sellerOrganizationId: 999999, stockOwnerOrganizationId: 999999 }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      const o = data(r.body)
      expect(o.sellerOrganizationId).to.not.eq(999999)
      expect(o.stockOwnerOrganizationId).to.eq(o.sellerOrganizationId)
      expect(o.custodianOrganizationId).to.eq(o.sellerOrganizationId)
      expect(o.fulfillerOrganizationId).to.eq(o.sellerOrganizationId)
    })
  })

  it('MKT-1c-03 [MKT-R20.2] [MKT-R7.6] a prescription product cannot be offered in Phase 1', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.proposeProduct, { sourceProductId: rxProduct, brand: 'GSK', model: `Rx ${run}` })
      .then((r) => expectRefused(r, 'Prescription'))
  })

  it('MKT-1c-04 [MKT-R20.2] a supplier/consignment offer is refused before its phase', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.saveOffer, { mktProductId, sourceProductId: aProduct, marketplacePrice: 52000, deliveryArea: 'Karachi',
      stockSourceType: 'SUPPLIER' }).then((r) => expectRefused(r, 'supplier'))
  })

  it('MKT-1c-05 [MKT-R7.4] the operator approves; a price outside the ceiling is refused', () => {
    cy.loginAsOperator()
    cy.visit(UI.operatorPage)
    cy.contains('#mktOfferQueue tr', '52,000').should('be.visible')
    post(API.decideOffer, { id: offerId, decision: 'APPROVED' }).then((r) => expect(ok(r.body)).to.eq(true))
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.saveOffer, { id: offerId, marketplacePrice: 99999999 }).then((r) => expectRefused(r, 'price'))
  })

  it('MKT-1c-06 [MKT-R9.2] [MKT-R9.3] the public projection carries no cost, margin or supplier data', () => {
    get(`${API.publicOffers(mktProductId)}?city=Karachi`).then((r) => {
      expect(ok(r.body)).to.eq(true)
      const row = list(r.body).find((o) => o.offerId === offerId)
      expect(row, 'approved offer is published').to.exist
      const keys = Object.keys(row).join(',').toLowerCase()
      ;['cost', 'margin', 'purchase', 'lastpurchaserate', 'supplier', 'stockmovement'].forEach((k) =>
        expect(keys, `projection must not expose ${k}`).to.not.contain(k))
    })
  })

  it('MKT-1c-07 [MKT-R7.6] a suspended seller disappears from the public projection at once', () => {
    cy.loginAsOperator()
    cy.orgOf(SELLER_A).then((org) => post(API.decideSeller, { organizationId: org.id, decision: 'SUSPEND', reason: 'mkt gate' }))
    get(`${API.publicOffers(mktProductId)}?city=Karachi`).then((r) =>
      expect(list(r.body).map((o) => o.offerId)).to.not.include(offerId))
    cy.orgOf(SELLER_A).then((org) => post(API.decideSeller, { organizationId: org.id, decision: 'REINSTATE' }))
  })

  it('MKT-1c-08 [MKT-R22.1] seller B cannot read or edit seller A\'s offer (404, with a positive control)', () => {
    makeSeller(SELLER_B)
    get(API.myOffers).then((r) => expect(ok(r.body), 'positive control').to.eq(true))
    get(API.getOffer(offerId)).then((r) => expect(r.status === 404 || !ok(r.body)).to.eq(true))
    post(API.saveOffer, { id: offerId, marketplacePrice: 1 }).then((r) => expectRefused(r))
  })

  it('MKT-1c-09 [MKT-R14.2] warranty shows the real provider, never MaxTheService by default', () => {
    get(`${API.publicOffers(mktProductId)}?city=Karachi`).then((r) => {
      const row = list(r.body).find((o) => o.offerId === offerId)
      expect(row.warrantyProvider).to.not.match(/maxtheservice/i)
      expect(row).to.include.keys('warrantyMonths', 'warrantyStartsOn', 'warrantyCovers', 'warrantyExcludes')
    })
  })
})
