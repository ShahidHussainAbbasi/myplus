# Slice MKT-4b — a supplier's dispatch deadline: a day to send what it accepts

**Status:** BUILT 2026-10-15. Unit-green (marketplace-service 464 tests, 6 new). Migration V39. Gate
`mkt-4b-supplier-dispatch.cy.js` **6/6 on a live stack, in two phases** (3 today, 3 on a clock two days later). Manual
cases M-4-03 and M-4-04 recorded.

Requirement: **MKT-R20.5** (supplier stock: acceptance, tracking, **SLA**), with R8.2 (the shopper deals with
MaxTheService, which chases the supplier) and R12.4 (a late record is a note, never a sanction). Acceptance (a
20-minute window) shipped in 4a; tracking is the supplier's own store order and parcels, as for any seller (MKT-3b).
What was missing is the SLA: nothing said when a supplier had to send an order it had accepted.

**Default taken (no ruling asked):** 24 hours from Accept, for supplier stock only. A shop's parts and the warehouse's
keep no dispatch deadline; nothing changes for them.

## 1. Document

| | Before | After |
|---|---|---|
| The supplier accepts an order of supplier stock | no deadline | it must leave within **24 hours**. Its row in Incoming marketplace orders reads **"Send within 23:59"** |
| The shopper, after Accept | "Confirmed by Phone Supplier" | **"Phone Supplier sends it within 24 hours"** (and the cash line) |
| 24 hours pass and nothing has left | nothing | the part is recorded **LATE** (audited `MKT_DISPATCH_LATE`). The supplier's row reads **"Late: it was due to leave within 24 hours of accepting. Send it now."** in red; the shopper's page **"Phone Supplier is late sending this. MaxTheService is chasing it."** |
| A parcel leaves | — | the clock and the late sentence are gone; the shopper sees "On its way from Phone Supplier" with the carrier and tracking (3b). A part sent late stays LATE on the record |
| The operator's Seller performance | accepted, time to accept, delivered on time | also, for a supplier, **"Sent within 24 hours: 80% (4 of 5)"** under "Delivered on time", and the flag **"Sends fewer than 90% of its orders within 24 hours"** (five settled orders needed, as every rate). The supplier's own scorecard shows the same line |

### 1a. Trace (RULE 0)

**New columns (V39), `mkt_seller_order`:** `dispatch_by` DATETIME NULL ↔ `LocalDateTime`; `dispatch_status`
VARCHAR(12) NULL ↔ `String` (length 12), index `idx_mkt_so_dispatch (dispatch_status, dispatch_by)`. Checked against
the entity under `ddl-auto=validate`: the service started with V39 applied (flyway 39). Every existing row stays NULL:
no deadline.

**Writers: 2.** `SellerOrderService.accept` stamps the deadline (the only path to ACCEPTED: 1 writer of that status);
the shortage reroute's new part is accepted through the same method. `SupplierDispatchService.settle` moves DUE once
to ON_TIME, LATE or VOID. Nothing recomputes either column.

**Readers: 4.** The seller's order view (list and single: `secondsToShip`, `dispatchLate`), the shopper's part view
(checkout `parts()`, used by tracking and My orders), the sweeper's new query, and the two scorecard queries
(`performanceFacts`, `performanceFactsOf`, one more column each). **Queries over `mkt_seller_order`: 13 before this
slice** (10 in its repository, 2 joins from order lines, 1 from returns): 2 now read the new column (the scorecard), 11
are unaffected (they select by id, order, seller, status, store order, hold or date, and neither need nor exclude a
part with a deadline).

**"Sent" is read, never copied (as 3b):** the first parcel of the store order that is not CANCELLED, by its
`shipped_at`; a store order moved to SHIPPED without a parcel record counts as sent when first seen sent; delivered
counts by `delivered_at`. A store order CANCELLED or REJECTED, or a part no longer ACCEPTED, settles VOID.

**Cost.** The seller's list stays two queries per page for every part with no deadline; a supplier's part still to be
sent adds two reads (its store order and parcels). The sweeper reads at most 100 overdue parts per pass, each in its
own transaction, optimistic-locked, so two instances cannot settle one part twice (the audit is written once).

**The wire.** `SellerOrderView` and `PartView` gain `secondsToShip` (seconds, from the server: no clock skew) and
`dispatchLate`; `SellerScore` gains `shipDue`, `shippedOnTime`, `shipOnTimeRate`. Constructor callers: 1, 1, 1.

## 2. Screens

Supplier: `marketplace-seller.js` incoming row (`.mkt-ship-by`, `.mkt-ship-late`) and its own scorecard
(`.mkt-perf-ship`). Shopper: `marketplace-public.js` `progress()` (order page, per-part list, My orders). Operator:
`marketplace-sellers.js` scorecard row (`.mkt-perf-ship`, flag `LATE_DISPATCH`). 6 keys × 6 languages.

## 3. Tests

- Unit (6): `SupplierDispatchServiceTest` (stamp only for supplier stock; the clock; ON_TIME / LATE / VOID; the sweep
  settles and audits once; on time without an audit) and `SellerPerformanceTest.dispatch`.
- Gate `mkt-4b-supplier-dispatch.cy.js`, two phases as MKT-2d: today 4b-01..03 (the clock on both sides, gone once
  sent, a shop's orders have none); then the whole stack restarted two days later (`up.sh +3d`), 4b-04..06 (late on
  both sides, the scorecard, sent late stays late). The two orders are kept in `cypress/walk/state-mkt-4b.json`
  (not `cypress/downloads`, which Cypress empties before every run).
- Walks M-4-03 (today) and M-4-04 (two days later), recorded.

## 4. Open items

- The deadline is fixed at 24 hours. A per-supplier or per-product dispatch time is a later setting if suppliers need it.
- "Chasing it" is the operator's own work from the late record and the scorecard; no case is opened automatically.
