# Slice 73 — M3 "stock → inventory-only": read-only audit (no code changed)

Purpose: size the parked **M3-stock** tech-debt (retire business-service's local `Stock`; make inventory the single
stock store) precisely, since the codebase moved through slices 62–72. **This is an audit only — nothing was changed.**

## What already happened (M3.1 / M3.2)
- **M3.1 (slice 62):** the Stock *list* (`getUserStock`) overlays inventory on-hand.
- **M3.2 (slice 63):** purchases auto-map + dual-write inventory (`PurchaseService.pushPurchaseToInventory`).
- So inventory is already the **de-facto** source; local `Stock` is now parallel dead-weight (+ drift risk).

## Local-`Stock` WRITES still happening
| # | Site | Trigger | Status |
|---|---|---|---|
| W1 | `StockService.updateStock(PurchaseDTO)` ← `PurchaseService.addPurchase` | every purchase | **redundant** — purchase already dual-writes inventory (M3.2) |
| W2 | `StockService.updateStock(Sell)` ← `SellService` | every (non-saga) sell line | **redundant** — saga confirm decrements inventory |
| W3 | `SellController.saleReturn` legacy branch (`stock += qty`) | return of a **non-saga** sell | G2 already routes saga sells to inventory; this is the legacy tail |
| W4 | `StockController` add-stock (`new Stock()`) | manual stock entry | superseded by inventory stock-in |

## Local-`Stock` READS still live
| # | Site | Used by | Risk to move |
|---|---|---|---|
| R1 | `StockController.getStockByBatch` | **purchase screen batch dropdown** (`businessDashboard.html:722`) | **medium — live UI** |
| R2 | `StockController.getBatchesByItem` | callers commented out in `main.js`/`business.js` | **low — effectively dead** |
| R3 | `getUserStock` row metadata (batch/expiry) | Stock list | low — list already shows inventory on-hand |
| R4 | `SellController` sell-line mapping `item.getStock()` | sell processing | medium — touches the till |

## Conclusion (confirms the parked rationale)
The blocker is exactly as flagged: **reads must move to inventory before writes can stop.** The only *live* local-Stock
read is `getStockByBatch` (purchase screen); the sell batch path (R4) and the list metadata (R3) also read it. None of
this is missing capability — it's drift-risk cleanup with **no user-facing value**, on the **core till**.

## Sequenced plan (each = its own build + headed Cypress gate)
1. **M3a — batch reads → inventory.** Reimplement `getStockByBatch` (and revive/repoint or delete the dead
   `getBatchesByItem`) to read inventory `StockEntry` batches by productId. Gate: purchase Cypress. *(smallest; unblocks the rest)*
2. **M3b — stop local-Stock writes.** Drop W1/W2 (`updateStock` calls) once nothing reads what they maintain;
   inventory becomes the sole writer. Gate: sell + purchase + stock Cypress.
3. **M3c — retire legacy return + delete `Stock`.** Route all returns to inventory (W3), then delete
   `Stock`/`StockService`/`StockRepo`/`updateStock`/`IStockService`. Gate: full regression (sell/purchase/return/stock).

**M4** (retire `Item`/`ItemCatalogMap`) stays **obviated** by master-sync — not in scope.

## Recommendation
Do **M3a first** (smallest, de-risks the rest) only when there's appetite for core-till changes; otherwise stay parked
— it blocks nothing. Each sub-slice is test-first and reversible. Estimated 3 sub-slices, medium risk concentrated in
the purchase + sell screens.
