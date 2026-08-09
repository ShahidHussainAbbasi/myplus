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

// ── Step 2: record() takes the SALE's lines and total, and allocates an SO- number ────────────────────────
describe.skip('OMS O5e step 2 — a POS order carries its lines, server total and SO- number', () => {
  it('the order total comes from the SALE, not the request body', () => {
    // Today `record()` stores dto.getTotal() — whatever the browser posted. That is the client-computed total
    // OMS-5 named and O1 removed from the storefront.
  })

  it('the order carries the sale line items', () => {
    // Without these, cancel and return skip stock restoration entirely (`!items.isEmpty()` guard), so a POS
    // order can never put goods back.
  })

  it('the order gets a merchant-facing SO- number', () => {
    // O2 gave placePublic the per-org series; record() never got it, so a POS order cannot be tracked or
    // quoted to a customer.
  })
})

// ── Step 3: the SALE creates the order server-side ────────────────────────────────────────────────────────
describe.skip('OMS O5e step 3 — completing a Store sale creates the order without the browser', () => {
  it('a sale recorded with the browser closed still produces an order', () => {
    // The whole point: today ecommerce.js posts AFTER addSell succeeds, so losing the tab or the network loses
    // the order while the sale survives.
  })

  it('cancelling a POS order restores stock', () => {
    // Impossible today — this is the user-visible payoff of steps 2 and 3 together.
  })
})
