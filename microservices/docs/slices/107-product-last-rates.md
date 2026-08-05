# Slice 107 — Product list: last purchase & last sale rate

Adds two columns to the Product master screen (`#tableProduct`): what each product was **last bought at** and what it
is **last priced to sell at**. Both are **stamped onto the catalog Product by the purchase flow** — never derived from
purchase/sell history when the screen opens.

## Why stamped, not queried

The first cut derived both rates on read (a `ROW_NUMBER()` window query per side in business-service behind a batch
`/productRateLevels` endpoint). That was discarded in favour of writing at purchase time, because:

- **Option B already did half of it.** `PurchaseService` has stamped the bill's sell rate onto
  `Product.sellingPrice` since the "re-price on receive" work. Writing the purchase rate beside it is the same
  mechanism, one field wider — not a new pattern.
- **The read path costs nothing.** The Product list already loads every product row; two more fields on that payload
  mean **zero** extra queries and zero extra round trips. The discarded design added one endpoint, one HTTP call per
  screen open and two indexed scans of `purchase`/`sell`.
- **Nothing lands on the sale hot path.** Both writes happen when a purchase is added or edited — a rare operation.
  Checkout is untouched.

The trade-off accepted: a stamped value is a snapshot, so it is only as current as the last purchase. Correcting a
bill re-stamps (see below), which covers the case that actually matters.

## Data flow

```
Purchase screen (bpurchaseRate, bsellRate)
        │  addPurchase / updatePurchase
        ▼
business-service  PurchaseService.stampRatesOnProduct
        │  PUT /products/{id}/price?price=&purchaseRate=   (best-effort)
        ▼
catalog-service   ProductService.updatePrice
        │  sellingPrice ← sell rate      (the LIVE master price)
        │  lastSaleRate ← sell rate      (what this bill set it to)
        │  lastPurchaseRate ← cost
        │  lastRateAt ← now
        ▼
monolith /getUserProduct  →  #tableProduct renders both cells from the row
```

## `lastSaleRate` vs `sellingPrice`

They are stamped equal by every purchase and diverge **only** when someone edits the price directly on the Product
form. That difference is the point: the Price column shows what the shop charges now, `Last sale rate` shows what the
last purchase said it should charge. A product whose two disagree has been hand-priced since it was last bought.

## Guards

| Bill carries | Effect |
|---|---|
| both rates | both stamped; `sellingPrice` moves |
| cost only (sell rate blank/0) | cost stamped; **`sellingPrice` untouched** — a goods-in must never silently re-price the shop |
| sell rate only | price + `lastSaleRate` stamped; last known cost kept |
| neither (blank/0) | nothing written at all — a bill with no rates must never wipe the master |

Rates are **null until the first purchase**, and the screen renders `—`. `0.00` would claim the shop bought it for
nothing.

Best-effort by design: the stock is already in and the bill already recorded when the stamp is attempted, so a catalog
outage degrades these two columns and never the purchase.

## Changes

**catalog-service**
- `Product` — `lastPurchaseRate`, `lastSaleRate`, `lastRateAt`.
- `ProductDTO` + `toDto` — carry all three, so the list renders from the row it already loads.
- `ProductService.updatePrice(id, price, purchaseRate)` — stamps both rates + the timestamp, per-field positive guard.
- `ProductController` `PUT /products/{id}/price` — `purchaseRate` added; both params now optional.
- **`V8__product_last_rates.sql`** — three nullable columns, guarded/idempotent (V28-style).

**commerce-contracts**
- `CatalogClient.updatePrice(id, price, purchaseRate)`.

**business-service**
- `PurchaseService.stampRatesOnProduct(saved, snap, phase)` — one helper, called identically from `addPurchase` and
  `updatePurchase`, replacing the two duplicated Option-B blocks. Fires when **either** rate is positive (the old
  blocks fired only on a positive sell rate, so a cost-only bill wrote nothing).

**monolith**
- `CatalogController.getUserProduct` — passes the three new fields through (the proxy hand-picks fields).
- `businessDashboard.html` — two `<th>` after Price.
- `business.js` — `lastRateCell()` helper + two cells in the Product row array.
- `messages*.properties` ×6 — `ui.lastPurchaseRate`, `ui.lastSaleRate`, `ui.js.lastPurchased`, `ui.js.lastSold`.

## Tests

- **`ProductLastRatesTest`** (catalog-service, Testcontainers, runs on `mvn test`) — the stamp, re-stamp, and all four
  guard rows above, plus null-until-first-purchase.
- **`product-last-rates.cy.js`** (headed gate) — purchase stamps both; a later purchase replaces both; **editing a
  purchase re-stamps** (a mistyped rate is correctable); a cost-only bill does not re-price; the two columns render;
  a never-purchased product shows `—`. The UI test also asserts row `td` count == visible header `th` count, which
  catches the DataTables column-shift bug directly.

## Known gap — no historical backfill

Existing products show `—` until their next purchase. Backfilling is not possible from either side's Flyway:
catalog-service's schema cannot read business-service's `purchase` table. Filling history would need a cross-tenant
system-auth path (a startup job in business-service pushing into catalog for every org), which is a slice of its own
and was not built here.

## Status
- [x] catalog columns + Flyway V8 + DTO/mapping
- [x] `updatePrice` stamps both rates, per-field guards
- [x] contract + both purchase call sites (add **and** edit)
- [x] monolith proxy passes the fields through
- [x] two columns + i18n ×6
- [x] `ProductLastRatesTest`
- [x] **Headed gate GREEN 6/6 — `product-last-rates.cy.js` (2026-08-05)**
