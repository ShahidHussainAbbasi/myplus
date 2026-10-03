/**
 * MKT-1b — canonical product + composite identity key + match review.
 * Source §5 (MKT-R5.1, R5.2), §6 (MKT-R6.1–6.6). Slice: microservices/docs/slices/mkt-1b-product-matching.md
 * Run headed: npx cypress run --headed --env mkt=1b --spec cypress/e2e/marketplace/mkt-1b-product-match.cy.js
 *
 * Seller A = owner.business@, seller B = owner.mobile@ — two real tenants offering the same phone.
 * The order is deliberate: the 64GB case runs AFTER the 128GB product is matched, so "no suggestion" is a real
 * answer rather than the trivially-null one an empty catalogue would give.
 */
const { gate, uniq, SELLER_A, SELLER_B, API, ok, data, list, msg, post, get, expectRefused, makeSeller,
  openMarketplace, seedProduct, a32 } = require('./mkt-helpers')

gate('1b')('MKT-1b — canonical product and match review', () => {
  const run = uniq()
  const model = `Galaxy A32 T${run}`
  const key128 = `SAMSUNG|GALAXY-A32-T${run}|128GB|BLACK|NEW|12M`
  let aProduct, bProduct, b64Product, aProposal, canonicalId

  before(() => {
    makeSeller(SELLER_A, 'Shahzad Mobile Shop')
    seedProduct({ name: `Galaxy A32 128 Black ${run}`, manufacturer: 'Samsung' }).then((id) => { aProduct = id })
    makeSeller(SELLER_B, 'Mobile Distributor')
    seedProduct({ name: `Samsung A-32 128GB blk ${run}`, manufacturer: 'Samsung' }).then((id) => { bProduct = id })
    seedProduct({ name: `Galaxy A32 64 Black ${run}`, manufacturer: 'Samsung' }).then((id) => { b64Product = id })
  })

  const asA = () => cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats', 'mkt-on')
  const asB = () => cy.loginAs(SELLER_B, 'Demo@2025!', '/getBusinessDashboardStats', 'mkt-on')

  it('MKT-1b-01 [MKT-R5.1] [MKT-R6.2] [MKT-R6.4] a seller proposes a product from the Marketplace screen (real UI)', () => {
    asA()
    openMarketplace()
    cy.get('#mktProductsBox').should('be.visible')
    cy.get('#mktProposeBtn').should('be.visible').click()
    cy.get('#mktProposeProduct option').should('have.length.greaterThan', 1)
    cy.get('#mktProposeProduct').select(String(aProduct))
    cy.get('#mktBrand').clear().type('Samsung')
    cy.get('#mktModel').clear().type(model)
    cy.get('#mktVariant').clear().type('128 GB')          // spacing is normalised by the SERVER
    cy.get('#mktColour').clear().type('black')
    cy.get('#mktCondition').select('New')
    cy.get('#mktProposeSubmit').click()
    cy.get('#mktProposeMsg', { timeout: 15000 }).should('contain', 'review')
    cy.contains('#mktProposalsTable tr', `GALAXY-A32-T${run}`)
      .should('contain', `SAMSUNG|GALAXY-A32-T${run}|128GB|BLACK|NEW`)
      .find('[data-status="PENDING_REVIEW"]').should('be.visible')
  })

  it('MKT-1b-02 [MKT-R6.2] the identity key is computed by the SERVER, whatever the browser sends', () => {
    asA()
    post(API.proposeProduct, { sourceProductId: aProduct, ...a32(run), identityKey: 'FORGED|KEY' }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      aProposal = data(r.body)
      expect(aProposal.proposedIdentityKey).to.eq(key128)
      expect(aProposal.matchStatus).to.eq('PENDING_REVIEW')
      expect(aProposal.mktProductId, 'nothing is published by proposing').to.eq(null)
    })
  })

  it('MKT-1b-03 [MKT-R6.4] [MKT-R5.2] the operator matches it from the review queue (real UI) — a canonical product is born', () => {
    cy.loginAsOperator()
    cy.visit('/platformDashboard')
    cy.get('#platMktMatchesBtn').should('be.visible').click()
    cy.contains('#mktMatchQueue tr', key128, { timeout: 15000 }).within(() => {
      cy.contains('New marketplace product').should('be.visible')
      cy.get('[data-decision="MATCHED"]').click()
    })
    cy.contains('#mktMatchQueue tr', key128).should('not.exist')
    get(API.matchQueue + '?status=MATCHED').then((r) => {
      const mine = list(r.body).find((p) => p.id === aProposal.id)
      expect(mine, 'A is matched').to.exist
      canonicalId = mine.mktProductId
      expect(canonicalId).to.be.a('number')
    })
  })

  it('MKT-1b-04 [MKT-R6.1] [MKT-R5.4] seller B\'s same phone is SUGGESTED for that product — never merged on its own', () => {
    asB()
    post(API.proposeProduct, { sourceProductId: bProduct, brand: ' samsung ', model: model.toLowerCase(),
      variant: '128 gb', colour: 'BLACK', condition: 'new', warrantyType: '12m' }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      expect(data(r.body).proposedIdentityKey).to.eq(key128)
      expect(data(r.body).suggestedProductId).to.eq(canonicalId)
      expect(data(r.body).matchStatus).to.eq('PENDING_REVIEW')
      expect(data(r.body).mktProductId).to.eq(null)
    })
  })

  it('MKT-1b-05 [MKT-R6.6] the 64GB phone is never suggested for the 128GB product', () => {
    asB()
    post(API.proposeProduct, { sourceProductId: b64Product, ...a32(run, '64GB') }).then((r) => {
      expect(ok(r.body)).to.eq(true)
      expect(data(r.body).proposedIdentityKey).to.contain('|64GB|')
      expect(data(r.body).suggestedProductId, 'a different storage is a different product').to.eq(null)
    })
  })

  it('MKT-1b-06 [MKT-R6.5] the operator corrects a bad match; the seller reads the note on their screen', () => {
    cy.loginAsOperator()
    post(API.decideMatch, { id: aProposal.id, decision: 'NEEDS_CORRECTION', note: ' ' })
      .then((r) => expectRefused(r, 'Tell the seller what is wrong'))
    post(API.decideMatch, { id: aProposal.id, decision: 'NEEDS_CORRECTION', note: 'colour is Blue on the box' })
      .then((r) => {
        expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
        expect(data(r.body).mktProductId, 'detached').to.eq(null)
      })
    asA()
    openMarketplace()
    cy.contains('#mktProposalsTable tr', `GALAXY-A32-T${run}`)
      .should('contain', 'colour is Blue on the box')
      .find('[data-status="NEEDS_CORRECTION"]').should('be.visible')
  })

  it('MKT-1b-07 [MKT-R22.1] seller B never sees seller A\'s proposal and cannot decide one (with positive controls)', () => {
    asB()
    get(API.myProposals).then((r) => {
      expect(ok(r.body), 'positive control: the read answers').to.eq(true)
      expect(list(r.body).length, 'B sees its own').to.be.at.least(2)
      expect(list(r.body).map((p) => p.id)).to.not.include(aProposal.id)
    })
    post(API.decideMatch, { id: aProposal.id, decision: 'MATCHED' }).then((r) => expect(ok(r.body)).to.eq(false))
    post(API.proposeProduct, { sourceProductId: aProduct, ...a32(run) })
      .then((r) => expectRefused(r, 'not in your catalogue'))
  })

  it('MKT-1b-08 [MKT-R6.2] a missing brand is refused with a sentence naming the field', () => {
    asA()
    post(API.proposeProduct, { sourceProductId: aProduct, brand: ' ', model: 'X' }).then((r) => expectRefused(r, 'brand'))
  })

  it('MKT-1b-09 [MKT-R20.2] a prescription product cannot even be proposed in Phase 1', () => {
    asA()
    seedProduct({ name: `Rx ${run}`, manufacturer: 'GSK', rx: true }).then((rx) =>
      post(API.proposeProduct, { sourceProductId: rx, brand: 'GSK', model: `Rx ${run}` })
        .then((r) => expectRefused(r, 'Prescription and restricted products cannot be sold')))
  })
})
