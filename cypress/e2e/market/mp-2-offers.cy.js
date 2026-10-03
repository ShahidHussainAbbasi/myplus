/**
 * MP-2 — Seller offers. PENDING: cases written first (STANDARDS "gate before implementation"); each `it()` has
 * no body yet, so Cypress reports it as pending — never as passed — until the slice is built.
 *
 * Programme: microservices/docs/platform-marketplace-design.md §6. Accounts: Operator · Seller A · Seller B · user tier.
 * The first case is the real-UI case (STANDARDS: at least one, first in the file).
 */

describe('MP-2 — Seller offers (pending until built)', () => {
  it('1 — Real UI: seller A lists one of its own POS products against a canonical product → Offer saved, awaiting approval')
  it('2 — Seller A sends seller B\'s product id → 404; nothing written')
  it('3 — The request body names another organisation as seller or stock owner → Ignored; the offer belongs to the caller')
  it('4 — Price below the policy floor, or a discount above the maximum → Refused with the rule\'s sentence')
  it('5 — Stock source PLATFORM, SUPPLIER or CONSIGNMENT → Refused in Phase 1')
  it('6 — Operator approves the offer → APPROVED and visible in the customer catalogue')
  it('7 — Operator suspends seller A → A\'s offers disappear from the catalogue; reinstated → they return')
  it('8 — A seller that is not ACTIVE creates an offer → Refused')
  it('9 — User tier views offers, then tries to publish → Can view; cannot publish (403)')
  it('10 — Owner switches selling OFF → Offers hidden; offer writes refused')
})
