/**
 * OMS O7 — the WHOLE field-order lifecycle, end to end, in the order a distributor actually lives it.
 *
 *     book → (reject → revise → resubmit) → confirm → pack → dispatch → deliver short → settle the cash
 *
 * <h3>What this spec is for</h3>
 * The pieces are each gated elsewhere: D1 approval, D3 packing, D4 delivery, D5 settlement. Nothing walked
 * the whole road in one run, so nothing proved the joins. This does, with one order, one outlet and one
 * salesman, and it asserts the two facts that every join can quietly break:
 *
 * <ul>
 *   <li><b>No invoice exists until goods leave.</b> Re-checked after booking and after confirming, because
 *       those are the two moments an "accepted" order looks finished enough to bill.</li>
 *   <li><b>The books follow the goods, not the paperwork.</b> A short delivery must reduce what the outlet
 *       owes; cash collected must reduce it again. Asserted against the outlet's real balance, never against
 *       the form data coming back — which is exactly how D4's "settle" once passed while moving no money.</li>
 * </ul>
 *
 * <h3>Reading it as a walkthrough</h3>
 * The tests run in order and share one order through {@code ctx}. Each is named for the step it performs, so
 * a failure names the stage that broke. Run headed and you can follow the same path a human takes.
 */
describe('OMS O7 — a field order from the booker\'s counter to the banked cash', () => {
  const run = String(Date.now()).slice(-6)
  const PRICE = 40
  const ORDERED = 10          // 10 × 40 = 400 booked
  const DELIVERED = 8         // 2 refused at the door  → 320 kept
  const COLLECTED = 250       // the shop pays 250 of the 320 → 70 still owed

  const ctx = {}

  // Tax deliberately 0: this spec is about the ORDER lifecycle. Tax correctness has its own gates, and a tax
  // line here would only make every figure below harder to check by eye.
  before(() => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name: 'LifeProd_' + run, sku: 'LF' + run, sellingPrice: PRICE, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      ctx.productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId: ctx.productId, quantity: 200 }, failOnStatusCode: false,
      })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    /*
     * The outlet, WITH a credit limit — and both halves of that matter.
     *
     * A real customer row, because booking on a name alone leaves Order.customerId null and the invoice at
     * dispatch then resolves the buyer by name, creating a duplicate (O7 D2c). A CREDIT LIMIT, because
     * /creditStanding answers null for an uncapped customer — "a customer with no limit is not at 0% of 0" —
     * and every balance assertion below reads `owed` off that endpoint. Without the limit this spec would
     * assert against null and pass by accident, which is the exact false-pass shape this programme keeps
     * hitting: existence is not eligibility.
     */
    ctx.outletName = 'LifeOutlet_' + run
    cy.then(() => cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name: ctx.outletName, contact: '0300' + run, creditLimit: 100000 },
    })).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    cy.then(() => cy.request('/getUserCustomer')).then((r) => {
      const rows = (r.body.collection || r.body.data || []).filter((c) => c.name === ctx.outletName)
      expect(rows.length, 'the outlet was created exactly once').to.eq(1)
      ctx.outletId = rows[0].customerId || rows[0].id
      expect(ctx.outletId, 'and it has an id to bill').to.be.a('number')
    })
  })

  // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────

  const order = () => cy.request('/getOrder?id=' + ctx.orderId).then((r) => {
    expect(r.body.data, 'order readable: ' + JSON.stringify(r.body)).to.be.an('object')
    return r.body.data
  })

  /** What the outlet actually owes, from the books — not from the order row. */
  const owed = () => cy.request('/creditStanding?customerId=' + ctx.outletId).then((r) => {
    expect(r.body.object, 'the outlet has a credit standing to read').to.not.be.null
    return Number(r.body.object.owed)
  })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const trialBalance = () => cy.request('/gl/trialBalance').then((r) => parse(r.body))

  // ── 1 · BOOK ──────────────────────────────────────────────────────────────────────────────────────────

  it('1 · the rep books the order at the counter — a request, not a sale', () => {
    cy.loginAsOrderBooker()
    cy.request({
      method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: {
        customerId: ctx.outletId,          // D2c: WHICH outlet — without it the invoice invents a duplicate
        customerName: ctx.outletName, customerContact: '0300' + run, shippingAddress: '18 Bazaar Rd',
        items: [{ productId: ctx.productId, quantity: ORDERED, price: PRICE, productName: 'LifeProd_' + run }],
      },
    }).then((r) => {
      expect(r.body.success, 'booked: ' + JSON.stringify(r.body)).to.eq(true)
      const o = r.body.data
      ctx.orderId = o.id
      ctx.orderNo = o.orderNo
      ctx.lineId = (o.items || [])[0].id

      expect(o.orderNo, 'the rep can quote a reference before leaving the shop').to.match(/^SO-\d+$/)
      expect(o.fulfilmentStatus).to.eq('PENDING_APPROVAL')
      expect(o.bookedByName, 'who took the order is stamped, not resolved later').to.be.a('string').and.not.be.empty
      // The founding rule of D1. A booked order is a request; billing one would mean voiding it on rejection.
      expect(o.invoiceNo, 'no invoice at booking').to.not.be.ok
    })
  })

  it('2 · the rep cannot release their own order — the gate, from the server', () => {
    cy.loginAsOrderBooker()
    cy.request({
      method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
      body: { id: ctx.orderId }, failOnStatusCode: false,
    }).then((r) => {
      // ROLE_ORDER_BOOKER carries no ADMIN_PRIVILEGE. The UI hides the button; this proves the button is not
      // the control.
      expect(r.body.success, 'a booker must not confirm their own order: ' + JSON.stringify(r.body)).to.not.eq(true)
    })
    cy.then(() => order()).then((o) => expect(o.fulfilmentStatus, 'unchanged').to.eq('PENDING_APPROVAL'))
  })

  // ── 2 · REVIEW ────────────────────────────────────────────────────────────────────────────────────────

  it('3 · the reviewer sends it back, with the reason the rep needs to act on', () => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/rejectOrder', headers: { 'Content-Type': 'application/json' },
      body: { id: ctx.orderId, reason: 'Outlet over credit limit' }, failOnStatusCode: false,
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.then(() => order()).then((o) => {
      expect(o.fulfilmentStatus).to.eq('REJECTED')
      expect(o.rejectionReason, 'a rejection with no reason gives the rep nothing to revise')
        .to.eq('Outlet over credit limit')
      expect(o.invoiceNo, 'a rejection voids nothing, because nothing was billed').to.not.be.ok
    })
  })

  it('4 · the rep revises and resubmits — back for review, never straight to accepted', () => {
    cy.loginAsOrderBooker()
    cy.request({
      method: 'POST', url: '/resubmitOrder', headers: { 'Content-Type': 'application/json' },
      body: { id: ctx.orderId }, failOnStatusCode: false,
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    // REJECTED → PENDING_APPROVAL, not → NEW. Otherwise "reject" is a one-click bypass of the approval it
    // exists to enforce.
    cy.then(() => order()).then((o) => expect(o.fulfilmentStatus).to.eq('PENDING_APPROVAL'))
  })

  it('5 · the reviewer confirms — released to the floor, still unbilled', () => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
      body: { id: ctx.orderId }, failOnStatusCode: false,
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.then(() => order()).then((o) => {
      expect(o.fulfilmentStatus).to.eq('NEW')
      // The second moment an order looks finished enough to bill. It is not: approval is permission to PICK.
      expect(o.invoiceNo, 'confirming is not dispatching').to.not.be.ok
      expect(o.allowedTransitions, 'ready for the warehouse').to.include('PACKED')
      expect(o.customerId, 'the order still knows WHICH account it is for').to.eq(ctx.outletId)
    })
  })

  // ── 3 · FULFIL ────────────────────────────────────────────────────────────────────────────────────────

  it('6 · the warehouse dispatches the parcel — and THIS is what raises the invoice', () => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { id: ctx.orderId, lines: [{ orderItemId: ctx.lineId, quantity: ORDERED }], carrier: 'Saeed Ahmed' },
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.then(() => order()).then((o) => {
      // SHIPPED is DERIVED from the parcel — no button can claim a dispatch that did not happen.
      expect(o.fulfilmentStatus).to.eq('SHIPPED')
      expect(o.invoiceNo, 'the goods left, so now there is an invoice').to.match(/^INV-/)
      ctx.invoiceNo = o.invoiceNo
      ctx.shipmentId = (o.shipments || [])[0].id
      expect(ctx.shipmentId, 'the parcel is recorded').to.be.a('number')
    })

    // Priced from what physically went out.
    cy.then(() => owed()).then((o) => {
      expect(o, 'the dispatch put the whole order on the outlet\'s account').to.eq(ORDERED * PRICE)
      ctx.owedAfterDispatch = o
    })
  })

  it('7 · the shop refuses 2 of 10 at the door and pays part — keyed from the salesman\'s sheet', () => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/recordDelivery', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: {
        id: ctx.orderId, shipmentId: ctx.shipmentId, deliveredBy: 'Saeed Ahmed',
        settlement: 'PARTIAL', amountCollected: COLLECTED,
        lines: [{ orderItemId: ctx.lineId, deliveredQuantity: DELIVERED }],
      },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      // A door rejection is a CREDIT NOTE against the issued invoice, never a retro-edit of it — the shop is
      // holding a copy of the invoice for all ten.
      const notes = (r.body.data || {}).creditNotes || []
      expect(notes.length, 'the 2 refused units come back as a credit note').to.be.at.least(1)
      ctx.creditNotes = notes
    })

    cy.then(() => order()).then((o) => expect(o.fulfilmentStatus).to.eq('DELIVERED'))

    // THE ASSERTION THAT MATTERS. The return must move the outlet's real balance, not merely be stored.
    cy.then(() => owed()).then((o) => {
      expect(o, 'the refused goods came off the account').to.eq(DELIVERED * PRICE)
    })
  })

  it('8 · the salesman hands in the cash and it clears the receivable', () => {
    cy.loginAsMarketplaceOwner()
    cy.request('/getDeliveries?id=' + ctx.orderId).then((r) => {
      const rows = r.body.data || []
      expect(rows.length, 'the delivery was keyed').to.eq(1)
      ctx.deliveryId = rows[0].id
      expect(rows[0].customerId, 'the collection knows which account to credit').to.eq(ctx.outletId)
    })

    let before
    cy.then(() => owed()).then((o) => {
      expect(o, 'positive control: there is a debt to clear').to.be.greaterThan(0)
      before = o
    })

    cy.then(() => cy.request({
      method: 'POST', url: '/settleDriver', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { deliveryIds: [ctx.deliveryId], countedAmount: COLLECTED, depositReference: 'SLIP-' + run },
    })).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.settlementNo, 'a real remittance document').to.match(/^DS-\d+$/)
    })

    // Settling is what moves the money — keying the delivery only says it was collected.
    cy.then(() => owed()).then((o) => {
      expect(o, 'the cash reduced what the outlet owes').to.eq(before - COLLECTED)
      expect(o, 'and what is left is the unpaid balance').to.eq(DELIVERED * PRICE - COLLECTED)
    })
  })

  it('9 · the ledger balances after all of it', () => {
    cy.loginAsMarketplaceOwner()
    trialBalance().then((tb) => {
      expect(tb.balanced, 'GL balanced at the end of the round').to.eq(true)
      expect(Number(tb.totalDebit)).to.eq(Number(tb.totalCredit))
    })
  })
})

/**
 * The refusals — the moves the lifecycle must NOT allow.
 *
 * A state machine is defined as much by what it forbids as by what it permits, and every rule below exists
 * because allowing it would put the books or the stock into a state nobody could explain.
 */
describe('OMS O7 — what the lifecycle refuses', () => {
  const run = String(Date.now()).slice(-6)
  const PRICE = 30
  const ctx = {}

  before(() => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name: 'RefuseProd_' + run, sku: 'RF' + run, sellingPrice: PRICE, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      ctx.productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId: ctx.productId, quantity: 100 }, failOnStatusCode: false,
      })
    })
  })

  const book = (name) => cy.request({
    method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      customerName: name, customerContact: '0300' + run, shippingAddress: '5 Refuse St',
      items: [{ productId: ctx.productId, quantity: 4, price: PRICE, productName: 'RefuseProd_' + run }],
    },
  }).then((r) => {
    expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
    return r.body.data
  })

  it('an empty order cannot be booked', () => {
    cy.loginAsOrderBooker()
    cy.request({
      method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { customerName: 'Empty_' + run, items: [] },
    }).then((r) => expect(r.body.success, 'an order with no lines is not an order').to.not.eq(true))
  })

  it('approval is not reachable through the generic status endpoint', () => {
    cy.loginAsOrderBooker()
    book('GenericMove_' + run).then((o) => {
      cy.loginAsMarketplaceOwner()
      cy.request({
        method: 'POST', url: '/updateOrderStatus', headers: { 'Content-Type': 'application/json' },
        body: { id: o.id, status: 'NEW' }, failOnStatusCode: false,
      }).then((r) => {
        // Refused ON PURPOSE, and the refusal names the fix. Reaching NEW this way would skip the admin gate
        // and, on the reject side, lose the reason — which is why the UI must never draw it as "Mark NEW".
        expect(r.body.success).to.not.eq(true)
        expect(String(r.body.message || ''), 'the refusal points at the right endpoint').to.contain('review decision')
      })
    })
  })

  it('a dispatched order cannot be cancelled — goods on a van are not back on the shelf', () => {
    cy.loginAsOrderBooker()
    book('NoCancel_' + run).then((o) => {
      cy.loginAsMarketplaceOwner()
      cy.request({
        method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
        body: { id: o.id }, failOnStatusCode: false,
      })
      cy.then(() => cy.request({
        method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        body: { id: o.id, lines: [{ orderItemId: o.items[0].id, quantity: 4 }], carrier: 'Ahsan' },
      })).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

      cy.then(() => cy.request({
        method: 'POST', url: '/updateOrderStatus', headers: { 'Content-Type': 'application/json' },
        body: { id: o.id, status: 'CANCELLED' }, failOnStatusCode: false,
      })).then((r) => {
        expect(r.body.success, 'a shipped order goes DELIVERED → RETURNED, never CANCELLED').to.not.eq(true)
      })
      // The state is unchanged — a refused move must not half-apply.
      cy.then(() => cy.request('/getOrder?id=' + o.id)).then((r) => {
        expect(r.body.data.fulfilmentStatus).to.eq('SHIPPED')
      })
    })
  })

  it('an order cannot be marked shipped — only a recorded parcel can do that', () => {
    cy.loginAsOrderBooker()
    book('NoMarkShip_' + run).then((o) => {
      cy.loginAsMarketplaceOwner()
      cy.request({
        method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
        body: { id: o.id }, failOnStatusCode: false,
      })
      cy.then(() => cy.request({
        method: 'POST', url: '/updateOrderStatus', headers: { 'Content-Type': 'application/json' },
        body: { id: o.id, status: 'SHIPPED' }, failOnStatusCode: false,
      })).then((r) => {
        // A status settable independently of its shipments is a status that can lie about them.
        expect(r.body.success).to.not.eq(true)
        expect(String(r.body.message || '')).to.contain('recording a shipment')
      })
    })
  })
})
