/**
 * OMS O5e — POS orders reach parity with storefront orders (closes OMS-5).
 * Design: microservices/docs/slices/oms-O5e-pos-order-parity.md
 *
 * `OrderService.record()` never received the hardening `placePublic` got: no line items (so a POS order can
 * NEVER restore stock, because cancel/return are guarded by `!items.isEmpty()`), a client-computed total, no
 * `SO-` number, and no idempotency key.
 *
 * ── THIS FILE IS BEING BUILT IN THE ORDER §2.3 MANDATES ──────────────────────────────────────────────────
 * Step 1 (idempotency) ships and gates ALONE, because during the migration BOTH the browser and
 * business-service will record the order. Without the key that is two writers and two orders for one sale —
 * the defect O2's OMS-3 work removed from the storefront, reappearing on the POS path.
 *
 * The `describe`s below are deliberately staged. Only STEP 1 is implemented in the service today; the rest are
 * `describe.skip` so they document the target without reporting green for code that does not exist. Un-skip
 * each as its step lands.
 */
describe('OMS O5e step 1 — one invoice is one order', () => {
  const run = String(Date.now()).slice(-6)

  beforeEach(() => cy.loginAsMarketplace())

  const record = (invoiceNo, extra = {}) => cy.request({
    method: 'POST', url: '/recordOrder', failOnStatusCode: false,
    headers: { 'Content-Type': 'application/json' },
    body: Object.assign({ invoiceNo, customerName: 'PosBuyer_' + run, total: 25 }, extra),
  })

  it('records a POS order', () => {
    const inv = 'POS-' + run
    record(inv).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.invoiceNo).to.eq(inv)
      expect(r.body.data.source).to.eq('POS')
    })
  })

  it('posting the SAME invoice twice yields ONE order, not two', () => {
    const inv = 'POSDUP-' + run
    let firstId
    record(inv).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      firstId = r.body.data.id
    })
    // A retry, a double-click, or (during the migration) the browser and business-service both reporting the
    // same sale. All three must converge on one order.
    cy.then(() => record(inv)).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.id, 'the replay returns the ORIGINAL order').to.eq(firstId)
    })
    // And the list agrees — one row, not two.
    cy.then(() => cy.request('/getOrders?q=' + encodeURIComponent(inv))).then((r) => {
      const mine = (r.body.data.content || []).filter((o) => o.invoiceNo === inv)
      expect(mine.length, 'exactly one order for one invoice').to.eq(1)
    })
  })

  it('two DIFFERENT invoices still produce two orders', () => {
    // The guard must not over-collapse: idempotency is per invoice, not "one POS order per shop".
    const a = 'POSA-' + run, b = 'POSB-' + run
    let idA
    record(a).then((r) => { idA = r.body.data.id })
    cy.then(() => record(b)).then((r) => {
      expect(r.body.success).to.eq(true)
      expect(r.body.data.id).to.not.eq(idA)
    })
  })
})

// ── Steps 2 + 3: the SALE creates the order, server-side, with its own lines and total ────────────────────
//
// These gate together on purpose. Step 2's server total could not be taken additively — it needs a caller that
// knows the sale, which is step 3 — so the two are only observable through one completed sale.
//
// Every assertion below goes through `POST /addSell` and NOTHING else. That is the whole claim of §2.5's
// option C: the monolith orchestrates, so the order appears without the browser posting `/recordOrder`. A
// cy.request to /addSell is exactly a browser that made the sale and then went away.
describe('OMS O5e steps 2+3 — a Store sale produces its order, server-side', () => {
  let productId
  const stamp = String(Date.now()).slice(-6)
  const PRICE = 30

  before(() => {
    cy.loginAsMarketplace()
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name: 'PosParity_' + stamp, sku: 'PP' + stamp, sellingPrice: PRICE, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId, quantity: 50 }, failOnStatusCode: false,
      })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
  })

  beforeEach(() => cy.loginAsMarketplace())

  const stockLevel = () => cy.request('/productStock?productId=' + productId).then((r) => parseFloat(r.body.stock))

  /** A completed Store sale. Returns the invoice number — the browser posts nothing else. */
  const sell = (qty, buyer) => cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      customer: { name: buyer, contact: '0300POS', paidAmount: 0, dueAmount: 0 },
      sales: [{ productId, quantity: qty, sellRate: PRICE, totalAmount: PRICE * qty, netAmount: PRICE * qty }],
      // Deliberately WRONG, and deliberately sent: this is the client-computed total (gap B). The order must
      // record what the sale actually posted to the books, so this number must NOT appear anywhere.
      paidAmount: 0, dueAmount: 0, grandTotal: 1,
    },
  }).then((r) => {
    expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
    return r.body.object
  })

  /** The order the sale produced. Found by invoice number — one invoice is one order (step 1). */
  const orderFor = (invoiceNo) => cy.request('/getOrders?q=' + encodeURIComponent(invoiceNo)).then((r) => {
    const found = ((r.body.data && r.body.data.content) || []).filter((o) => o.invoiceNo === invoiceNo)
    expect(found.length, 'exactly one order for invoice ' + invoiceNo).to.eq(1)
    return found[0]
  })

  it('a sale creates its order with NO browser involvement', () => {
    // The defect this closes: ecommerce.js posted AFTER addSell returned, so losing the tab or the network lost
    // the order while the sale survived. Nothing in this test posts /recordOrder.
    const buyer = 'PosSrv_' + stamp
    sell(2, buyer).then((invoiceNo) => orderFor(invoiceNo).then((o) => {
      expect(o.source, 'recorded as a POS order').to.eq('POS')
      expect(o.customerName).to.eq(buyer)
    }))
  })

  it('the order total is the SALE\'s, not the one the client posted', () => {
    // grandTotal:1 went in with the sale. The order must say 60 — what business-service actually invoiced.
    sell(2, 'PosTotal_' + stamp).then((invoiceNo) => orderFor(invoiceNo).then((o) => {
      expect(Number(o.total), 'the server total, not the posted 1').to.eq(PRICE * 2)
    }))
  })

  it('the order carries the sale\'s line items', () => {
    // Gap A. Without these, cancel and return skip stock restoration entirely (`!items.isEmpty()`).
    sell(3, 'PosLines_' + stamp).then((invoiceNo) => orderFor(invoiceNo).then((o) => {
      cy.request('/getOrder?id=' + o.id).then((r) => {
        const items = (r.body.data || {}).items || []
        expect(items.length, 'one line, from the invoice').to.eq(1)
        expect(items[0].productId).to.eq(productId)
        expect(items[0].quantity).to.eq(3)
        expect(Number(items[0].price)).to.eq(PRICE)
      })
    }))
  })

  it('the order gets a merchant-facing SO- number', () => {
    // O2 gave placePublic the per-org series; record() never got it, so a POS order was the one kind of order
    // a merchant could not quote or track.
    sell(1, 'PosNum_' + stamp).then((invoiceNo) => orderFor(invoiceNo).then((o) => {
      expect(o.orderNo, 'per-org SO- series').to.match(/^SO-\d+$/)
    }))
  })

  it('cancelling a POS order restores stock', () => {
    // THE case that proves OMS-5 closed. Impossible before step 3: a line-less order fails the
    // `!items.isEmpty()` guard, so the cancel reversed nothing and the goods stayed sold.
    let before
    stockLevel().then((s) => { before = s })
    sell(4, 'PosCancel_' + stamp).then((invoiceNo) => {
      stockLevel().then((s) => expect(s, 'the sale took the goods').to.eq(before - 4))
      return orderFor(invoiceNo)
    }).then((o) => {
      cy.request({
        method: 'POST', url: '/updateOrderStatus', headers: { 'Content-Type': 'application/json' },
        body: { id: o.id, status: 'CANCELLED' }, failOnStatusCode: false,
      }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
      stockLevel().then((s) => expect(s, 'the cancel put them back').to.eq(before))
    })
  })

  it('a sale that was REFUSED produces no order', () => {
    // business-service answers a rejected sale with HTTP 200 and a FAILED/ERROR envelope, having written
    // nothing. An order built from one of those would reference an invoice that does not exist.
    cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: {
        customer: { name: 'PosRefused_' + stamp, contact: '0300NO', paidAmount: 0, dueAmount: 0 },
        sales: [{ productId, quantity: 99999, sellRate: PRICE, totalAmount: 1, netAmount: 1 }],
        paidAmount: 0, dueAmount: 0, grandTotal: 1,
      },
    }).then((r) => {
      expect(r.body.status, 'insufficient stock is refused').to.not.eq('SUCCESS')
      cy.request('/getOrders?q=' + encodeURIComponent('PosRefused_' + stamp)).then((o) => {
        const rows = (o.body.data && o.body.data.content) || []
        expect(rows.length, 'no order for a sale that never happened').to.eq(0)
      })
    })
  })
})
