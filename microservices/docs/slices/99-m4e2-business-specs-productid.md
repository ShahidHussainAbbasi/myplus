# Slice 99 — M4e.2 (business-only): business write-path specs go productId-native

Per the chosen scope ("business-only bridge removal"): make the **business** sell/purchase specs submit `productId`
(not `itemId`), proving the business write path no longer relies on the itemId→productId bridge. The saga itemId
fallback + `PurchaseService.ensureMapped` are **kept** (pharmacy + the bridge-test specs still use them), and
`Item`/`ItemCatalogMap` **stay** (no table drop — M4e.5 remains blocked on the pharmacy migration).

Production already prefers `productId` (M4b/M4c) and the UI sends it (M4e.1b), so this slice is **test-only**.

## Specs migrated to productId
- **`saga-sell.cy.js`** — sale submits `productId` (captured from `getUserItem`, which now carries productId).
- **`flow.cy.js`** — "Item to Stock" purchase + "Full Sale" sale submit `productId` (seeds capture it from `cy.seedProduct`).
- **`purchase-batch-prefill.cy.js`** — purchase + `getStockByBatch?productId=` (this spec exercises the M4e.1b backend
  change from itemId→productId; it would otherwise fail).
- **`purchase-self-describing.cy.js`** — purchase submits `productId` (the on-hand `getStock?itemId=` poll stays — that
  endpoint is still itemId-based).

## Kept as-is (still exercise the bridge — intentionally)
- `product-master-sync.cy.js` (tests Product→Item sync + saga sell via itemId).
- `purchase-inventory.cy.js` (tests `ensureMapped` auto-map of a legacy unmapped item).
- All pharmacy specs (itemId — the saga/ensureMapped fallback stays for them).

## Build + test (user)
- **No rebuild** — test-only (the productId production paths + `getStockByBatch(productId)` shipped in M4b/M4c/M4e.1b).
- Re-run: `saga-sell.cy.js`, `flow.cy.js`, `purchase-batch-prefill.cy.js`, `purchase-self-describing.cy.js`
  (+ confirm `product-master-sync.cy.js`, `purchase-inventory.cy.js` still green via the retained fallback).

## Status
- [x] business sell/purchase specs → productId; bridge-test + pharmacy specs kept on itemId
- [ ] **Awaiting (user-run) Cypress**

## M4e remaining (blocked on pharmacy)
M4e.3 (retire item/stock screens), M4e.4 (itemCount → Products), and M4e.5 (delete Item/ItemCatalogMap + drop tables)
remain — gated on migrating the **pharmacy domain** (pharma.js pickers + pharma-service prescriptions/dispense + pharmacy
specs) off itemId, which the user deferred. Business convergence is functionally complete.
