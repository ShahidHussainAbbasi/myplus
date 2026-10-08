# Slice MKT-2e — merchant performance: how each seller handles its orders

**Status:** BUILT 2026-10-08. Unit-green (marketplace-service 409 tests, 0 failures, 13 new). Migration V33 (one index),
applied on the live database. Gate `mkt-2e-merchant-performance.cy.js` **8/8 on a live stack**. Manual cases M-2e-01..03
recorded.

Requirement: **MKT-R20.3** ("merchant performance"), with source §18 ("acceptance history" in the ranking), R11.4 (a cause
names a party) and R12.4 (no sanction without evidence and a dispute process). Depends on MKT-1e (seller orders and the
acceptance window), MKT-1f (returns and who bears them), MKT-2b (shortages, disputes, rulings), MKT-2d (late for cash
orders). Out of scope: customer ratings (no rating is collected yet; the ranking's rating key stays empty), automatic
sanctions of any kind, and a stored history of past scorecards.

## 1. Document

Before this slice nobody could see how a seller handled its orders: the operator read orders one by one, a seller had no
figure about itself, and the catalogue's "acceptance history" tie-break (MKT-1a `OfferRanker`) was 0 for every seller,
so it never decided anything.

| | Before | After |
|---|---|---|
| The operator | — | Platform → **Seller performance**: per seller, for orders placed in the last 7, 30 or 90 days (30 by default): orders accepted, average time to accept, delivered on time, not fulfilled by the seller (with "under dispute" and "not the seller's fault" below it), returns the seller caused, and **Needs attention**: "Accepts fewer than 80% of its orders", "Delivers fewer than 90% on time", "Late paying for cash orders". Sellers that need attention first |
| The seller | — | Sale → Marketplace → **Your performance**: the same four figures for the last 30 days, and "MaxTheService has noted: …" for each flag |
| The catalogue | two equal offers were ordered by offer id | of two offers equal in delivery time, price, promotion, rating and distance, the seller that accepts more of its orders is listed first. It stays the last tie-break: one rupee cheaper still wins |
| Reroute (MKT-2b) | candidates of equal standing in offer-id order | the same tie-break: the seller that accepts more is tried first |

**What counts.** Every seller order placed in the window:

| Status | Counted as |
|---|---|
| ACCEPTED, HANDED_OVER, FAILED | accepted (FAILED was accepted before it failed; no path reaches it today) |
| REJECTED, EXPIRED, and the shortage names the MERCHANT and stands (RECORDED, UPHELD) | missed |
| REJECTED, EXPIRED with no shortage record (before MKT-2b) | missed |
| REJECTED, EXPIRED caused by someone else (supplier, platform, custodian, carrier, customer) or OVERTURNED | not the seller's fault: not counted |
| REJECTED, EXPIRED and DISPUTED | under dispute: not counted until decided (R12.4), shown on its own |
| OFFERED, UNASSIGNED | not decided yet: not counted |
| CANCELLED | ended before the seller answered (the customer cancelled, or the payment failed): not counted |

**Acceptance rate** = accepted ÷ (accepted + missed). **On time**: an accepted part is due once its promise has passed or
it was delivered; it is on time when delivered by acceptance + the longest promise of its lines, the same "expected by"
the customer is shown (`MarketplaceCheckoutService.parts`); a part past its promise and not delivered is late. **Time to
accept**: decided − placed, averaged ("Under a minute on average" below one minute). **Returns the seller caused**: returns
on those orders whose cost the seller itself bears (R13.1: the bearer organisation is the seller), not refused.

A rate needs **5 orders** behind it, else "Not enough orders yet" and no flag. The thresholds (80%, 90%) are fixed in
`SellerPerformance` and shown in the panel's hint; they are not settings, because a flag does nothing but inform.

**Ranking.** A seller with fewer than 5 decided orders, or none, ranks as 1.0: a new seller is not pushed down for having
no history.

### 1a. Trace (RULE 0)

**Readers of `mkt_seller_order`.** The scorecard adds 2 queries (`performanceFacts`, `performanceFactsOf`), both read-only,
both over `created_at` (V33 index `idx_mkt_so_created (created_at, seller_organization_id)`; before it there was no index
on `created_at`). No existing reader changes.

**Writers.** None. The slice writes nothing: no table, no column, no ledger row, no audit event. The scorecard is computed
on demand, so nothing can recompute it away and it can never disagree with the orders.

**Readers of `mkt_return`.** 1 new (`sellerFaultReturns`), grouped per seller. `mkt_shortage`: joined by `seller_order_id`
(unique, `uk_mkt_shortage_part`), so a part joins at most one record and is never counted twice.

**Callers of `PublicOfferService.eligible`: 4, each classified.**

| Caller | Effect of the acceptance tie-break |
|---|---|
| `offers` (the product page) | **wanted**: order of equal offers |
| `search` (the cards) | none: a card shows a count, the lowest price and the fastest promise, not an order |
| `MarketplaceCheckoutService` (is this offer still eligible?) | none: it only asks `isEmpty()` |
| `MarketplaceShortageService` (reroute candidates) | **wanted**: equal candidates tried in the new order |

`PublicOfferService.candidate`: 1 caller (`eligible`). It now takes the rate; before, it passed `0d`.

**The rate's source.** `SellerPerformanceService.acceptanceRate(org)` reads an in-memory map (30-day rates), refreshed by
`@Scheduled` every 10 minutes (`mkt.performance.refresh-ms`, first after 1 minute) and whenever the operator opens the
30-day scorecard. A catalogue request reads no orders. A failed refresh keeps the last map (logged), so a database blip
cannot reorder the catalogue. Every service instance keeps its own map; they agree within 10 minutes.

**The wire.** New `PerformanceDTOs.SellerScore`, `PerformanceView`. The monolith relays the JSON untouched
(`Map<String,Object>`), so no twin DTO. `days` is passed through; anything but 7, 30, 90 is refused by the service:
"Choose 7, 30 or 90 days."

**Column types.** No column added. V33 is an index, guarded by `information_schema` (idempotent, as V30).

**Cost.** One query per scorecard: one row per part placed in the window (90 days on today's stack: under 1,000 rows),
with a correlated `max(promise_hours)` per part over `idx_mkt_line_seller_order`. When volume makes this slow, a daily
per-seller summary table is the next step; it is not built because it would be a second copy that can disagree.

**Defect found on screen, not by any test:** the first operator table put "Needs attention" last, off the right edge of a
1366-pixel screen, so every flag was invisible while the gate passed. The column is now second, and headers wrap.

## 2. Design

`SellerPerformance` (domain, pure): `Part` in, `Score` out; `flags(codOverdue)`. `SellerPerformanceService`: `operator(days)`
(operator only), `mine(days)` (the caller's own organisation only), `acceptanceRate(org)`, `refreshRanking()`. Late for
cash orders comes from `CodStandingService` (MKT-2d), asked only for sellers whose balance is negative.

## 3. Screens

| Who | Where | What |
|---|---|---|
| Operator | Platform → **Seller performance** | 7 / 30 / 90 days; per seller: Needs attention, Orders accepted, Time to accept, Delivered on time, Not fulfilled by the seller, Returns the seller caused |
| Seller | Sale → Marketplace → **Your performance** (under the settlement statement) | Orders accepted, Time to accept, Delivered on time, Returns you caused; "MaxTheService has noted: …"; disputed orders explained |
| Customer | the product page | equal offers: the seller that accepts more first |

## 4. Endpoints

| Method | Path (monolith → service) | Who |
|---|---|---|
| GET | `/platform/mkt/sellerPerformance?days=` → `/mkt/operator/performance` | operator |
| GET | `/mkt/myPerformance?days=` → `/mkt/seller/performance` | the seller, its own |

## 5. Tests

- **Unit** `SellerPerformanceTest` (6): acceptanceRate, excusedAndDisputed, undecidedNotCounted, tooFewOrders, onTime,
  thresholds. `SellerPerformanceServiceTest` (6): operatorScorecard, codFlag, windows, access, rankingRates,
  scorecardRefreshesRanking. `PublicOfferServiceTest.acceptanceBreaksTies` (1).
- **Gate** `mkt-2e-merchant-performance.cy.js` (8), `--env '{"mkt":"2e"}'`. Every figure is a difference from the figures
  read just before the action (the sellers carry every earlier run's history); before the first case both sellers'
  waiting orders are answered, so the sweeper cannot expire one mid-case. 2e-08 publishes the better seller's offer
  second, with a positive control that the offer id alone would list it last.
- **Manual** M-2e-01..03.

## 6. Open items

- The stack's two shops show low rates (16% and 40%): every gate and walk ends orders by rejecting them ("gate cleanup",
  "walk cleanup"), which counts as the seller's miss unless a cause is named. The 2e gate names a platform cause for its
  own cleanup; the older ones do not. Test data, not a product defect.
- Customer ratings are not collected, so the ranking's rating key and a "rating" column do not exist yet.
- A flag never stops or charges anything. Any consequence (lower placement beyond the tie-break, suspension) would be a
  new decision with its own dispute process (R12.4).
