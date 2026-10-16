# Slice MKT-5e — unsold consigned stock goes back to its owner

**Status:** IN PROGRESS 2026-10-16. Migration V44 (`mkt_consigned_return`). Gate `mkt-5e-unsold-returns.cy.js` (5), walk M-5-05.

Requirements: **MKT-R20.5** (unsold-stock return), **MKT-R4.6** (the agreement's return-of-unsold-stock term: after how
many days, at whose cost), **MKT-R2.1** (the stock is the consignor's until it sells). Depends on 5a (the terms), 5b (the
ledger, the owner's holds lot by lot), 5c (what a listing can sell), 5d (what a count found missing).

## 1. What changes

| | Before | After |
|---|---|---|
| The held row (owner and warehouse) | units, value, sold, listing, counts | also the term ("Unsold stock goes back after 180 days, at the cost of the owner") and, in amber, "N held past the term" |
| The owner | could only end the agreement once nothing was held, and had no way to get its stock back | **Ask for them back** (units; up to what is on the shelf and not promised to an order): "Asked for back as RT-…: 2 × …, at the owner's cost. No longer sold; the warehouse hands it over next." Before the term the owner pays; for no more than is past the term, the agreement's party does |
| The warehouse | — | **Send back to the owner**, only for units held longer than the term (lot by lot, as each came in): "Only 1 has been held longer than 180 days (the agreement's term), so only it can be sent back." |
| A return | — | REQUESTED (no longer sold at once) → the warehouse **Hand over** with the carrier's reference and the cost: "Handed over: RT-…, Rs 650.00 charged to the owner." (a debit on the owner's account, the return number its reference, only when the owner bears it) → the owner **Received**: "Back in your stock: 2 × … (RT-…)." Whoever asked can **Cancel** before hand-over: "RT-… cancelled. The units are on sale again." |
| The owner's books | — | at Received its hold on the units is let go (they never left its stock, so nothing is bought or sold): free stock +N, on hand unchanged |
| The ledger | — | RETURNED rows (−N, doc_no = the return), lot by lot, oldest first |
| Ending the agreement | refused while anything is held | the same rule; returned units no longer count, so returning everything lets it end |

## 2. Design decisions (defaults taken)

- **The owner may ask at any time; the warehouse only past the term.** The owner owns the stock (R2.1); the term (R4.6)
  is MaxTheService's right to stop holding it. Asked for before the term, the owner bears the cost; asked for no more
  than is past it, the agreement's `unsoldReturnBearer` does. The bearer is fixed on the return when it is asked for.
- **The cost is what the warehouse paid the carrier, entered at hand-over** (Rs 0 to 100,000, with a reference); a
  cost the owner bears is debited then, as a settlement ADJUSTMENT (ref = RT-…, GL `MKT_ADJUSTMENT` −x), like 5d's
  credit. Charging at hand-over (not at receipt) means an owner that never confirms does not escape the cost; it only
  keeps its own stock set aside.
- **The units stop selling when the return is asked for**, not at hand-over: the ledger's `balance` subtracts every
  return not yet RECEIVED. Only units on the shelf and not promised to an order waiting for the warehouse can go, under
  the agreement's lock (the lock checkout counts under), so no order is left without its unit.
- **No count while a return waits for hand-over**: those units are on the shelf but no longer in the balance, so a count
  would read them as surplus. "Hand it over (or cancel the return) first, then count."
- **Receipt is two-phase.** RECEIVING is set first (the version serialises two presses); the owner's holds are then let
  go lot by lot (release, set the rest aside), which gives the same holds when repeated; only then are the RETURNED
  rows written. A failure leaves it RECEIVING, still off the shelf; pressing again finishes it.

## 3. Trace (RULE 0)

- **`MarketplaceConsignedStockRepository.balance` now also subtracts returns under way** (REQUESTED, DISPATCHED,
  RECEIVING). Call sites (6), all want what is on the shelf and free: `ConsignedSaleService.list` (the "holds none"
  check), `unpromised` (checkout, under the agreement lock), `sell` (min of lots and balance at Accept),
  `OfferProjectionService.consignedAvailable`, `ShrinkageService.count` (expected), `ConsignedReturnService.returnable`.
- **`mkt_consigned_stock` gains RETURNED rows (writer 4: `ConsignedReturnService.receive`).** Readers (10): `heldUnits`
  (END rule: wanted, a returned unit is no longer held), `heldAt` (changing the warehouse: wanted), `heldFor` (units
  and value fall; sold counts SOLD only; a row netting to 0 leaves the screen), `balance` (falls in the same
  transaction that ends the return's subtraction: no double count), `lots` (the lot shrinks, matching the hold set
  aside again), `heldSince` (new: aged lots net of what left them), `existsByTransferIdAndMovement` (RECEIVED only),
  `findByOrderLineIdAndMovement` (SOLD only), `countBy…` (VOIDED only), `findFirst…Movement` (RECEIVED only).
  `doc_no` = RT-…, so the unique key (transfer, movement, line, attempt, doc_no) keeps two returns of one lot apart.
- **`mkt_consigned_return`:** writer 1 (`ConsignedReturnService`); readers: the service itself, `balance`'s subquery,
  `ShrinkageService.count` (REQUESTED units). Every change locks the row (`lockById`).
- **The owner's books:** `releaseHold` then `setAside(left − take)` as the owner (`AsOrg`), the same calls 5c's sale and
  5d's write-off make. No inventory adjustment: the units never left the owner's stock.
- **Settlement:** `chargeReturn` writes one ADJUSTMENT debit keyed `return:<RT-…>`; readers: the account view, balance
  and payouts (keyed on the org). GL: `MKT_ADJUSTMENT` with a negative total (Dr 2400 / Cr 4510 per
  `MarketplacePostingRules`).
- **Column types against the entity under `ddl-auto=validate`:** BIGINT ↔ Long, INT ↔ Integer, VARCHAR ↔ String,
  DECIMAL(19,2) ↔ BigDecimal, DATETIME(6) ↔ LocalDateTime. The service started with V44 applied.
- **The wire:** the monolith relays 2 routes as `Map`s (`/mkt/consignedReturn`, `/mkt/consignedReturnDecision`); who may
  do what is decided in the service from the caller's org (owner, warehouse), never from the body.

## 4. Tests

- Unit: `ConsignedReturnServiceTest` 10 (owner asks any time and pays before the term; warehouse only past the term and
  the agreement's bearer; what is past the term leaves out missing and going units; replay and guards; hand-over
  charges only the owner-borne cost, needs a reference, refuses a second; receive lets the holds go oldest first and
  writes RETURNED rows; a refused receive carries on; cancel only by whoever asked and only before hand-over; the
  screen), `ShrinkageServiceTest` +1 (no count while a return waits).
- Gate `mkt-5e-unsold-returns.cy.js` (5), `--env '{"mkt":"5e"}'`. The term is reached by dating one lot's arrival back
  181 days on the local stack (task `ageConsignedLot`).
- Walk M-5-05.

## 5. Open items

- **A return that arrives short** is confirmed whole or not at all; a shortfall is raised with MaxTheService outside
  the portal (it would be a count against the returned lot, which 5d does not model).
- **A sale and a receipt on the same lot at the same moment** can leave the lot's hold one sale behind until the next
  movement on it (the same window as 5d's write-off); the ledger stays right.
- **No reminder** is sent when units pass the term; the warehouse sees "N held past the term" on its screen.
