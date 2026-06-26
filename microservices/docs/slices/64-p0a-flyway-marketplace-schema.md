# Slice 64 — P0a: Flyway owns the marketplace schema (prod/validate-ready)

**Standards gap (from the audit):** prod runs `ddl-auto: validate` (`application-prod.yml`), so Hibernate will NOT
create schema — Flyway must. But marketplace's `V1__baseline.sql` is **empty**; every table (`orders` + slice-46→61
columns, `order_items`, `order_events`, `storefront_customer`) was created by `ddl-auto:update` in dev and exists in
**no Flyway script**. A fresh prod deploy would have **no marketplace schema** → validate fails. This slice makes
Flyway author the complete marketplace schema.

First of a per-service P0 series (marketplace is the worst gap — only an empty baseline).

## Change
- **marketplace-service** `db/migration/V2__commerce_schema.sql` — `CREATE TABLE IF NOT EXISTS` for `orders`,
  `order_items`, `order_events`, `storefront_customer`, matching the **live Hibernate-generated DDL** exactly (so
  `validate` accepts it). Idempotent: a no-op on the existing dev DB (tables already there); builds the full schema on
  a fresh/prod DB. (Follows the project's existing idempotent-migration convention.)

## Tests
- **Fresh-DB verification (run here, non-destructive):** create a throwaway DB, apply V1+V2, and diff its schema
  against the live `myplusdb_marketplace` — they must match (proves a prod deploy yields the validated schema).
- **Dev safety (Cypress):** after a marketplace rebuild, V2 no-ops on the existing dev DB → marketplace still starts →
  `storefront-account.cy.js` smoke stays green (the migration didn't disturb the running schema).

## Status
- [x] Design (this doc)
- [x] `V2__commerce_schema.sql` + **fresh-DB verification DONE (2026-06-27):** V1+V2 on a throwaway DB produced a
      schema **identical** to live `myplusdb_marketplace` (column name/type/nullable/key diff empty across all 4
      tables) → prod `validate` will pass. Idempotent (no-op on dev).
- [x] **Dev verified (2026-06-27):** marketplace started (Flyway V2 applied at boot, `success=1` in
      flyway_schema_history) + `storefront-account.cy.js` 2/2. Migration is safe on dev and complete for prod.

## Follow-ups (rest of the P0 series)
- Audit every other service's Flyway scripts vs live schema (business slices 33–40 tables, inventory `restockable`,
  the business `payment.method` INSURANCE enum from manual migration #4, etc.) and backfill so all are validate-ready.
- Optionally flip **dev** `ddl-auto: validate` per service once its Flyway is complete (catches drift in dev too).
