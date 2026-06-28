# Slice 94 — M4d.2: SellController reads resolve from catalog (off the Item entity)

Rewire all SellController read screens to resolve line display fields (name/sku/description) from catalog `ProductRef`
(via `CatalogClient.getProducts`, M4d.1) instead of loading the local `Item` entity. `itemId` (still needed by the edit
picker until M4e makes it productId-valued) comes from the `ItemCatalogMap` reverse map — **no Item entity load**.

## Changes (business-service `SellController`)
- Injected `CatalogClient`; added two helpers:
  - `productRefs(productIds)` → `Map<productId, ProductRef>` via `catalogClient.getProducts` (best-effort; blank names on a catalog hiccup, never throws).
  - `productToItem(productIds)` → `Map<productId, itemId>` via `ItemCatalogMapRepo.findItemIdsByProductIds` (no Item load).
- Rewired the 4 read blocks — `getUserSell`, `getSellInvoice` (edit), `getReceipt` (names only; a printed receipt needs
  no itemId), date-range report — from `itemService.findAllById(...)` + `Item` to `ProductRef` (name←name, code←sku,
  description←description) + `itemId` from the reverse map.
- Removed the now-unused `IItemService itemService` field + the `entity.Item` / `IItemService` imports (only the
  commented legacy `addSell(@Validated SellDTO)` block still names them, and comments need no imports).

Display values are unchanged — `Item` was a faithful Product projection (ProductSyncService keeps iname=name, icode=sku,
idesc=description) — so this is source-only (decouple reads from `Item`), clearing SellController for M4e.

## Build + test (user)
- Rebuild **business-service** (uses the `CatalogClient.getProducts` shipped in M4d.1).
- Cypress: `sell.cy.js` (list names), `sell-edit.cy.js` (getSellInvoice → edit cart names + itemId picker),
  `saga-sell.cy.js`, `flow.cy.js`. Receipts/report covered by `day-close.cy.js` / `dashboard-api.cy.js` if run.

## Status
- [x] CatalogClient injected + productRefs/productToItem helpers
- [x] getUserSell, getSellInvoice, getReceipt, date-range report rewired to catalog
- [x] itemService field + Item/IItemService imports removed
- [ ] **Awaiting business rebuild + (user-run) Cypress**

## Next — M4d.3
- PurchaseController `getUserPurchase` (resolves item name via `itemService.findById`) and BusinessDashboardController
  top-items (`itemService.findById`) → catalog `getProducts`. Then M4d is complete and M4e can delete `Item`.
