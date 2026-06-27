# Slice 76 — M3c.1: historical product_id backfill (foundation for retiring local `Stock`)

First step of M3c (delete local `Stock`). Before `Stock` can go, every historical **Stock-linked** sell + purchase
must carry a `product_id` (they currently identify their item only via the `Stock` FK). This slice stamps it —
**additive, nothing deleted, no behaviour change.**

## SaaS / industry-standard properties
- **Tenant-scoped** — only the caller's org rows (org match OR legacy `org IS NULL AND user` fallback); never touches
  another tenant's data.
- **Idempotent** — fills only `product_id IS NULL` rows; safe to re-run.
- **Role-gated** — `@PreAuthorize("hasAuthority('ADD_ITEM')")` (admin/ops action).
- **Observable** — returns `{sellsBackfilled, purchasesBackfilled, sellsRemaining, purchasesRemaining}`; `*Remaining`
  surfaces rows whose item still isn't mapped (run `/migrate-catalog` first → expect 0).
- **Reversible/safe** — additive only; legacy `stock_id` FK left intact.
- **Tested** — Testcontainers migration test (seed → backfill → assert + idempotent) + headed smoke.

## Changes (business-service + monolith)
- **`SellRepo` / `PurchaseRepo`** — `@Modifying` native backfill (`UPDATE … JOIN stock JOIN item_catalog_map SET
  product_id …`) + `countWithoutProductId` (coverage). `clearAutomatically` so callers re-read fresh values.
- **`CatalogMigrationService.backfillProductIds(org,user)`** → `BackfillResult` (counts).
- **`CatalogMigrationController POST /admin/backfill-product-ids`** (role-gated). Monolith proxy `POST /backfillProductIds`.
- No schema change (uses the `product_id` columns added in M3b / saga).

## Operating sequence (per tenant)
`/migrate-catalog` (map every item) → `/backfill-product-ids` (stamp product_id) → confirm `*Remaining == 0`. Then
M3c.2+ can move reads off `Stock` and eventually drop it.

## Tests
- **CatalogBackfillTest** (Testcontainers) — legacy Stock-linked sell+purchase → backfill stamps product_id, remaining
  0, re-run no-op.
- **Cypress `backfill-productids.cy.js`** (user-run) — endpoint runs, returns numeric counts, idempotent on re-run.

## Status
- [x] Design (this doc)
- [x] SellRepo/PurchaseRepo backfill+count + CatalogMigrationService.backfillProductIds + controller + monolith proxy
- [x] CatalogBackfillTest + backfill-productids.cy.js authored
- [ ] **Awaiting business + monolith rebuild + (user-run) Cypress** `backfill-productids.cy.js`

## Next in M3c
M3c.2 reads productId-first (Stock fallback) + legacy-return restock-by-product · M3c.3 stop remaining Stock writes ·
M3c.4 drop `Stock`. Then M4.
