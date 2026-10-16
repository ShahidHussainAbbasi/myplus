# Slice MKT-5d — counts of consigned stock and shrinkage claims

**Status:** VERIFIED 2026-10-16. Migration V43 (`mkt_consignment_count`, `mkt_shrinkage_claim`; `mkt_consigned_stock.doc_no`
in the movement's uniqueness). Unit: marketplace-service 498 (11 new). Gate 6/6 live, walk M-5-02 recorded ([live verification](../marketplace/live-verification-2026-10-03.md) §27). Gate `mkt-5d-counts-shrinkage.cy.js` (6).

Requirements: **MKT-R12.1** (shrinkage kinds), **MKT-R12.2** (the agreement defines responsibility, tolerance, count
frequency, proof, claim window, approval, settlement effect), **MKT-R12.3** (default responsibility table), **MKT-R12.4**
(never debit without evidence and a dispute step), **MKT-R20.5** (custodian counts, shrinkage claims). Depends on 5a (the
terms), 5b (the ledger, the owner's holds lot by lot), 5c (what a listing can sell).

## 1. What changes

| | Before | After |
|---|---|---|
| The warehouse's held row | units, value, sold, listing | also "Next count due by …" (the agreement's frequency after the last count, or after the stock came in; red once overdue), and a count form: units counted, note → **Record count** |
| A count that matches | — | "Counted 5 of 5 (CC-…): the count matches." Nothing moves |
| A count that is short | — | "Counted 3 of 5 (CC-…): 2 missing, no longer sold. The owner can write them off or claim them." The listing at once sells only what is on the shelf; the units stay the owner's, in the ledger and set aside in its books, until resolved. Of them, the agreement's tolerance (rounded down: 1% of 5 is none) is the owner's to bear |
| A count that is over | — | recorded, nothing moves |
| The owner's held row | — | each count, what it found, and per count: **Claim from MaxTheService** (what happened: the 9 kinds of R12.1; units; proof) and **Write off N** |
| A claim | — | follows the agreement (R12.2/R12.3): theft, miscount, unrecorded use, loss in delivery are the custodian's; damage and breakage follow the damage term; expiry and spoilage the expiry term; a short shipment is the shipper's (the owner). A kind that is the owner's is refused: "Under CA-…, expiry is yours to bear, so there is nothing to claim from MaxTheService. Write the units off instead." Proof when the agreement asks; inside the claim window; never more than is missing beyond the tolerance and not already claimed. "Claimed as SC-…: Rs 40,000.00 for 1 × …. Nothing is paid until MaxTheService decides." |
| The operator | — | Platform → **Shrinkage claims**: waiting claims first. **Approve and pay**: the owner's account is credited the units' declared value (a row with the claim number) and the units leave its books. **Reject** needs a reason the owner reads; the units can then be written off |
| Write-off | — | the owner's own decision: the units leave its books (its hold let go, the units written off with the reason, the rest of the lot set aside again); nothing is paid |

## 2. Design decisions (defaults taken)

- **Who counts:** the custodian, the warehouse. The operator does not count (it has no shelf).
- **Missing units are not written off at the count.** R12.4: nothing changes the owner's books or money without the owner's
  own write-off or an approved claim. They stop being sold at once (the ledger's `balance` subtracts unresolved units).
- **Approval pays the declared value**, the value the owner declared when consigning (5b), credited as a settlement
  ADJUSTMENT (ref = the claim number) and posted to the GL as `MKT_ADJUSTMENT`, like an operator correction.
- **Tolerance applies per count** to its expected units, rounded down to whole units.

## 3. Trace (RULE 0)

- **`MarketplaceConsignedStockRepository.balance` now subtracts unresolved missing units.** Readers (4), all want what is
  on the shelf: `ConsignedSaleService.list` (the "holds none" check), `unpromised` (checkout, under the agreement lock),
  `OfferProjectionService.consignedAvailable`, `ShrinkageService.count` (a count's expected). The Accept check in
  `ConsignedSaleService.sell` takes min(lots, balance): the lots still contain missing units (the owner's books do).
- **`mkt_consigned_stock` gains LOST rows (writer 3: `ShrinkageService.lostRows`).** Readers (9): `heldUnits` (END rule:
  wanted, a lost unit is no longer held), `heldAt`, `heldFor` (units and value fall; sold counts SOLD only), `balance`,
  `lots` (the lot shrinks with what was lost, matching the hold set aside again), `existsByTransferIdAndMovement`
  (RECEIVED only), `findByOrderLineIdAndMovement` (SOLD only), `countBy…` (VOIDED only), `findFirst…Movement` (RECEIVED).
  Unique key widened to (transfer, movement, line, attempt, doc_no): two resolutions of one lot are two rows.
- **Counts and claims:** writers 1 each (`ShrinkageService`). A count is locked (`lockById`) for every change; a claim's
  units are held out of a write-off by `openUnits`, a write-off's by `pending`, set before the owner's books are touched
  and given back if they refuse.
- **The owner's books:** release the lot's hold, `/api/inventory/stock/adjust` DECREASE as the owner (BLK-5: keyed
  `MKT-LOST-<doc>-T<transfer>`, so a retry replays), set the rest of the lot aside. Lots done before a failure stay done;
  the same document retried carries on.
- **Settlement:** `creditClaim` writes one ADJUSTMENT credit keyed `claim:<SC-…>`. Readers: the account view, the
  balance and payouts (keyed on the org: the owner). Nothing reads ADJUSTMENT specially.
- **Column types against the entities under `ddl-auto=validate`:** INT ↔ Integer, BIGINT ↔ Long, VARCHAR ↔ String,
  DECIMAL(19,2) ↔ BigDecimal, DATETIME(6) ↔ LocalDateTime. The service started with V43 applied.
- **The wire:** the monolith relays 5 routes as `Map`s; the operator's 2 behind `ROLE_ADMIN` and `assertOperator`.

## 4. Tests

- Unit: `ShrinkageServiceTest` 11 (count by the warehouse only, tolerance, replay, match/surplus; claim proof, bearer,
  limit, window, someone else's count; approve order and keys and credit; refused books leave the claim open; reject
  then write off; write-off rolls back; withdraw; the screen; R12.1/R12.3 table); `ConsignedSaleServiceTest` +1 case
  (missing units are not sold at Accept).
- Gate `mkt-5d-counts-shrinkage.cy.js` (6), `--env '{"mkt":"5d"}'`.
- Walk M-5-02.

## 5. Open items

- **A sale between a failed write-off and its retry** can change the lot split, and the inventory key then answers 409
  (a different correction under the same key): the owner presses again after the sale settles in.
- **A count found more than held** is recorded only; units that turn up again after a write-off are not put back.
- **Carrier loss after handover** is the custodian's under consignment (MaxTheService contracts the carrier); a carrier
  as its own party belongs with delivery partners.
