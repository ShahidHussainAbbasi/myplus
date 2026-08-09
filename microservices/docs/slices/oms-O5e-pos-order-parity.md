# OMS O5e — POS orders: close OMS-5

*Design gate — no code until this is approved.*

Parent: [order-management-design.md](../order-management-design.md) · Programme:
[oms-program-plan.md](../oms-program-plan.md) · Predecessors: O1–O5d.

---

## 1. Verified state of `OrderService.record()` (2026-08-09)

OMS-5 is the last of the eight original defects still open. Reading the method (`OrderService.java:60-73`) it is
**wider than the register describes** — the storefront path has been hardened five times since slice 46 and the
POS path has received none of it:

```java
Order o = Order.builder()
        .organizationId(orgId).userId(userId)
        .invoiceNo(dto.getInvoiceNo())
        .customerName(dto.getCustomerName())
        .total(dto.getTotal())              // CLIENT-COMPUTED
        .source("POS")
        .fulfilmentStatus(FulfilmentStatus.NEW)
        .build();                            // no items, no orderNo, no idempotencyKey
```

| # | Gap | Consequence | Fixed for the storefront by |
|---|---|---|---|
| **A** | **No line items.** `.items(...)` is never set. | Cancel and return are guarded by `!o.getItems().isEmpty()`, so **a POS order can never restore stock**. O5b's Ship action has nothing to dispatch; O5d has nothing to pick. | — |
| **B** | **Client-computed total.** `dto.getTotal()` is whatever the browser posted. | The same defect OMS-5 named and O1 removed from the storefront. | O1 |
| **C** | **No `orderNo`.** No `orderSeq`, no `SO-` number. | A POS order cannot be tracked, quoted to a customer, or found by number in the O4 list. | O2 |
| **D** | **No idempotency key.** | A retried post inserts a second order for one sale. | O2 (OMS-3) |
| **E** | **Fire-and-forget from the browser.** `ecommerce.js` posts after `addSell` succeeds. | Close the tab, lose the network — the sale exists, the order silently never does. | — |

**A and E are the same underlying mistake**: the order is assembled by the client *after* the fact, from data
the client happens to still hold, instead of by the server from the sale it just wrote.

---

## 2. Design — stop having the browser report the sale

### 2.1 The order is created by the SALE, not by the browser

business-service already knows everything the order needs at the moment it writes the invoice: the lines, the
authoritative total, the customer, the invoice number. The browser adds nothing except a chance to fail.

```mermaid
sequenceDiagram
    autonumber
    participant U as Cashier
    participant B as business-service (SagaSellService)
    participant M as marketplace /orders

    U->>B: addSell
    B->>B: invoice + GL + stock (unchanged)
    alt store vertical
        B->>M: record order — server-side, from the sale
        Note over B,M: lines, authoritative total,<br/>invoiceNo as idempotency key
    end
    B-->>U: sale complete
    Note over U: the browser posts NOTHING;<br/>closing the tab loses no order
```

**This is O1 in reverse.** O1 made the storefront's order produce a sale; O5e makes the POS sale produce an
order. Both end at the same rule: **one server-side path owns the pair, and the client reports neither.**

`invoiceNo` is the natural idempotency key — one invoice is one order, and it is already unique per org.

### 2.2 What `record()` becomes

Kept as the endpoint, but it takes the sale's own data: lines with product ids and prices, the server total, and
the invoice number as the key. It gains `orderSeq`/`orderNo` (the same `SO-` allocation `placePublic` uses) so a
POS order is a first-class order, and the idempotency check `placePublic` already has.

### 2.3 The migration question — the honest part

`record()` is called today by `ecommerce.js` after every Store-vertical sale. Moving the call server-side means
**two writers exist during the transition**, so the idempotency key has to land *first* — otherwise the browser
and the service both create one, and the fix produces duplicates.

Order of work matters here and is not negotiable:
1. idempotency on `invoiceNo` (safe on its own — makes a double-post a no-op),
2. server-side call from business-service,
3. only then remove the browser call.

### 2.4 Historical POS orders

Rows already written have no items and cannot get them — the sale's lines exist in business-service, but
back-filling them would mean reconstructing an order from an invoice weeks later. They stay as they are, and
`booksStatus`/`items.isEmpty()` already make them identifiable. **No backfill**, same decision O1 took for
`LEGACY_UNPOSTED`.

---

## 2.5 ⚠️ STEP 3 NEEDS A DECISION — §2.1 would invert the service dependency

**Verified 2026-08-09:** business-service has **no marketplace client**. The only mention of marketplace in its
source is `InternalSalesController` — which exists because marketplace calls *in*.

The dependency today runs **one way**: marketplace → business-service (`TradeClient` → `/internal/sales`). That
is what O1 established, and it is the right direction: the order domain depends on the money domain, not the
reverse.

§2.1 as drawn has business-service call marketplace after a sale. That would make the pair **mutually
dependent** — each service holding a client for the other. That is a real architectural cost, not a detail:
it couples deploys, invites a cycle at startup, and contradicts the standing rule that a cross-cutting
capability gets its own contract in one direction (DIP).

### The options

| | | |
|---|---|---|
| **A — business-service calls marketplace** (as drawn in §2.1) | Fewest moving parts. | **Creates the circular dependency.** Rejected unless B and C are both unworkable. |
| **B — outbox event** | business-service writes an outbox row; marketplace consumes it. Correct direction, durable, and the pattern the platform already uses for GL. | **No event broker exists** — that is Track C. Would mean building the transport here, which is a much larger slice than O5e. |
| **C — the MONOLITH orchestrates** (recommended) | The monolith already calls business-service for the sale *and* already proxies marketplace (`/recordOrder`). Moving the call from the browser into `SellController`, server-side, right after `addSell` succeeds. | Closes the "close the tab, lose the order" gap — which is the actual defect — **without inverting anything**. No new client, no new transport. The order is created by the server, just not by the service that writes the invoice. |

**C is the recommendation.** It achieves what §2 set out to do — *the browser reports neither the sale nor the
order* — at a fraction of the cost, and leaves the clean one-way service dependency O1 established intact. The
sale's lines and authoritative total are already in `addSell`'s response, so the monolith has everything the
order needs.

The trade to state plainly: with C, the order is created by the orchestrator rather than atomically with the
sale, so a monolith crash between the two still loses an order. That is strictly better than today (a *browser*
crash loses it) and is exactly what B would fix later, once a broker exists. **Do not let the perfect version
block the fix.**

**This decision must be made before step 3 is written.** Steps 1 and 2 are unaffected either way — they are
already deployed and green.

### DECIDED 2026-08-09 — option C, the monolith orchestrates

The one-way service dependency O1 established stays intact; no new client, no new transport.

**Implementation spec (execute in this order):**

1. **`SellController.addSell` (monolith)** — after the sale succeeds and the invoice number is known, call the
   existing marketplace proxy path server-side. Guard it to the **Store/ECOMMERCE vertical only**, which is the
   same condition `ecommerce.js` uses today to decide whether to post at all.
2. **Send what the browser could not:** the sale's line items (productId, quantity, price) and the
   **authoritative total from the sale response** — never the request body's total. That closes gap **B**
   (client-computed total) and gap **A** (no line items) together.
3. **Best-effort, never fatal.** A failed order-record must NOT fail the sale: the money is already written and
   correct. Log at WARN and carry on — step 1's idempotency means a later retry converges on one order.
4. **Only then** delete `recordOrder()` from `ecommerce.js` and its call site in `main.js`'s post-sale hook.
   Between 1 and 4 both writers are live, which is safe *because* step 1 shipped first — that is the whole
   reason for §2.3's ordering.

**Gate:** un-skip the step 2 and step 3 blocks in `pos-order-parity.cy.js`. The case that proves OMS-5 closed is
*"cancelling a POS order restores stock"* — impossible today, and the reason the lines matter.

**Watch for:** the monolith already proxies `/recordOrder`; do not add a second path to marketplace. Reuse it.

### Injection point — VERIFIED 2026-08-09, so the next session need not re-derive it

| | |
|---|---|
| **Where** | `SellController.addSell` — `src/main/java/com/web/controller/business/SellController.java:195-204`. It is a thin proxy: `return client.postJson("/addSell", dto);` at **line 199**. The order-record call goes after that returns successfully. |
| **What the browser does today** | `main.js:1013` → `if (method === 'addSell' && (window.MODULE||'').toUpperCase() === 'MARKETPLACE' && typeof recordOrder === 'function') recordOrder(data.object);` — so **`data.object` is the invoice number**, and the vertical gate is `MODULE === 'MARKETPLACE'`. |
| **The client-computed total to replace** | `ecommerce.js:524-532` sums `global.data` (the cart) in the browser. Gap **B**. Use the sale response / `dto` lines instead. |
| **Lines are already in hand** | `addSell` receives `CustomerHistoryDTO dto` — the same object carrying the sale's lines. No extra call is needed to get them. |

**Still to confirm before writing the edit** (the two things that stopped this session):
1. **How to determine the MARKETPLACE vertical server-side.** The browser uses `window.MODULE`; the monolith
   equivalent is likely the active org type (`ModuleRouter` / the JWT's `activeOrgType`) — confirm which, do not
   guess. Getting this wrong either records orders for every trade sale or for none.
2. **The authoritative total.** Prefer the value business-service returns over anything recomputed. Check what
   `postJson("/addSell", …)` actually returns alongside `object` before choosing.

## 3. Not in O5e

Changing what a POS sale *is* (it stays an instant invoice — sales orders with advance/layaway are **O7**). No
change to the storefront path, which O1 already fixed.

---

## 4. Test

**Java:** `PosOrderRecordTest` — the order's total comes from the SALE not the request; lines are persisted;
a second call with the same `invoiceNo` returns the first order rather than creating a second.

**Cypress — `pos-order-parity.cy.js`:** complete a Store-vertical sale → an order exists with an `SO-` number,
the sale's lines, and the server's total; cancelling it **restores stock** (impossible today); posting the same
invoice twice yields one order; the order can be shipped through O5b's action.

**Regression:** `sell`, `ecommerce-orders`, `order-cancel`, `order-back-office`, `order-fulfilment`.

---

## 5. Exit criteria

A POS sale produces its order server-side, with lines, a server total, an `SO-` number and idempotency; that
order can be cancelled with stock restored, shipped, and returned; closing the browser loses nothing; gate
green; no regression.

---

## 6. Checklist

- [x] **Review this design — §2.3's ordering is the risky part** — **APPROVED 2026-08-09**
- [x] Idempotency on `invoiceNo` in `record()` — **DONE, DEPLOYED, GATED 2026-08-09.**
      `pos-order-parity.cy.js` **3/3** (same invoice twice ⇒ one order; two invoices ⇒ two orders, so the guard
      does not over-collapse). Regression **33/33** across `order-fulfilment`, `ecommerce-orders`,
      `order-cancel`, `order-back-office`. Marketplace unit suite **121 / 0 failures**. V17 (O5d) applied in the
      same deploy with no effect on shipments.
      *Shipped first and alone by design: it is what makes step 3 safe, because during the migration both the
      browser and business-service record the order and without this key that is two writers, two orders.*
- [~] step 2 — **HALF DONE, deployed 2026-08-09, existing gate still green.**
      IN (both additive, so the browser caller is untouched): `orderSeq`/`orderNo` allocated unconditionally —
      a POS order is no longer the one kind a merchant cannot quote or track — and `items` persisted when the
      caller supplies them.
      **NOT IN: the server total.** It cannot be additive: it requires the CALLER to be business-service, which
      is step 3. Taking it now would change the browser contract mid-flight, which is what §2.3 exists to stop.
      ⚠️ **No gate case asserts the `SO-` number yet** — the step 2 block in `pos-order-parity.cy.js` is still
      `describe.skip`. Un-skip and assert it with step 3.
- [ ] **START HERE →** step 3: business-service records the order after a Store-vertical sale, sending the
      sale's lines and its authoritative total. **This is what actually closes OMS-5** — until the lines arrive,
      cancel/return still cannot restore stock (`!items.isEmpty()`).
- [ ] `record()` takes lines + server total; allocates `orderSeq`/`orderNo`
- [ ] business-service calls it after a Store-vertical sale
- [ ] Remove the browser's `recordOrder()` call — only after the above are green
- [ ] `PosOrderRecordTest`
- [ ] `pos-order-parity.cy.js` + regression
