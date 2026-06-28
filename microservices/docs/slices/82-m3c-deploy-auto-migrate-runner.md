# Slice 82 — M3c: deploy-time auto-migrate (cross-service mapping) so an existing DB is fully reproducible

Completes deploy-reproducibility. The **V5 Flyway** script backfills `product_id` for items **already** catalog-mapped.
The piece Flyway can't do — mapping **not-yet-mapped** legacy items to catalog Products — is **cross-service** (it
creates `Product` rows in catalog-service's *own* DB through its import logic). So it's done as an **idempotent startup
runner**, not a Flyway script. Net: a fresh/prod deploy of a pre-convergence DB converges itself with **no manual step**.

## Why a runner, not Flyway
`/migrate-catalog` writes another service's DB (`myplusdb_catalog`) via `CatalogClient` (SKU-dedup etc.). A
business-service Flyway script can't do that without assuming co-located DBs + bypassing catalog's logic (breaks the
service boundary). A startup runner respects the boundary (uses `CatalogClient`) and still auto-runs on deploy.

## Changes (business-service, code-only)
- **`ItemRepo.findUnmappedOrgUser()`** — distinct `(org, user)` with items not in `item_catalog_map` (empty in the
  normal case).
- **`CatalogMigrationService.migrateAllUnmapped()`** — per tenant group, `runAs(user, org)` → `migrate(org, user)`
  (idempotent; **0 groups → no catalog call**, the common case). **`backfillAll()`** — all-tenant `product_id` backfill
  (re-runs after mapping, since V5 ran before the new maps existed). `SellRepo`/`PurchaseRepo.backfillAllProductIds()`.
- **`CatalogMigrationStartupRunner`** (`ApplicationRunner`, `@ConditionalOnProperty(convergence.auto-migrate-catalog,
  matchIfMissing=true)`) — daemon thread, **best-effort + retry** until catalog-service is reachable, **never blocks/fails
  startup**, idempotent. Disable with `convergence.auto-migrate-catalog=false`. Admin `/migrate-catalog` stays as the
  manual fallback.

## Deploy reproducibility (existing DB), end state
V4 (purchase columns) → V5 (backfill mapped items) → **runner** (map any unmapped legacy items + re-backfill) → all sells
render productId-only. No manual step.

## Tests
- Underlying `migrate` + backfill are covered by `CatalogBackfillTest`. The runner is best-effort deploy tooling —
  verified at boot via logs ("all items already mapped — nothing to do" on an already-converged DB; or the mapped/backfilled
  counts on a pre-convergence DB). No-op + safe on an empty/test DB (runs before any seed).

## Status
- [x] ItemRepo.findUnmappedOrgUser + migrateAllUnmapped + backfillAll + all-tenant backfill repo methods + startup runner
- [ ] **Awaiting business rebuild** — on boot, logs "all items already mapped — nothing to do" for your already-converged DB.
