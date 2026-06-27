# Slice 67 — P0d: Flyway backfill for auth + catalog (P0 series complete)

Finishes the P0 (prod runs `ddl-auto: validate`; Flyway must own every service's schema). A full fresh-build-vs-live
diff was run across **all** remaining services. Result:

- **Already clean (no work):** education, welfare, agriculture, campaign, analytics — their V1/V2 baselines already
  match live (fresh-DB diff empty). The audit confirming this is the deliverable; no migration needed.
- **auth** — one column missing from Flyway: `users.demo bit(1)` (slice-32 signup flag; added in dev by ddl-auto).
- **catalog** — had **no Flyway at all** (no `flyway-core`/`flyway-mysql` dep, no `db/migration`). Its `products` +
  `categories` tables existed only via dev `ddl-auto: update`, so a fresh prod deploy under `validate` would have had
  no schema. This was the largest remaining gap.

## Changes
- **auth-service** `db/migration/V3__user_demo_flag.sql` — information_schema-guarded `ADD COLUMN users.demo bit(1)
  NOT NULL DEFAULT b'0'` (idempotent; legacy users default to non-demo).
- **catalog-service**:
  - `pom.xml` — add `flyway-core` + `flyway-mysql` (matching peers).
  - `db/migration/V1__baseline.sql` — `CREATE TABLE IF NOT EXISTS` for `categories` then `products` (self-FK on
    `categories.parent_id`, `products.category_id` → `categories`), DDL mirroring live so `validate` accepts it.

## Tests
- **Fresh-DB full-diff (run here, non-destructive):** apply each full Flyway chain to a throwaway DB; the complete
  table+column diff vs live is **empty** for both auth and catalog. ✅
- **Idempotency (run here):** apply only the new migration (auth V3 / catalog V1) against a clone of the live schema
  → clean no-op (no errors) for both, so the dev re-run is safe. ✅
- **Dev safety:** after rebuild, auth V3 + catalog V1 no-op on dev → both services start, Flyway records the new
  version `success=1`. Smoke: catalog product list/search + a storefront browse; auth login.

## Status
- [x] Design (this doc)
- [x] auth `V3` + catalog Flyway bootstrap + **fresh-DB diff verified empty + idempotent no-op verified (2026-06-27)**
- [x] **Dev verified (2026-06-27):** auth V3 `success=1` + `users.demo` present; catalog auto-baselined V1 on the
      existing dev DB (no-op, the project pattern — V1 runs on a fresh/empty prod DB). Smoke headed: auth/login 9/9 +
      catalog-product 2/2 + storefront-search 2/2 = 13/13.

## P0 series — COMPLETE (pending the auth+catalog rebuild)
marketplace (64) ✅ · inventory + pharma (65) ✅ · business (66) ✅ · auth + catalog (67, this) · and education,
welfare, agriculture, campaign, analytics audited clean. **All 12 microservices are now Flyway-owned and
`ddl-auto: validate`-ready.** Follow-up (separate slice): flip each service's dev `ddl-auto: update` → `validate` now
that Flyway is authoritative.
