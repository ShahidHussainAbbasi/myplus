/**
 * MKT-5e — unsold consigned stock goes back to its owner.
 * Source R20.5 ("unsold-stock return"), R4.6 (the agreement's return-of-unsold-stock term: after how many days, at whose
 * cost), R2.1 (the stock stays the consignor's until it sells). Contract: microservices/docs/slices/mkt-5e-unsold-returns.md
 *
 * The owner asks for its stock back at any time (before the term, at its own cost); the warehouse may send back only
 * what has been held longer than the term. From the request the units are no longer sold; the warehouse hands them over
 * with the carrier's reference and the cost (debited to the owner when the owner bears it); the owner confirms it has
 * them, and its hold on them is let go. What is no longer held no longer stops the agreement ending.
 *
 * Mobile Distributor (owner.mobile@) is the consignor, as in 5b-5d, under terms where unsold stock goes back after 180
 * days at the owner's cost. Each run consigns its own product in two lots (3, then 2), so every number it asserts on is
 * its own; the 180 days are stood in for by dating the older lot's arrival back (task ageConsignedLot, local stack only).
 */
const { gate, uniq, SELLER_B, ok, data, post, get, expectRefused, seedPolicies, publishOffer, makeSeller, openMarketplace } =
  require('./mkt-helpers')

const WAREHOUSE = 'owner.warehouse@myplus.com'
const VALUE = 40000
const T = { view: '/mkt/transfers', offer: '/mkt/transferOffer', receive: '/mkt/transferReceive', list: '/mkt/consignedList',
  count: '/mkt/consignedCount', back: '/mkt/consignedReturn', decide: '/mkt/consignedReturnDecision' }
const CA = { list: '/platform/mkt/consignments', offer: '/platform/mkt/consignmentOffer', decide: '/mkt/consignmentDecision',
  mine: '/mkt/consignments' }
const TERMS = { ownershipTransfer: 'ON_SALE', priceSetBy: 'CONSIGNOR', commissionPct: 10, settleEveryDays: 7, reconcileEveryDays: 30,
  countEveryDays: 30, shrinkageTolerancePct: 1, damageBearer: 'CONSIGNEE', expiryBearer: 'CONSIGNOR', insuredBy: 'CONSIGNEE',
  claimWindowDays: 14, proofRequired: true, unsoldReturnDays: 180, unsoldReturnBearer: 'CONSIGNOR' }

gate('5e')('MKT-5e — unsold consigned stock goes back to its owner', () => {
  const run = uniq()
  const S = {}

  const as = (email) => cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')
  const view = () => get(T.view).then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body) })
  // null, not undefined: a .then() that yields undefined passes the view on instead
  const held = () => view().then((v) => (v.held || []).find((h) => h.mktProductId === S.mkt) || null)
  const backRow = () => `#mktHeld tr.mkt-held-back[data-product="${S.mkt}"]`
  const line = (no) => `${backRow()} .mkt-return[data-return-no="${no}"]`
  const level = () => cy.request('/productStockLevels').then((r) => {
    const l = (r.body.levels || {})[String(S.ownerProduct)]
    expect(l, 'inventory reports a level for the owner\'s product').to.exist
    return { onHand: Number(l.onHand), sellable: Number(l.sellable != null ? l.sellable : l.onHand) }
  })
  const account = () => get('/mkt/settlementAccount').then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body) })
  // what ending the agreement says is still held (the END rule counts every product of the pair)
  const stillHeld = () => get(CA.mine).then((r) => {
    const a = (data(r.body).agreements || []).find((x) => x.status === 'ACTIVE')
    expect(a, 'the owner\'s active agreement').to.exist
    return post(CA.decide, { id: a.id, version: a.version, action: 'END', reason: `gate 5e ${run}` }).then((e) => {
      expectRefused(e, 'must be sold or returned before it can end')
      return Number(/still holds (\d+) unit/.exec(e.body.message)[1])
    })
  })
  const consign = (qty) => {
    as(SELLER_B)
    cy.then(() => post(T.offer, { mktProductId: S.mkt, quantity: qty, unitPrice: VALUE, kind: 'CONSIGN', note: `5e ${run}` }))
      .then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); S.lots.push(data(r.body)) })
    as(WAREHOUSE)
    return cy.then(() => post(T.receive, { transferNo: S.lots[S.lots.length - 1].transferNo, version: S.lots[S.lots.length - 1].version }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  }

  before(() => {
    S.lots = []
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
        && t(a).ownershipTransfer === 'ON_SALE' && Number(t(a).unsoldReturnDays) === 180 && t(a).unsoldReturnBearer === 'CONSIGNOR')
      if (fits) { S.agreement = fits.agreementNo; return }
      cy.then(() => post(CA.offer, { consignorOrganizationId: S.owner, terms: TERMS, note: `gate 5e ${run}` })).then((o) => {
        expect(ok(o.body), JSON.stringify(o.body)).to.eq(true)
        const a = data(o.body)
        S.agreement = a.agreementNo
        as(SELLER_B)
        post(CA.decide, { id: a.id, action: 'ACCEPT', version: a.version }).then((d) => expect(ok(d.body), JSON.stringify(d.body)).to.eq(true))
      })
    })
    // a phone both carry; the owner has 8 and consigns 5 at Rs 40,000 a unit in two lots (3, then 2), and lists them
    seedPolicies(run).then((p) => {
      S.policies = p
      cy.loginAsOperator()
      cy.then(() => publishOffer(WAREHOUSE, { run, price: 52000, qty: 1, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))
        .then((o) => { S.mkt = o.mktProductId })
      cy.then(() => publishOffer(SELLER_B, { run, price: 52000, qty: 8, mktProductId: S.mkt, warrantyPolicyId: p.warranty,
        returnPolicyId: p.returns })).then((o) => { S.ownerProduct = o.sourceProductId })
    })
    consign(3)
    consign(2)
    as(SELLER_B)
    cy.then(() => post(T.list, { mktProductId: S.mkt, price: 52000, deliveryAreas: 'Karachi', promiseHours: 48,
      warrantyPolicyId: S.policies.warranty, returnPolicyId: S.policies.returns }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => stillHeld()).then((n) => { S.heldAtStart = n })
  })

  after(() => {
    // nothing this run started is left waiting: what is asked for is cancelled by whoever asked, what is handed over received
    as(SELLER_B)
    cy.then(() => held()).then((h) => (((h && h.back && h.back.returns) || []).filter((r) => r.byOwner && r.status === 'REQUESTED')
      .forEach((r) => post(T.decide, { id: r.id, version: r.version, action: 'CANCEL' }))))
    cy.then(() => held()).then((h) => (((h && h.back && h.back.returns) || []).filter((r) => r.status === 'DISPATCHED' || r.status === 'RECEIVING')
      .forEach((r) => post(T.decide, { id: r.id, version: r.version, action: 'RECEIVE' }))))
    as(WAREHOUSE)
    cy.then(() => held()).then((h) => (((h && h.back && h.back.returns) || []).filter((r) => !r.byOwner && r.status === 'REQUESTED')
      .forEach((r) => post(T.decide, { id: r.id, version: r.version, action: 'CANCEL' }))))
  })

  it('MKT-5e-01 [MKT-R20.5] [MKT-R2.1] the owner asks for 2 back in the real UI, before the term: at its cost, no longer sold, still its stock', () => {
    as(SELLER_B)
    cy.then(() => level()).then((l) => { S.before = l })
    cy.then(() => account()).then((a) => { S.balance = Number(a.balance) })
    openMarketplace()
    cy.then(() => cy.get(backRow()).find('.mkt-return-terms').should('have.text', 'Unsold stock goes back after 180 days, at the cost of the owner'))
    cy.then(() => cy.get(backRow()).find('.mkt-return-due').should('not.exist'))   // nothing is past the term yet
    cy.then(() => cy.get(backRow()).find('.mkt-return-hint').should('have.text',
      'Up to 5 can go back now (on the shelf and not promised to an order). Asked for before the term, you pay to send them.'))
    cy.then(() => cy.get(backRow()).find('.mkt-return-n').type('6'))
    cy.then(() => cy.get(backRow()).find('.mkt-return-ask').click())
    cy.get('#mktTransferMsg').should('have.text', 'Only 5 can go back now: the rest is promised to orders, counted missing or already going back.')
    cy.then(() => cy.get(backRow()).find('.mkt-return-n').should('have.value', '6').clear().type('2'))   // what was typed is kept
    cy.then(() => cy.get(backRow()).find('.mkt-return-ask').click())
    cy.get('#mktTransferMsg').should('contain', 'Asked for back as RT-')
      .and('contain', ': 2 × ').and('contain', ", at the owner's cost. No longer sold; the warehouse hands it over next.")
    cy.then(() => held()).then((h) => {
      const r = h.back.returns[0]
      S.r1 = r
      expect(r.status).to.eq('REQUESTED')
      expect(r.bearer, 'asked for before the term: the owner pays').to.eq('CONSIGNOR')
      expect(r.byOwner).to.eq(true)
      expect(h.units, 'still held, still the owner\'s, until it has them').to.eq(5)
      expect(h.listing.available, 'no longer sold from the moment it is asked for').to.eq(3)
      expect(h.back.returnable).to.eq(3)
    })
    cy.then(() => cy.get(line(S.r1.returnNo)).find('.mkt-return-status').should('have.text', 'Waiting to be handed over'))
    cy.then(() => cy.get(line(S.r1.returnNo)).find('.mkt-return-said').should('have.text',
      `${S.r1.returnNo} · 2 · asked for by the owner · at the cost of the owner`))
    cy.then(() => level()).then((l) => expect(l, 'nothing moved in the owner\'s books yet').to.deep.eq(S.before))
    // while it waits to go, the shelf holds units the balance no longer counts: no count until it is handed over
    as(WAREHOUSE)
    cy.then(() => post(T.count, { consignorOrgId: S.owner, mktProductId: S.mkt, counted: 5, idempotencyKey: `c-${run}` }))
      .then((r) => expectRefused(r, 'Some of this stock is waiting to go back to Mobile Distributor. Hand it over (or cancel the return) first, then count.'))
  })

  it('MKT-5e-02 [MKT-R4.6] the warehouse cannot send back what is inside the term; it hands RT over with a reference and the cost, debited to the owner', () => {
    as(WAREHOUSE)
    cy.then(() => post(T.back, { consignorOrgId: S.owner, mktProductId: S.mkt, units: 1, idempotencyKey: `w0-${run}` }))
      .then((r) => expectRefused(r, "None of Mobile Distributor's stock of this has been held 180 days yet (the agreement's term), so it cannot be sent back. The owner can ask for it."))
    openMarketplace()
    cy.then(() => cy.get(backRow()).find('.mkt-return-send').should('not.exist'))
    cy.then(() => cy.get(line(S.r1.returnNo)).find('.mkt-return-cancel').should('not.exist'))   // the owner asked; only it cancels
    cy.then(() => cy.get(line(S.r1.returnNo)).find('.mkt-return-cost').type('650'))
    cy.then(() => cy.get(line(S.r1.returnNo)).find('.mkt-return-dispatch').click())
    cy.get('#mktTransferMsg').should('have.text', "Give the carrier's reference, or who collected it.")
    cy.then(() => cy.get(line(S.r1.returnNo)).find('.mkt-return-cost').should('have.value', '650'))
    cy.then(() => cy.get(line(S.r1.returnNo)).find('.mkt-return-ref').type('TCS 4471-0915'))
    cy.then(() => cy.get(line(S.r1.returnNo)).find('.mkt-return-dispatch').click())
    cy.get('#mktTransferMsg').should('have.text',
      `Handed over: ${S.r1.returnNo}, Rs 650.00 charged to the owner. The owner confirms when it arrives.`)
    cy.then(() => cy.get(line(S.r1.returnNo)).find('.mkt-return-status').should('have.text', 'Handed over'))
    as(SELLER_B)
    cy.then(() => account()).then((a) => {
      expect(Number(a.balance), 'the owner bears the cost').to.eq(S.balance - 650)
      const e = a.entries.find((x) => x.ref === S.r1.returnNo)
      expect(e, 'a row on the owner\'s account, the return its reference').to.exist
      expect(Number(e.debit)).to.eq(650)
      expect(e.memo).to.contain('Consigned stock sent back').and.contain('TCS 4471-0915')
    })
    cy.then(() => level()).then((l) => expect(l, 'still not in the owner\'s free stock: it has not arrived').to.deep.eq(S.before))
    cy.then(() => post(T.decide, { id: S.r1.id, version: S.r1.version, action: 'CANCEL' }))
      .then((r) => expectRefused(r, `${S.r1.returnNo} is already handed over.`))
  })

  it('MKT-5e-03 [MKT-R20.5] [MKT-R2.1] the owner confirms it arrived (real UI): back in its free stock, out of the ledger', () => {
    as(SELLER_B)
    openMarketplace()
    cy.on('window:confirm', (t) => { expect(t).to.eq('Confirm 2 arrived back? They go back into your stock for sale.'); return true })
    cy.then(() => cy.get(line(S.r1.returnNo)).find('.mkt-return-ref-said').should('contain', 'TCS 4471-0915')
      .and('contain', '650.00').and('contain', '(charged to the owner)'))
    cy.then(() => cy.get(line(S.r1.returnNo)).find('.mkt-return-receive').click())
    cy.get('#mktTransferMsg').should('contain', 'Back in your stock: 2 × ').and('contain', `(${S.r1.returnNo}).`)
    cy.then(() => cy.get(line(S.r1.returnNo)).find('.mkt-return-status').should('have.text', 'Back with the owner'))
    cy.then(() => level()).then((l) => {
      expect(l.onHand, 'they never left its books').to.eq(S.before.onHand)
      expect(l.sellable, 'its hold on them is let go').to.eq(S.before.sellable + 2)
    })
    cy.then(() => held()).then((h) => {
      expect(h.units, 'out of the ledger').to.eq(3)
      expect(h.value).to.eq(3 * VALUE)
      expect(h.listing.available).to.eq(3)
      expect(h.back.returnable).to.eq(3)
    })
    cy.then(() => stillHeld()).then((n) => expect(n, 'what ending the agreement waits on fell by 2').to.eq(S.heldAtStart - 2))
    cy.then(() => post(T.decide, { id: S.r1.id, version: S.r1.version, action: 'RECEIVE' }))
      .then((r) => expectRefused(r, `${S.r1.returnNo} is already back with the owner.`))
  })

  it('MKT-5e-04 [MKT-R4.6] past the term (the older lot dated 181 days back) the warehouse sends it back in the real UI; then cancels it', () => {
    // the 2 that went back came out of the older lot (oldest first): 1 of it is left, past the term once it is dated back
    cy.task('ageConsignedLot', { transferNo: S.lots[0].transferNo, days: 181 }).then((n) => expect(n, 'one lot dated back').to.eq(1))
    as(WAREHOUSE)
    openMarketplace()
    cy.then(() => cy.get(backRow()).find('.mkt-return-due').should('have.text', '1 held past the term'))
    cy.then(() => cy.get(backRow()).find('.mkt-return-hint').should('have.text', 'Up to 1 have been held longer than the term.'))
    cy.then(() => cy.get(backRow()).find('.mkt-return-n').type('2'))
    cy.then(() => cy.get(backRow()).find('.mkt-return-send').click())
    cy.get('#mktTransferMsg').should('have.text',
      "Only 1 has been held longer than 180 days (the agreement's term), so only it can be sent back.")
    cy.then(() => cy.get(backRow()).find('.mkt-return-n').clear().type('1'))
    cy.then(() => cy.get(backRow()).find('.mkt-return-send').click())
    cy.get('#mktTransferMsg').should('contain', 'Sent back as RT-')
      .and('contain', ": 1 × ").and('contain', ", at the owner's cost. No longer sold; hand it over next, with the carrier's reference.")
    cy.then(() => held()).then((h) => {
      S.r2 = h.back.returns[0]
      expect(S.r2.byOwner).to.eq(false)
      expect(S.r2.reason).to.eq('UNSOLD')
      expect(S.r2.bearer, 'past the term the agreement says who pays').to.eq('CONSIGNOR')
      expect(h.listing.available).to.eq(2)
    })
    as(SELLER_B)
    openMarketplace()
    cy.then(() => cy.get(line(S.r2.returnNo)).find('.mkt-return-said').should('contain', 'sent back by MaxTheService'))
    cy.then(() => cy.get(line(S.r2.returnNo)).find('.mkt-return-cancel').should('not.exist'))   // the warehouse asked
    cy.then(() => cy.get(line(S.r2.returnNo)).find('.mkt-return-receive').should('not.exist'))   // not handed over yet
    as(WAREHOUSE)
    openMarketplace()
    cy.then(() => cy.get(line(S.r2.returnNo)).find('.mkt-return-cancel').click())
    cy.then(() => cy.get('#mktTransferMsg').should('have.text', `${S.r2.returnNo} cancelled. The units are on sale again.`))
    cy.then(() => cy.get(line(S.r2.returnNo)).find('.mkt-return-status').should('have.text', 'Cancelled'))
    cy.then(() => held()).then((h) => expect(h.listing.available, 'on sale again').to.eq(3))
  })

  it('MKT-5e-05 [MKT-R20.5] [MKT-R4.6] the owner takes everything back, handed over at no cost: nothing held, nothing charged', () => {
    as(SELLER_B)
    cy.then(() => account()).then((a) => { S.balance2 = Number(a.balance) })
    cy.then(() => post(T.back, { mktProductId: S.mkt, units: 3, note: 'closing the line', idempotencyKey: `all-${run}` })).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      S.r3 = data(r.body)
      expect(S.r3.bearer, 'more than is past the term: the owner pays').to.eq('CONSIGNOR')
    })
    cy.then(() => post(T.back, { mktProductId: S.mkt, units: 3, note: 'closing the line', idempotencyKey: `all-${run}` }))
      .then((r) => expect(data(r.body).returnNo, 'a repeat with the same key is the same return').to.eq(S.r3.returnNo))
    as(WAREHOUSE)
    cy.then(() => post(T.decide, { id: S.r3.id, version: S.r3.version, action: 'DISPATCH', cost: 0, reference: "Owner's rider collected" }))
      .then((r) => {
        expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
        expect(r.body.message).to.eq(`Handed over: ${S.r3.returnNo}. The owner confirms when it arrives.`)
        S.r3 = data(r.body)
      })
    as(SELLER_B)
    cy.then(() => post(T.decide, { id: S.r3.id, version: S.r3.version, action: 'RECEIVE' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => held()).then((h) => expect(h, 'nothing of it is held any more').to.eq(null))
    openMarketplace()
    cy.then(() => cy.get(`#mktHeld tr.mkt-held[data-product="${S.mkt}"]`).should('not.exist'))
    cy.then(() => level()).then((l) => expect(l.sellable, 'all 5 back in its free stock').to.eq(S.before.sellable + 5))
    cy.then(() => account()).then((a) => {
      expect(Number(a.balance), 'no cost, no charge').to.eq(S.balance2)
      expect(a.entries.map((e) => e.ref)).not.to.include(S.r3.returnNo)
    })
    cy.then(() => stillHeld()).then((n) => expect(n, 'none of this run\'s 5 stops the agreement ending').to.eq(S.heldAtStart - 5))
  })
})
