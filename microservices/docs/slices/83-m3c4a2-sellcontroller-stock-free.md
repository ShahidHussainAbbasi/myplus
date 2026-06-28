# Slice 83 — M3c.4a(2): SellController fully off local `Stock`

Finishes M3c.4a. After the read methods went productId-only (slice 81), this removes the last live `Stock` touches in
`SellController` so the `Sell.stock` FK + `Stock` entity can be dropped (M3c.4f).

## Changes (business-service `SellController`)
- **`updateSell`** edit-delta: productId resolution is now `o.getProductId()` only (dropped the `stock.itemId`
  fallback — backfill is complete).
- **`saleReturn`**: dropped the pre-backfill local-`Stock` restock branch (`else if (sellSId)…`). Returns now route:
  saga → inverse-saga; non-saga (has productId) → inventory `importStock`. No local `Stock` write.
- **`addSelling`** (dead bulk endpoint) + **`revertSell`** (dead; its UI button was already commented out) — **neutered**
  to clear stubs that return a clear "use addSell / Sale Return" message (removed their `item.getStock()` bodies).
- Removed the `IStockService stockService` field + the `IStockService`/`Stock`/`StockDTO` imports (all unused now; the
  `Stock` entity import would otherwise break when the entity is dropped).

Only inert **commented** legacy code still mentions `Stock`. No DB/contract change.

## Tests (user-run)
- `sell.cy.js` + `flow.cy.js`: list, edit (qty up/down → inventory delta), receipt, returns — all work productId-only.
  (`addSelling`/`revertSell` aren't called by any UI.)

## Status
- [x] SellController fully off live `Stock` (updateSell fallback, saleReturn fallback, addSelling/revertSell neutered, field+imports removed)
- [ ] **Awaiting business rebuild + (user-run) Cypress** sell + flow

## Remaining M3c
- **4b** PurchaseController/Service · **4c** StockController (the Stock screen) · **4d** Item↔Stock relation
  (ItemService/AppUtil/BatchService) · **4e** StockMigrationService/CatalogMigrationService.sellRateOf ·
  **4f** drop `Stock` entity + `Sell.stock`/`Purchase.stock` columns + StockService/StockRepo + Flyway column/table drop.
- Monolith `/addSelling` + `/revertSell` proxies now hit the deprecation stub (harmless) — remove in a monolith cleanup pass.
