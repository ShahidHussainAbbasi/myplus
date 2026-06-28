# Slice 92 — M4c: productId-native purchase

Make the purchase write carry `productId` so `PurchaseService` uses it directly instead of resolving it from `itemId`
via `ensureMapped` — the purchase counterpart of M4b, continuing toward retiring `Item`/`ItemCatalogMap` (M4e).

## How the purchase submits (no monolith DTO change needed)
The monolith `/addPurchase` is a **form passthrough** (`client.postForm`, raw request params), so productId only needs to
be (a) a form param the client sends and (b) a field on the **business** `PurchaseDTO`.

## Changes
- **business `PurchaseDTO`** — added `private Long productId;` (Lombok `@Data`).
- **monolith `main.js`** (the generic add handler) — for `buttonV=="Purchase"`, inject `productId` into the submit
  payload from the picker's `data-product` (emitted by `getUserItems` in M4b): `fd.productId = $("#purchaseItemDD :selected").data('product')`.
  `callAjax` form-encodes, so it arrives as a form param the monolith forwards.
- **business `PurchaseService.addPurchase`** — prefer the submitted `productId`; fall back to
  `ensureMapped(itemId)` for legacy submissions / not-yet-mapped items (idempotent, so it still auto-maps on demand).
  `pushPurchaseToInventory` already keys off `obj.getProductId()`.

`itemId` stays on the line as a back-compat fallback; legacy itemId-only purchases still work (e.g.
`purchase-inventory.cy.js`'s unmapped-item auto-map).

## Verification
- **`purchase.cy.js`** CRUD test now submits **productId-native** (`body: { itemId, productId, … }`) and asserts
  `status === 'SUCCESS'`.

## Build + test (user)
- Rebuild **business-service** (PurchaseDTO/PurchaseService) **and the monolith** (`main.js` static — served from
  `target/classes`, so rebuild required for the JS to load).
- Cypress: `purchase.cy.js` (productId-native), `purchase-inventory.cy.js` (itemId fallback still works),
  `flow.cy.js` purchase block.

## Status
- [x] business PurchaseDTO.productId · main.js purchase productId inject · PurchaseService prefers productId
- [x] purchase.cy.js verification (productId-native)
- [ ] **Awaiting business + monolith rebuild + (user-run) Cypress**

## Next
- **M4d** enrich `ProductRef` (description/category/manufacturer) + batch `getProducts(ids)` + cache; rewire all read
  name-resolution (SellController/PurchaseController/BusinessDashboard `itemByProductId`) to catalog.
- **M4e** delete Item/ItemCatalogMap/ProductSyncService/ItemRepo + Item form + `/addItem`/`/addStock` + tables.
