/**
 * A DISTRIBUTOR'S DAY, END TO END — the demo spec.
 *
 * Written to be watched, not just to pass. Run it headed and it plays out one working day at a medicine
 * distributor: the catalogue, the rep's round, the review desk, the warehouse, the van, and the cash-up —
 * with a client-recognisable set of products, outlets and figures.
 *
 *     npx cypress run --headed --no-exit --spec "cypress/e2e/business/distribution-round-demo.cy.js"
 *
 * <h3>The figures are deliberately familiar</h3>
 * Act 2's first order reproduces a real invoice line for line — three IV fluids, 20 + 20 + 10 units at trade
 * prices 70, 90 and 90, coming to <b>4,100.00</b>. When the invoice appears in Act 4 it should be the same
 * document the client already recognises, produced by this system instead of their old one.
 *
 * <h3>What a viewer should take away</h3>
 * <ol>
 *   <li>A booked order is a <b>request</b>. It carries no invoice, so rejecting one voids nothing.</li>
 *   <li>The invoice appears when <b>goods leave</b>, priced from what physically went out.</li>
 *   <li>A short delivery and a part payment both move the <b>outlet's real balance</b> — the round ends with
 *       three shops owing three different, correct amounts.</li>
 *   <li>Nothing is settled until the cash is <b>handed in and counted</b>.</li>
 * </ol>
 *
 * Its sibling {@code field-order-lifecycle.cy.js} covers the refusals — what the system will NOT let you do.
 * This one is the happy day, with the three delivery outcomes a real round contains.
 */
describe('A distributor\'s day — from the rep\'s round to the banked cash', () => {
  const run = String(Date.now()).slice(-6)

  /** The van's stock. Trade prices and pack sizes as a medicine distributor would hold them. */
  const CATALOGUE = [
    { key: 'ringer500', name: 'G RINGER 500 ML',      pack: '500ML',   tp: 70, batch: '611168',  expiry: '2027-06-30' },
    { key: 'ringerJhk', name: 'G RINGER JHK',         pack: '1000 ml', tp: 90, batch: 'A265007', expiry: '2027-09-30' },
    { key: 'nsalin',    name: 'G N/SALIN 1000ML JHK', pack: '1000 ml', tp: 90, batch: 'C262139', expiry: '2027-03-31' },
  ]

  /** Three shops on one round, across two areas. Every one on credit terms, as a distributor's book is. */
  const OUTLETS = [
    { key: 'ayesha', name: 'AYESHA MADICARE',  area: 'NAWAKOT',   limit: 50000 },
    { key: 'labaik', name: 'LABAIK PHARMACY',  area: 'ZAHIR PIR', limit: 50000 },
    { key: 'madina', name: 'AL MADINA',        area: 'GHOUS PUR', limit: 50000 },
  ]

  const ctx = { product: {}, outlet: {}, order: {} }

  // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────

  const order = (id) => cy.request('/getOrder?id=' + id).then((r) => {
    expect(r.body.data, 'order readable: ' + JSON.stringify(r.body)).to.be.an('object')
    return r.body.data
  })

  /** What a shop owes, read from the BOOKS — never from the order row we are asserting about. */
  const owed = (outletId) => cy.request('/creditStanding?customerId=' + outletId).then((r) => {
    expect(r.body.object, 'the outlet has a credit standing to read').to.not.be.null
    return Number(r.body.object.owed)
  })

  /** The invoice as the shopkeeper receives it. GenericResponse carries one payload on `object`. */
  const invoice = (invoiceNo) => cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo))
    .then((r) => {
      expect(r.body.status, 'invoice ' + invoiceNo + ' readable: ' + JSON.stringify(r.body)).to.eq('SUCCESS')
      return r.body.object
    })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)

  /** Book a round order as the rep would, at the shop counter. */
  const book = (outletKey, lines) => cy.request({
    method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      customerId: ctx.outlet[outletKey].id,
      customerName: ctx.outlet[outletKey].name,
      customerContact: '0300' + run,
      shippingAddress: ctx.outlet[outletKey].area,
      paymentMode: 'CREDIT',
      items: lines.map((l) => ({
        productId: ctx.product[l.key].id,
        productName: ctx.product[l.key].name,
        quantity: l.qty,
        price: ctx.product[l.key].tp,
      })),
    },
  }).then((r) => {
    expect(r.body.success, 'booked for ' + outletKey + ': ' + JSON.stringify(r.body)).to.eq(true)
    return r.body.data
  })

  const confirm = (id) => cy.request({
    method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
    body: { id }, failOnStatusCode: false,
  }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

  const dispatch = (o, qtyByLine) => cy.request({
    method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: { id: o.id, carrier: 'SAEED AHMED', lines: o.items.map((it, i) => ({ orderItemId: it.id, quantity: qtyByLine[i] })) },
  }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

  const deliver = (o, shipmentId, deliveredByLine, collected, settlement) => cy.request({
    method: 'POST', url: '/recordDelivery', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      id: o.id, shipmentId, deliveredBy: 'SAEED AHMED', settlement, amountCollected: collected,
      lines: o.items.map((it, i) => ({ orderItemId: it.id, deliveredQuantity: deliveredByLine[i] })),
    },
  }).then((r) => {
    expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
    return r.body.data
  })

  // ── ACT 1 · the distributor's own records ─────────────────────────────────────────────────────────────

  it('ACT 1 · the warehouse holds three IV fluids, each with its batch and expiry', () => {
    cy.loginAsMarketplaceOwner()
    CATALOGUE.forEach((p) => {
      cy.request({
        method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        // `unit` carries the pack size, which is what prints in the Packing column of a distribution invoice.
        body: { name: p.name + ' #' + run, sku: p.key.toUpperCase() + run, sellingPrice: p.tp, taxRate: 0, unit: p.pack },
      }).then((r) => {
        expect(r.body.success, 'catalogued ' + p.name + ': ' + JSON.stringify(r.body)).to.eq(true)
        ctx.product[p.key] = { id: r.body.data.id, name: p.name + ' #' + run, tp: p.tp }
        // Batch + expiry are stocked with the goods, not held on the product: FEFO picks the shortest-dated
        // batch first, and it is the BATCH that has to print on a pharmaceutical invoice.
        return cy.request({
          method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
          body: { productId: ctx.product[p.key].id, quantity: 500, batchNo: p.batch, expiryDate: p.expiry },
        })
      }).then((r) => expect(r.body.success, 'stocked: ' + JSON.stringify(r.body)).to.eq(true))
    })
  })

  it('ACT 1 · three shops are opened on the book, each with a credit limit', () => {
    cy.loginAsMarketplaceOwner()
    OUTLETS.forEach((o) => {
      const name = o.name + ' #' + run
      cy.request({
        method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
        body: { name, contact: '0300' + run, address: o.area, creditLimit: o.limit },
      }).then((r) => expect(r.body.status, 'opened ' + o.name + ': ' + JSON.stringify(r.body)).to.eq('SUCCESS'))
    })
    cy.then(() => cy.request('/getUserCustomer')).then((r) => {
      const rows = r.body.collection || r.body.data || []
      OUTLETS.forEach((o) => {
        const name = o.name + ' #' + run
        const hit = rows.filter((c) => c.name === name)
        expect(hit.length, o.name + ' was opened exactly once').to.eq(1)
        ctx.outlet[o.key] = { id: hit[0].customerId || hit[0].id, name, area: o.area }
      })
      // A credit limit is not decoration here: /creditStanding answers null for an uncapped customer, and
      // every balance shown at the end of this round is read from it.
      expect(Object.keys(ctx.outlet).length, 'all three shops are on the book').to.eq(3)
    })
  })

  // ── ACT 2 · the rep's round ───────────────────────────────────────────────────────────────────────────

  it('ACT 2 · the rep books three orders on his round — and none of them is a sale yet', () => {
    cy.loginAsOrderBooker()

    // AYESHA MADICARE — the familiar one: 20 + 20 + 10 at 70 / 90 / 90 = 4,100.00
    book('ayesha', [{ key: 'ringer500', qty: 20 }, { key: 'ringerJhk', qty: 20 }, { key: 'nsalin', qty: 10 }])
      .then((o) => {
        ctx.order.ayesha = o
        expect(Number(o.total), 'the order the shopkeeper agreed to').to.eq(4100)
      })

    book('labaik', [{ key: 'ringer500', qty: 10 }, { key: 'nsalin', qty: 10 }])
      .then((o) => { ctx.order.labaik = o })

    book('madina', [{ key: 'ringerJhk', qty: 5 }])
      .then((o) => { ctx.order.madina = o })

    cy.then(() => {
      ['ayesha', 'labaik', 'madina'].forEach((k) => {
        const o = ctx.order[k]
        expect(o.orderNo, 'the rep can quote a reference before leaving the shop').to.match(/^SO-\d+$/)
        expect(o.fulfilmentStatus, k + ' waits for the office').to.eq('PENDING_APPROVAL')
        expect(o.bookedByName, 'who took the order is recorded').to.be.a('string').and.not.be.empty
        // The idea the whole model rests on. Nothing has been billed, so the office can still say no.
        expect(o.invoiceNo, 'a booked order carries no invoice').to.not.be.ok
      })
    })

    // And nothing has touched any shop's account yet.
    cy.then(() => owed(ctx.outlet.ayesha.id)).then((v) => expect(v, 'no debt from a booking').to.eq(0))
  })

  // ── ACT 3 · the review desk ───────────────────────────────────────────────────────────────────────────

  it('ACT 3 · the office sends one order back to the rep, with a reason', () => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/rejectOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { id: ctx.order.madina.id, reason: 'Outlet over its limit — collect the old balance first' },
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.then(() => order(ctx.order.madina.id)).then((o) => {
      expect(o.fulfilmentStatus).to.eq('REJECTED')
      expect(o.rejectionReason, 'the rep is told WHY, so he can do something about it').to.contain('over its limit')
      // The payoff of not billing at booking: a rejection cancels nothing, because nothing was raised.
      expect(o.invoiceNo, 'nothing to void').to.not.be.ok
    })
  })

  it('ACT 3 · the rep resubmits it, and the office releases all three', () => {
    cy.loginAsOrderBooker()
    cy.request({
      method: 'POST', url: '/resubmitOrder', headers: { 'Content-Type': 'application/json' },
      body: { id: ctx.order.madina.id }, failOnStatusCode: false,
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.then(() => {
      cy.loginAsMarketplaceOwner()
      confirm(ctx.order.ayesha.id)
      confirm(ctx.order.labaik.id)
      confirm(ctx.order.madina.id)
    })

    cy.then(() => {
      ['ayesha', 'labaik', 'madina'].forEach((k) => {
        order(ctx.order[k].id).then((o) => {
          expect(o.fulfilmentStatus, k + ' is on the picking list').to.eq('NEW')
          // Released for picking is still not sold.
          expect(o.invoiceNo, 'approval is permission to pick, not to bill').to.not.be.ok
        })
      })
    })
  })

  // ── ACT 4 · the warehouse ─────────────────────────────────────────────────────────────────────────────

  it('ACT 4 · the goods go on the van — and THAT is what raises the invoices', () => {
    cy.loginAsMarketplaceOwner()
    ;['ayesha', 'labaik', 'madina'].forEach((k) => {
      cy.then(() => order(ctx.order[k].id)).then((o) => {
        ctx.order[k] = o
        return dispatch(o, o.items.map((it) => it.quantity))     // everything ordered goes out
      })
      cy.then(() => order(ctx.order[k].id)).then((o) => {
        expect(o.fulfilmentStatus, k + ' has left the building').to.eq('SHIPPED')
        expect(o.invoiceNo, 'now, and only now, there is an invoice').to.match(/^INV-/)
        ctx.order[k] = o
      })
    })
  })

  it('ACT 4 · the invoice for AYESHA MADICARE is the document the shop already knows', () => {
    cy.loginAsMarketplaceOwner()
    cy.then(() => invoice(ctx.order.ayesha.invoiceNo)).then((inv) => {
      // 20×70 + 20×90 + 10×90 = 1400 + 1800 + 900
      expect(Number(inv.grandTotal), 'the same 4,100.00 the rep quoted at the counter').to.eq(4100)
      expect(Number(inv.subTotal)).to.eq(4100)

      /*
       * KNOWN GAP — "Booked By" does not reach the invoice yet.
       *
       * The ORDER records the rep correctly, and the receipt layout can already print a `bookedBy` line. What
       * is missing is the road between them: `SaleRecordRequest` has no field for it, so when the invoice is
       * raised at dispatch the name cannot travel. `SagaSaleWriter` then falls back to stamping the operator's
       * email — and a dispatch invoice is written by a service call that has no email, so the field stays null.
       *
       * Pinned as CURRENT behaviour rather than dropped, so that closing the gap makes this line fail and
       * whoever closes it updates the assertion deliberately.
       */
      expect(inv.bookedByName, 'not yet carried onto the invoice — see the gap note above').to.not.be.ok
    })
    // …and the shop now owes it.
    cy.then(() => owed(ctx.outlet.ayesha.id)).then((v) => expect(v, 'on the account').to.eq(4100))
  })

  // ── ACT 5 · the van ───────────────────────────────────────────────────────────────────────────────────

  it('ACT 5 · stop one — AYESHA takes the lot and pays cash', () => {
    cy.loginAsMarketplaceOwner()
    cy.then(() => order(ctx.order.ayesha.id)).then((o) => {
      ctx.order.ayesha = o
      return deliver(o, o.shipments[0].id, o.items.map((it) => it.quantity), 4100, 'PAID')
    })
    cy.then(() => order(ctx.order.ayesha.id)).then((o) => expect(o.fulfilmentStatus).to.eq('DELIVERED'))
    // Still owed: the cash is in the salesman's bag, not yet in the business.
    cy.then(() => owed(ctx.outlet.ayesha.id)).then((v) => {
      expect(v, 'collecting is not the same as settling').to.eq(4100)
    })
  })

  it('ACT 5 · stop two — LABAIK refuses half the Ringer and pays part', () => {
    cy.loginAsMarketplaceOwner()
    cy.then(() => order(ctx.order.labaik.id)).then((o) => {
      ctx.order.labaik = o
      const full = o.items.map((it) => it.quantity)
      const short = [full[0] - 5, full[1]]        // 5 units of the 500ML refused at the door
      return deliver(o, o.shipments[0].id, short, 500, 'PARTIAL')
    }).then((res) => {
      // A refusal at the door is a CREDIT NOTE against the invoice the shop is holding — never a quiet edit
      // of a document they already have a copy of.
      expect((res.creditNotes || []).length, 'the refused goods come back as a credit note').to.be.at.least(1)
    })

    // 10×70 + 10×90 = 1600 invoiced, less 5×70 = 350 returned → 1250 owed.
    cy.then(() => owed(ctx.outlet.labaik.id)).then((v) => {
      expect(v, 'the returned goods came off the account straight away').to.eq(1250)
    })
  })

  it('ACT 5 · stop three — AL MADINA takes the goods on credit and pays nothing', () => {
    cy.loginAsMarketplaceOwner()
    cy.then(() => order(ctx.order.madina.id)).then((o) => {
      ctx.order.madina = o
      return deliver(o, o.shipments[0].id, o.items.map((it) => it.quantity), 0, 'CREDIT')
    })
    cy.then(() => owed(ctx.outlet.madina.id)).then((v) => {
      expect(v, '5 × 90 on the slate').to.eq(450)
    })
  })

  // ── ACT 6 · the cash-up ───────────────────────────────────────────────────────────────────────────────

  it('ACT 6 · the salesman hands in the bag, and the money reaches the accounts', () => {
    cy.loginAsMarketplaceOwner()
    const withCash = []

    // Every stop is keyed, including the one that paid nothing — the round has to account for all three.
    ;['ayesha', 'labaik', 'madina'].forEach((k) => {
      cy.then(() => cy.request('/getDeliveries?id=' + ctx.order[k].id)).then((r) => {
        const rows = r.body.data || []
        expect(rows.length, k + '\'s stop was keyed').to.eq(1)
        if (Number(rows[0].amountCollected) > 0) { withCash.push(rows[0].id) }
      })
    })

    /*
     * ONLY the stops that collected cash go into the remittance.
     *
     * A settlement raises a RECEIPT per collection, and a receipt for nothing is refused — "A positive amount
     * is required". Including AL MADINA's nil stop fails the whole batch, not just that line.
     *
     * That is defensible for a receipt, and awkward for a route: on a 29-stop round several shops will pay
     * nothing, and the cashier has to know to leave exactly those out. Worth revisiting when the route sheet
     * is built — the natural fix is for the settlement to skip nil collections itself rather than refuse the
     * batch, since the sheet the cashier works from will list every stop.
     */
    cy.then(() => {
      expect(withCash.length, 'two of the three stops produced cash').to.eq(2)
      return cy.request({
        method: 'POST', url: '/settleDriver', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        body: { deliveryIds: withCash, countedAmount: 4600, depositReference: 'SLIP-' + run },
      })
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.settlementNo, 'a real remittance document').to.match(/^DS-\d+$/)
    })
  })

  // ── ACT 7 · the books at the end of the day ───────────────────────────────────────────────────────────

  it('ACT 7 · three shops, three different balances — and every one of them right', () => {
    cy.loginAsMarketplaceOwner()

    // Paid in full and the cash is in: nothing outstanding.
    cy.then(() => owed(ctx.outlet.ayesha.id)).then((v) => {
      expect(v, 'AYESHA MADICARE — paid in full').to.eq(0)
    })
    // Invoiced 1,600, returned 350, paid 500.
    cy.then(() => owed(ctx.outlet.labaik.id)).then((v) => {
      expect(v, 'LABAIK PHARMACY — 1,600 less 350 returned less 500 paid').to.eq(750)
    })
    // Took the goods, paid nothing.
    cy.then(() => owed(ctx.outlet.madina.id)).then((v) => {
      expect(v, 'AL MADINA — the whole bill still on the slate').to.eq(450)
    })
  })

  it('ACT 7 · and the ledger balances after all of it', () => {
    cy.loginAsMarketplaceOwner()
    cy.request('/gl/trialBalance').then((r) => {
      const tb = parse(r.body)
      expect(tb.balanced, 'a day of orders, returns and part payments, still in balance').to.eq(true)
      expect(Number(tb.totalDebit)).to.eq(Number(tb.totalCredit))
    })
  })
})
