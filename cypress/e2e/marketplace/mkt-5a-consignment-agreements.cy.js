/**
 * MKT-5a — consignment agreements: every term is set before any stock is consigned. Source R4.6 (terms configurable
 * BEFORE consignment is enabled), R12.2 (count frequency, tolerance, proof, claim window), R2.1/R2.2 (the consignor owns
 * the stock, the consignee holds and sells it). Contract: microservices/docs/slices/mkt-5a-consignment-agreements.md
 *
 * Ruling 2026-10-15 ("Warehouse first"): the consignee is the MaxTheService warehouse; the consignor is an approved
 * seller. The operator offers the terms, the seller's owner accepts or declines, either side ends an active agreement
 * with a reason. Terms are never edited: newer terms replace the active ones when accepted.
 *
 * Leaves Shahzad Mobile Shop with no waiting offer and no active agreement.
 */
const { gate, uniq, SELLER_A, SELLER_B, API, ok, data, post, get, expectRefused, makeSeller, openMarketplace } = require('./mkt-helpers')

const WAREHOUSE = 'owner.warehouse@myplus.com'
const SHOP = 'Shahzad Mobile Shop'
const CA = {
  list: '/platform/mkt/consignments',
  offer: '/platform/mkt/consignmentOffer',
  opDecide: '/platform/mkt/consignmentDecision',
  mine: '/mkt/consignments',
  decide: '/mkt/consignmentDecision',
}
const TERMS = { ownershipTransfer: 'ON_SALE', priceSetBy: 'CONSIGNOR', commissionPct: 10, settleEveryDays: 7, reconcileEveryDays: 30,
  countEveryDays: 30, shrinkageTolerancePct: 1, damageBearer: 'CONSIGNEE', expiryBearer: 'CONSIGNOR', insuredBy: 'CONSIGNEE',
  claimWindowDays: 14, proofRequired: true, unsoldReturnDays: 180, unsoldReturnBearer: 'CONSIGNOR' }
const TERM_LINES = ['Ownership passes to the customer when it is sold', 'Selling price set by the owner',
  'MaxTheService keeps 10% of each sale', 'Settled every 7 days, after the return period; reconciled every 30 days',
  'Stock counted every 30 days; up to 1% shrinkage tolerated',
  'Damage borne by MaxTheService; expiry borne by the owner; insured by MaxTheService',
  'Shrinkage claims within 14 days, with proof', 'Unsold stock goes back after 180 days, at the cost of the owner']

gate('5a')('MKT-5a — consignment agreements: terms first, accepted by the owner of the stock', () => {
  const run = uniq()
  const S = {}

  const asShop = () => cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
  const agreementsOf = (status) => {
    cy.loginAsOperator()
    return get(`${CA.list}${status ? `?status=${status}` : ''}`).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      return data(r.body).filter((a) => a.consignorOrganizationId === S.shop)
    })
  }
  const offer = (over = {}) => {
    cy.loginAsOperator()
    return post(CA.offer, { consignorOrganizationId: S.shop, terms: Object.assign({}, TERMS, over), note: `gate ${run}` })
  }
  /** Nothing waiting and nothing active for the shop: withdraw and end what an earlier run left. */
  const clear = () => {
    cy.then(() => agreementsOf('OFFERED')).then((xs) => xs.forEach((a) => post(CA.opDecide, { id: a.id, action: 'WITHDRAW', version: a.version })))
    cy.then(() => agreementsOf('ACTIVE')).then((xs) => xs.forEach((a) => post(CA.opDecide, { id: a.id, action: 'END', version: a.version, reason: 'gate cleanup' })))
  }
  const consignPanel = () => {
    cy.loginAsOperator()
    cy.visit('/platformDashboard')
    cy.intercept('GET', '**/platform/mkt/consignments*').as('cas')
    cy.get('#platMktConsignBtn').should('be.visible').click()
    return cy.wait('@cas')
  }

  before(() => {
    makeSeller(SELLER_A)                        // 0a leaves the shared shop's module off (GATE-RUNBOOK §5)
    makeSeller(WAREHOUSE, 'Central Warehouse')
    cy.loginAsOperator()
    cy.orgOf(WAREHOUSE).then((o) => { S.warehouse = o.id })
    cy.then(() => get('/platform/mkt/warehouse')).then((r) => {
      if (data(r.body).organizationId !== S.warehouse) post('/platform/mkt/warehouse', { organizationId: S.warehouse })
    })
    cy.orgOf(SELLER_A).then((o) => { S.shop = o.id })
    clear()
  })

  after(() => clear())

  it('MKT-5a-01 [MKT-R4.6] [MKT-R12.2] [MKT-R2.2] the operator offers every term to a seller; the warehouse holds; nothing is active yet (real UI)', () => {
    consignPanel()
    cy.get('#mktCaSeller option').should('have.length.at.least', 1)
    cy.get('#mktCaSeller').select(String(S.shop))
    cy.get('#mktCaNote').type(`gate ${run}`)
    cy.get('#mktCaOffer').click()
    cy.get('#mktCaMsg').invoke('text').should('match', new RegExp(`^Offered to ${SHOP} as CA-\\d{6}\\. Nothing can be consigned until they accept\\.$`))
    cy.contains('#mktCaList .mkt-ca-row', `· ${SHOP}`).first().within(() => {
      cy.get('.mkt-ca-status').should('have.text', 'Offered, waiting for the seller')
      cy.get('.mkt-ca-terms li').then(($l) => expect([...$l].map((x) => x.textContent)).to.deep.eq(TERM_LINES))
    })
    cy.then(() => agreementsOf()).then((xs) => {
      const a = xs[0]
      expect(a.status).to.eq('OFFERED')
      expect(a.consigneeOrganizationId, 'the warehouse holds and sells it').to.eq(S.warehouse)
      expect(a.consigneeName).to.eq('MaxTheService')
      expect(a.terms).to.deep.eq(Object.assign({}, TERMS, { commissionPct: 10, shrinkageTolerancePct: 1 }))
      S.first = a
    })
    cy.then(() => agreementsOf('ACTIVE')).then((xs) => expect(xs, 'not active until the seller accepts').to.have.length(0))
  })

  it('MKT-5a-02 [MKT-R4.6] a term out of range is refused by name; a second offer waits for the first to be answered', () => {
    offer({ commissionPct: 60 }).then((r) => expectRefused(r, 'Commission: enter a percentage from 0 to 50.'))
    offer({ countEveryDays: 0 }).then((r) => expectRefused(r, 'Count the stock every: enter a number of days from 1 to 365.'))
    offer({ insuredBy: null }).then((r) => expectRefused(r, 'Choose who insures the stock, or none.'))
    offer().then((r) => expectRefused(r, `${S.first.agreementNo} is still waiting for ${SHOP}'s answer. Withdraw it before offering new terms.`))
    cy.then(() => agreementsOf()).then((xs) => expect(xs.filter((a) => a.status === 'OFFERED'), 'nothing new saved').to.have.length(1))
  })

  it('MKT-5a-03 [MKT-R2.1] [MKT-R4.6] the seller reads every term on its own screen and accepts: active (real UI)', () => {
    asShop()
    openMarketplace()
    cy.then(() => cy.contains('#mktConsignList .mkt-consign', S.first.agreementNo).should('be.visible')).within(() => {
      cy.get('.mkt-consign-status').should('have.text', 'Waiting for your answer')
      cy.get('.mkt-consign-terms li').then(($l) => expect([...$l].map((x) => x.textContent)).to.deep.eq(TERM_LINES))
      cy.get('.mkt-consign-accept').click()
    })
    cy.get('#mktConsignMsg').should('have.text', `${S.first.agreementNo} is active. Stock can be consigned to MaxTheService on these terms.`)
    cy.contains('#mktConsignList .mkt-consign', S.first.agreementNo).find('.mkt-consign-status').should('have.text', 'Active')
    cy.then(() => agreementsOf('ACTIVE')).then((xs) => {
      expect(xs.map((a) => a.agreementNo)).to.deep.eq([S.first.agreementNo])
      S.first = xs[0]
    })
  })

  it('MKT-5a-04 [MKT-R4.6] new terms never edit the old: accepted, they replace it, and the old terms stay as they were', () => {
    offer({ commissionPct: 8 }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      S.second = data(r.body)
    })
    asShop()
    cy.then(() => post(CA.decide, { id: S.second.id, action: 'ACCEPT', version: S.second.version }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => agreementsOf()).then((xs) => {
      const old = xs.find((a) => a.id === S.first.id)
      expect(old.status).to.eq('REPLACED')
      expect(old.replacedByNo).to.eq(S.second.agreementNo)
      expect(old.terms.commissionPct, 'the old terms are kept as accepted').to.eq(10)
      expect(xs.filter((a) => a.status === 'ACTIVE').map((a) => a.agreementNo)).to.deep.eq([S.second.agreementNo])
    })
    consignPanel()
    cy.then(() => cy.contains('#mktCaList .mkt-ca-row', S.first.agreementNo).find('.mkt-ca-status')
      .should('have.text', `Replaced by ${S.second.agreementNo}`))
  })

  it('MKT-5a-05 [MKT-R2.2] another seller neither sees nor answers it; the warehouse reads it and cannot answer it', () => {
    cy.loginAs(SELLER_B, 'Demo@2025!', '/getBusinessDashboardStats')
    cy.then(() => get(CA.mine)).then((r) => expect(data(r.body).agreements.map((a) => a.id)).not.to.include(S.second.id))
    cy.then(() => post(CA.decide, { id: S.second.id, action: 'END', reason: 'not mine' })).then((r) => expectRefused(r, 'No such consignment agreement.'))
    cy.loginAs(WAREHOUSE, 'Demo@2025!', '/getBusinessDashboardStats')
    cy.then(() => get(CA.mine)).then((r) => {
      expect(data(r.body).warehouse).to.eq(true)
      expect(data(r.body).agreements.find((a) => a.id === S.second.id).consignorName).to.eq(SHOP)
    })
    cy.then(() => post(CA.decide, { id: S.second.id, action: 'END', reason: 'warehouse' })).then((r) => expectRefused(r, 'No such consignment agreement.'))
  })

  it('MKT-5a-06 [MKT-R4.6] ending needs a reason, and so does declining; the operator reads the seller\'s reason (real UI)', () => {
    asShop()
    cy.then(() => post(CA.decide, { id: S.second.id, action: 'END', reason: ' ' }))
      .then((r) => expectRefused(r, 'Say why the agreement ends.'))
    openMarketplace()
    cy.then(() => cy.contains('#mktConsignList .mkt-consign', S.second.agreementNo).within(() => {
      cy.get('.mkt-consign-why').type('Moving to buy-outright transfers')
      cy.get('.mkt-consign-end').click()
    }))
    cy.then(() => cy.get('#mktConsignMsg').should('have.text', `${S.second.agreementNo} has ended. No new stock can be consigned under it.`))
    offer({ ownershipTransfer: 'ON_DELIVERY' }).then((r) => { S.third = data(r.body) })
    asShop()
    cy.then(() => post(CA.decide, { id: S.third.id, action: 'DECLINE', version: S.third.version }))
      .then((r) => expectRefused(r, 'Say why you decline, so MaxTheService can offer better terms.'))
    cy.then(() => post(CA.decide, { id: S.third.id, action: 'DECLINE', version: S.third.version, reason: 'Commission too high' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    consignPanel()
    cy.then(() => cy.contains('#mktCaList .mkt-ca-row', S.third.agreementNo).within(() => {
      cy.get('.mkt-ca-status').should('have.text', 'Declined')
      cy.get('.mkt-ca-reason').should('have.text', 'Reason: Commission too high')
    }))
    cy.then(() => agreementsOf('ACTIVE')).then((xs) => expect(xs, 'nothing active is left').to.have.length(0))
  })
})
