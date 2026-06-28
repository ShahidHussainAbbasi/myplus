# Slice 80 — M3c.3b: edit-invoice adjusts inventory (not local `Stock`) + keeps productId

The riskiest write path. `updateSell` (invoice edit) adjusted **local `Stock`** by `stock_id` delta and re-inserted lines
from the `Stock` row. Since edits route here for *all* sells (main.js) but `SagaSellService` has no update path, editing a
**saga** sale did **no inventory adjustment and dropped `productId`** (latent bug). M3c.3b makes the edit
**inventory-correct and `Stock`-free**.

## Change (business-service `SellController.updateSell`)
- **Inventory delta by product** (was local `Stock` by `stock_id`): `delta[productId] = Σ old qty − Σ new qty`.
  - `delta > 0` (sold less now) → **return excess** to inventory (`importStock`).
  - `delta < 0` (sold more now) → **take more** via one `reserve` + `confirm`; **reject the edit** (out of stock) if the
    reservation isn't granted — atomic across products (single reservation).
- **Re-inserted lines** carry `productId` (`productIdOfLine`: line's own, else mapped from itemId) + a `sellRate` derived
  from the line `total/qty`; **no `Stock` FK** written. Money (total/net/tax) comes from the DTO.
- Customer/header update + `recomputeDue` unchanged.

`productId` is resolved line's-own-first, falling back to `stock.itemId`→map for any not-yet-backfilled row (a read only).

## Why this is correct + standard
- Inventory becomes the single source even on edits (multi-tenant identity forwarded on the inventory calls).
- Over-selling on edit is now rejected (reserve gate), matching new-sale behaviour.
- Fixes the saga-edit bug (previously: no inventory change + lost product identity).

## Tests (user-run)
- `sell.cy.js` + `flow.cy.js` (+ any edit/negative spec): edit an invoice's quantities up and down → totals correct,
  inventory on-hand moves by the delta, line item names preserved; an over-quantity edit is rejected.

## Status
- [x] updateSell inventory-delta + productId-preserving (+ `productIdOfLine` helper)
- [ ] **Awaiting business rebuild + (user-run) Cypress** sell + flow

## Remaining
- **M3c.3c** folds into M3c.4: `addSelling` is **dead** (no UI caller) — remove with the `Stock` teardown.
- **M3c.4** drop `Stock` (entity + `Sell.stock`/`Purchase.stock` FK columns + `StockService`/`StockRepo` + dead
  `addSelling`/manual add-stock + the leftover `stock` display blocks) — full regression. Then **M4**.
