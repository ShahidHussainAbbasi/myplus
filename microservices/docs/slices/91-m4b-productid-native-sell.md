# Slice 91 — M4b: productId-native sell submission

Make the sell path carry `productId` end-to-end so the saga uses it directly (no `itemId→ItemCatalogMap` translation
on the write path) — the prerequisite for deleting `Item`/`ItemCatalogMap` in M4e. Closes the gap found in the M4a
proof: the monolith `/addSell` proxy was stripping `productId` because its `SellDTO` lacked the field.

## Changes
- **monolith `web.dto.business.SellDTO`** — added `private Long productId;` (Lombok `@Data`), so productId-native sale
  lines pass through the `/addSell` (+ `/updateSell`) proxy to business-service instead of being dropped.
- **business `ItemDTO`** — added `productId`; **`ItemController.getUserItem`** populates it from a one-shot
  `ItemCatalogMapRepo.findAllScoped` itemId→productId map. Injected `ItemCatalogMapRepo` into `ItemController`.
- **business `ItemController.getUserItems`** (the picker HTML) — each `<option>` now carries `data-product=<productId>`.
- **monolith `business.js`** —
  - cart-push (`#sellItemDD`): sets `obj.productId` from the selected option's `data-product`.
  - edit-restore (load invoice for editing): rebuilt cart line keeps `productId: line.productId`.
  So real POS sales (new + edit) submit `productId`; `itemId` stays on the line as a back-compat fallback.

The saga already prefers `s.getProductId()` and falls back to `itemId` translation, so **itemId submissions still work**
(e.g. `flow.cy.js`) — this is additive.

## Verification
- **`sell.cy.js`** (Invoice Numbering) flipped back to **productId-native** (`sales:[{ productId, … }]`) — previously this
  exact payload failed with "Item 0 is not migrated" (productId stripped); now it flows through → `SUCCESS` + `INV-######`.

## Build + test (user)
- Rebuild **business-service** (ItemDTO/ItemController) **and** the **monolith** (SellDTO Java + `business.js` static —
  served from `target/classes`, so a rebuild is required for the JS to load).
- Cypress: `sell.cy.js` (productId-native invoice), `saga-sell-ui.cy.js` (UI cart sale now sends productId),
  `flow.cy.js` (itemId fallback still works), `sell-edit.cy.js` (edit keeps productId).

## Status
- [x] monolith SellDTO.productId · business ItemDTO.productId + getUserItem · getUserItems data-product · cart-push + edit-restore productId
- [x] sell.cy.js verification flipped to productId-native
- [ ] **Awaiting business + monolith rebuild + (user-run) Cypress**

## Next
- **M4c** purchase write productId-native (the picker already emits `data-product`; wire the purchase form + PurchaseDTO).
- **M4d** enrich `ProductRef` + batch `getProducts` + cache; rewire reads. **M4e** delete Item/ItemCatalogMap + endpoints + Item form + tables.
