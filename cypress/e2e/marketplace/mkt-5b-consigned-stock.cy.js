/**
 * MKT-5b — a seller consigns stock to the MaxTheService warehouse under its active agreement, and keeps owning it.
 * Source R2.1 (the consignor owns, the consignee holds), R4.5 (CONSIGNMENT: owner = consignor, custodian = consignee),
 * R20.5 (the consigned stock ledger). Contract: microservices/docs/slices/mkt-5b-consigned-stock.md
 *
 * Received on consignment, nothing is bought: no sale in the seller's books, no purchase in the warehouse's. The units
 * are set aside in the SELLER's books (a hold that never lapses), so the shop can no longer sell them, and counted into
 * the consigned stock ledger. An agreement cannot end while the warehouse holds stock under it.
 *
 * The consignor is Mobile Distributor (owner.mobile@), not the 5a shop: consigned stock cannot go back until the
 * unsold-returns slice, so its agreement stays active between runs, and 5a's own agreement cycle stays clean.
 * Each run lists its own product, so the ledger rows a run asserts on are its own.
 */
const { gate, uniq, SELLER_A, SELLER_B, ok, data, msg, post, get, expectRefused, seedPolicies, publishOffer, makeSeller,
  openMarketplace } = require('./mkt-helpers')

const WAREHOUSE = 'owner.warehouse@myplus.com'
const OWNER = 'Mobile Distributor'
const T = { view: '/mkt/transfers', offer: '/mkt/transferOffer', reject: '/mkt/transferReject', receive: '/mkt/transferReceive' }
const CA = { list: '/platform/mkt/consignments', offer: '/platform/mkt/consignmentOffer', opDecide: '/platform/mkt/consignmentDecision',
  mine: '/mkt/consignments', decide: '/mkt/consignmentDecision' }
const TERMS = { ownershipTransfer: 'ON_SALE', priceSetBy: 'CONSIGNOR', commissionPct: 10, settleEveryDays: 7, reconcileEveryDays: 30,
  countEveryDays: 30, shrinkageTolerancePct: 1, damageBearer: 'CONSIGNEE', expiryBearer: 'CONSIGNOR', insuredBy: 'CONSIGNEE',
  claimWindowDays: 14, proofRequired: true, unsoldReturnDays: 180, unsoldReturnBearer: 'CONSIGNOR' }

gate('5b')('MKT-5b — consigned stock into the MaxTheService warehouse: it stays the seller\'s', () => {
  const run = uniq()
  const S = {}

  const as = (email) => cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')
  const view = () => get(T.view).then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body) })
  const row = (no) => `#mktTransfers tr[data-transfer-no="${no}"]`
  const sellable = () => cy.request('/productStockLevels').then((r) => {
    expect(r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)
    const lvl = (r.body.levels || {})[String(S.ownerProduct)]
    expect(lvl, 'inventory reports a level for the owner\'s product').to.exist
    return Number(lvl.sellable != null ? lvl.sellable : lvl.onHand)
  })
  const agreementsOf = (shop, status) => {
    cy.loginAsOperator()
    return get(`${CA.list}?status=${status}`).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      return data(r.body).filter((a) => a.consignorOrganizationId === shop)
    })
  }

  before(() => {
    makeSeller(SELLER_A)                        // 0a leaves the shared shop's module off (GATE-RUNBOOK §5)
    makeSeller(SELLER_B)
    makeSeller(WAREHOUSE, 'Central Warehouse')
    cy.loginAsOperator()
    cy.orgOf(WAREHOUSE).then((o) => { S.warehouse = o.id })
    cy.then(() => get('/platform/mkt/warehouse')).then((r) => {
      if (data(r.body).organizationId !== S.warehouse) post('/platform/mkt/warehouse', { organizationId: S.warehouse })
    })
    cy.orgOf(SELLER_A).then((o) => { S.shop = o.id })
    cy.orgOf(SELLER_B).then((o) => { S.owner = o.id })
    // the shop of 5b-01 has no agreement: withdraw and end what an earlier run of 5a may have left (it never consigns)
    cy.then(() => agreementsOf(S.shop, 'OFFERED')).then((xs) => xs.forEach((a) => post(CA.opDecide, { id: a.id, action: 'WITHDRAW', version: a.version })))
    cy.then(() => agreementsOf(S.shop, 'ACTIVE')).then((xs) => xs.forEach((a) => post(CA.opDecide, { id: a.id, action: 'END', version: a.version, reason: 'gate 5b setup' })))
    // the owner has an active agreement: offered by the operator, accepted by its owner, unless an earlier run left one
    cy.then(() => agreementsOf(S.owner, 'ACTIVE')).then((xs) => {
      if (xs.length) { S.agreement = xs[0].agreementNo; return }
      cy.then(() => agreementsOf(S.owner, 'OFFERED')).then((ys) => ys.forEach((a) => post(CA.opDecide, { id: a.id, action: 'WITHDRAW', version: a.version })))
      cy.then(() => post(CA.offer, { consignorOrganizationId: S.owner, terms: TERMS, note: `gate 5b ${run}` })).then((r) => {
        expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
        const a = data(r.body)
        S.agreement = a.agreementNo
        as(SELLER_B)
        post(CA.decide, { id: a.id, action: 'ACCEPT', version: a.version }).then((d) => expect(ok(d.body), JSON.stringify(d.body)).to.eq(true))
      })
    })
    // one phone both carry: the warehouse lists it (so it can receive it), the owner has 5 of it on its shelf
    seedPolicies(run).then((p) => {
      cy.loginAsOperator()
      cy.then(() => publishOffer(WAREHOUSE, { run, price: 52000, qty: 1, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))
        .then((o) => { S.mkt = o.mktProductId })
      cy.then(() => publishOffer(SELLER_B, { run, price: 52000, qty: 5, mktProductId: S.mkt, warrantyPolicyId: p.warranty,
        returnPolicyId: p.returns })).then((o) => { S.ownerProduct = o.sourceProductId })
    })
  })

  it('MKT-5b-01 [MKT-R2.1] [MKT-R4.6] without an active agreement there is no consignment: the form only sells, and the server refuses it', () => {
    as(SELLER_A)
    cy.then(() => view()).then((v) => expect(v.consignUnder, 'no agreement').to.eq(null))
    openMarketplace()
    cy.get('#mktTransferForm').should('be.visible')
    cy.get('#mktTrKind').should('not.be.visible')
    cy.get('#mktTrSend').should('have.text', 'Send to MaxTheService')
    cy.then(() => post(T.offer, { mktProductId: S.mkt, quantity: 1, unitPrice: 40000, kind: 'CONSIGN' })).then((r) => expectRefused(r,
      'You have no active consignment agreement with MaxTheService. Accept its terms under Consignment agreements first, or sell the stock to MaxTheService instead.'))
  })

  it('MKT-5b-02 [MKT-R2.1] [MKT-R4.5] the owner consigns 3 under its agreement, in the real form; the row names the agreement', () => {
    as(SELLER_B)
    cy.then(() => sellable()).then((n) => { S.freeBefore = n })
    openMarketplace()
    cy.get('#mktTrKind').should('be.visible').select('CONSIGN')
    cy.get('#mktTrPrice').should('have.attr', 'placeholder', 'Value a unit')
    cy.get('#mktTrSend').should('have.text', 'Consign to MaxTheService')
    cy.then(() => cy.get('#mktTransferHint').should('contain', `On consignment under ${S.agreement}: the stock stays yours.`))
    cy.get('#mktTrProduct').select(String(S.mkt), { force: true })
    cy.get('#mktTrQty').clear().type('3')
    cy.get('#mktTrPrice').clear().type('40000')
    cy.get('#mktTrNote').clear().type(`consigned ${run}`)
    cy.get('#mktTrSend').click()
    cy.then(() => cy.get('#mktTransferMsg').should('contain', `on consignment as ST-`).and('contain', `under ${S.agreement}. It stays yours`))
    cy.get('#mktTransferMsg').invoke('text').then((t) => { S.consigned = t.match(/ST-\d+/)[0] })
    cy.then(() => cy.get(row(S.consigned)).should('contain', 'Sent, waiting for the warehouse')
      .and('contain', `On consignment, ${S.agreement}`).and('contain', '120,000'))
    // sent is not received: the shop's stock is untouched until the warehouse takes it
    cy.then(() => sellable()).then((n) => expect(n, 'still free in the shop').to.eq(S.freeBefore))
  })

  it('MKT-5b-03 [MKT-R2.1] [MKT-R4.5] the warehouse receives it: nothing is bought; the 3 are set aside in the owner\'s books (real UI)', () => {
    as(WAREHOUSE)
    cy.then(() => get('/getAllPurchase')).then((r) => {
      const rows = (r.body && (r.body.collection || r.body.data)) || []
      S.purchasesBefore = (Array.isArray(rows) ? rows : []).length
    })
    openMarketplace()
    cy.then(() => {
      cy.get(row(S.consigned)).should('contain', `From ${OWNER}`).and('contain', `On consignment, ${S.agreement}`)
      cy.get(row(S.consigned)).find('.mkt-tr-receive').click()
      cy.get('#mktTransferMsg').should('have.text',
        `Received on consignment under ${S.agreement}. It is ${OWNER}'s stock on your shelf: nothing is owed until it sells.`)
      cy.get(row(S.consigned)).should('contain', 'Received').and('not.contain', 'INV-')
    })
    cy.then(() => view()).then((v) => {
      const t = v.transfers.find((x) => x.transferNo === S.consigned)
      expect(t.saleInvoiceNo, 'no sale in the owner\'s books').to.eq(null)
      expect(t.purchaseId, 'no purchase in the warehouse\'s books').to.eq(null)
    })
    cy.then(() => get('/getAllPurchase')).then((r) => {
      const rows = (r.body && (r.body.collection || r.body.data)) || []
      expect((Array.isArray(rows) ? rows : []).length, 'the warehouse bought nothing').to.eq(S.purchasesBefore)
    })
    cy.then(() => post(T.receive, { transferNo: S.consigned })).then((r) => expectRefused(r, 'This transfer is already received.'))
    as(SELLER_B)
    cy.then(() => sellable()).then((n) => expect(n, '3 set aside: the shop can no longer sell them').to.eq(S.freeBefore - 3))
  })

  it('MKT-5b-04 [MKT-R20.5] the consigned stock list: the owner reads its own 3 units; the warehouse reads them under the owner\'s name (real UI)', () => {
    as(SELLER_B)
    openMarketplace()
    cy.get('#mktHeldBox').should('be.visible')
    cy.get('#mktHeld .mkt-held-owner').should('not.be.visible')
    cy.then(() => cy.get(`#mktHeld tr.mkt-held[data-product="${S.mkt}"]`).should('contain', '120,000')
      .find('.mkt-held-units').should('have.text', '3'))
    as(WAREHOUSE)
    openMarketplace()
    cy.get('#mktHeld .mkt-held-owner').should('be.visible')
    cy.then(() => cy.get(`#mktHeld tr.mkt-held[data-product="${S.mkt}"]`).should('contain', OWNER)
      .find('.mkt-held-units').should('have.text', '3'))
    as(SELLER_A)
    cy.then(() => view()).then((v) => expect(v.held.map((h) => h.mktProductId), 'another seller reads none of it').not.to.include(S.mkt))
  })

  it('MKT-5b-05 [MKT-R2.1] an agreement cannot end while the warehouse holds stock under it, by the owner or by the operator', () => {
    cy.then(() => agreementsOf(S.owner, 'ACTIVE')).then((xs) => {
      expect(xs, 'one active agreement').to.have.length(1)
      S.active = xs[0]
      post(CA.opDecide, { id: S.active.id, action: 'END', version: S.active.version, reason: 'gate 5b operator' })
        .then((r) => expectRefused(r, 'units consigned under this agreement. They must be sold or returned before it can end.'))
    })
    as(SELLER_B)
    cy.then(() => post(CA.decide, { id: S.active.id, action: 'END', version: S.active.version, reason: 'gate 5b owner' }))
      .then((r) => expectRefused(r, 'They must be sold or returned before it can end.'))
    cy.then(() => agreementsOf(S.owner, 'ACTIVE')).then((xs) => expect(xs.map((a) => a.id)).to.include(S.active.id))
  })

  it('MKT-5b-06 [MKT-R2.1] more than the owner has free cannot be received: nothing is set aside or counted; the warehouse says why (real UI)', () => {
    as(SELLER_B)
    cy.then(() => post(T.offer, { mktProductId: S.mkt, quantity: 50, unitPrice: 40000, kind: 'CONSIGN' })).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      S.tooMany = data(r.body).transferNo
    })
    as(WAREHOUSE)
    openMarketplace()
    cy.then(() => {
      cy.get(row(S.tooMany)).find('.mkt-tr-receive').click()
      cy.get('#mktTransferMsg').should('contain', 'The seller\'s books do not show 50 × ').and('contain', 'free to set aside, so nothing was received')
      cy.get(row(S.tooMany)).should('contain', 'Sent, waiting for the warehouse')
      cy.get(row(S.tooMany)).find('.mkt-tr-reason').type('More than the owner has free')
      cy.get(row(S.tooMany)).find('.mkt-tr-reject').click()
      cy.get('#mktTransferMsg').should('have.text', 'Not taken. The seller sees your reason.')
    })
    cy.then(() => cy.get(`#mktHeld tr.mkt-held[data-product="${S.mkt}"] .mkt-held-units`).should('have.text', '3'))
    as(SELLER_B)
    cy.then(() => sellable()).then((n) => expect(n, 'unchanged by the refused Receive').to.eq(S.freeBefore - 3))
  })
})
