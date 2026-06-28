# Slice 86 — M3c.4d: sever the Item/Sell↔`Stock` relation readers + writers

Removes the remaining LIVE readers/writers of the local `Stock` entity outside the Stock service layer itself, so the
only `entity.Stock` references left are the 4e/4f teardown targets. No schema change (the `Sell.stock`/`Purchase.stock`
FK columns + `Stock` table drop in 4f).

## Changes (business-service, code)
- **`SellController.addSell`** — the inventory reservation saga is now the **only** sell-write path. Removed the legacy
  non-saga branch (saved Customer/CustomerHistory then `sellService.addSell` → local Stock decrement) and the
  `tradeSagaProperties.isEnabled()` guard. `SagaSellService.addSell` already persists customer + invoice header + lines
  (via `SagaSaleWriter.writePending`) and compensates on failure.
- **`SellService.addSell`** — neutered to a loud `UnsupportedOperationException` stub (no longer wired to any endpoint).
  Removed the `entity.Stock` import + the `IStockService stockService` field (its only uses were here).
- **`BusinessDashboardController`** (top-items) — dropped the `s.getStock()` (Sell→Stock) fallback; productId-only
  (historical sells backfilled by V5).
- **`AppUtil`** — removed the unused `StockDTO` import (only a commented block referenced it).
- **Deleted dead `Stock`-importing files**: `BatchService`, `IBatchService` (a `Stock` DAO facade — only referenced by a
  commented line in PurchaseService), `BatchRepo` (used only by those), `StockRepository` (zero usages).
- **`ItemService`** — already clean (its `updateItemStock` is inside a `/* */` block and names no `Stock` type).

## Test (ships with the slice)
- **`AddSellAtomicityTest`** repurposed: the legacy premise (controller saves Customer in its own tx, rolls back on
  sale-write failure) is gone — the saga owns persistence + compensation (covered by `SagaSellServiceTest`). It now
  mocks `SagaSellService` to throw and pins the controller boundary: a saga failure propagates as an error and nothing
  persists. (Testcontainers MySQL; skips without Docker.)

## Post-4d state
Only these still import `entity.Stock`: `StockRepo`, `CatalogMigrationService` (sellRateOf — 4e), `IStockService`,
`StockService` (4f). All other live `.getStock().` reads are `StockDTO` (the UI DTO) or inside `StockService`.

## Status
- [x] SellController saga-only; SellService.addSell neutered + Stock import/field removed
- [x] BusinessDashboard productId-only; AppUtil StockDTO import removed
- [x] Deleted BatchService/IBatchService/BatchRepo/StockRepository
- [x] AddSellAtomicityTest updated to the saga path
- [ ] **Awaiting business rebuild + `mvn test` + (user-run) Cypress** sell + flow + dashboard

## Remaining M3c
- **4e** `StockMigrationService` (if present) + `CatalogMigrationService.sellRateOf` (still reads `stockRepo.findByItemId`).
- **4f** drop `Stock` entity + `Sell.stock`/`Purchase.stock` FK columns + `StockService`/`StockRepo`/`IStockService` +
  Flyway column/table-drop migration.
