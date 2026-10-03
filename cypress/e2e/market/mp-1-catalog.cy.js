/**
 * MP-1 — Canonical products and matching. PENDING: cases written first (STANDARDS "gate before implementation"); each `it()` has
 * no body yet, so Cypress reports it as pending — never as passed — until the slice is built.
 *
 * Programme: microservices/docs/platform-marketplace-design.md §6. Accounts: Operator · Seller A (mobile) · Seller B (pharmacy owner.pharma@).
 * The first case is the real-UI case (STANDARDS: at least one, first in the file).
 */

describe('MP-1 — Canonical products and matching (pending until built)', () => {
  it('1 — Real UI: operator creates "Samsung Galaxy A32 128GB Black, New, 12-month warranty" → Product saved with approval PENDING_REVIEW')
  it('2 — The identity key is built by the server → SAMSUNG|GALAXY-A32|128GB|BLACK|NEW|WARRANTY-12M')
  it('3 — Create A32 64GB and A32 128GB → Two products; never merged')
  it('4 — Create Panadol Extra 500 mg tablets in packs of 10 and of 20 → Two products; pack size is part of the key')
  it('5 — A seller product with only a name (no brand/model) is proposed for matching → Goes to PENDING_REVIEW; never auto-matched by name')
  it('6 — Seller product with a GTIN equal to a canonical product\'s → Proposed MATCHED; the operator still confirms for a new seller')
  it('7 — Operator corrects a bad match with a reason → NEEDS_CORRECTION then MATCHED to the right product; audited with the reason')
  it('8 — A tenant tries to create or approve a canonical product → 403')
  it('9 — A product marked regulated is approved for Phase 1 → Refused: regulated products wait for Phase 6')
})
