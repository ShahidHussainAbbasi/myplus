/**
 * OMS O7 D4 — delivery keying, credit notes for what came back, and settlement.
 * Design: microservices/docs/slices/oms-O7-distribution-presales.md §12
 *
 * Ahsan has no device (§6 D-5), so this is the ADMIN's keying screen: he brings back signed paper invoices and
 * Javed records the outcome per parcel. Three facts are kept separate on purpose — what arrived, what came
 * back, and what was collected — because most of this trade is on credit and goods arriving is not money
 * arriving.
 *
 * The case that carries the slice is **"a partial delivery raises a credit note against THAT parcel's
 * invoice"**: it is the whole reason the invoice moved onto the shipment, and the reason a new contract
 * operation exists at all.
 */
describe('OMS O7 D4 — what happened at the shop door', () => {
  const run = String(Date.now()).slice(-6)
  let productId, orderId, lineId, shipmentId, invoiceNo

  before(() => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name: 'DelProd_' + run, sku: 'DL' + run, sellingPrice: 25, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId, quantity: 200 }, failOnStatusCode: false,
      })
    })
  })

  /** A field order, confirmed and dispatched — the state a returning driver's paperwork describes. */
  const dispatchedOrder = (qty) => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: {
        customerName: 'DelOutlet ' + run + '-' + Date.now(), customerContact: '0300' + run,
        items: [{ productId, quantity: qty, price: 25, productName: 'DelProd_' + run }],
      },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      orderId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
        body: { id: orderId }, failOnStatusCode: false,
      })
    })
    cy.then(() => cy.request('/getOrder?id=' + orderId)).then((r) => { lineId = r.body.data.items[0].id })
    cy.then(() => cy.request({
      method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { id: orderId, lines: [{ orderItemId: lineId, quantity: qty }], carrier: 'Ahsan' },
    })).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
    cy.then(() => cy.request('/getOrder?id=' + orderId)).then((r) => {
      const parcel = (r.body.data.shipments || [])[0]
      expect(parcel, 'the parcel exists').to.exist
      shipmentId = parcel.id
      invoiceNo = parcel.invoiceNo
    })
  }

  it('a dispatched parcel carries ITS OWN invoice number', () => {
    // The D1 limitation this slice owns: `orders.invoice_no` is overwritten by each dispatch, so a
    // part-delivered order could only ever be credited for its LAST parcel. An invoice corresponds
    // one-for-one to a parcel, so it belongs on the parcel.
    dispatchedOrder(10)
    cy.then(() => {
      // STRENGTHENED after this case passed while `invoiceNo` was undefined — ShipmentDTO had no such field,
      // so `.to.match(/\S/)` was being applied to nothing and could not fail. `.to.be.a('string')` is what
      // makes the absence detectable; the format check is what makes it the RIGHT string.
      expect(invoiceNo, 'the parcel exposes an invoice at all').to.be.a('string')
      expect(invoiceNo, 'and it is a real INV- document').to.match(/^INV-\d+$/)
    })
    // Single-parcel order, so the order and its one parcel must agree about the invoice. On a MULTI-parcel
    // order they deliberately would not — that divergence is the whole reason this column exists.
    cy.then(() => cy.request('/getOrder?id=' + orderId)).then((r) => {
      expect(r.body.data.shipments[0].invoiceNo).to.eq(r.body.data.invoiceNo)
    })
  })

  it('a full delivery settles the order without crediting anything', () => {
    dispatchedOrder(6)
    cy.then(() => cy.request({
      method: 'POST', url: '/recordDelivery', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: {
        id: orderId, shipmentId, deliveredBy: 'Ahsan', settlement: 'CREDIT', amountCollected: 0,
        lines: [{ orderItemId: lineId, deliveredQuantity: 6 }],
      },
    })).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.outcome).to.eq('DELIVERED')
      expect(r.body.data.creditNotes, 'nothing came back, so nothing is credited').to.be.empty
      // Attributed to whoever KEYED it — never to the driver, who was not here.
      expect(r.body.data.recordedByName).to.contain('owner.marketplace')
      expect(r.body.data.deliveredBy, 'the driver is a NOTE').to.eq('Ahsan')
    })
    cy.then(() => cy.request('/getOrder?id=' + orderId))
      .then((r) => expect(r.body.data.fulfilmentStatus).to.eq('DELIVERED'))
  })

  it('THE CASE — a PARTIAL delivery raises a credit note against that parcel\'s invoice', () => {
    // The shop takes 8 of 10 and refuses the rest. The invoice was raised for 10 at dispatch and the
    // shopkeeper is holding it, so the 2 come back as a CREDIT NOTE — never as a retro-edit of a document
    // the customer has a copy of (B2B-P3f), and never as a full void of an invoice that was 80% correct.
    dispatchedOrder(10)
    cy.then(() => cy.request({
      method: 'POST', url: '/recordDelivery', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: {
        id: orderId, shipmentId, deliveredBy: 'Ahsan', settlement: 'CREDIT',
        note: 'Short-expiry batch refused',
        lines: [{ orderItemId: lineId, deliveredQuantity: 8 }],
      },
    })).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.outcome).to.eq('PARTIAL')
      expect(r.body.data.creditNotes, 'the shortfall is credited').to.not.be.empty
      expect(r.body.data.creditNotes[0], 'a real CRN- document').to.match(/^CRN-/)
    })

    // The credit note exists in the BOOKS, not just on the order — this is the assertion that proves the
    // contract op ran business-service's own return path rather than marketplace inventing accounting.
    cy.then(() => cy.request('/getSaleReturns')).then((r) => {
      // `object`, not `collection`. GenericResponse has BOTH, and which one a list lands in depends on the
      // constructor the endpoint happened to use: the 3-arg (status, message, Object) puts it in `object`,
      // the (status, message, Collection) overload in `collection`. getSaleReturns uses the former.
      const rows = r.body.object || r.body.collection || r.body.data || []
      expect(rows.length, 'positive control: the returns read is live and returning rows').to.be.greaterThan(0)
      const mine = rows.filter((x) => x.invoiceNo === invoiceNo)
      expect(mine.length, 'the return is recorded against this parcel\'s invoice').to.be.greaterThan(0)
      expect(mine[0].creditNoteNo, 'and it is a real credit-note document').to.match(/^CRN-/)
    })
  })

  it('the returned units are owed again — they went out and came back', () => {
    dispatchedOrder(10)
    cy.then(() => cy.request({
      method: 'POST', url: '/recordDelivery', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: {
        id: orderId, shipmentId, settlement: 'CREDIT',
        lines: [{ orderItemId: lineId, deliveredQuantity: 7 }],
      },
    })).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
    cy.then(() => cy.request('/getOrder?id=' + orderId)).then((r) => {
      // 10 shipped, 3 refused ⇒ 7 actually delivered. The order still owes those 3.
      expect(r.body.data.items[0].quantityShipped, 'only what stayed counts as shipped').to.eq(7)
    })
  })

  it('a wholly REFUSED parcel does not mark the order delivered', () => {
    // Nothing arrived, so nothing is settled — the order still owes everything and stays workable.
    dispatchedOrder(4)
    cy.then(() => cy.request({
      method: 'POST', url: '/recordDelivery', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: {
        id: orderId, shipmentId, settlement: 'CREDIT',
        lines: [{ orderItemId: lineId, deliveredQuantity: 0 }],
      },
    })).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.outcome).to.eq('REFUSED')
      expect(r.body.data.creditNotes).to.not.be.empty
    })
    cy.then(() => cy.request('/getOrder?id=' + orderId))
      .then((r) => expect(r.body.data.fulfilmentStatus, 'nothing arrived, so nothing is delivered')
        .to.not.eq('DELIVERED'))
  })

  it('a parcel cannot be keyed twice', () => {
    // The idempotency guard lives HERE, not in the contract: a shop genuinely can refuse the same product on
    // two different deliveries, so the trade op cannot dedupe. This service is what knows the parcel is done.
    dispatchedOrder(5)
    const key = () => cy.request({
      method: 'POST', url: '/recordDelivery', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: { id: orderId, shipmentId, settlement: 'PAID', amountCollected: 125,
              lines: [{ orderItemId: lineId, deliveredQuantity: 5 }] },
    })
    cy.then(() => key()).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
    cy.then(() => key()).then((r) => {
      expect(r.body.success, 'the second attempt is refused').to.not.eq(true)
      expect(JSON.stringify(r.body)).to.match(/already/i)
    })
  })

  it('the delivery history records who KEYED it, and when it was RECORDED', () => {
    dispatchedOrder(3)
    cy.then(() => cy.request({
      method: 'POST', url: '/recordDelivery', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: { id: orderId, shipmentId, deliveredBy: 'Ahsan', settlement: 'PAID', amountCollected: 75,
              lines: [{ orderItemId: lineId, deliveredQuantity: 3 }] },
    })).then((r) => expect(r.body.success).to.eq(true))
    cy.then(() => cy.request('/getDeliveries?id=' + orderId)).then((r) => {
      const rows = r.body.data || []
      expect(rows.length).to.eq(1)
      expect(rows[0].recordedAt, 'RECORDED at — not "delivered at", which nobody observed').to.match(/\S/)
      expect(rows[0].recordedByName).to.contain('owner.marketplace')
      expect(rows[0].deliveredBy).to.eq('Ahsan')
      expect(rows[0].settlement).to.eq('PAID')
      expect(Number(rows[0].amountCollected)).to.eq(75)
    })
  })
})
