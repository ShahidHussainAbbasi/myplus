# Slice 98 — M4e.1b: the business POS picker + cart go productId-native (off Item)

The keystone of M4e: the sell/purchase picker now lists catalog **Products** (value = productId) and the cart keys by
**productId** — so the POS no longer depends on the local `Item` table. Builds on M4e.1a (`productStock` is the full
productId pre-fill).

## Changes (monolith `business.js`)
- **`loadUserItems(table)`** — populates `#sellItemDD`/`#purchaseItemDD` from **`/catalogProducts`** (value=productId,
  data-product=productId, text=name) instead of `/getUserItems` (Items).
- **`loadStock`** — pre-fills from **`productStock?productId=`** (on-hand + price + description + FEFO batches) instead of
  `getStock?itemId=`.
- **cart-add** — the line keys by `productId` (`obj.productId` = picker value); cart row + `UIT(productId)` +
  edit-replace `findIndex` all by productId. `obj.itemId` kept = same value for back-compat submission (removed in M4e.5).
- **`UIT(id)`** — removes by `productId`.
- **`loadCartLineIntoForm`** — reloads the catalog-product picker, selects the line by `productId` (injects a one-off
  option for an orphaned/deleted product so the sale stays editable).
- **purchase batch pre-fill** — `getStockByBatch` now sends `productId` (the picker value).

## Changes (business-service)
- **`StockController.productStock`** (M4e.1a) — full productId pre-fill (on-hand + price + description + FEFO batches).
- **`StockController.getStockByBatch`** — takes `productId` directly (no itemId→ItemCatalogMap lookup).

## Coexistence / scope note
- **Pharmacy is NOT touched.** `pharma.js` clinical/dispense pickers still use `getUserItems` (itemId) and pharma-service
  stores prescriptions/clinical by itemId. They keep working (the `getUserItems` endpoint + Item still exist). **But the
  full Item deletion (M4e.5) will require migrating the pharmacy domain to productId first** — a significant addition to
  M4e's scope, to plan before the destructive drop.

## Build + test (user)
- Rebuild **business-service** (StockController) **and the monolith** (`business.js` static — needs the rebuild to load).
- Cypress (this is a core-POS change — run the lot): `sell.cy.js`, `sell-edit.cy.js`, `purchase.cy.js`, `flow.cy.js`,
  `saga-sell.cy.js`, `saga-sell-ui.cy.js`; plus pharmacy `dispense.cy.js` to confirm no collateral.

## Status
- [x] picker → catalogProducts (productId) · loadStock → productStock · cart keyed by productId · UIT/edit by productId · getStockByBatch by productId
- [ ] **Awaiting business + monolith rebuild + (user-run) Cypress**

## Next M4e
- **M4e.2** remove the write-path bridge (SagaSellService itemId fallback, PurchaseService ensureMapped, addProduct→syncProductItem).
- **M4e.3** retire item/stock screens. **M4e.4** itemCount → Products. **M4e.pharma** migrate pharmacy to productId.
  **M4e.5** delete Item + tables (destructive Flyway).
