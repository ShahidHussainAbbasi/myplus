# Slice 87 — M3c.4e: catalog price-carry off local `Stock`

Removes the last `Stock` read in the **live** flow: `CatalogMigrationService.sellRateOf`, called during catalog mapping
(`migrate` / `ensureMapped`-on-purchase) to carry a legacy item's sell price into the new catalog Product.

## Why latest-purchase bsellRate
`sellRateOf` read the item's local `Stock.bsellRate`. After M3b (slice 75) purchases no longer write `Stock`, so that
read only ever mattered for carrying the **legacy** price of an un-migrated old item — and **V6 (slice 84) already
backfilled `bsellRate` onto historical purchase rows**. So the deploy-reproducible, `Stock`-free source for that legacy
price is the item's most recent purchase. This is ≥ the prior behavior (which already returned null for any item created
after M3b, since no `Stock` row is written).

## Changes (business-service, code)
- **`PurchaseRepo.findFirstByItemIdAndBsellRateNotNullOrderByPurchaseIdDesc(itemId)`** — derived query for the latest
  purchase sell rate.
- **`CatalogMigrationService.sellRateOf`** — now reads that instead of `stockRepo.findByItemId(...).map(Stock::getBsellRate)`.
  Removed the `StockRepo stockRepo` field + the `entity.Stock` import; added the `Purchase` import.

## Test (ships with the slice)
- **`CatalogMigrationServiceTest`** — swapped the `@Mock StockRepo` (+ its `findByItemId` stub, now an unused/strict
  failure) for `@Mock PurchaseRepo` and stubbed `findFirstByItemIdAndBsellRateNotNullOrderByPurchaseIdDesc` in the
  ensure-mapped case. `migrate` needs no extra stub (Mockito returns `Optional.empty()` by default). `CatalogBackfillTest`
  is unaffected (it autowires `StockRepo` independently to seed the backfill JOIN; the table still exists until 4f).

## Scope note — StockMigrationService → 4f
`StockMigrationService` (the one-time local-Stock → inventory **on-hand seed**, admin-only via `/migrate-stock`) is the
seed *source*: it must coexist with the `Stock` table until the drop, and auto-running it is unsafe (it would double-count
on-hand that purchases already dual-wrote to inventory). It uses `StockRepo` (not `entity.Stock` directly). So it is
**deleted in 4f together with the table** — not here. For this converged DB inventory is already authoritative; for a
generic pre-convergence deploy the seed remains the documented one-time operator step before the 4f drop.

## Post-4e state
Only `StockRepo`, `IStockService`, `StockService` import `entity.Stock`. `StockRepo` users: `StockService`,
`StockMigrationService` (+ their tests). All are 4f targets.

## Status
- [x] sellRateOf reads latest purchase bsellRate (Stock-free); StockRepo field + Stock import removed
- [x] PurchaseRepo derived query added
- [x] CatalogMigrationServiceTest updated
- [ ] **Awaiting business rebuild + `mvn test`** (CatalogMigrationServiceTest, CatalogBackfillTest, PurchaseStockInTest)

## Remaining M3c
- **4f** drop `Stock` entity + `Sell.stock`/`Purchase.stock` FK columns + `StockController` screen wiring (already off
  Stock) + `StockService`/`StockRepo`/`IStockService`/`StockMigrationService` + the `/migrate-stock` admin endpoint +
  Flyway column/table-drop migration (after confirming the on-hand seed is complete).
