/**
 * MP-7 — Settlement ledger and payouts. PENDING: cases written first (STANDARDS "gate before implementation"); each `it()` has
 * no body yet, so Cypress reports it as pending — never as passed — until the slice is built.
 *
 * Programme: microservices/docs/platform-marketplace-design.md §6. Accounts: Operator · Seller A · Seller B.
 * The first case is the real-UI case (STANDARDS: at least one, first in the file).
 */

describe('MP-7 — Settlement ledger and payouts (pending until built)', () => {
  it('1 — Real UI: operator opens Settlements after a delivery → Seller A\'s entries, PENDING_RETURN_WINDOW')
  it('2 — Reconcile the source example → 5,000 = 4,150 seller + 500 commission + 200 delivery + 50 fees + 100 reserve, to the paisa')
  it('3 — Try to change an entry → No edit path; a correction is a new REVERSAL entry')
  it('4 — Delivered on a Friday, T+1 → Eligible on the next business day (Monday or later for holidays)')
  it('5 — An open return or dispute → Entry ON_HOLD, not eligible')
  it('6 — Approve a payout twice with the same key → One payout')
  it('7 — Operator\'s trial balance after delivery → 2400 Seller Payables, 4400 Commission, 4300 Delivery move exactly; balanced')
  it('8 — Payout marked PAID with a bank reference → 2400 and bank fall; seller sees PAID with the reference')
  it('9 — Seller B opens seller A\'s ledger → 404')
})
