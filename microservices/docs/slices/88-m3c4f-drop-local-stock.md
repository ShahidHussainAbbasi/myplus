# Slice 88 — M3c.4f: drop the local `Stock` table (convergence complete)

The final teardown. Inventory-service is authoritative for on-hand/batches; sells/purchases carry `productId` (V5) +
self-describing snapshots (V6); 4a–4e removed every live read/write of local `Stock`. This deletes the entity, its
service/repo/migration, the FK fields on Sell/Purchase/Item, and the `stock` table itself.

## DESTRUCTIVE migration — `V7__drop_local_stock.sql`
- Drops the Sell/Purchase/Item → `stock` foreign keys (resolved dynamically by name from `information_schema`),
  then the `stock_id` columns (guarded), then `DROP TABLE IF EXISTS stock`.
- **Irreversible.** Ordered after V5/V6 so the backfills-from-stock complete first. Idempotent / safe to re-run.
- Runs automatically on the next business-service start (Flyway). After it, Hibernate `validate` passes (entities +
  schema are both Stock-free).
- ⚠️ **Back up the business-service DB before the restart that applies V7.**

## Code removed
- **Deleted**: `entity/Stock`, `service/StockService`, `service/IStockService`, `repository/StockRepo`,
  `service/StockMigrationService`, `dto/StockMigrationResult`.
- **Entities**: removed the `stock` field + FK mapping from `Item`, `Purchase`, `Sell`.
- **`PurchaseService`**: removed `obj.setStock(null)`.
- **Backfill plumbing retired** (the queries JOIN `stock`, invalid after the drop; the historical backfill already ran at
  Flyway time V5/V6): `SellRepo`/`PurchaseRepo` `backfillProductIds` + `countWithoutProductId` + `backfillAllProductIds`;
  `CatalogMigrationService.backfillProductIds` + `backfillAll` + the `BackfillResult` record (dropped the unused `SellRepo`
  field); the startup runner's `backfillAll()` call; the `CatalogMigrationController` `/migrate-stock` +
  `/backfill-product-ids` admin endpoints. `/migrate-catalog` + `migrateAllUnmapped` (runner) remain — they map items to
  catalog Products (cross-service, no Stock).
- `LegacyController`'s Stock refs were already commented; no change needed.

## Tests
- **Deleted** `StockMigrationServiceTest` (service gone) + `CatalogBackfillTest` (tested the removed backfill-from-stock).
- `CatalogMigrationServiceTest` (4e), `PurchaseStockInTest`, `SagaSellServiceTest`, `AddSellAtomicityTest` (4d) are
  unaffected.

## Follow-up (not in this slice)
- Monolith `ItemController` still has `/backfillProductIds` (→ removed `/admin/backfill-product-ids`) and any
  `/migrateStock` proxy — now dead; remove in a monolith cleanup pass. `/migrateCatalog` stays valid.
- `StockController` (the Stock **screen**) stays — it lists `Item` + inventory on-hand (already off the entity in 4c).
- `StockDTO` stays — it's the UI batch/rate contract carried on `ItemDTO`.

## Status — M3c COMPLETE
- [x] V7 destructive drop migration authored
- [x] Stock entity/service/repo/migration deleted; FK fields removed from Item/Purchase/Sell
- [x] obsolete backfill plumbing + admin endpoints retired
- [x] tests removed/aligned; zero dangling references
- [ ] **Awaiting business rebuild + `mvn test` + (user-run) Cypress** — and the V7-applying restart (back up DB first)

## What's next after M3c
- **M4**: rewire `itemId` → `productId` end-to-end, resolve names from catalog Product (retire the `Item` lookups), and
  retire the legacy Item form in favour of the Product form.
- Deferred: AWS deploy fix; E14 admin screens; E9 shipping; E2 variants.
