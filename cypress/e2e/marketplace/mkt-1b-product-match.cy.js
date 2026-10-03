/**
 * MKT-1b — canonical product + composite identity key + match review.
 * Source §5 (MKT-R5.1, R5.2), §6 (MKT-R6.1–6.6). Run headed: --env mkt=1b
 */
const { gate, uniq, SELLER_A, SELLER_B, API, UI, ok, data, list, post, get, expectRefused, makeSeller, openMarketplace, seedProduct,
  a32 } = require('./mkt-helpers')

gate('1b')('MKT-1b — canonical product and match review', () => {
  const run = uniq()
  let aProduct, bProduct, b64Product, aProposal

  before(() => {
    makeSeller(SELLER_A)
    seedProduct({ name: `Galaxy A32 128 Black ${run}`, manufacturer: 'Samsung' }).then((id) => { aProduct = id })
    makeSeller(SELLER_B)
    seedProduct({ name: `Samsung A-32 128GB blk ${run}`, manufacturer: 'Samsung' }).then((id) => { bProduct = id })
    seedProduct({ name: `Galaxy A32 64 Black ${run}`, manufacturer: 'Samsung' }).then((id) => { b64Product = id })
  })

  it('MKT-1b-01 [MKT-R5.1] [MKT-R6.2] a seller proposes a product from the Marketplace screen (real UI)', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    openMarketplace()
    cy.get(UI.proposeBtn).should('be.visible').click()
    cy.get('#mktProposeProduct').should('be.visible').select(String(aProduct))
    const k = a32(run)
    cy.get('#mktBrand').clear().type(k.brand)
    cy.get('#mktModel').clear().type(k.model)
    cy.get('#mktVariant').clear().type(k.variant)
    cy.get('#mktColour').clear().type(k.colour)
    cy.get('#mktCondition').select(k.condition)
    cy.get('#mktProposeSubmit').click()
    cy.contains('#mktProposalsTable tr', k.model).should('contain', 'PENDING_REVIEW')
      .and('contain', `SAMSUNG|GALAXY-A32-T${run}|128GB|BLACK|NEW`)
  })

  it('MKT-1b-02 [MKT-R6.2] the identity key is computed by the SERVER, whatever the browser sends', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.proposeProduct, { sourceProductId: aProduct, ...a32(run), identityKey: 'FORGED|KEY' }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      aProposal = data(r.body)
      expect(aProposal.proposedIdentityKey).to.eq(`SAMSUNG|GALAXY-A32-T${run}|128GB|BLACK|NEW|12M`)
    })
  })

  it('MKT-1b-03 [MKT-R6.6] 64GB is never matched to 128GB, even with the same name and brand', () => {
    cy.loginAs(SELLER_B, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.proposeProduct, { sourceProductId: b64Product, ...a32(run, '64GB') }).then((r) => {
      expect(ok(r.body)).to.eq(true)
      expect(data(r.body).proposedIdentityKey).to.contain('|64GB|')
      expect(data(r.body).suggestedProductId, 'no merge suggestion across storage').to.not.eq(aProposal.mktProductId)
    })
  })

  it('MKT-1b-04 [MKT-R6.1] [MKT-R6.4] the same key from a second seller is SUGGESTED, never auto-merged', () => {
    cy.loginAs(SELLER_B, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.proposeProduct, { sourceProductId: bProduct, ...a32(run) }).then((r) => {
      expect(ok(r.body)).to.eq(true)
      expect(data(r.body).matchStatus).to.eq('PENDING_REVIEW')
    })
  })

  it('MKT-1b-05 [MKT-R6.5] the operator matches, then corrects a bad match', () => {
    cy.loginAsOperator()
    cy.visit(UI.operatorPage)
    cy.contains('#mktMatchQueue tr', `GALAXY-A32-T${run}`).should('be.visible')
    get(API.matchQueue).then((r) => {
      const mine = list(r.body).filter((m) => String(m.proposedIdentityKey || '').includes(`T${run}`))
      expect(mine.length, 'proposals from this run').to.be.at.least(2)
      const p = mine[0]
      post(API.decideMatch, { id: p.id, decision: 'MATCHED' }).then((d) => expect(ok(d.body)).to.eq(true))
      post(API.decideMatch, { id: p.id, decision: 'NEEDS_CORRECTION', note: 'colour is Blue on the box' })
        .then((d) => expect(ok(d.body), JSON.stringify(d.body)).to.eq(true))
    })
  })

  it('MKT-1b-06 [MKT-R22.1] seller B cannot see or decide seller A\'s proposal (anti-IDOR, with a positive control)', () => {
    cy.loginAs(SELLER_B, 'Demo@2025!', '/getBusinessDashboardStats')
    get(API.myProposals).then((r) => {
      expect(ok(r.body), 'positive control: the endpoint exists and answers').to.eq(true)
      expect(list(r.body).map((p) => p.id)).to.not.include(aProposal.id)
    })
    post(API.decideMatch, { id: aProposal.id, decision: 'MATCHED' }).then((r) => expectRefused(r))
  })

  it('MKT-1b-07 [MKT-R6.2] a missing brand or model is refused with a sentence naming the field', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.proposeProduct, { sourceProductId: aProduct, brand: ' ', model: 'X' })
      .then((r) => expectRefused(r, 'brand'))
  })
})
