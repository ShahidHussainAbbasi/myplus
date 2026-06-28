# Slice 84 — M3c.4b: Purchase path off local `Stock`

Takes `PurchaseController` + `PurchaseService` fully off the local `Stock` entity. The purchase row is already
self-describing (M3b, slice 75) and dual-writes stock-in to inventory; this removes the last reads/maps through `Stock`
so the `Stock` entity + `Purchase.stock` FK can be dropped (M3c.4f).

## Deploy-reproducible migration (existing DB)
- **`V6__backfill_purchase_self_describing.sql`** — copies the snapshot columns (`item_id`, `batch_no`,
  `bpurchase_rate`, `bsell_rate`, discounts + types, `bexp_date`) from the linked `stock` onto **historical** purchase
  rows (`stock_id IS NOT NULL AND item_id IS NULL`). V5 only backfilled `product_id`; this fills the rest so the grid
  renders batch/rate without reading the FK. Idempotent, no-op on empty/fresh DB.

## Changes (business-service, code)
- **`PurchaseController.getUserPurchase`** — identity + batch/rate now come from the Purchase's OWN fields; removed the
  `if (o.getStock() != null)` legacy branch and the `o.getStock().getItemId()` fallback. Still emits the nested
  `StockDTO` the UI grid expects, built from the purchase. Removed unused `IStockService` field + import.
- **`PurchaseService.addPurchase`** — copies the batch/rate snapshot straight off `dto.getStock()` (a `StockDTO`, whose
  scalar types already match `Purchase`); `bexpDate` (String) parsed via new `AppUtil.toLocalDateOrNull`. Dropped the
  throwaway in-memory `Stock` map + the `Stock` entity import + the unused `IStockService stockService` field.
- **`AppUtil.toLocalDateOrNull(String)`** — dd-MM-yyyy → LocalDate (null when blank), mirroring
  `stringToLocalDateIgnoreEmptyOrNull` for direct (non-ModelMapper) use.

`obj.setStock(null)` remains (sets the still-present `Purchase.stock` FK to null) until the column is dropped in 4f. No
contract change; inventory stays authoritative for on-hand.

## Tests (user-run)
- `purchase.cy.js` + `flow.cy.js`: add a purchase (batch/rate/expiry persist + show in grid), grid renders historical +
  new rows with batch/rate, stock-in reaches inventory.

## Status
- [x] V6 backfill migration (snapshot columns from stock)
- [x] PurchaseController off `Stock` (read path + field/import)
- [x] PurchaseService off `Stock` entity (addPurchase reads StockDTO; AppUtil helper)
- [ ] **Awaiting business rebuild + (user-run) Cypress** purchase + flow

## Remaining M3c
- **4c** StockController (the Stock screen) · **4d** Item↔Stock relation (ItemService/AppUtil/BatchService) ·
  **4e** StockMigrationService + `CatalogMigrationService.sellRateOf` (still reads `stockRepo.findByItemId`) ·
  **4f** drop `Stock` entity + `Sell.stock`/`Purchase.stock` columns + StockService/StockRepo + Flyway drop.
