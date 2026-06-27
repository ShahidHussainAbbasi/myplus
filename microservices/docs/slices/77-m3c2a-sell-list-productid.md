# Slice 77 — M3c.2a: sell list renders productId-first

First read-path of M3c.2. `getUserSell` (the sells list — an always-run reader) resolved the item name from
`sell.stock.itemId` for legacy rows. After the M3c.1 backfill every sell carries `product_id`, so this now resolves
**productId-first** via the reverse catalog map; the local `Stock.itemId` path is only a fallback for any
not-yet-backfilled row. The `Stock` *display* block (batch info) is untouched — it's removed at M3c.4 when the FK goes.

## Change (business-service `SellController.getUserSell`)
- `itemIds` (legacy fallback) collected only for `productId == null` rows.
- `sagaProductIds` collected for **all** rows with a `productId` (was `stock == null` only).
- Per-row: `item = productId != null ? itemByProductId.get(productId) : itemsById.get(stock.itemId)`.

No DB/contract change; saga + backfilled + legacy rows all render identically.

## Tests
- **Cypress** (user-run): `sell.cy.js` + `flow.cy.js` — the sells list still shows the right item names/amounts for
  saga sells (and any legacy rows). No new spec; this is covered by the existing list assertions.

## Status
- [x] Design + `getUserSell` productId-first
- [ ] **Awaiting business rebuild + (user-run) Cypress** `sell.cy.js`, `flow.cy.js`

## Remaining M3c.2 read paths (next sub-steps, same pattern)
- M3c.2b: `getSellInvoice` (edit-load — needs the saga reverse-map *added*) + the SR report renderer.
- M3c.2c: dashboard top-items (inject `ItemCatalogMapRepo`, aggregate productId-first).
Then **M3c.3** (write paths: `updateSell` edit-delta, `saleReturn`, `addSelling`) → **M3c.4** (drop `Stock`) → **M4**.
