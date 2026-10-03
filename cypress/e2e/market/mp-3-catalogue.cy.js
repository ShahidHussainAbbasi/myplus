/**
 * MP-3 — Customer catalogue and filters. PENDING: cases written first (STANDARDS "gate before implementation"); each `it()` has
 * no body yet, so Cypress reports it as pending — never as passed — until the slice is built.
 *
 * Programme: microservices/docs/platform-marketplace-design.md §6. Accounts: Anonymous browser · two approved sellers with offers on the same product.
 * The first case is the real-UI case (STANDARDS: at least one, first in the file).
 */

describe('MP-3 — Customer catalogue and filters (pending until built)', () => {
  it('1 — Real UI: open /market and search "A32" → One product, "Available from 2 sellers, from Rs. 51,500"')
  it('2 — Open the product → Each offer shows seller, price, delivery, distance, warranty, rating')
  it('3 — Sort by lowest price, nearest, fastest → The order changes; the set of offers does not')
  it('4 — Filters with an unapproved, a suspended and an out-of-stock offer present → None of the three is ever shown')
  it('5 — Sell the last unit of an offer at seller A\'s POS → The offer shows out of stock after the sync interval')
  it('6 — Choose the more expensive offer → The chosen seller stays selected; never swapped for the cheapest')
  it('7 — Inspect the public response → No cost price, internal SKU or seller-private fields')
})
