# Slice 101 — M4e.a: remove the itemId write-path bridge (now dead)

With business POS + pharmacy both productId-native (M4e.1/.2 + M5), no caller submits `itemId` on the write path, so
the itemId→productId translation is dead code. Removed it.

## Changes (business-service)
- **`SagaSellService.addSell`** — requires `s.getProductId()` (throws if null); removed the
  `itemCatalogMapRepo.findProductIdByItemId(itemId)` fallback + the `ItemCatalogMapRepo` field/import.
- **`PurchaseService.addPurchase`** — `productId = dto.getProductId()` directly; removed the
  `catalogMigrationService.ensureMapped(itemId)` fallback + the `CatalogMigrationService` and `ItemCatalogMapRepo`
  fields.

## Specs retired (obsolete — they tested the removed bridge)
- **`product-master-sync.cy.js`** (saga sell via itemId) — deleted.
- **`purchase-inventory.cy.js`** (`ensureMapped` legacy-unmapped-item auto-map) — deleted.

## Build + test (user)
- Rebuild **business-service**.
- Cypress: `sell`, `sell-edit`, `purchase`, `flow`, `saga-sell`, `saga-sell-ui`, + pharmacy — all productId-native.

## Status
- [x] saga + purchase fallbacks removed; bridge-test specs retired
- [ ] **Awaiting business rebuild + (user-run) Cypress**

## M4e remaining
- **M4e.b** retire item/stock screens + Item form + `/addItem`/`/addStock` + `ProductSyncService`/`syncProductItem`.
- **M4e.c** `itemCount` → count catalog Products.
- **M4e.d** delete `Item`/`ItemCatalogMap`/`CatalogMigrationService`/`ItemRepo` + destructive Flyway dropping
  `item` + `item_catalog_map` (the final irreversible step — walk through before running).
