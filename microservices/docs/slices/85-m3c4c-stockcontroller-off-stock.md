# Slice 85 — M3c.4c: StockController (Stock screen) off local `Stock`

The Stock screen already operated on `Item` + inventory on-hand for listing; the **only** remaining live read of the
local `Stock` entity was `getStock(itemId)` (the sell/dispense pre-fill), where a local-Stock sum was computed and then
overridden by inventory/catalog when the saga was enabled. This makes that path source **purely** from inventory +
catalog by productId, removing the last `Stock` entity dependency in the controller.

## Changes (business-service `StockController`, code-only)
- **`getStock(itemId)`** — removed the `Stock` example query (`service.findAll`), the local-stock sum, and the
  `modelMapper.map(Stock → StockDTO)`. Now: anti-IDOR tenant check → seed a default `StockDTO`
  (`bpurchaseDiscountType="%"`, `bpurchaseDiscount=0`, `stock=0`, `iDesc` from the Item) → resolve `productId` via
  `ItemCatalogMap` → on-hand from `inventoryClient.getStockLevel`, sell price from `catalogClient.getProduct`, FEFO
  batches/batchNo/expiry from `inventoryClient.getBatches`. Dropped the now-redundant `tradeSagaProperties` guard
  (inventory is authoritative regardless of the write-saga flag; siblings `productStock`/`getStockByBatch` already do this).
- Removed the unused `IStockService service` field + its import, and the `entity.Stock`, `Example`, `Optional` imports.

The other endpoints were already `Stock`-entity-free: `getUserStock`/`getAllStock` list `Item` (+ batched inventory
on-hand), `getUserStocks` is the item dropdown, `productStock`/`getStockByBatch` hit inventory/catalog, `addStock`/
`deleteStock` operate on `Item`.

## No migration
Pure read-path rewiring — no schema/data change. On-hand/batches already live in inventory; the deploy-time backfills
(V5/V6) + `ensureMapped`-on-purchase + the startup runner guarantee items are catalog-mapped, so `productId` resolves.

## Tests (user-run)
- `stock.cy.js`: Stock screen lists items with inventory on-hand; add/delete item.
- `sell.cy.js` + `flow.cy.js`: selecting an item on the sell screen pre-fills on-hand + sell price + batch/expiry
  (via `getStock`); pharmacy dispense FEFO batch shows.

## Status
- [x] getStock sources purely from inventory + catalog (no local Stock read)
- [x] removed IStockService field + Stock/Example/Optional imports
- [ ] **Awaiting business rebuild + (user-run) Cypress** stock + sell + flow

## Remaining M3c
- **4d** Item↔Stock relation (ItemService/AppUtil/BatchService) · **4e** StockMigrationService +
  `CatalogMigrationService.sellRateOf` (still reads `stockRepo.findByItemId`) · **4f** drop `Stock` entity +
  `Sell.stock`/`Purchase.stock` columns + StockService/StockRepo/IStockService + Flyway drop.
