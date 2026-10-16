# Slice MKT-4c — a slow supplier never hangs checkout; a retried step is done once

**Status:** VERIFIED 2026-10-15. **No product code.** Gate `mkt-4c-supplier-resilience.cy.js` **3/3 on a live stack**;
manual case M-4-02 recorded (it replaces the design case "A slow supplier API does not hang checkout").

Requirement: **MKT-R22.5** ("every external supplier call has timeout + circuit breaker; every webhook/event
retryable and idempotent").

## 1. Why there is nothing to build

The plan wrote 4c as "an API stock and price feed with timeouts, circuit breakers and idempotent webhooks", for a
supplier that is an outside company. Shahid's ruling of 2026-10-15 (decision card) made a supplier a **MyPlus business
the operator marks** (MKT-4a). So a supplier's stock is in its own MyPlus inventory and is held at checkout through the
**same live routing as every seller's** (MKT-2c, `LiveRouting`):

| R22.5 asks for | Where it already is | Shown by |
|---|---|---|
| a timeout on every supplier call | 800 ms per seller, 2 s per checkout across its sellers | 4c-01, walk M-4-02 step 2 |
| a circuit breaker | 3 misses in a row: not asked for 30 s, then one trial call; the operator sees it and can ask it again | 4c-02, walk M-4-02 steps 3–5 |
| a call abandoned at its timeout leaves nothing held | each hold has its own key and is released by it when it lands late | 4c-01 (the only unit sells afterwards), walk step 6 |
| retried events processed once | checkout by its idempotency key; a retried Accept returns the first sale; a parcel sent again is refused ("only 0 still to go") | 4c-03 |

There are no webhooks: no outside system calls the marketplace for a supplier. A decision card was posted in the
thread with this as the recommended option; building an outside-supplier API is the alternative and would reopen the
ruling.

### 1a. Trace (RULE 0)

**Every path that asks a supplier's stock: 2**, both through `LiveRouting`: checkout (`holdAll`, every part of the
basket) and the shortage reroute (`hold` with the checkout's deadline). Accept records the sale through `TradeClient`
with the client's own timeouts (MKT-1e); a failed sale re-holds and refuses in a sentence (unchanged).
**Steps a client may retry, for supplier stock: 4**: checkout (idempotency key), Accept (a second Accept of an
ACCEPTED part returns its view: the same invoice), Reject (a second returns the REJECTED view), a parcel (the store
order refuses quantity already sent). Each is shown live in 4c-03 or by the existing gates (1e, 2c).

## 2. Tests

- Unit: `LiveRoutingTest` (the deadline caps a seller's timeout; the breaker's open, trial and close; a late hold
  released by its key), now also tagged R22.5: the code is the same for a supplier.
- Gate `mkt-4c-supplier-resilience.cy.js` (3): a marked supplier made slow by the operator's test switch is refused
  within the deadline and its late hold given back; after 3 misses it is refused at once and listed as not answering,
  then asked again (real UI); a checkout, an Accept and a parcel sent twice take effect once.
- Walk M-4-02, 6 steps and a cleanup, recorded.

## 3. Open items

- If MaxTheService later takes suppliers that are not MyPlus businesses, their API (keys, price and stock feed,
  webhooks with their own breaker) is a new slice.
