# Slice 75 — M3b: purchase off local `Stock` (self-describing purchase)

Stop the **last writer** of local `Stock` (purchases). Today `PurchaseService.addPurchase` creates a `Stock` row and
links it (`purchase.stock`), and `getUserPurchase` reads batch/rate/item **only** from that FK (it even *skips*
stock-less purchases). So a purchase's batch/rate/identity live entirely on `Stock`. M3b makes the **purchase row
self-describing** (carries its own batch/rate snapshot, like an order line) so it no longer needs a `Stock` row;
inventory stays authoritative for on-hand (already dual-written, M3.2). New sells are already off `Stock` (saga →
`productId`), so after this **no new local `Stock` rows are written**. The `Stock` table stays as a **read-only legacy
shell** for historical sells/purchases (not deleted — that needs a data backfill = M3c, out of scope).

## Changes (business-service)
- **`Purchase` entity** — add own snapshot fields (previously only on the `Stock` FK):
  `item_id, product_id, batch_no, bpurchase_rate, bsell_rate, bpurchase_discount, bsell_discount,
  bpurchase_discount_type, bsell_discount_type, bexp_date`. Keep the nullable `stock` FK for legacy rows.
- **`addPurchase`** — populate those fields from the DTO (reuse the existing ModelMapper date/number converters via an
  in-memory `Stock` map, **not persisted**); set `product_id` via `ensureMapped`; **drop** `stockService.updateStock`
  + `setStock(stock)`. `pushPurchaseToInventory` now reads batch/rate from the `Purchase` (no `Stock` entity).
- **`getUserPurchase`** — render from the `Purchase`'s own fields when there's no `Stock` (new rows); keep the
  `Stock`-FK path for legacy rows; **stop skipping** stock-less purchases.
- **V4 migration** (business / myplusdb) — idempotent guarded `ADD COLUMN` for the 10 fields.

## Not in scope
- `Stock` entity/table is **kept** (legacy reads). Physical delete + historical `productId` backfill = **M3c**.
- `StockController` manual add-stock + `getItemBatchScoped`/`findByBatchScoped` orphans = swept with M3c.
- **M4** (retire `Item`) — recommend never (bridge is harmless).

## Tests
- **PurchaseStockInTest** (pure Mockito) — updated to the new `pushPurchaseToInventory(purchase, dto, user)` signature
  (reads batch/rate from the Purchase).
- **Cypress `purchase-self-describing.cy.js`** (user-run) — purchase a batch → it appears in `/getUserPurchase` with
  batch/rate (rendered from the Purchase, no `Stock` row) → inventory on-hand reflects it. Plus the existing
  `purchase-inventory.cy.js` must stay green (inventory authoritative).

## Risk
Medium — touches the purchase write + list rendering (core till). No user-facing change intended; gated by purchase
Cypress + the microservice tests. Additive migration (no data loss; legacy rows keep their `stock` FK).

## Status
- [x] Design (this doc)
- [x] Purchase entity (+10 self-describing fields) + **V4 migration** (verified: 10 cols added, correct types,
      idempotent on the live purchase schema) + addPurchase (no Stock write; in-memory snapshot map) +
      pushPurchaseToInventory(Purchase,dto,user) reads the Purchase + getUserPurchase renders from Purchase/legacy-Stock,
      no skip + PurchaseStockInTest updated. `updateStock(PurchaseDTO)` now dead (harmless legacy).
- [x] purchase-self-describing.cy.js authored
- [ ] **Awaiting business-service rebuild (V4) + (user-run) Cypress** `purchase-self-describing.cy.js` +
      `purchase-inventory.cy.js` (must stay green). Build = **business-service only** (no contract/monolith change).
