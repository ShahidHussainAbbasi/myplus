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

### Both open items — RESOLVED 2026-08-10, against the code

**1. The MARKETPLACE vertical, server-side → `ModuleRouter.moduleOf(user)`.**

Traced end to end: `window.MODULE` ← `businessDashboard.html:2526` ← the `module` model attribute ←
`CommerceDashboardController.resolveModule()`, which reads **`user.getUserType()` only** (constrained to
{BUSINESS, PHARMA, MARKETPLACE}, defaulting BUSINESS). `ModuleRouter.moduleOf` prefers **`activeOrgType`** and
falls back to `userType`.

They agree for every single-module user and disagree for a multi-org one. **`ModuleRouter` is the right one**:
it names the tenant the invoice was actually written into, which is the tenant the order must be created in,
and it is the platform's single documented rule (the class exists precisely because that mapping had already
been written twice and drifted). `CommerceDashboardController` reading `userType` alone is a **latent
inconsistency of its own** — a user who switches into their store gets POS wording on the dashboard — but it is
a separate defect and is *not* fixed here.

Widening the gate cannot double-record: step 1's idempotency makes the browser's still-live post a no-op.
`activeOrgType` is populated at login (`AuthServerAuthenticationProvider:95`) and re-stamped on org switch
(`OrganizationController:85`), so it is genuinely available. Fixture check: `demo.marketplace@myplus.com` is
seeded `userType=MARKETPLACE`, so the gate resolves to MARKETPLACE under **either** rule — the Cypress gate
cannot tell the two apart, which is why this had to be settled by reading rather than by testing.

**2. The authoritative total → NOT in `addSell`'s response. Read the invoice back via `/getReceipt`.**

`SellController.addSell` (business-service) returns `new GenericResponse("SUCCESS", msg, invoiceNo)` — `object`
is the **invoice number and nothing else**. No total, no lines. §2.5's line *"the sale's lines and authoritative
total are already in `addSell`'s response"* was **wrong**, and that is what made this item worth confirming.

So step 3 reads the invoice back: `GET /getReceipt?invoiceNo=…` returns the persisted `CustomerHistoryDTO` —
`grandTotal` (the figure the sale posted to the ledger) plus `sales[]` with `productId`, `quantity`, `sellRate`
and `itemName`. It is already proxied by the monolith, org/store/role-scoped from the caller's own token, and
it closes gap **B** and gap **A** from **one** source: what was written, not what was asked for.

Rejected alternatives: recomputing the total in the monolith from `dto` (that is the client's arithmetic moved
one hop, and it cannot see tax or discount — SF-12 is the standing proof that cart arithmetic can be wrong in
ways the books are not); and widening `addSell`'s response (its `object` is consumed as the invoice number by
`main.js`, `printReceipt` and `dispensePrescription`, so changing it is a breaking change for three callers to
save one read).

**The cost, stated:** a Store sale now makes two extra hops (`/getReceipt`, then `/orders`) before the cashier
sees the sale complete. Store-vertical only; every other vertical is byte-for-byte unchanged. Kept synchronous
because the gate must be able to assert the order exists the moment the sale returns; moving it to an
`@Async` hand-off is the obvious follow-up if the latency is felt, and does **not** change the crash exposure
option C already accepted.

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
- [x] step 3 — **BUILT 2026-08-10, awaiting deploy + gate.** Both §2.5 open items resolved against the code
      first (see *Both open items — RESOLVED* above); the second one found the design's own claim about
      `addSell`'s response to be false, which is why the shape below differs from what §2.5 sketched.
      - `PosOrderRecorder` (`src/main/java/com/web/util/PosOrderRecorder.java`) — **new**. Gate
        (`ModuleRouter.moduleOf == MARKETPLACE`), authoritative read (`/getReceipt`), mapping, and the
        best-effort POST to `/orders`. Every path ends in a WARN, never a throw: the money is already written.
        The mapping is pure and static, so `PosOrderRecordTest` pins it with no Spring.
      - `SellController.addSell` (monolith) — one line: `posOrderRecorder.afterSale(response)` after the proxy
        returns. The proxy itself is unchanged.
      - `OrderService.record()` — now stamps `booksStatus = POSTED` when the order names an invoice. It was
        left null, which made the one order source that definitely *has* revenue behind it the only one that
        would not say so, and made the `REVERSED` stamp a cancel writes a transition out of nothing. (Null
        never matched the `LEGACY_UNPOSTED` reconciliation read, so no backlog was polluted — but the field
        was simply silent where it should have spoken.)
      - **Known limitation, logged at WARN:** `Sell.quantity` is a `Float` (1.5 kg is a real POS sale) and
        `OrderItem.quantity` is an `Integer`, so a fractional line is rounded — and a cancel would then restore
        the rounded quantity. Widening the order line is a marketplace schema change, outside O5e.
- [x] `record()` takes lines + server total; allocates `orderSeq`/`orderNo`
- [x] the monolith calls it after a Store-vertical sale (option C — *not* business-service; see §2.5)
- [x] step 4 — **browser writer REMOVED 2026-08-10**, immediately after the step 3 gate went green, which is
      the order §2.3 mandates. `global.recordOrder` deleted from `ecommerce.js`; the call site deleted from
      `main.js`'s post-sale hook. Both replaced by a comment naming the three gaps that lived in that one
      function, so it cannot be reintroduced as a convenience.
      **The migration window is now closed — there is exactly ONE writer.** Step 1's idempotency stays load-
      bearing regardless: it is what makes a retried or replayed sale converge on one order.
      **KEPT on purpose:** the monolith's `/recordOrder` proxy (`OrderController:132`). It is no longer called
      by any browser code, but it is the route `pos-order-parity.cy.js` step 1 and `ecommerce-orders.cy.js` use
      to exercise `record()` directly. Deleting it would delete the idempotency gate with it.
      **Minor UI loss, accepted:** the "Order <invoice> created" toast is gone — the browser no longer knows an
      order was created, which is the entire point. The "Sale recorded — Invoice …" toast is unaffected.
      Leaves `ui.js.order2` orphaned in all six `messages*.properties`; left in place rather than deleted from
      six aligned files for one unused key.
- [x] `PosOrderRecordTest` — `src/test/java/com/web/util/PosOrderRecordTest.java`, pure logic, runs on
      `mvn test`. Pins the server total (incl. that it is NOT the sum of the lines — tax lives on the header),
      the lines, the sold rate vs the catalog price, dropped product-less lines, and that a FAILED / ERROR /
      CONFIRM envelope yields no order.
- [x] `pos-order-parity.cy.js` + regression — **GREEN 2026-08-10.** Steps 2+3 un-skipped: 6 cases including
      *cancelling a POS order restores stock*, which was impossible before this slice and is the case that
      proves OMS-5 closed.
      ⚠️ **Re-run after step 4** — the browser writer was removed *after* this run, so the green above was
      recorded with both writers live. The specs drive `/addSell` by `cy.request` and never invoked the browser
      hook, so nothing is expected to move; run `pos-order-parity` + `sell` + `ecommerce-orders` once more to
      confirm the single-writer path, since "expected not to move" is not the same as observed.

**OMS-5 is CLOSED.** All eight original OMS defects are now closed.
