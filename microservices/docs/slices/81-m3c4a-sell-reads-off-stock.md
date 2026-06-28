# Slice 81 — M3c.4a(1): SellController read paths fully off `Stock`

First cut of M3c.4 (drop `Stock`). With the backfill complete (`*Remaining == 0`), every sell carries `productId`, so the
SellController **read** methods drop the local-`Stock` fallback + display entirely and resolve the item **productId-only**
via the reverse catalog map. The file still compiles — the dead `addSelling` + edge fallbacks still reference the (still
present) `Stock` field; they're removed in the next cut before the field/entity drop.

## Changes (business-service `SellController`)
- **`getUserSell`** (list), **`getSellInvoice`** (edit-load), **`getReceipt`** (receipt/SR), **date-range report** —
  removed the `itemIds`/`itemsById` (Stock) collection + the `if (getStock()…) setStock(…)` display blocks; item now
  resolves from `itemByProductId.get(productId)` only. The report's prior **NPE on saga sells** (`obj.getStock()…`) is
  fixed; its live `itemStock` column (Stock on-hand) is dropped (it's a sales report).

- **`V5__backfill_sale_product_ids.sql`** (business/myplusdb) — **Flyway data migration** that backfills `product_id`
  onto historical Stock-linked sells/purchases on deploy (idempotent; no-op on empty). Makes the productId-only
  renderers safe on a fresh/prod deploy **without** relying on the manual admin endpoint (which stays as the
  completeness tool for not-yet-mapped items). *(Added per review: all schema/data changes are Flyway scripts.)*

No contract change. All sells render identically (every sell is backfilled, now also at deploy time).

## Tests (user-run)
- `sell.cy.js` + `flow.cy.js` (+ receipt/report spec if present): list, edit-load, receipt, and the date-range report
  show item names/amounts; saga + (backfilled) legacy rows identical.

## Status
- [x] 4 read methods productId-only (no getStock); report NPE fixed
- [ ] **Awaiting business rebuild + (user-run) Cypress** sell + flow

## Remaining in M3c.4a (next cut) + M3c.4
- **4a(2):** SellController writes/edges off `Stock` — neuter dead `addSelling`, drop the `saleReturn` + `updateSell`
  pre-backfill fallbacks, remove the `IStockService stockService` field.
- **4b** PurchaseController/Service · **4c** StockController (the Stock screen) · **4d** Item↔Stock relation
  (ItemService/AppUtil/BatchService) · **4e** StockMigrationService/CatalogMigrationService.sellRateOf ·
  **4f** drop `Stock` entity + `Sell.stock`/`Purchase.stock` columns + StockService/StockRepo + column-drop migration.
