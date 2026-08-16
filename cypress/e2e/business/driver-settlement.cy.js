/**
 * OMS O7 D5 — driver settlement / remittance. The day-end cash-up, and backlog item B1.
 * Design: microservices/docs/slices/oms-O7-distribution-presales.md §13
 *
 * ── Why the central case is what it is ───────────────────────────────────────────────────────────────────
 *
 * D4 shipped green while the money went NOWHERE. `DeliveryService` stored `settlement` and `amountCollected`
 * on the delivery record and never called anything; its own javadoc claimed otherwise. The D4 gate could not
 * see that, because its assertions were `settlement === 'PAID'` and `amountCollected === 75` — i.e. that the
 * FORM DATA came back. Both pass whether or not a single rupee reaches AR. The artefact, not the property.
 *
 * So the case that carries this slice is **"settling a driver reduces the outlet's outstanding balance"**, read
 * from `/creditStanding`, which moves only when a receipt actually posts. Nothing about the shape of a stored
 * record can satisfy it.
 *
 * ── House rules this spec follows ────────────────────────────────────────────────────────────────────────
 *   * every case seeds its OWN outlet and its OWN collection — state is never inherited, because a failing
 *     case never reaches a cleanup step;
 *   * every "X is refused" case carries a POSITIVE CONTROL in the same case, so a refusal cannot pass because
 *     the endpoint is missing rather than because the rule works;
 *   * re-login in beforeEach — testIsolation clears the session between cases.
 */
describe('OMS O7 D5 — the driver hands over the cash', () => {
  const run = String(Date.now()).slice(-6)
  const PRODUCT = 'DsProd_' + run
  let productId

  /** A generous limit: the outlet must never be refused at dispatch for a reason this slice is not about. */
  const LIMIT = 1000000

  before(() => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name: PRODUCT, sku: 'DS' + run, sellingPrice: 100, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId, quantity: 900 }, failOnStatusCode: false,
      })
    })
  })

  beforeEach(() => {
    cy.loginAsMarketplaceOwner()
  })

  // ── fixtures ───────────────────────────────────────────────────────────────────────────────────────────

  /**
   * A fresh trade outlet, WITH a credit limit.
   *
   * The limit is not decoration: `/creditStanding` returns null for an uncapped customer ("a customer with no
   * limit is not at 0% of 0"), and this spec's central assertion reads `owed` off that endpoint. An outlet with
   * no limit would make the whole case assert against null — the exact false-pass shape this programme has hit
   * four times.
   */
  const seedOutlet = (ctx, label) => {
    ctx.outletName = 'DsOutlet_' + label + '_' + run + '_' + String(Date.now()).slice(-5)
    cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name: ctx.outletName, contact: '0300' + run, creditLimit: LIMIT },
    }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    cy.then(() => cy.request('/getUserCustomer')).then((r) => {
      const rows = (r.body.collection || r.body.data || []).filter((c) => c.name === ctx.outletName)
      expect(rows.length, 'the outlet was created exactly once').to.eq(1)
      ctx.outletId = rows[0].customerId || rows[0].id
      expect(ctx.outletId, 'and it has an id to bill').to.be.a('number')
    })
  }

  /** What `/creditStanding` says this outlet currently owes. */
  const owedBy = (customerId) =>
    cy.request('/creditStanding?customerId=' + customerId).then((r) => {
      expect(r.body.object, 'the outlet has a credit standing to read').to.not.be.null
      return Number(r.body.object.owed)
    })

  /**
   * Book → confirm → dispatch (which raises the invoice) → key the delivery with cash collected.
   *
   * `customerId` is sent on the booking deliberately: without it the dispatch resolves the buyer by NAME and
   * creates a duplicate outlet (D2c), and the invoice would then bill an account this spec is not watching —
   * so the balance assertion would read zero movement and be indistinguishable from the money never posting.
   */
  const collectCash = (ctx, opts) => {
    const qty = opts.qty, driver = opts.driver, collected = opts.collected
    cy.request({
      method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: {
        customerId: ctx.outletId, customerName: ctx.outletName, customerContact: '0300' + run,
        items: [{ productId, quantity: qty, price: 100, productName: PRODUCT }],
      },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      ctx.orderId = r.body.data.id
    })
    cy.then(() => cy.request({
      method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
      body: { id: ctx.orderId }, failOnStatusCode: false,
    })).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.then(() => cy.request('/getOrder?id=' + ctx.orderId)).then((r) => { ctx.lineId = r.body.data.items[0].id })

    cy.then(() => cy.request({
      method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { id: ctx.orderId, lines: [{ orderItemId: ctx.lineId, quantity: qty }], carrier: driver },
    })).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.then(() => cy.request('/getOrder?id=' + ctx.orderId)).then((r) => {
      const parcel = (r.body.data.shipments || [])[0]
      expect(parcel, 'the parcel exists').to.exist
      ctx.shipmentId = parcel.id
      ctx.invoiceNo = parcel.invoiceNo
      expect(ctx.invoiceNo, 'the dispatch raised an invoice for the driver to carry').to.match(/^INV-\d+$/)
    })

    cy.then(() => cy.request({
      method: 'POST', url: '/recordDelivery', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: {
        id: ctx.orderId, shipmentId: ctx.shipmentId, deliveredBy: driver,
        settlement: 'PAID', amountCollected: collected,
        lines: [{ orderItemId: ctx.lineId, deliveredQuantity: qty }],
      },
    })).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.then(() => cy.request('/getDeliveries?id=' + ctx.orderId)).then((r) => {
      const rows = r.body.data || []
      expect(rows.length, 'the delivery was keyed').to.eq(1)
      ctx.deliveryId = rows[0].id
      expect(ctx.deliveryId, 'the collection has an id to settle').to.be.a('number')
      // The stamp D5 added at keying. Without it the settlement has nobody to credit and refuses by name —
      // asserted here so a missing stamp reports as itself instead of as a mysterious refusal later.
      expect(rows[0].customerId, 'the collection knows WHICH account to credit').to.eq(ctx.outletId)
    })
  }

  const settle = (body) => cy.request({
    method: 'POST', url: '/settleDriver', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false, body,
  })

  // ── the cases ──────────────────────────────────────────────────────────────────────────────────────────

  it('THE CASE — settling the driver is what reduces the outlet\'s outstanding balance', () => {
    // D4 keyed "the shop paid 300" and the books never heard about it. This is the assertion that could not
    // have passed before D5, and the one no amount of correctly-stored form data can satisfy.
    const ctx = {}
    seedOutlet(ctx, 'main')
    cy.then(() => collectCash(ctx, { qty: 5, driver: 'Ahsan', collected: 300 }))

    let before
    cy.then(() => owedBy(ctx.outletId)).then((o) => {
      // POSITIVE CONTROL: the dispatch billed this account, so the read is live and there is a debt to clear.
      // Without this, "the balance is not what it was" could pass against an endpoint returning nothing.
      expect(o, 'positive control: the dispatch put a receivable on THIS outlet').to.be.greaterThan(0)
      before = o
    })

    // And the money has NOT posted yet — keying a delivery records the fact, it does not settle it. If this
    // ever fails, the receipt moved back to keying time and the day-end count has stopped being mandatory.
    cy.then(() => cy.request('/getDriverCollections?size=200')).then((r) => {
      const mine = ((r.body.data || {}).content || []).filter((c) => c.id === ctx.deliveryId)
      expect(mine.length, 'the collection is waiting to be handed over').to.eq(1)
      // == null covers both null and an omitted key. Safe to be lenient HERE only because the positive
      // counterpart — `settlementId` is a number after settling — is asserted strictly in the receipts case,
      // and that is what would catch the field vanishing altogether.
      expect(mine[0].settlementId == null, 'and it has not been remitted').to.eq(true)
      expect(Number(mine[0].amountCollected), 'for the amount the shop paid at the door').to.eq(300)
    })

    cy.then(() => settle({
      deliveryIds: [ctx.deliveryId], countedAmount: 300,
      depositReference: 'SLIP-' + run, note: '',
    })).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const d = r.body.data
      expect(d.settlementNo, 'a real DS- document').to.match(/^DS-\d+$/)
      expect(Number(d.declaredAmount), 'declared is computed from the collections, not sent by the client').to.eq(300)
      expect(Number(d.countedAmount)).to.eq(300)
      expect(Number(d.varianceAmount), 'the bag matched').to.eq(0)
      expect(d.driverName, 'attributed to the driver named on the collections').to.eq('Ahsan')
      expect(d.settledByName, 'and stamped with who signed it off').to.contain('owner.marketplace')
    })

    // ── the property ──────────────────────────────────────────────────────────────────────────────────
    cy.then(() => owedBy(ctx.outletId)).then((after) => {
      expect(after, 'the 300 the shop paid has reached the ledger').to.be.closeTo(before - 300, 0.01)
    })
  })

  it('the receipts are real documents, one per collection', () => {
    // Split from the case above on purpose: if finance-service is down the voucher number comes back null
    // while the allocation still happens, and that should read as its own named failure rather than sinking
    // the balance assertion.
    const ctx = {}
    seedOutlet(ctx, 'rcpt')
    cy.then(() => collectCash(ctx, { qty: 4, driver: 'Ahsan', collected: 250 }))
    cy.then(() => settle({ deliveryIds: [ctx.deliveryId], countedAmount: 250 })).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const receipts = r.body.data.receipts || []
      expect(receipts.length, 'one receipt for the one collection').to.eq(1)
      expect(receipts[0], 'a real receipt from the shared ledger').to.match(/^RCPT-/)
    })
    // And the receipt is reachable FROM the delivery, so a shopkeeper disputing "you say I still owe this" is
    // answered from one row. D10: a stamped field no read returns is invisible.
    cy.then(() => cy.request('/getDeliveries?id=' + ctx.orderId)).then((r) => {
      const row = (r.body.data || [])[0]
      expect(row.receiptNo, 'the collection carries the receipt that cleared it').to.match(/^RCPT-/)
      expect(row.settlementId, 'and the remittance it went out in').to.be.a('number')
    })
  })

  it('a collection is OPEN until it is counted, and gone from the list afterwards', () => {
    // The state machine, and the whole control surface: a row on this list is a claim that somebody is
    // holding the company's money.
    const ctx = {}
    seedOutlet(ctx, 'state')
    cy.then(() => collectCash(ctx, { qty: 3, driver: 'Ahsan', collected: 150 }))

    cy.then(() => cy.request('/getDriverCollections?size=200')).then((r) => {
      const mine = ((r.body.data || {}).content || []).filter((c) => c.id === ctx.deliveryId)
      expect(mine.length, 'positive control: it is on the open list before we settle').to.eq(1)
      expect(mine[0].receiptNo == null, 'with no receipt yet').to.eq(true)
    })

    cy.then(() => settle({ deliveryIds: [ctx.deliveryId], countedAmount: 150 }))
      .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.then(() => cy.request('/getDriverCollections?size=200')).then((r) => {
      const content = (r.body.data || {}).content || []
      // The negative half, with the positive control above making it evidence rather than an empty response.
      expect(content.filter((c) => c.id === ctx.deliveryId).length,
        'counted cash is no longer waiting to be handed over').to.eq(0)
    })
  })

  it('a SHORT bag is refused without an explanation — and recorded, in full, with one', () => {
    // B1 in one case. The refusal is the cheap control; the second half is the part that matters, because a
    // driver being short does not mean the SHOP did not pay. The receipt still posts for what was declared.
    const ctx = {}
    seedOutlet(ctx, 'short')
    cy.then(() => collectCash(ctx, { qty: 6, driver: 'Ahsan', collected: 500 }))

    let before
    cy.then(() => owedBy(ctx.outletId)).then((o) => {
      expect(o, 'positive control: there is a receivable to clear').to.be.greaterThan(0)
      before = o
    })

    cy.then(() => settle({ deliveryIds: [ctx.deliveryId], countedAmount: 400 })).then((r) => {
      expect(r.body.success, 'a 100 shortfall with no explanation is refused').to.not.eq(true)
      expect(String(r.body.message || ''), 'and it says so in words, not just a minus sign').to.match(/short/i)
    })
    // Nothing was posted by the refusal: the claim happens before any receipt, so a refused settlement leaves
    // the collection exactly where it was. Asserted, because "it was refused" and "it changed nothing" are
    // two different facts and only the second one protects the books.
    cy.then(() => owedBy(ctx.outletId))
      .then((o) => expect(o, 'a refused settlement moved no money').to.eq(before))

    // POSITIVE CONTROL for the refusal: the same request, explained, goes through.
    cy.then(() => settle({
      deliveryIds: [ctx.deliveryId], countedAmount: 400,
      note: 'Ahsan paid a 100 fuel advance out of the bag', depositReference: 'SLIP2-' + run,
    })).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(Number(r.body.data.varianceAmount), 'short is NEGATIVE, as the till Z report has it').to.eq(-100)
      expect(Number(r.body.data.declaredAmount)).to.eq(500)
    })

    // The shop paid 500 and their account must show 500 cleared — the driver being 100 short is a matter
    // between the company and Ahsan, not something the outlet should be billed for.
    cy.then(() => owedBy(ctx.outletId)).then((after) => {
      expect(after, 'the outlet is credited with what it PAID, not with what was counted')
        .to.be.closeTo(before - 500, 0.01)
    })
  })

  it('one bag, one driver — a mixed remittance is refused', () => {
    // `deliveredBy` is free text (D4: "a note, not an identity"), so a settlement that spanned two names would
    // make its driver column a lie and its variance unattributable to anybody.
    const ctx = {}, other = {}
    seedOutlet(ctx, 'mixA')
    cy.then(() => collectCash(ctx, { qty: 2, driver: 'Ahsan', collected: 100 }))
    cy.then(() => { other.outletId = ctx.outletId; other.outletName = ctx.outletName })
    cy.then(() => collectCash(other, { qty: 2, driver: 'Bilal', collected: 120 }))

    cy.then(() => settle({ deliveryIds: [ctx.deliveryId, other.deliveryId], countedAmount: 220 })).then((r) => {
      expect(r.body.success, 'two drivers in one bag is refused').to.not.eq(true)
      expect(String(r.body.message || '')).to.match(/more than one driver/i)
      expect(String(r.body.message || ''), 'and it names them, so the admin can split the bag').to.match(/Ahsan/)
    })

    // POSITIVE CONTROL: one driver at a time works, so the refusal above is the rule and not a broken endpoint.
    cy.then(() => settle({ deliveryIds: [ctx.deliveryId], countedAmount: 100 })).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.driverName).to.eq('Ahsan')
    })
  })

  it('a collection cannot be handed over twice — and the second attempt moves NO money', () => {
    // The once-only guarantee is structural (one settlement_id column, claimed by an UPDATE … WHERE NULL).
    // The refusal message alone would pass while a duplicate receipt posted, so the balance is the assertion.
    const ctx = {}
    seedOutlet(ctx, 'twice')
    cy.then(() => collectCash(ctx, { qty: 4, driver: 'Ahsan', collected: 200 }))

    let before
    cy.then(() => owedBy(ctx.outletId)).then((o) => { before = o })

    cy.then(() => settle({ deliveryIds: [ctx.deliveryId], countedAmount: 200 }))
      .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    let afterFirst
    cy.then(() => owedBy(ctx.outletId)).then((o) => {
      expect(o, 'positive control: the first settlement DID move the money').to.be.closeTo(before - 200, 0.01)
      afterFirst = o
    })

    cy.then(() => settle({ deliveryIds: [ctx.deliveryId], countedAmount: 200 })).then((r) => {
      expect(r.body.success, 'the second attempt is refused').to.not.eq(true)
      expect(String(r.body.message || '')).to.match(/still open|already/i)
    })
    cy.then(() => owedBy(ctx.outletId))
      .then((o) => expect(o, 'and it did not credit the outlet a second time').to.eq(afterFirst))
  })

  it('another tenant can neither see nor settle this org\'s cash', () => {
    // Anti-IDOR on financial data, and the fixture is chosen so it proves SCOPING rather than authority:
    // owner.business is an OWNER, so it clears the same @PreAuthorize the marketplace owner does. It simply
    // belongs to a different org. This is the shape of the leak D2 introduced and caught.
    const ctx = {}
    seedOutlet(ctx, 'idor')
    cy.then(() => collectCash(ctx, { qty: 2, driver: 'Ahsan', collected: 110 }))
    cy.then(() => settle({ deliveryIds: [ctx.deliveryId], countedAmount: 110 })).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      ctx.settlementId = r.body.data.id
    })

    // POSITIVE CONTROL, in this case: the OWNER can read it back. Without this, "the other tenant got
    // nothing" would pass against a missing endpoint — which is exactly how D2's anti-IDOR case went green
    // while proving nothing at all.
    cy.then(() => cy.request('/getDriverSettlement?id=' + ctx.settlementId)).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.settlementNo).to.match(/^DS-\d+$/)
    })

    cy.then(() => cy.loginAsOwner())
    cy.then(() => cy.request({ url: '/getDriverSettlement?id=' + ctx.settlementId, failOnStatusCode: false }))
      .then((r) => {
        expect(r.body.success, 'another tenant reads it as absent, exactly as a missing one')
          .to.not.eq(true)
      })
    cy.then(() => cy.request('/getDriverCollections?size=200')).then((r) => {
      const content = ((r.body.data || {}).content) || []
      expect(content.filter((c) => c.id === ctx.deliveryId).length,
        'and it is not on their open list either').to.eq(0)
    })
  })

  it('a booker cannot settle the cash they collected against', () => {
    // Segregation of duties, the same control D2 built: the person in the field does not close the books on
    // their own round. A 403 from the SERVER, not a hidden button.
    const ctx = {}
    seedOutlet(ctx, 'authz')
    cy.then(() => collectCash(ctx, { qty: 2, driver: 'Ahsan', collected: 90 }))

    cy.then(() => cy.loginAsOrderBooker())
    cy.then(() => settle({ deliveryIds: [ctx.deliveryId], countedAmount: 90 }))
      .then((r) => expect(r.body.success, 'a rep with no ADMIN_PRIVILEGE is refused').to.not.eq(true))

    // POSITIVE CONTROL: the admin CAN, so the refusal is the authority gate and not a broken route.
    cy.then(() => cy.loginAsMarketplaceOwner())
    cy.then(() => settle({ deliveryIds: [ctx.deliveryId], countedAmount: 90 }))
      .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
  })

  it('the screen exists, lists the open cash, and names the shortfall in words', () => {
    // R7 — three capabilities in this programme shipped with nothing able to reach them. And the live variance
    // is where an admin actually meets this control, so it is asserted here rather than trusted.
    const ctx = {}
    seedOutlet(ctx, 'ui')
    cy.then(() => collectCash(ctx, { qty: 3, driver: 'Ahsan', collected: 175 }))

    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showDriverSettlement')
    cy.window().then((w) => w.showDriverSettlement())
    cy.get('#DriverSettlementDiv').should('be.visible')
    cy.get('#dsBody tr').should('have.length.greaterThan', 0)

    // Read the declared total the screen computed, then count 100 less than it.
    cy.get('#dsDeclared').invoke('text').then((txt) => {
      const declared = Number(txt)
      expect(declared, 'the screen adds up the ticked collections').to.be.greaterThan(0)
      cy.get('#dsCounted').clear().type(String(declared - 100))
      cy.get('#dsVariance').should('contain', '-100.00').and('contain', 'short')
      // The requirement is stated when it applies, not sprung as a refusal after Settle is pressed.
      cy.get('#dsVarianceHint').should('be.visible')
      // POSITIVE CONTROL for that hint: it is not simply always on.
      cy.get('#dsCounted').clear().type(String(declared))
      cy.get('#dsVarianceHint').should('not.be.visible')
    })
  })
})
