/**
 * MKT-1c — offers with party roles, policies and approval; the published projection.
 * Source §3, §4.1, §5.3, §7.4–7.5, §9.2–9.3, §14. Run headed: --env mkt=1c
 *
 * Contract (implemented, slices/mkt-1c-offers.md): offer decisions are APPROVE | REJECT | SUSPEND | REINSTATE;
 * the approval status is PENDING_REVIEW after submit; approving needs an ACTIVE default COMMISSION policy;
 * policies are created by the operator and never edited.
 */
const { gate, uniq, SELLER_A, SELLER_B, API, UI, ok, data, list, msg, post, get, expectRefused, makeSeller,
  openMarketplace, seedProduct, a32 } = require('./mkt-helpers')

gate('1c')('MKT-1c — offers, roles, policies, approval and projection', () => {
  const run = uniq()
  const WARRANTY_NAME = `12 months — authorised distributor ${run}`
  const RETURN_NAME = `7 days ${run}`
  let mktProductId, aProduct, offerId, warrantyId, returnId

  before(() => {
    // policies are the operator's: one warranty, one return; a default commission only if none is active
    // (creating a default replaces the platform-wide default, so the gate never does it needlessly)
    cy.loginAsOperator()
    post(API.createPolicy, { policyType: 'WARRANTY', name: WARRANTY_NAME, warrantyProvider: 'Samsung Pakistan (authorised distributor)',
      warrantyMonths: 12, warrantyCovers: 'Manufacturing defects', warrantyExcludes: 'Physical and liquid damage' })
      .then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); warrantyId = data(r.body).id })
    post(API.createPolicy, { policyType: 'RETURN', name: RETURN_NAME, returnDays: 7 })
      .then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); returnId = data(r.body).id })
    get(API.policies).then((r) => {
      const hasDefault = list(r.body).some((p) => p.policyType === 'COMMISSION' && p.active && p.isDefault)
      if (!hasDefault) post(API.createPolicy, { policyType: 'COMMISSION', name: `Standard ${run}`, isDefault: true,
        commissionBasis: 'ITEMS', commissionRate: 0.08 }).then((c) => expect(ok(c.body), JSON.stringify(c.body)).to.eq(true))
    })

    makeSeller(SELLER_A)
    seedProduct({ name: `Offer phone ${run}` }).then((id) => { aProduct = id })
    cy.then(() => post(API.proposeProduct, { sourceProductId: aProduct, ...a32(run) })).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      const prop = data(r.body)
      cy.loginAsOperator()
      post(API.decideMatch, { id: prop.id, decision: 'MATCHED', version: prop.version }).then((d) => {
        expect(ok(d.body), JSON.stringify(d.body)).to.eq(true)
        mktProductId = data(d.body).mktProductId
        expect(mktProductId, 'matched canonical product').to.exist
      })
    })
  })

  it('MKT-1c-01 [MKT-R5.3] [MKT-R7.5] [MKT-R14.1] a seller creates and submits an offer from the screen (real UI)', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    openMarketplace()
    cy.get('#mktNewOfferBtn').should('be.visible').click()
    cy.get('#mktOfferProduct').should('be.visible').select(String(mktProductId))
    cy.get('#mktOfferPrice').clear().type('52000')
    cy.get('#mktOfferArea').clear().type('Karachi, karachi , Lahore')
    cy.get('#mktOfferWarranty').select(WARRANTY_NAME)
    cy.get('#mktOfferReturn').select(RETURN_NAME)
    cy.get('#mktOfferSubmit').click()
    cy.get('#mktOfferMsg').should('not.be.empty')
    cy.contains(`${UI.offersTable} tr`, '52,000').within(() => {
      cy.get('[data-status]').should('have.attr', 'data-status', 'PENDING_REVIEW')
      cy.contains('td', /^Karachi, Lahore$/)   // trimmed and de-duplicated by the server, as typed
    }).invoke('attr', 'data-offer-id').then((id) => { offerId = Number(id) })
  })

  it('MKT-1c-02 [MKT-R3.1] [MKT-R4.1] [MKT-R22.1] party roles come from the token, not the JSON', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    // an edit that tries to name other parties: they are not bound, the token's org is stamped
    post(API.saveOffer, { id: offerId, sellerSku: `SKU${run}`, sellerOrganizationId: 999999,
      stockOwnerOrganizationId: 999999, custodianOrganizationId: 999999, fulfillerOrganizationId: 999999 }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      const o = data(r.body)
      expect(o.sellerOrganizationId).to.not.eq(999999)
      expect(o.organizationId).to.eq(o.sellerOrganizationId)
      expect(o.stockOwnerOrganizationId).to.eq(o.sellerOrganizationId)
      expect(o.custodianOrganizationId).to.eq(o.sellerOrganizationId)
      expect(o.fulfillerOrganizationId).to.eq(o.sellerOrganizationId)
      expect(o.stockSourceType).to.eq('MERCHANT')
      expect(o.marketplacePrice, 'a partial edit keeps the price').to.eq(52000)
    })
  })

  it('MKT-1c-03 [MKT-R20.2] [MKT-R7.6] a prescription product cannot be offered in Phase 1', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    seedProduct({ name: `Rx ${run}`, manufacturer: 'GSK', rx: true }).then((rx) =>
      post(API.proposeProduct, { sourceProductId: rx, brand: 'GSK', model: `Rx ${run}` }))
      .then((r) => expectRefused(r, 'Prescription'))
  })

  it('MKT-1c-04 [MKT-R20.2] a supplier/consignment offer is refused before its phase', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.saveOffer, { id: offerId, stockSourceType: 'SUPPLIER' }).then((r) => expectRefused(r, 'supplier stock'))
    post(API.saveOffer, { id: offerId, stockSourceType: 'CONSIGNMENT' }).then((r) => expectRefused(r, 'consignment stock'))
    get(API.getOffer(offerId)).then((r) => expect(data(r.body).stockSourceType, 'refused write changed nothing').to.eq('MERCHANT'))
  })

  it('MKT-1c-05 [MKT-R7.4] the operator approves from the console; a price outside the ceiling is refused', () => {
    cy.loginAsOperator()
    cy.visit(UI.operatorPage)
    cy.get('#platMktOffersBtn').click()
    cy.get(`#mktOfferQueue tr[data-offer-id="${offerId}"]`).should('be.visible')
      .find('button[data-decision="APPROVE"]').click()
    cy.get(`#mktOfferQueue tr[data-offer-id="${offerId}"]`).should('not.exist')   // left the PENDING_REVIEW queue
    get(`${API.offerQueue}?status=APPROVED&size=100`).then((r) => {
      const o = list(r.body).find((x) => x.id === offerId)
      expect(o, 'approved').to.exist
      expect(o.commissionPolicyId, 'the default commission is stamped on approval').to.exist
    })
    post(API.productLimits, { id: mktProductId, priceFloor: 40000, priceCeiling: 60000 })
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.saveOffer, { id: offerId, marketplacePrice: 99999 }).then((r) => expectRefused(r, 'allowed range'))
    post(API.saveOffer, { id: offerId, marketplacePrice: 51500 }).then((r) => expect(ok(r.body), 'positive control').to.eq(true))
  })

  it('MKT-1c-06 [MKT-R9.2] [MKT-R9.3] the public projection carries no cost, margin or supplier data (anonymous)', () => {
    cy.clearCookies()
    get(`${API.publicOffers(mktProductId)}?city=karachi`).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      const row = list(r.body).find((o) => o.offerId === offerId)
      expect(row, 'approved offer is published').to.exist
      expect(row.price).to.eq(51500)
      const keys = Object.keys(row).join(',').toLowerCase()
      ;['cost', 'margin', 'purchase', 'supplier', 'movement', 'organizationid,'].forEach((k) =>
        expect(keys, `projection must not expose ${k}`).to.not.contain(k))
    })
    get(`${API.publicOffers(mktProductId)}?city=Quetta`).then((r) =>
      expect(list(r.body).map((o) => o.offerId), 'not delivered to that city').to.not.include(offerId))
  })

  it('MKT-1c-07 [MKT-R7.6] a suspended seller disappears from the public projection at once', () => {
    cy.loginAsOperator()
    cy.orgOf(SELLER_A).then((org) => post(API.decideSeller, { organizationId: org.id, decision: 'SUSPEND', reason: 'mkt gate' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    get(`${API.publicOffers(mktProductId)}?city=Karachi`).then((r) =>
      expect(list(r.body).map((o) => o.offerId)).to.not.include(offerId))
    cy.orgOf(SELLER_A).then((org) => post(API.decideSeller, { organizationId: org.id, decision: 'REINSTATE' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    get(`${API.publicOffers(mktProductId)}?city=Karachi`).then((r) =>
      expect(list(r.body).map((o) => o.offerId), 'back after reinstatement').to.include(offerId))
  })

  it('MKT-1c-08 [MKT-R22.1] seller B cannot read or edit seller A\'s offer (with a positive control)', () => {
    makeSeller(SELLER_B)
    get(API.myOffers).then((r) => expect(ok(r.body), 'positive control').to.eq(true))
    get(API.getOffer(offerId)).then((r) => expectRefused(r, 'No such offer'))
    post(API.saveOffer, { id: offerId, marketplacePrice: 1 }).then((r) => expectRefused(r, 'No such offer'))
    post(API.submitOffer, { id: offerId }).then((r) => expectRefused(r, 'No such offer'))
  })

  it('MKT-1c-09 [MKT-R14.2] warranty shows the real provider, never MaxTheService by default', () => {
    get(`${API.publicOffers(mktProductId)}?city=Karachi`).then((r) => {
      const row = list(r.body).find((o) => o.offerId === offerId)
      expect(row.warrantyProvider).to.eq('Samsung Pakistan (authorised distributor)')
      expect(row.warrantyMonths).to.eq(12)
      expect(row.warrantyStartsOn).to.eq('DELIVERY')
      expect(row.returnDays).to.eq(7)
    })
    cy.loginAsOperator()
    post(API.createPolicy, { policyType: 'WARRANTY', name: `No provider ${run}`, warrantyMonths: 12 })
      .then((r) => expectRefused(r, 'warranty provider'))
  })

  it('MKT-1c-10 [MKT-R7.4] policies are never edited: deactivating one keeps sold terms, and it cannot be chosen again', () => {
    cy.loginAsOperator()
    post(API.deactivatePolicy, { id: returnId }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    get(API.getOffer(offerId)).then((r) => expect(data(r.body).returnPolicyId, 'existing offer keeps its terms').to.eq(returnId))
    get(API.sellerPolicies).then((r) => expect(list(r.body).map((p) => p.id)).to.not.include(returnId))
    post(API.saveOffer, { id: offerId, returnPolicyId: returnId }).then((r) => expectRefused(r, 'active return policy'))
  })

  it('MKT-1c-11 [MKT-R22.1] a tenant cannot use the operator routes (with a positive control)', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.decideOffer, { id: offerId, decision: 'SUSPEND', note: 'self' }).then((r) => expect(ok(r.body)).to.eq(false))
    post(API.createPolicy, { policyType: 'RETURN', name: 'x', returnDays: 1 }).then((r) => expect(ok(r.body)).to.eq(false))
    cy.loginAsOperator()
    get(`${API.offerQueue}?status=APPROVED&size=100`).then((r) =>
      expect(list(r.body).find((x) => x.id === offerId).approvalStatus, 'unchanged by the tenant').to.eq('APPROVED'))
  })
})
