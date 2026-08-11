/**
 * OMS O7 D1 — distribution pre-sales: book → review → amend → confirm / reject.
 * Design: microservices/docs/slices/oms-O7-distribution-presales.md
 *
 * The founding requirement: an order booker takes an order at a medical store, the warehouse admin reviews it,
 * amends it, and confirms or rejects it. Before D1 none of that phase existed — `NEW` already meant "accepted
 * and ready to pack", and there was no way to change an order's contents at all.
 *
 * The case that proves D1 works is **"a booked order carries no invoice until its goods leave"**: everything
 * else in the slice follows from that one decision (§6 D-1).
 */
describe('OMS O7 D1 — booked orders are reviewed before they reach the floor', () => {
  const run = String(Date.now()).slice(-6)
  let productId
  const PRICE = 40

  before(() => {
    cy.loginAsMarketplace()
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name: 'FieldProd_' + run, sku: 'FP' + run, sellingPrice: PRICE, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId, quantity: 200 }, failOnStatusCode: false,
      })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
  })

  beforeEach(() => cy.loginAsMarketplace())

  /** Book an order the way an order booker would, from the shop counter. */
  const book = (outlet, qty = 5, extra = {}) => cy.request({
    method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: Object.assign({
      customerName: outlet, customerContact: '0300' + run, shippingAddress: '12 Market Rd',
      items: [{ productId, quantity: qty, price: PRICE, productName: 'FieldProd_' + run }],
    }, extra),
  })

  const detail = (id) => cy.request('/getOrder?id=' + id).then((r) => r.body.data)

  it('a booked order awaits review, with NO invoice and NO stock taken', () => {
    // The heart of D-1. If booking raised an invoice, the admin's amendment below would be an edit to an
    // issued fiscal document and a rejection would be a void.
    let before
    cy.request('/productStock?productId=' + productId).then((r) => { before = parseFloat(r.body.stock) })
    book('Irfan Medical ' + run).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const o = r.body.data
      expect(o.fulfilmentStatus).to.eq('PENDING_APPROVAL')
      expect(o.orderNo, 'the booker can quote a reference before leaving the counter').to.match(/^SO-\d+$/)
      expect(o.invoiceNo, 'nothing is invoiced at booking').to.be.oneOf([null, undefined, ''])
      expect(o.booksStatus).to.eq('AWAITING_DISPATCH')
      expect(o.source).to.eq('FIELD')
    })
    cy.request('/productStock?productId=' + productId)
      .then((r) => expect(parseFloat(r.body.stock), 'booking moves no stock').to.eq(before))
  })

  it('a booked order cannot jump the review — it must not be packable or shippable', () => {
    // The control the whole model rests on. If this passes, a booker can push goods out of the warehouse
    // without anyone reviewing what they promised, at what price.
    book('SkipReview ' + run).then((r) => {
      const id = r.body.data.id
      cy.request({
        method: 'POST', url: '/updateOrderStatus', headers: { 'Content-Type': 'application/json' },
        body: { id, status: 'PACKED' }, failOnStatusCode: false,
      }).then((s) => expect(s.body.success, JSON.stringify(s.body)).to.not.eq(true))
    })
  })

  it('an unreviewed order cannot be DISPATCHED — the back door is shut too', () => {
    // Found while re-reading ship() during D1: it refused only CANCELLED/RETURNED, so a booked order could be
    // dispatched — and, now that dispatch raises the invoice, INVOICED — without anyone approving it. The
    // lifecycle whitelist does not cover this, because dispatch is not a status move: it is a shipment, and
    // the status follows from it. Adding a capability means re-examining the existing refusals.
    book('NoSneakyShip ' + run, 5).then((r) => {
      const o = r.body.data
      cy.request({
        method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        body: { id: o.id, lines: [{ orderItemId: o.items[0].id, quantity: 5 }], carrier: 'Ahsan' },
      }).then((s) => {
        expect(s.body.success, JSON.stringify(s.body)).to.not.eq(true)
        expect(JSON.stringify(s.body)).to.match(/approved|confirm/i)
      })
      // And nothing was invoiced on the way past.
      detail(o.id).then((d) => {
        expect(d.invoiceNo, 'a refused dispatch raises no invoice').to.be.oneOf([null, undefined, ''])
        expect(d.booksStatus).to.eq('AWAITING_DISPATCH')
      })
    })
  })

  it('confirming through the generic status endpoint is refused, and names the right one', () => {
    // Reaching NEW through /updateOrderStatus would bypass the confirm gate; reaching REJECTED that way would
    // bypass the mandatory reason. Same idiom O5b used for the derived states: refuse, and say where to go.
    book('WrongDoor ' + run).then((r) => {
      const id = r.body.data.id
      cy.request({
        method: 'POST', url: '/updateOrderStatus', headers: { 'Content-Type': 'application/json' },
        body: { id, status: 'NEW' }, failOnStatusCode: false,
      }).then((s) => {
        expect(s.body.success).to.not.eq(true)
        expect(JSON.stringify(s.body)).to.match(/confirm/i)
      })
    })
  })

  it('the admin amends the order — lines, price and outlet — and every change is recorded', () => {
    // D-3: the admin may change prices. D-2: two people may edit, so the trail is what makes "who dropped the
    // price?" answerable at all.
    book('AmendMe ' + run, 10).then((r) => {
      const o = r.body.data
      const lineId = o.items[0].id
      return cy.request({
        method: 'POST', url: '/amendOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        body: {
          id: o.id,
          customerName: 'AmendMe ' + run + ' (corrected)',
          amendmentReason: 'Agreed trade price with the shop',
          items: [{ id: lineId, quantity: 6, price: 35 }],
        },
      }).then((a) => {
        expect(a.body.success, JSON.stringify(a.body)).to.eq(true)
        expect(a.body.data.items[0].quantity).to.eq(6)
        expect(Number(a.body.data.items[0].price)).to.eq(35)
        expect(Number(a.body.data.total), 'the total follows the amended lines').to.eq(6 * 35)
        return cy.request('/getOrderAmendments?id=' + o.id)
      })
    }).then((h) => {
      const rows = h.body.data || []
      expect(rows.length, 'the amendment is on the record').to.eq(1)
      expect(rows[0].reason).to.contain('trade price')
      expect(rows[0].changes, 'the before/after of the price is kept').to.contain('price')
      expect(rows[0].userName, 'attributed to whoever made it').to.be.a('string')
    })
  })

  it('an amendment that changes nothing writes no audit row', () => {
    // An empty amendment is noise in the one record that has to stay readable.
    book('NoChange ' + run, 4).then((r) => {
      const o = r.body.data
      return cy.request({
        method: 'POST', url: '/amendOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        body: { id: o.id, customerName: o.customerName },
      }).then(() => cy.request('/getOrderAmendments?id=' + o.id))
    }).then((h) => expect((h.body.data || []).length).to.eq(0))
  })

  it('rejection REQUIRES a reason, and the booker can revise and resubmit', () => {
    book('RejectMe ' + run).then((r) => {
      const id = r.body.data.id
      // No reason ⇒ refused. A rejection the booker cannot act on wastes the visit twice.
      cy.request({
        method: 'POST', url: '/rejectOrder', headers: { 'Content-Type': 'application/json' },
        body: { id }, failOnStatusCode: false,
      }).then((s) => expect(s.body.success, 'a reasonless rejection is refused').to.not.eq(true))

      cy.request({
        method: 'POST', url: '/rejectOrder', headers: { 'Content-Type': 'application/json' },
        body: { id, reason: 'Outlet is over its credit limit' }, failOnStatusCode: false,
      }).then((s) => {
        expect(s.body.success, JSON.stringify(s.body)).to.eq(true)
        expect(s.body.data.fulfilmentStatus).to.eq('REJECTED')
        expect(s.body.data.rejectionReason, 'the booker is told WHY').to.contain('credit limit')
      })

      // D-2: not terminal. The booker revises rather than re-keying the whole order.
      cy.request({
        method: 'POST', url: '/resubmitOrder', headers: { 'Content-Type': 'application/json' },
        body: { id }, failOnStatusCode: false,
      }).then((s) => {
        expect(s.body.success, JSON.stringify(s.body)).to.eq(true)
        expect(s.body.data.fulfilmentStatus, 'back for review, NOT straight to the floor').to.eq('PENDING_APPROVAL')
      })
    })
  })

  it('a confirmed order is ready to pack — and STILL carries no invoice', () => {
    book('ConfirmMe ' + run).then((r) => {
      const id = r.body.data.id
      cy.request({
        method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
        body: { id }, failOnStatusCode: false,
      }).then((s) => {
        expect(s.body.success, JSON.stringify(s.body)).to.eq(true)
        expect(s.body.data.fulfilmentStatus).to.eq('NEW')
        expect(s.body.data.booksStatus, 'the invoice waits for dispatch').to.eq('AWAITING_DISPATCH')
        expect(s.body.data.invoiceNo).to.be.oneOf([null, undefined, ''])
      })
    })
  })

  it('a confirmed order can no longer be amended', () => {
    // Past confirmation it is a picking instruction; past dispatch it is an invoice. Editing either behind the
    // operation's back is how a warehouse packs something the paperwork does not describe.
    book('LockedAfterConfirm ' + run).then((r) => {
      const o = r.body.data
      cy.request({
        method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
        body: { id: o.id }, failOnStatusCode: false,
      })
      cy.request({
        method: 'POST', url: '/amendOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        body: { id: o.id, items: [{ id: o.items[0].id, quantity: 99 }] },
      }).then((a) => {
        expect(a.body.success).to.not.eq(true)
        expect(JSON.stringify(a.body)).to.match(/under review|reject|cancel/i)
      })
    })
  })

  it('THE PAYOFF — dispatching a confirmed order is what raises its invoice, and takes the stock', () => {
    // ON_DISPATCH end to end (§6 D-1): the invoice is raised from what physically left, so the amended price
    // is what gets billed — not the catalog price, and not what was first booked.
    let before
    cy.request('/productStock?productId=' + productId).then((r) => { before = parseFloat(r.body.stock) })

    book('DispatchMe ' + run, 10).then((r) => {
      const o = r.body.data
      // Amend to a trade price, so the assertion below proves the invoice honours the AGREED price.
      return cy.request({
        method: 'POST', url: '/amendOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        body: { id: o.id, amendmentReason: 'trade price', items: [{ id: o.items[0].id, quantity: 4, price: 25 }] },
      }).then(() => cy.request({
        method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
        body: { id: o.id }, failOnStatusCode: false,
      })).then(() => detail(o.id)).then((d) => {
        const lineId = d.items[0].id
        return cy.request({
          method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
          body: { id: o.id, lines: [{ orderItemId: lineId, quantity: 4 }], carrier: 'Ahsan', trackingNumber: 'VAN-1' },
        })
      }).then((s) => {
        expect(s.body.success, JSON.stringify(s.body)).to.eq(true)
        return detail(o.id)
      })
    }).then((d) => {
      expect(d.invoiceNo, 'the invoice is raised AT DISPATCH').to.match(/\S/)
      expect(d.booksStatus, 'and the order is now in the books').to.eq('POSTED')
    })

    cy.request('/productStock?productId=' + productId)
      .then((r) => expect(parseFloat(r.body.stock), 'stock leaves at dispatch, not at booking').to.eq(before - 4))
  })

  it('a SECOND parcel with identical contents raises its OWN invoice', () => {
    // The case that caught a live defect during D1. The dispatch idempotency key was built from the parcel
    // contents alone, so shipping 2 units today and 2 more tomorrow from the same line produced an IDENTICAL
    // key — the second dispatch replayed the first invoice, and those goods left the building with nothing
    // behind them. That is OMS-1, in the one place that raises invoices. Partial delivery is routine here, so
    // it is a likely path rather than a corner. The key now includes the already-shipped state.
    book('TwoParcels ' + run, 8).then((r) => {
      const o = r.body.data
      cy.request({
        method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
        body: { id: o.id }, failOnStatusCode: false,
      })
      const parcel = () => cy.request({
        method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        body: { id: o.id, lines: [{ orderItemId: o.items[0].id, quantity: 2 }], carrier: 'Ahsan' },
      })
      let firstInvoice
      cy.then(() => parcel()).then((s) => {
        expect(s.body.success, JSON.stringify(s.body)).to.eq(true)
        return detail(o.id)
      }).then((d) => { firstInvoice = d.invoiceNo; expect(firstInvoice).to.match(/\S/) })

      // Same line, same quantity, a genuinely different parcel.
      cy.then(() => parcel()).then((s) => {
        expect(s.body.success, JSON.stringify(s.body)).to.eq(true)
        return detail(o.id)
      }).then((d) => {
        // THE assertion. quantityShipped reaches 4 either way — the shipment records fine; it is the INVOICE
        // that was being replayed. Only this distinguishes fixed from broken.
        expect(d.invoiceNo, 'the second parcel is invoiced in its own right, not replayed')
          .to.not.eq(firstInvoice)
        expect(d.items[0].quantityShipped, 'and both parcels went out').to.eq(4)
      })
    })
  })

  it('booking is idempotent — a booker on a bad connection cannot double-order a shop', () => {
    const key = 'BOOK-' + run + '-DUP'
    let firstId
    book('DupOutlet ' + run, 3, { idempotencyKey: key }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      firstId = r.body.data.id
    })
    cy.then(() => book('DupOutlet ' + run, 3, { idempotencyKey: key })).then((r) => {
      expect(r.body.data.id, 'the retry returns the SAME order').to.eq(firstId)
    })
  })
})
