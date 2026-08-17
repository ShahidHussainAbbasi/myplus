/**
 * OMS O8 slice 5 — the sheet comes BACK: keying a whole round in one pass, and the cash-up that follows.
 *
 * <h3>The moment this covers</h3>
 * The salesman returns with the route sheet, an amount written against each line and "CR" against the shops
 * that paid nothing. One action turns that page into deliveries and receipts.
 *
 * <h3>The two properties that matter</h3>
 * <ol>
 *   <li><b>A stop that paid NOTHING still belongs to the round.</b> This is the bug this slice fixes: a receipt
 *       for zero is correctly refused by the books, and the settlement used to hand it one — so a single "CR"
 *       row failed the whole cash-up and the cashier had to know to exclude exactly the rows that paid nothing.
 *       The sheet lists every stop, so the settlement must cope with every stop.</li>
 *   <li><b>The money lands in the books.</b> Asserted against each outlet's balance read independently, not
 *       against the response — a keying screen that reported success while moving nothing is precisely how D4's
 *       "settle" once passed.</li>
 * </ol>
 *
 * <h3>And one that keeps it usable</h3>
 * Pressing it twice must be harmless. It is one button over twenty-nine stops; an operator who is unsure will
 * press it again, and the second answer has to be "nothing more to do".
 */
describe('OMS O8 — keying the round back in from the marked-up sheet', () => {
  const run = String(Date.now()).slice(-6)
  const PRICE = 100
  const ctx = { outlet: {}, order: {} }

  const iso = (d) => d.toISOString().slice(0, 10)
  const TODAY = iso(new Date())

  before(() => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name: 'RkProd_' + run, sku: 'RK' + run, sellingPrice: PRICE, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      ctx.productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId: ctx.productId, quantity: 300 }, failOnStatusCode: false,
      })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    // Three shops: one pays in full, one pays part, one pays NOTHING. The third is the whole point.
    ;['paid', 'part', 'nil'].forEach((k) => {
      const name = 'RkOutlet_' + k + '_' + run
      cy.then(() => cy.request({
        method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
        body: { name, contact: '0300' + run, address: 'ZAHIR PIR', creditLimit: 500000 },
      })).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
      cy.then(() => cy.request('/getUserCustomer')).then((r) => {
        const rows = (r.body.collection || r.body.data || []).filter((c) => c.name === name)
        expect(rows.length, name + ' created once').to.eq(1)
        ctx.outlet[k] = { id: rows[0].customerId || rows[0].id, name }
      })
    })
  })

  const owed = (outletId) => cy.request('/creditStanding?customerId=' + outletId).then((r) => {
    expect(r.body.object, 'the outlet has a credit standing to read').to.not.be.null
    return Number(r.body.object.owed)
  })

  /** Book → confirm → dispatch: a stop out on the van, with an invoice against it. */
  const dispatchTo = (key, qty) => {
    let order
    cy.then(() => cy.request({
      method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: {
        customerId: ctx.outlet[key].id, customerName: ctx.outlet[key].name, customerContact: '0300' + run,
        shippingAddress: 'ZAHIR PIR', paymentMode: 'CREDIT',
        items: [{ productId: ctx.productId, productName: 'RkProd_' + run, quantity: qty, price: PRICE }],
      },
    })).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      order = r.body.data
      return cy.request({
        method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
        body: { id: order.id }, failOnStatusCode: false,
      })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.then(() => cy.request({
      method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { id: order.id, carrier: 'SAEED AHMED', lines: [{ orderItemId: order.items[0].id, quantity: qty }] },
    })).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    return cy.then(() => cy.request('/getOrder?id=' + order.id)).then((r) => r.body.data)
  }

  const keyRound = (body) => cy.request({
    method: 'POST', url: '/keyRound', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false, body,
  })

  // ── the round ─────────────────────────────────────────────────────────────────────────────────────────

  it('sets up three stops on the van: 500, 300 and 200 owed', () => {
    cy.loginAsMarketplaceOwner()
    dispatchTo('paid', 5).then((o) => { ctx.order.paid = o })     // 500
    cy.then(() => dispatchTo('part', 3)).then((o) => { ctx.order.part = o })   // 300
    cy.then(() => dispatchTo('nil', 2)).then((o) => { ctx.order.nil = o })     // 200

    cy.then(() => owed(ctx.outlet.paid.id)).then((v) => expect(v, 'dispatch billed it').to.eq(500))
    cy.then(() => owed(ctx.outlet.part.id)).then((v) => expect(v).to.eq(300))
    cy.then(() => owed(ctx.outlet.nil.id)).then((v) => expect(v).to.eq(200))
  })

  it('the round sheet offers the order id the keying posts against', () => {
    cy.loginAsMarketplaceOwner()
    cy.request({ url: '/roundSheet', qs: { from: TODAY, to: TODAY }, failOnStatusCode: false }).then((r) => {
      const stops = (r.body.data || {}).stops || []
      const mine = stops.filter((s) => [ctx.order.paid.id, ctx.order.part.id, ctx.order.nil.id].indexOf(s.orderId) !== -1)
      // Without orderId on the stop the screen would have nothing to post against — it is the one field the
      // printed sheet does not need and the keying screen cannot work without.
      expect(mine.length, 'all three stops carry their order id').to.eq(3)
    })
  })

  it('THE CASE — one request keys all three, and the stop that paid NOTHING does not fail the batch', () => {
    cy.loginAsMarketplaceOwner()
    keyRound({
      salesman: 'SAEED AHMED',
      countedAmount: 650,                 // 500 + 150 + 0
      depositReference: 'SLIP-' + run,
      stops: [
        { orderId: ctx.order.paid.id, amountCollected: 500 },
        { orderId: ctx.order.part.id, amountCollected: 150 },
        { orderId: ctx.order.nil.id, amountCollected: 0 },     // "CR" on the sheet
      ],
    }).then((r) => {
      expect(r.body.success, 'the round was keyed: ' + JSON.stringify(r.body)).to.eq(true)
      const d = r.body.data
      expect(d.keyed, 'all three stops keyed — the nil one included').to.eq(3)
      expect(d.skipped, 'nothing skipped').to.have.length(0)
      expect(d.settlementNo, 'and the cash was settled in one remittance').to.match(/^DS-\d+$/)
      expect(Number(d.declared), '500 + 150 + 0').to.eq(650)
      // Two receipts, not three: a receipt for zero is not a receipt, and the books refuse one. The nil stop is
      // counted in the settlement and simply raises none.
      expect(d.receipts, 'one receipt per stop that actually paid').to.have.length(2)
    })
  })

  it('and the money is in the BOOKS — each shop owes exactly what is left', () => {
    cy.loginAsMarketplaceOwner()
    cy.then(() => owed(ctx.outlet.paid.id)).then((v) => expect(v, 'paid in full').to.eq(0))
    cy.then(() => owed(ctx.outlet.part.id)).then((v) => expect(v, '300 less 150').to.eq(150))
    // The stop that paid nothing: delivered, accounted for on the sheet, and still owing every rupee.
    cy.then(() => owed(ctx.outlet.nil.id)).then((v) => expect(v, 'took the goods on credit').to.eq(200))
  })

  it('all three orders are now DELIVERED', () => {
    cy.loginAsMarketplaceOwner()
    ;['paid', 'part', 'nil'].forEach((k) => {
      cy.request('/getOrder?id=' + ctx.order[k].id).then((r) => {
        expect(r.body.data.fulfilmentStatus, k + ' was keyed').to.eq('DELIVERED')
      })
    })
  })

  it('pressing it again is harmless — every stop is reported as nothing left to do', () => {
    cy.loginAsMarketplaceOwner()
    keyRound({
      salesman: 'SAEED AHMED', countedAmount: 650,
      stops: [
        { orderId: ctx.order.paid.id, amountCollected: 500 },
        { orderId: ctx.order.part.id, amountCollected: 150 },
        { orderId: ctx.order.nil.id, amountCollected: 0 },
      ],
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const d = r.body.data
      // The property that makes one button over 29 stops safe to press when you are unsure.
      expect(d.keyed, 'nothing keyed a second time').to.eq(0)
      expect(d.skipped, 'and each stop says why').to.have.length(3)
      expect(d.settlementNo, 'no second remittance').to.not.be.ok
    })

    // And no balance moved a second time — the real test of idempotence.
    cy.then(() => owed(ctx.outlet.part.id)).then((v) => expect(v, 'still 150').to.eq(150))
  })

  it('a variance with no explanation is refused — the cheapest control there is', () => {
    cy.loginAsMarketplaceOwner()
    dispatchTo('paid', 1).then((o) => {                       // a fresh stop, 100 owed
      keyRound({
        salesman: 'SAEED AHMED',
        countedAmount: 40,                                     // the bag is 60 short of the 100 declared
        stops: [{ orderId: o.id, amountCollected: 100 }],
      }).then((r) => {
        expect(r.body.success, 'refused').to.not.eq(true)
        expect(String(r.body.message || ''), 'and says what is wrong').to.match(/SHORT|note/i)
      })
    })
  })

  it('the screen turns the sheet into inputs, and only when keying is switched on', () => {
    cy.loginAsMarketplaceOwner()
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => w.showRoundSheet())

    const d = new Date()
    const dmy = String(d.getDate()).padStart(2, '0') + '-'
      + String(d.getMonth() + 1).padStart(2, '0') + '-' + d.getFullYear()
    cy.get('#rsFrom').clear().type(dmy)
    cy.get('#rsTo').clear().type(dmy)
    cy.window().then((w) => w.loadRoundSheet())
    cy.get('#rsBody tr', { timeout: 15000 }).should('have.length.at.least', 1)

    // Going OUT: the Received column is blank paper.
    cy.get('.rs-received').should('not.exist')
    cy.get('#rsKeyFields').should('not.be.visible')

    // Coming BACK: the same cell becomes the box the marked-up amount is typed into.
    cy.get('#rsKeyMode').check()
    cy.get('#rsKeyFields').should('be.visible')
    cy.get('.rs-received').should('have.length.at.least', 1)

    // The declared running total is shown BESIDE the counted box, never filled into it: they are different
    // facts, and pre-filling one from the other would guarantee a zero variance.
    cy.get('.rs-received').first().type('25')
    cy.get('#rsDeclared').should('contain.text', '25.00')
    cy.get('#rsCounted').should('have.value', '')
  })
})
