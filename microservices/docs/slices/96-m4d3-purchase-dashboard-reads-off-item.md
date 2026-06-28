# Slice 96 — M4d.3: Purchase + Dashboard reads resolve from catalog (off the Item entity)

Completes the M4d read-rewire: the remaining transaction-read screens resolve line/product names from catalog
`ProductRef` (via `CatalogClient.getProducts`) instead of loading the local `Item`.

## Changes (business-service)
- **`PurchaseController.getUserPurchase`** — batch-resolve `ProductRef` by the purchases' `productId` (the purchase
  carries its own `itemId` + `productId`, so no reverse map needed); per line set `iname`←name, `icode`←sku, keep
  `itemId` from the purchase. Removed `IItemService itemService` field + import + the `entity.Item` import; added
  `CatalogClient` + a `productRefs` helper. (`itemService` was used only here.)
- **`BusinessDashboardController`** top-items — aggregate monthly sells **by `productId`** and resolve the top-5 names
  from catalog (≤5 lookups) instead of the `ItemCatalogMap` reverse map + `itemService.findById`. Swapped the
  `ItemCatalogMapRepo` field for `CatalogClient`; removed the now-unused `Item` / `Optional` / `ItemCatalogMapRepo`
  imports. `itemService` stays only for the `itemCount` KPI (until M4e).

Display values unchanged (Item was a faithful Product projection) — source-only decoupling.

## Build + test (user)
- Rebuild **business-service** (uses `CatalogClient.getProducts` from M4d.1).
- Cypress: `purchase.cy.js` (grid item names), `dashboard-api.cy.js` / dashboard (top-items names), `flow.cy.js`
  purchase block.

## Status — M4d COMPLETE after this
- [x] PurchaseController.getUserPurchase → catalog (+ removed itemService)
- [x] BusinessDashboard top-items → catalog (aggregate by productId)
- [ ] **Awaiting business rebuild + (user-run) Cypress**

## M4d done → M4e next (the teardown)
The transaction reads (Sell/Purchase/Dashboard) no longer load `Item`. Remaining `Item` references are the item/stock
**screens** + write/migration plumbing — all deleted in **M4e**: `ItemController`, `StockController` item-list,
`ProductSyncService`, `CatalogMigrationService`, `ItemRepo`, `ItemCatalogMap(Repo)`, the Item form,
`/addItem`/`/addStock`, the `itemCount` KPI source, and a destructive Flyway dropping `item` + `item_catalog_map`
(+ item_type/item_unit if they go). Picker becomes productId-valued; the legacy-endpoint specs retire there.
