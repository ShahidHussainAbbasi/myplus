# Slice MKT-2c — live routing: a seller is asked for its stock with a deadline

**Status:** BUILT 2026-10-08. Unit-green (marketplace-service 385 tests, 0 failures; 11 new). No migration. Gate
`mkt-2c-live-routing.cy.js` **6/6 on a live stack**. Manual cases M-2c-01..04 replace M-2-05.

Requirements: **MKT-R18.1** (a shortlist from the projection, then a live hold on a few sellers only), **MKT-R18.3**
(a timeout per seller, one deadline per checkout, a circuit breaker per seller), **MKT-R18.5** (on a deadline, never a
silent confirm: the shopper is told and given the way forward). Depends on MKT-1e (the hold at checkout), MKT-2a (one
part per seller) and MKT-2b (the reroute's candidate holds). Out of scope: value-based acceptance terms (MKT-2-06, R10.6),
which stay in `mkt-2-multiseller-oms.cy.js`.

## 1. Document

At checkout MaxTheService asks each seller in the order to set the stock aside. Until now it waited as long as that
took, one seller after another. Now:

| | Before | After |
|---|---|---|
| One seller | waited until the seller's system answered | **800 ms**, then the shopper is told "This seller did not answer in time. Please choose another offer." |
| A basket from several sellers | asked one after another, the waits added up | all asked **at once**, the whole checkout within **2 s** |
| A seller that keeps failing | asked again on every checkout, every shopper waiting | after **3** calls in a row it did not answer, not asked for **30 s**: refused at once |
| An answer that comes after the shopper was told no | (could not happen) | whatever it set aside is **given back** at once |

"Not enough stock" is still an answer: it never counts against the seller, and the shopper hears "no longer has enough
stock" as before. A system error is now told as "did not answer in time" (before: "no longer has enough stock"), which is
what happened.

The operator sees this under **Platform → Marketplace policies → "Asking sellers for stock at checkout"**: the limits in
force, and each seller that is not being asked, until when, with **"Ask it again now"** for a seller that says it is
fixed. A test system started with `MKT_ROUTING_TEST_SWITCH=true` also shows a test switch that makes one seller slow, so
the deadline can be seen working. Production never starts with it; with it off, the stored value is ignored and the
POST is refused.

### 1a. Trace (RULE 0)

**Callers of the remote hold (`TradeClient.holdStock`) in marketplace-service: 4.**

| Caller | Routed? | Why |
|---|---|---|
| `MarketplaceCheckoutService.checkout` (placement) | **yes**, every part at once, one deadline | the shopper is waiting |
| `MarketplaceShortageService.resolve` → `hold(candidate)` (MKT-2b) | **yes**, one deadline across the ≤ 3 candidates | a seller's reject request is waiting; a slow candidate is passed over like one without stock |
| `SellerOrderService.accept` → `checkout.hold` (re-hold after a refused sale) | no | not routing: the part is already the seller's, and the flag must say what is true, so it waits for the answer |
| `OrderStockHoldService.hold` (the one-shop storefront, not the marketplace) | no | outside the multi-seller marketplace |

So 2 routed, 2 unchanged.

**Releases (`TradeClient.releaseHold`): 4 call sites before, 5 now.** The new one is `LiveRouting`'s late answer: when a call
it stopped waiting for finishes with anything but a refusal, it releases that attempt's key. The 4 existing ones are
unchanged. The order's own release cannot cover this case: inventory **re-arms** a RELEASED key on a later reserve
(`ReservationService` line 113), so a hold that lands after the release would otherwise stay held for days (an ORDER
hold). Every routed hold uses a key that belongs only to that attempt (a part's UUID key, or a candidate's), so the late
release can never free a hold someone else relies on.

**Readers and writers.** No new table and no new column. One new platform setting key (`routing.testSlowSeller`), read
only when the test switch property is on. The circuits are in memory, per instance (with two instances, each learns
on its own: a seller is tried at most 3 times per instance before it is skipped).

**The wire.** The checkout response is unchanged; only a refusal's sentence is new. New operator DTOs: `RoutingView`,
`RoutingCircuit`, `RoutingSeller`, `RoutingTest`, `RoutingClose`.

**Tests that relied on the order of the calls: 2.** `splitsPerSeller` asserted the holds arrived A then B, and
`oneRefusalCancelsAll` answered by call order. Both now answer and assert by seller, since the sellers are asked at
once.

## 2. Design

`LiveRouting` (new) runs each hold on a bounded pool (64 threads, a direct hand-off: when every thread is busy the
seller is "not answering", never queued). Each answer waits at most `min(started + 800 ms, deadline)`.

| Answer | Shopper hears | Counts against the seller |
|---|---|---|
| HELD | the order goes ahead | resets it |
| REFUSED (not enough stock) | "no longer has enough stock" | no (an answer) |
| TIMED_OUT | "did not answer in time" | yes |
| NOT_ANSWERING (error, no answer, circuit open, pool full) | "did not answer in time" | yes, except an open circuit or a full pool |

**The circuit:** closed → after 3 failures in a row, open for 30 s (refused without a call) → one trial call → closed on
an answer, open again on a failure. The operator can close it.

**Limits** (`mkt.routing.*`, defaults from source §18.3, starting points for a load test): `hold-timeout-ms` 800,
`deadline-ms` 2000, `breaker-failures` 3, `breaker-open-seconds` 30, `test-switch` false.

## 3. Screens

| Who | Where | What |
|---|---|---|
| Customer | checkout, basket | "This seller did not answer in time. Please choose another offer." · "{seller} did not answer in time. Please remove its items and place the order again." |
| Operator | Platform → Marketplace policies | the limits; "{seller} is not answering: not asked until hh:mm:ss." with "Ask it again now"; on a test system, the test switch |

## 4. Endpoints

| Method | Path | Who |
|---|---|---|
| GET | `/platform/mkt/routing` | operator: limits, sellers not answering, the test switch |
| POST | `/platform/mkt/routingClose` | operator: `{sellerOrganizationId}` |
| POST | `/platform/mkt/routingTest` | operator, test systems only: `{sellerOrganizationId, delayMs}` (0 = off) |

## 5. Tests

- **Unit:** `LiveRoutingTest` (6): the deadline caps the timeout, a refusal is an answer, the half-open trial, a late
  refusal not released, a late hold released, the test switch. `MarketplaceOrderFlowTest` (5): slowSellerRefusedInTime,
  sellersAskedAtOnce, slowSellerInBasketNamed, circuitOpens, slowCandidatePassedOver.
- **Gate:** `mkt-2c-live-routing.cy.js`, cases 2c-01..06. The placeholder MKT-2-05 moved here.
- **Manual:** M-2c-01..04 in the walk, step by step, each with its cleanup.

## 6. Open items

- The circuits are per instance (see 1a). A shared store is only worth it with many instances.
- The checkout is all or nothing (MKT-2a): a slow seller in a basket refuses the basket. Offering the shopper the rest
  of the basket at once, or another seller of the same product, is not designed.
- The limits are the source's starting points; a load test should set them.
