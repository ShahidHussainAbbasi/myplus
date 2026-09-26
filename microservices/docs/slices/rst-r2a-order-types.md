# RST-R2a · Order type, end to end

**Status:** DESIGN — awaiting consent. No code written.
**Parent:** `microservices/docs/restaurant-vertical-design.md` §4 Phase R2, gap **G1**.
**Prerequisite, already green:** `MADE_TO_ORDER` + the tile counter (`restaurant-counter.cy.js` 7/7, commit `7ddbdf77`),
capability guard on the flag (`f9f23f0e`).

---

## 0 · Why this is one slice and not "Phase R2"

Phase R2 as written is four capabilities — `ORDER_TYPES`, `KITCHEN_TICKETS`, `MENU_MODIFIERS`, and tables folded
into the first. Building them together would be one long branch touching the sale path, a new screen, a new
entity and the receipt, with nothing demonstrable until the end.

**Order type alone is independently useful.** A counter that records dine-in / take-away / delivery and splits
the day's takings by it answers a question the owner has today, with no kitchen screen and no tables. It is
also the foundation the other three need: a kitchen ticket header prints the type, a table only exists for
dine-in, and delivery is what makes the delivery charge legitimate.

So: **R2a = the type reaches the invoice, the receipt and the report. Nothing else.**

---

## 1 · Review — what exists, verified in code

| Question | Answer | Where |
|---|---|---|
| Does the POS invoice carry a service mode? | **No.** `CustomerHistory` has no channel, no fulfilment, no type — only `shiftId` | `CustomerHistory.java:185` |
| Is there an existing `channel`? | Yes, but it is the **B2B/B2C commercial axis**, derived from `CustomerType` — not a service mode | `CustomerType.java:43-49`, `SellDTO:98` |
| Is there a fulfilment lifecycle to reuse? | Yes — but it lives in **marketplace-service on `Order`**, not on the POS invoice | `FulfilmentStatus.java`, `Order.java` |
| Does any sale report group by a type today? | No. Grouping is by year/month, day, and product | `SellRepo:239,247,262` |
| Are receipt templates selectable per channel? | Yes — `DocumentTemplate.channel` | `DocumentTemplate.java:55` |

### 1.1 Three things NOT to reuse, and why

**Not `Channel`.** It answers "what kind of customer is this" (retail / wholesale), and it is *derived* from
`CustomerType`. Service mode is orthogonal: a wholesale customer can take away, a walk-in can dine in.
Overloading it would make one field answer two questions, which is the mistake `Shape` vs `Capability` exists
to prevent on the other axis.

**Not `FulfilmentStatus`.** Its states are `PENDING_APPROVAL → NEW → PACKED → SHIPPED → DELIVERED`, designed for
goods leaving a warehouse on a van, and it lives in another service on another entity. A kitchen's states are
`NEW → PREPARING → READY → SERVED`. Borrowing the enum would put "PACKED" on a burger.

**Not a second payment field.** Kitchen state and money state advance independently — see §3.

### 1.2 What IS reused

`FulfilmentStatus`'s **shape**: an `ALLOWED` whitelist of legal moves, refusing everything else. Its javadoc
records why — before OMS O2 an order could go `CANCELLED → SHIPPED`, dispatching goods whose money and stock
had been reversed. *"The safe default must be no."* The kitchen lifecycle in R2b copies that pattern rather
than the enum.

---

## 2 · The model

One column on the invoice. Not a table, not a second entity.

```java
// CustomerHistory
@Enumerated(EnumType.STRING)
@Column(name = "order_type", length = 16)   // NULL = not a food counter; every existing row
private OrderType orderType;                // and every non-restaurant tenant stays NULL
```

```java
public enum OrderType {
    DINE_IN,     // consumed on the premises; R2b attaches a table
    TAKE_AWAY,   // packed and handed over; the default for a counter
    DELIVERY     // leaves with a rider; the only type a delivery charge is legitimate on
}
```

**`@Enumerated(STRING)` and a VARCHAR column, never a MySQL `ENUM`.** A String field against a MySQL `ENUM`
column is one of the two shapes that crash-looped two services under `ddl-auto=validate` (59 and 9 restarts).
Migration is `VARCHAR(16) NULL`, idempotent and `information_schema`-guarded like every other.

**NULL is a real answer**, not a missing one: it means "this tenant does not work in service modes". Every
existing invoice in every tenant is NULL after the migration, and every screen must render that as *absent*,
never as `DINE_IN` by default. A default would retro-label a year of retail sales as dine-in.

---

## 3 · ⚠ Two clocks, never one

Kitchen state and money state advance **independently**, and this slice must not accidentally couple them.

A take-away is normally paid before it is cooked. A dine-in table is cooked, eaten, and paid an hour later.
Collapsing them into one status makes a paid order look unmade, and an unmade order look owed for.

R2a introduces no kitchen state at all — but it must not build anything that would need unpicking when R2b
does. Concretely: **`orderType` is a property of the sale, not a status**, it never changes as a consequence of
payment, and nothing in this slice may read it to decide whether money is owed.

---

## 4 · Scope

### In
1. `OrderType` enum + `order_type` column on `CustomerHistory` (business-service, Flyway).
2. Carried on `CustomerHistoryDTO`, written by `SagaSaleWriter` from the sale payload.
3. Chosen on the counter screen — three buttons above the order, take-away pre-selected.
4. Printed on the receipt.
5. The day's report splits totals by type, and the parts sum to the day's take.
6. Capability `ORDER_TYPES`, off by default, so no existing tenant sees any of it.

### Out — named so the boundary is deliberate
- Tables and open tabs (R2b) · kitchen tickets and stations (R2b) · modifiers (R2c)
- Delivery charge as its own ledger line — **depends on this slice** but is its own money question, and money
  changes get their own gate. Flagged in `restaurant-vertical-design.md` §7.
- Rider assignment (R4, and OMS O7 already has most of it)

---

## 5 · The gate — `restaurant-order-types.cy.js`

Each case must fail before its feature exists. The red messages to expect are written down now, because a
red run that is merely *counted* rather than *read* is how five invalid failures shipped in one feature here.

| # | Case | Must be red before because |
|---|---|---|
| 1 | ⭐⭐ An invoice carries its type to the DB | no column — the sale saves and reads back NULL |
| 2 | ⭐⭐ **Existing invoices stay NULL** — the control | a default would retro-label every historic retail sale |
| 3 | The three types reach the day's report and **sum to the day's take** | no grouping exists; a split that does not reconcile is worse than none |
| 4 | The type prints on the receipt | template has no such field |
| 5 | ⭐ A tenant without `ORDER_TYPES` sees no chooser **and its sales still save** | the capability must hide a feature, never break the till |
| 6 | ⭐ Paying does not change the type, and changing the type does not alter what is owed | §3 — the two clocks, asserted before a kitchen state exists to confuse it |

Case 2 and case 6 are the ones that keep this honest. Case 3's *sum* is the assertion, not the split: this
platform has twice shipped a figure that looked right and did not reconcile against an independent source.

Unit: `OrderTypeTest` — a null stays null through the DTO round trip; `valueOf` of an unknown stored value
does not throw the screen away.

---

## 6 · ⚠ Coordination

`business-service` is actively edited by a peer session (PAID-1 landed; `BusinessSettingsCatalog` claimed for
the pharmacy formula work). This slice touches `CustomerHistory`, `CustomerHistoryDTO`, `SagaSaleWriter`, a new
Flyway migration and `BusinessSettingsCatalog` — **the last one collides**. Announce regions and take the
migration number only when starting, not when designing: business-service is at V67 (PAID-1).

The monolith side touches `counter.js` (mine) and the sale screen's markup.

---

## 7 · Open questions — to be ruled, not guessed

1. **Default type.** Take-away is proposed, being the commonest at a counter. A restaurant that is mostly
   dine-in would want the opposite. A per-tenant setting, or just accept the default and let them tap?
2. **Is the type editable after the sale is saved?** A cashier who mis-taps has to fix it somehow, but an
   editable type on a settled invoice changes what the day's report says after it was read. Leaning: editable
   only while the shift is open, which matches how corrections work elsewhere here.
3. **Does `DELIVERY` require a customer?** A delivery with no address is not deliverable, but the counter's
   fastest path is no customer at all. Leaning: require a contact for `DELIVERY` only — and that is a
   refusal, so it needs its own case.
