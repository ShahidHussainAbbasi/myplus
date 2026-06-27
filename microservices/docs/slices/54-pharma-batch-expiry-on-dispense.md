# Slice 54 — P10: batch + expiry shown on the dispense screen

The FEFO reservation already picks by batch/expiry (and G1 blocks expired), but the pharmacist can't *see* which
batch/expiry they're about to dispense. This surfaces it: selecting a medicine on the (shared) sell/dispense screen
shows the **FEFO batch(es) + expiry** that the next sale/dispense will draw from. Compliance + safety; reuses the
existing FEFO query.

## Changes
- **commerce-contracts** — `StockBatch {productId, batchNo, expiryDate, available}` + `InventoryClient.getBatches(productId)`
  (`GET /stock/batches/{productId}`).
- **inventory-service** — `StockService.getFefoBatches` (from `findForFefo`, available = qty−reserved > 0, expired
  excluded by G1) + `StockController GET /stock/batches/{productId}` (org-scoped via CurrentUser).
- **business-service** — `StockDTO.batches` (FEFO-ordered); `getStock` (saga branch) attaches the inventory batches
  and sets `batchNo`/`bexpDate` to the FEFO-first batch (back-compat).
- **monolith** — `/getStock` passes the batches through (no change). `/addProductStock` extended to accept optional
  `batchNo` + `expiryDate` so a batch'd lot can be stocked (forwarded to inventory `/stock/import`).
- **UI** — `business.js loadStock` renders a `#sellBatchInfo` line ("FEFO: Batch B123 • Exp 2027-01-31 (+N more)")
  when the selected item has inventory batches; cleared otherwise. Visible on the dispense screen (and POS).

## Tests
- Cypress `pharmacy/dispense-batch.cy.js` (headed): register a Product (master-sync projects the Item) → stock a lot
  with batchNo+expiry → `/getStock?itemId=` returns `batches[0]` with that batch/expiry → on the dispense screen the
  batch/expiry line is shown.

## Status
- [x] Design (this doc)
- [x] contracts `StockBatch`+`getBatches`; inventory `StockService.getFefoBatches`+`/stock/batches/{id}`;
      business `StockDTO.batches`+`getStock` wiring; monolith `/addProductStock` batch/expiry; UI `#sellBatchInfo`+`renderSellBatches`
- [x] Cypress `pharmacy/dispense-batch.cy.js` authored
- [x] **Cypress green (headed, 2026-06-26): dispense-batch 2/2 + dispense 2/2 + catalog-product-stock 2/2 + product-master-sync 2/2 regression.**

## Deferred
- Per-batch dispense selection (choose a non-FEFO batch) — out of scope; FEFO is the policy.
- Batch/expiry on the POS purchase/stock screens (this slice is the sell/dispense read path).
