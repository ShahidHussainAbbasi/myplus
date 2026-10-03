/**
 * MP-6 — Returns and support. PENDING: cases written first (STANDARDS "gate before implementation"); each `it()` has
 * no body yet, so Cypress reports it as pending — never as passed — until the slice is built.
 *
 * Programme: microservices/docs/platform-marketplace-design.md §6. Accounts: Customer · Seller A · Operator.
 * The first case is the real-UI case (STANDARDS: at least one, first in the file).
 */

describe('MP-6 — Returns and support (pending until built)', () => {
  it('1 — Real UI: request a return on a delivered line inside the window → RETURN_REQUESTED; a support case opens')
  it('2 — Request a return after the window → Refused, quoting the window from the order')
  it('3 — Wrong product vs change of mind → Cost bearer: fulfiller vs customer, from the order-time snapshot')
  it('4 — Inspection accepted, and inspection rejected → Accepted → refund pending; rejected → reason sent to the customer')
  it('5 — Returned item outcome → Stock returned to seller, quarantined or written off as decided')
  it('6 — Operator reads the case → Every note and action in order, all audited')
  it('7 — Customer B opens A\'s case → 404')
})
