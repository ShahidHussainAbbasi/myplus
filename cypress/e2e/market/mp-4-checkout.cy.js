/**
 * MP-4 — Customer account, cart and checkout. PENDING: cases written first (STANDARDS "gate before implementation"); each `it()` has
 * no body yet, so Cypress reports it as pending — never as passed — until the slice is built.
 *
 * Programme: microservices/docs/platform-marketplace-design.md §6. Accounts: New customer · Customer B · Seller A.
 * The first case is the real-UI case (STANDARDS: at least one, first in the file).
 */

describe('MP-4 — Customer account, cart and checkout (pending until built)', () => {
  it('1 — Real UI: sign up, add an offer, check out with Cash on Delivery → Order MKT-… shown as "Waiting for the seller"')
  it('2 — Send a different price in the request → Server price used')
  it('3 — Submit twice with the same Idempotency-Key → One order, one stock hold')
  it('4 — Seller\'s stock runs out between cart and checkout → "Please choose another offer"; no order; nothing charged')
  it('5 — Sandbox card "fail" → Order not confirmed; hold released')
  it('6 — Cart with offers from two sellers → Refused: one seller per checkout in Phase 1')
  it('7 — Operator publishes a new returns policy after the order → The order keeps its original price, commission and return terms')
  it('8 — Check the four statuses of a new COD order → Order PAYMENT_PENDING · Fulfilment OFFERED · Payment UNPAID · Settlement NOT_ELIGIBLE')
  it('9 — Customer B opens customer A\'s order → 404')
  it('10 — Check out a regulated product → Refused')
})
