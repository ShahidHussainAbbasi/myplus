# Slice 78 — M3c.2b/2c: remaining sell read paths productId-first

Finishes the **read** side of M3c.2 (after slice 77 did `getUserSell`). All sell readers now resolve the item via
`productId` (reverse catalog map) first, with the legacy `Stock.itemId` only as a fallback — so none of them *need*
the `Stock` FK any more (the `Stock` *display* blocks stay until M3c.4 drops the column).

## Changes (business-service)
- **`SellController.getSellInvoice`** (edit-load) — added the productId reverse-map + productId-first resolution.
  Bonus fix: pure-saga invoices (no `Stock`) now load **with** their item name (previously blank).
- **`SellController.getReceipt`** (SR report / receipt) — `sagaProductIds` filter + per-row resolution flipped to
  productId-first.
- **`BusinessDashboardController`** top-items widget — inject `ItemCatalogMapRepo`; aggregate each sell's quantity by
  its item resolved **productId-first** (reverse-map), Stock only as fallback.

No DB/contract change. Saga + backfilled + legacy rows all resolve identically.

## Tests (user-run)
- `sell.cy.js` + `flow.cy.js` (list + edit-load + receipt) and the dashboard/analytics spec — item names/amounts
  unchanged for saga sells (and any legacy rows).

## Status
- [x] getSellInvoice + getReceipt + dashboard productId-first
- [ ] **Awaiting business rebuild + (user-run) Cypress** sell + flow (+ dashboard)

## M3c.2 complete after this
All sell **reads** are off the `Stock` FK. Remaining: **M3c.3** (write paths — `updateSell` edit-delta, `saleReturn`,
`addSelling` — restock via inventory, not local `Stock`) → **M3c.4** (drop `Stock` entity + `Sell.stock`/`Purchase.stock`
columns + service/repo) → **M4** (retire `Item`/`ItemCatalogMap`).
