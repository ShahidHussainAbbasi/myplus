/**
 * MKT-5d — counts of consigned stock and shrinkage claims.
 * Source R12.1 (shrinkage kinds), R12.2 (the agreement decides responsibility, tolerance, frequency, proof, claim window,
 * approval, settlement effect), R12.3 (default responsibility), R12.4 (never debit without evidence and a dispute step),
 * R20.5 (custodian counts, shrinkage claims). Contract: microservices/docs/slices/mkt-5d-counts-shrinkage.md
 *
 * The warehouse (the custodian) counts; units found missing stop being sold at once but stay the owner's, set aside in
 * its books, until the owner writes them off or a claim for them is approved. The operator's approval is the only thing
 * that pays: it credits the owner the units' declared value and writes them off its books.
 *
 * Mobile Distributor (owner.mobile@) is the consignor, as in 5b/5c, under terms where MaxTheService bears damage and the
 * owner bears expiry, 1% tolerance, 14 days to claim, proof required. Each run consigns 5 of its own product, so every
 * number it asserts on is its own.
 */
const { gate, uniq, SELLER_B, ok, data, post, get, expectRefused, seedPolicies, publishOffer, makeSeller, openMarketplace } =
  require('./mkt-helpers')

const WAREHOUSE = 'owner.warehouse@myplus.com'
const OWNER = 'Mobile Distributor'
const VALUE = 40000
const T = { view: '/mkt/transfers', offer: '/mkt/transferOffer', receive: '/mkt/transferReceive', list: '/mkt/consignedList',
  count: '/mkt/consignedCount', claim: '/mkt/consignedClaim', shrink: '/mkt/consignedShrinkage' }
const OP = { claims: '/platform/mkt/shrinkageClaims', decide: '/platform/mkt/shrinkageDecision' }
const CA = { list: '/platform/mkt/consignments', offer: '/platform/mkt/consignmentOffer', decide: '/mkt/consignmentDecision' }
const TERMS = { ownershipTransfer: 'ON_SALE', priceSetBy: 'CONSIGNOR', commissionPct: 10, settleEveryDays: 7, reconcileEveryDays: 30,
  countEveryDays: 30, shrinkageTolerancePct: 1, damageBearer: 'CONSIGNEE', expiryBearer: 'CONSIGNOR', insuredBy: 'CONSIGNEE',
  claimWindowDays: 14, proofRequired: true, unsoldReturnDays: 180, unsoldReturnBearer: 'CONSIGNOR' }

gate('5d')('MKT-5d — counts of consigned stock and shrinkage claims', () => {
  const run = uniq()
  const S = {}

  const as = (email) => cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')
  const view = () => get(T.view).then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body) })
  const held = () => view().then((v) => (v.held || []).find((h) => h.mktProductId === S.mkt))
  const heldRow = () => `#mktHeld tr.mkt-held[data-product="${S.mkt}"]`
  const shrinkRow = () => `#mktHeld tr.mkt-held-shrink[data-product="${S.mkt}"]`
  const level = () => cy.request('/productStockLevels').then((r) => {
    const l = (r.body.levels || {})[String(S.ownerProduct)]
    expect(l, 'inventory reports a level for the owner\'s product').to.exist
    return { onHand: Number(l.onHand), sellable: Number(l.sellable != null ? l.sellable : l.onHand) }
  })
  const account = () => get('/mkt/settlementAccount').then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body) })
  const shrinkPanel = () => {
    cy.loginAsOperator()
    cy.visit('/platformDashboard')
    cy.intercept('GET', '**/platform/mkt/shrinkageClaims*').as('scs')
    cy.get('#platMktShrinkBtn').should('be.visible').click()
    return cy.wait('@scs')
  }

  before(() => {
    makeSeller(SELLER_B)
    makeSeller(WAREHOUSE, 'Central Warehouse')
    cy.loginAsOperator()
    cy.orgOf(WAREHOUSE).then((o) => { S.warehouse = o.id })
    cy.then(() => get('/platform/mkt/warehouse')).then((r) => {
      if (data(r.body).organizationId !== S.warehouse) post('/platform/mkt/warehouse', { organizationId: S.warehouse })
    })
    cy.orgOf(SELLER_B).then((o) => { S.owner = o.id })
    cy.then(() => get(`${CA.list}?status=ACTIVE`)).then((r) => {
      const t = (a) => a.terms || a
      const fits = data(r.body).find((a) => a.consignorOrganizationId === S.owner && t(a).priceSetBy === 'CONSIGNOR'
        && t(a).ownershipTransfer === 'ON_SALE' && t(a).damageBearer === 'CONSIGNEE' && t(a).expiryBearer === 'CONSIGNOR'
        && t(a).proofRequired && Number(t(a).claimWindowDays) === 14 && Number(t(a).shrinkageTolerancePct) === 1)
      if (fits) { S.agreement = fits.agreementNo; return }
      cy.then(() => post(CA.offer, { consignorOrganizationId: S.owner, terms: TERMS, note: `gate 5d ${run}` })).then((o) => {
        expect(ok(o.body), JSON.stringify(o.body)).to.eq(true)
        const a = data(o.body)
        S.agreement = a.agreementNo
        as(SELLER_B)
        post(CA.decide, { id: a.id, action: 'ACCEPT', version: a.version }).then((d) => expect(ok(d.body), JSON.stringify(d.body)).to.eq(true))
      })
    })
    // a phone both carry; the owner has 8, consigns 5 at Rs 40,000 a unit, lists them; the warehouse receives them
    seedPolicies(run).then((p) => {
      S.policies = p
      cy.loginAsOperator()
      cy.then(() => publishOffer(WAREHOUSE, { run, price: 52000, qty: 1, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))
        .then((o) => { S.mkt = o.mktProductId })
      cy.then(() => publishOffer(SELLER_B, { run, price: 52000, qty: 8, mktProductId: S.mkt, warrantyPolicyId: p.warranty,
        returnPolicyId: p.returns })).then((o) => { S.ownerProduct = o.sourceProductId })
    })
    as(SELLER_B)
    cy.then(() => post(T.offer, { mktProductId: S.mkt, quantity: 5, unitPrice: VALUE, kind: 'CONSIGN', note: `5d ${run}` })).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      S.transfer = data(r.body)
    })
    as(WAREHOUSE)
    cy.then(() => post(T.receive, { transferNo: S.transfer.transferNo, version: S.transfer.version }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    as(SELLER_B)
    cy.then(() => post(T.list, { mktProductId: S.mkt, price: 52000, deliveryAreas: 'Karachi', promiseHours: 48,
      warrantyPolicyId: S.policies.warranty, returnPolicyId: S.policies.returns }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  })

  after(() => {
    // anything this run left open is withdrawn, and what is still missing written off, so nothing is left waiting
    as(SELLER_B)
    cy.then(() => held()).then((h) => {
      const counts = (h && h.shrink && h.shrink.counts) || []
      counts.forEach((c) => (c.claims || []).filter((m) => m.status === 'OPEN')
        .forEach((m) => post(T.shrink, { id: m.id, version: m.version, action: 'WITHDRAW' })))
    })
    cy.then(() => held()).then((h) => {
      ((h && h.shrink && h.shrink.counts) || []).filter((c) => c.writable > 0)
        .forEach((c) => post(T.shrink, { id: c.id, version: c.version, action: 'WRITE_OFF' }))
    })
  })

  it('MKT-5d-01 [MKT-R20.5] [MKT-R12.2] the warehouse counts 3 of 5 in the real UI: 2 missing, no longer sold; only it counts', () => {
    as(SELLER_B)
    cy.then(() => post(T.count, { consignorOrgId: S.owner, mktProductId: S.mkt, counted: 3, idempotencyKey: `x-${run}` }))
      .then((r) => expectRefused(r, 'Only the MaxTheService warehouse counts consigned stock.'))
    cy.then(() => level()).then((l) => { S.before = l })
    as(WAREHOUSE)
    openMarketplace()
    cy.then(() => cy.get(shrinkRow()).find('.mkt-count-due').should('contain', 'Next count due by'))
    cy.then(() => cy.get(shrinkRow()).find('.mkt-count-n').type('3'))
    cy.then(() => cy.get(shrinkRow()).find('.mkt-count-note').type('Shelf B, two boxes empty'))
    cy.then(() => cy.get(shrinkRow()).find('.mkt-count-save').click())
    cy.get('#mktTransferMsg').should('contain', 'Counted 3 of 5 (CC-')
      .and('contain', '2 missing, no longer sold. The owner can write them off or claim them.')
    cy.then(() => cy.get(shrinkRow()).find('.mkt-shrink-missing').should('have.text', '2 missing, not sold'))
    cy.then(() => cy.get(shrinkRow()).find('.mkt-count-said').first().should('contain', 'counted 3 of 5, 2 missing'))
    cy.then(() => cy.get(shrinkRow()).find('.mkt-claim-open').should('not.exist'))   // the warehouse does not claim
    as(SELLER_B)
    cy.then(() => held()).then((h) => {
      expect(h.units, 'still in the ledger and the owner\'s books').to.eq(5)
      expect(h.listing.available, 'the listing sells only what is on the shelf').to.eq(3)
      expect(h.shrink.missing).to.eq(2)
      const c = h.shrink.counts[0]
      S.count = c
      expect(c.expected).to.eq(5)
      expect(c.tolerated, '1% of 5 is no whole unit').to.eq(0)
      expect(c.claimable).to.eq(2)
    })
    cy.then(() => level()).then((l) => expect(l, 'nothing moved in the owner\'s books').to.deep.eq(S.before))
  })

  it('MKT-5d-02 [MKT-R12.1] [MKT-R12.3] [MKT-R12.2] a claim follows the agreement: proof needed, expiry is the owner\'s, no more than is missing', () => {
    as(SELLER_B)
    openMarketplace()
    cy.then(() => cy.get(shrinkRow()).find('.mkt-claim-open').click())
    cy.then(() => cy.get(shrinkRow()).find('.mkt-claim-kind option').should('have.length', 10))   // the 9 kinds of R12.1
    cy.then(() => cy.get(shrinkRow()).find('.mkt-claim-kind').select('EXPIRY', { force: true }))
    cy.then(() => cy.get(shrinkRow()).find('.mkt-claim-who').should('have.text',
      `Under ${S.agreement} this is yours to bear: write it off instead.`))
    cy.then(() => cy.get(shrinkRow()).find('.mkt-claim-kind').select('THEFT', { force: true }))
    cy.then(() => cy.get(shrinkRow()).find('.mkt-claim-who').should('have.text', `Under ${S.agreement} MaxTheService answers for this.`))
    cy.then(() => cy.get(shrinkRow()).find('.mkt-claim-save').click())
    cy.get('#mktTransferMsg').should('have.text',
      `Under ${S.agreement} a claim needs proof: say what shows it, for example the CCTV time or a photo link.`)
    cy.then(() => post(T.claim, { countId: S.count.id, kind: 'EXPIRY', units: 1, proof: 'Expired on the shelf' }))
      .then((r) => expectRefused(r, `Under ${S.agreement}, expiry is yours to bear`))
    cy.then(() => post(T.claim, { countId: S.count.id, kind: 'THEFT', units: 3, proof: 'CCTV 14:02' }))
      .then((r) => expectRefused(r, `Only 2 of ${S.count.countNo}'s missing units can be claimed.`))
    cy.then(() => account()).then((a) => { S.balance = Number(a.balance) })
  })

  it('MKT-5d-03 [MKT-R12.4] the owner claims 1 for theft in the real UI: OPEN, nothing paid, nothing moved', () => {
    as(SELLER_B)
    openMarketplace()
    cy.then(() => cy.get(shrinkRow()).find('.mkt-claim-open').click())
    cy.then(() => cy.get(shrinkRow()).find('.mkt-claim-kind').select('THEFT', { force: true }))
    cy.then(() => cy.get(shrinkRow()).find('.mkt-claim-units').clear().type('1'))
    cy.then(() => cy.get(shrinkRow()).find('.mkt-claim-proof').type('CCTV 14:02, aisle 3'))
    cy.then(() => cy.get(shrinkRow()).find('.mkt-claim-save').click())
    cy.get('#mktTransferMsg').should('contain', 'Claimed as SC-')
      .and('contain', `: Rs 40,000.00 for 1 × `).and('contain', 'Nothing is paid until MaxTheService decides.')
    cy.then(() => cy.get(shrinkRow()).find('.mkt-claim[data-status="OPEN"] .mkt-claim-status').should('have.text', 'Waiting for MaxTheService'))
    cy.then(() => held()).then((h) => {
      const m = h.shrink.counts[0].claims[0]
      S.claim1 = m
      expect(m.responsible).to.eq('CONSIGNEE')
      expect(h.shrink.counts[0].claimable, 'the other unit can still be claimed').to.eq(1)
      expect(h.shrink.counts[0].writable, 'the claimed one cannot be written off meanwhile').to.eq(1)
    })
    cy.then(() => account()).then((a) => {
      expect(Number(a.balance), 'no money before a decision').to.eq(S.balance)
      expect(a.entries.map((e) => e.ref)).not.to.include(S.claim1.claimNo)
    })
    cy.then(() => level()).then((l) => expect(l, 'nothing moved in the owner\'s books').to.deep.eq(S.before))
  })

  it('MKT-5d-04 [MKT-R12.4] [MKT-R12.2] the operator rejects it with a reason (real UI): nothing paid; the owner reads why', () => {
    shrinkPanel()
    cy.then(() => cy.get(`.mkt-sc-row[data-claim-no="${S.claim1.claimNo}"]`).should('contain', OWNER).and('contain', 'Theft')
      .and('contain', 'CCTV 14:02, aisle 3').find('.mkt-sc-status').should('have.text', 'Waiting for your decision'))
    cy.then(() => cy.get(`.mkt-sc-row[data-claim-no="${S.claim1.claimNo}"] .mkt-sc-reject`).click())
    cy.get('#mktScMsg').should('have.text', 'Say why it is rejected: the owner reads it.')
    cy.then(() => cy.get(`.mkt-sc-row[data-claim-no="${S.claim1.claimNo}"] .mkt-sc-why`).type('CCTV shows the box left in a sale'))
    cy.then(() => cy.get(`.mkt-sc-row[data-claim-no="${S.claim1.claimNo}"] .mkt-sc-reject`).click())
    cy.get('#mktScMsg').should('have.text', 'Rejected. The owner sees your reason and can write the units off.')
    cy.then(() => cy.get(`.mkt-sc-row[data-claim-no="${S.claim1.claimNo}"]`).should('have.attr', 'data-status', 'REJECTED'))
    as(SELLER_B)
    openMarketplace()
    cy.then(() => cy.get(`${shrinkRow()} .mkt-claim[data-claim-no="${S.claim1.claimNo}"]`).should('contain', 'Rejected')
      .and('contain', 'CCTV shows the box left in a sale'))
    cy.then(() => account()).then((a) => expect(Number(a.balance), 'a rejection pays nothing').to.eq(S.balance))
    cy.then(() => held()).then((h) => expect(h.shrink.counts[0].claimable, "only the other unit: a rejected one is not claimed again").to.eq(1))
  })

  it('MKT-5d-05 [MKT-R12.2] [MKT-R12.4] the other unit, claimed as damage and approved: the owner is credited Rs 40,000 and it leaves its books', () => {
    as(SELLER_B)
    cy.then(() => post(T.claim, { countId: S.count.id, kind: 'DAMAGE', units: 1, proof: 'Photo of the crushed box: img/5d.jpg' }))
      .then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); S.claim2 = data(r.body) })
    shrinkPanel()
    cy.then(() => cy.get(`.mkt-sc-row[data-claim-no="${S.claim2.claimNo}"] .mkt-sc-approve`).click())
    cy.get('#mktScMsg').should('have.text', 'Approved. The owner is credited and the units are written off its books.')
    as(SELLER_B)
    cy.then(() => account()).then((a) => {
      expect(Number(a.balance), 'credited the declared value').to.eq(S.balance + VALUE)
      const e = a.entries.find((x) => x.ref === S.claim2.claimNo)
      expect(e, 'a row on the owner\'s account').to.exist
      expect(Number(e.credit)).to.eq(VALUE)
      expect(e.memo).to.contain('Shrinkage claim').and.contain('approved')
    })
    cy.then(() => level()).then((l) => {
      expect(l.onHand, 'written off the owner\'s books').to.eq(S.before.onHand - 1)
      expect(l.sellable, 'its free stock is unchanged').to.eq(S.before.sellable)
    })
    cy.then(() => held()).then((h) => {
      expect(h.units, 'held at the warehouse').to.eq(4)
      expect(h.shrink.missing, 'one still missing: the rejected one').to.eq(1)
      expect(h.listing.available, 'still 3 on the shelf').to.eq(3)
      expect(h.shrink.counts[0].approved).to.eq(1)
    })
    openMarketplace()
    cy.then(() => cy.get(`${shrinkRow()} .mkt-claim[data-claim-no="${S.claim2.claimNo}"] .mkt-claim-status`)
      .should('have.text', 'Approved: credited to you'))
  })

  it('MKT-5d-06 [MKT-R12.2] the owner writes the last missing unit off in the real UI: out of its books, nothing paid, nothing left missing', () => {
    as(SELLER_B)
    cy.then(() => account()).then((a) => { S.balance2 = Number(a.balance) })
    openMarketplace()
    cy.on('window:confirm', (t) => { expect(t).to.eq('Write 1 off your stock? You bear the loss.'); return true })
    cy.then(() => cy.get(shrinkRow()).find('.mkt-count-writeoff').should('have.text', 'Write off 1').click())
    cy.get('#mktTransferMsg').should('contain', `Written off your books: the missing units of ${S.count.countNo}`)
    cy.then(() => cy.get(shrinkRow()).find('.mkt-shrink-missing').should('not.exist'))
    cy.then(() => cy.get(shrinkRow()).find('.mkt-count-said').first().should('contain', '1 written off').and('contain', '1 paid by claim'))
    cy.then(() => level()).then((l) => {
      expect(l.onHand).to.eq(S.before.onHand - 2)
      expect(l.sellable).to.eq(S.before.sellable)
    })
    cy.then(() => held()).then((h) => {
      expect(h.units).to.eq(3)
      expect(h.shrink.missing).to.eq(0)
      expect(h.listing.available).to.eq(3)
    })
    cy.then(() => account()).then((a) => expect(Number(a.balance), 'a write-off pays nothing').to.eq(S.balance2))
    cy.then(() => post(T.shrink, { id: S.count.id, action: 'WRITE_OFF' }))
      .then((r) => expectRefused(r, `Nothing of ${S.count.countNo} is left to write off.`))
  })
})
