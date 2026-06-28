# Slice 89 — M4 plan: rewire itemId → productId end-to-end, delete the local `Item`

Decision (user, 2026-06-28): **full rewrite** — end-state is business-service holding **no** product projection; all product
data resolves from catalog `Product`. Done phased, each sub-slice with a build/test/Cypress gate.

## Post-M3c starting point
- Catalog `Product` is the single master. `ProductSyncService` projects each Product → local `Item` + `ItemCatalogMap`
  (name/sku/desc/unit/category). Local `Item` is therefore a **read-projection**, not a source of truth.
- `Sell` is productId-native (no itemId). `Purchase` carries itemId + productId. Pickers submit `itemId`; the saga
  translates itemId→productId.
- Redundant write path: `ItemController.addItem` + `StockController.addStock` + the Item form in `businessDashboard.html`.
- `ProductRef` is thin (id/sku/name/unit/sellingPrice/taxRate) — no description/category/manufacturer.

## Sub-slices
- **M4a — retire the Item form.** Product form becomes the only create/edit path (it already syncs Product→Item via
  `ProductSyncService`). Hide/redirect the Item form UI; neuter `ItemController.addItem` + `StockController.addStock`
  write endpoints. Item reads keep working (read-model). *No data loss: confirm the Product form covers Item's fields.*
- **M4b — pickers submit productId.** Sell + purchase item dropdowns carry `value=productId`; submissions are
  productId-native; keep itemId translation only as back-compat for old payloads.
- **M4c — purchase write productId-native.** Purchase DTO/form + `PurchaseService` use productId directly; drop the
  itemId/ensureMapped-from-item reliance.
- **M4d — reads resolve from catalog.** Enrich `ProductRef` (description, category, manufacturer) + add a batch
  `getProducts(ids)` to `CatalogClient` + a short-TTL cache; replace every `itemByProductId` / `Item item =`
  name-resolution (SellController, PurchaseController, BusinessDashboardController) with catalog lookups.
- **M4e — delete Item.** Remove `Item`, `ItemCatalogMap`, `ProductSyncService`, `ItemRepo`, `ItemController`,
  `StockController`'s item-list endpoints, `Item`-based tests; destructive Flyway dropping `item` + `item_catalog_map`
  (+ item_type/item_unit if they go too). Mirror the M3c.4f expand/contract discipline.

## Status
- [ ] M4a (in progress) · [ ] M4b · [ ] M4c · [ ] M4d · [ ] M4e
