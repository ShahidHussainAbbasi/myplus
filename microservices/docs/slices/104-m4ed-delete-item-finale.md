# Slice 104–106 — M4e.d: delete the Item entity (convergence finale)

**Branch:** feature/commerce-gaps · **Status:** d1–d3 green; d4 (destructive) pending apply

The last step of the Item→Product convergence: the catalog `Product` is now the single product master
end-to-end, so the legacy business-service `Item` entity + its `item_catalog_map` bridge are removed —
UI/tests, monolith proxies, business-service code, and finally the database.

## Why

`Item` was a synced projection of catalog `Product` (via `ItemCatalogMap` + `ProductSyncService`), kept only
so the old itemId-based POS/pharmacy screens kept working during the migration. After M4a–M4e.2 + M5, every
caller is productId-native (sell saga, purchase, pharmacy, pickers, reads). The bridge is now dead weight and a
correctness hazard (two masters). This slice deletes it.

## Design — four ordered sub-phases (destructive step last)

```
d1  test layer      cy.seedProduct + ~15 specs off getUserItem/itemId → productId + catalog/productStock
d2  monolith        /addProduct stops syncProductItem; delete ItemController; StockController → getStockByBatch
                    (productId-native); retire dead item/stock proxies
d3  business code    delete Item/ItemCatalogMap/ItemDTO/ItemRepo(sitory)/ItemCatalogMapRepo/IItemService/
                    ItemService/ProductSyncService/CatalogMigration* ; fix kept files (Sell/Purchase reads,
                    DashboardService itemsCount→catalog, drop Purchase.itemId + SellDTO.itemId); fix 3 tests
d4  database         V8__drop_item_tables.sql — DESTRUCTIVE: drop purchase.item_id, item_catalog_map, item
```

Order matters: nothing may reference `Item` before the code is deleted, and no code may be deleted before the
tests/pickers stop needing it. The table drop is **irreversible** and runs only after d1–d3 are green.

## Implement

- **d1** — `cy.seedProduct` returns `{ productId, name, sku }` (no more `getUserItem`→itemId lookup); specs read
  the catalog list (`/catalogProducts` → `data.content`) and inventory on-hand (`/productStock`), sell/purchase
  by productId. `saga-sell` seeds a stocked product instead of scanning items.
- **d2** — `CatalogController.addProduct` no longer projects an Item; monolith `ItemController` deleted; monolith
  `StockController` keeps only `getStockByBatch` and now forwards **productId** (was the always-null itemId — a
  latent drift fixed here).
- **d3** — 15 main + 2 test files deleted; `StockController` (business) keeps only `productStock` +
  `getStockByBatch`; `DashboardService.itemsCount` = `catalogClient.countProducts()`; `Purchase.itemId`,
  `SellDTO.itemId`, `PurchaseDTO.itemId`, `PurchaseRepo.findFirstByItemId…` removed; `SagaSellServiceTest`,
  `PurchaseStockInTest`, `SellInvoiceMoneyRepoTest` repointed to productId.
- **d4** — `V8__drop_item_tables.sql`: guarded `DROP COLUMN purchase.item_id` (+ `sell.item_id` if present),
  `DROP TABLE IF EXISTS item_catalog_map`, `DROP TABLE IF EXISTS item`. MyISAM tables → no FK drops needed.
  Idempotent + fresh/prod-deploy-safe (applies after the V5/V6 backfills).

## Test (Cypress gate + unit)

- d1: business + auth + pharmacy specs green against the pre-drop backend (endpoints all still existed).
- d2: monolith rebuild; same business/auth set green (sync-free addProduct + productId getStockByBatch).
- d3: `mvn -pl business-service test` (3 fixed unit tests + deletions compile) + business/pharmacy Cypress green.
- d4: after apply, re-run the full business + pharmacy suite; confirm business-service starts clean (Hibernate
  validates against the Item-free schema).

## Rollback

d1–d3 revert via git. **d4 is irreversible** — restore the `item`/`item_catalog_map` tables + `purchase.item_id`
from backup only. Commit the d1–d3 milestone before applying V8.
