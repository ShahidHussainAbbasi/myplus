# Slice 79 — M3c.3a: legacy sale returns restock inventory (not local `Stock`)

First write-path of M3c.3. `saleReturn` already sends **saga** returns through inventory (inverse saga by
`reservationId`). The **legacy** branch still did `localStock += qty`. After the M3c.1 backfill a legacy sell carries a
`productId` (but no reservation), so its return now restocks **inventory by product** (`importStock`, a fresh entry) —
keeping inventory authoritative and writing no local `Stock`.

## Change (business-service `SellController.saleReturn`)
Return routing is now:
1. **saga sell** (`productId` + `reservationId`) → `inventoryClient.returnStock(reservationId, …)` (inverse saga, exact batch). *(unchanged)*
2. **backfilled legacy sell** (`productId`, no reservation) → `inventoryClient.importStock([{productId, qty}])` — restock by product. *(new)*
3. **pre-backfill legacy** (no `productId`) → local `Stock += qty`. *(last-resort fallback only)*

No DB/contract change. Money-refund + line delete (below the restock) unchanged.

## Tests (user-run)
- `sell.cy.js` + `flow.cy.js` + the return spec (`commerce-gaps.cy.js` / `negative.cy.js`): a saga sale return restores
  inventory on-hand (unchanged); any legacy return now also restores inventory.

## Status
- [x] saleReturn legacy branch → inventory restock-by-product
- [ ] **Awaiting business rebuild + (user-run) Cypress** sell + flow + return spec

## Remaining M3c.3
- **M3c.3b** — `updateSell` (edit): replace the local-Stock `stock_id` delta with an **inventory per-product delta**
  (reserve/return the difference) **and preserve `productId`** on re-inserted lines. *Fixes a current latent bug: editing
  a saga sale today does no inventory adjustment and drops productId.* (Biggest/riskiest write path.)
- **M3c.3c** — `addSelling` (legacy bulk endpoint; appears unused by the UI) — neuter/remove. Then **M3c.4** drop `Stock`.
