# Slice 102 — M4e.b: retire the legacy Item/Stock write screens

The item/stock *creation* path is retired now that everything is productId-native. The catalog Product form is the only
way to register a product (it auto-projects a bridged Item via `syncProductItem`, kept until M4e.d). Reads
(`getUserItem(s)`, `getUserStock`) + `ProductSyncService` stay until the entity delete (M4e.d).

## Changes
- **business `ItemController.addItem`** + **`StockController.addStock`** — neutered to deprecation stubs (return an ERROR
  message; route kept resolvable). `syncProductItem` (Product→Item projection) is untouched — `cy.seedProduct` relies on it.
- **monolith `businessDashboard.html`** — removed the `#registrationType` "Item" option + the "Legacy Items (edit)" nav
  item, so the Item form is unreachable.

## Specs
- **Deleted** (fully tested the retired form/endpoints): `item.cy.js`, `stock.cy.js`, `registration-product-path.cy.js`.
- **Trimmed**: removed the "selecting Item shows itemDiv" test from `pages/businessDashboard.cy.js` and the
  "Register > Item shows itemDiv" test from `pages/navigation.cy.js` (the nav entry is gone).
- **Left as-is** (still pass): `negative.cy.js` (its addItem/addStock cases assert "returns error, not 500" — the stubs
  do exactly that) and `flow.cy.js` (its addStock block uses `failOnStatusCode:false` + lenient asserts).

## Build + test (user)
- Rebuild **business-service** (ItemController/StockController) **and the monolith** (`businessDashboard.html` static).
- Cypress: `pages/businessDashboard.cy.js`, `pages/navigation.cy.js`, `negative.cy.js`, `flow.cy.js`, `sell.cy.js`.

## Status
- [x] addItem/addStock neutered · Item form nav removed · obsolete specs deleted/trimmed
- [ ] **Awaiting business + monolith rebuild + (user-run) Cypress**

## M4e remaining
- **M4e.c** `itemCount` → count catalog Products.
- **M4e.d** move `cy.seedProduct` off itemId + delete `Item`/`ItemCatalogMap`/`ProductSyncService`/`CatalogMigrationService`/`ItemRepo`
  + `getUserItem(s)`/`getUserStock` item screens + destructive Flyway dropping `item` + `item_catalog_map` (walk through before running).
