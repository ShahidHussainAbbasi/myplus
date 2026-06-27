# Slice 65 — P0b: Flyway backfill for inventory + pharma

Continues the P0 (prod runs `ddl-auto: validate`; Flyway must own the schema). A **rigorous full-schema diff**
(fresh Flyway build vs live, per service) found the baselines were well behind — far more than the obvious columns:
- **inventory** (`V4`): whole tables `reservations`, `reservation_picks`, `stock_levels` (saga, slice 33) +
  `stock_entries.reserved_quantity` (slice 33) + `stock_entries.restockable` (slice 55) were never in Flyway.
- **pharma** (`V2`): whole tables `medicine_profile` + `medicine_clinical` + itemId/productId/org columns on
  `dispensing`, `drug_interactions`, `prescription_items`, `prescriptions` (the slice 41–45 rebase).
A fresh prod deploy would have been missing all of this → validate failures.

## Changes
- **inventory-service** `db/migration/V4__restockable.sql` — `CREATE TABLE IF NOT EXISTS` for the 3 saga tables +
  guarded `ADD COLUMN` for the 2 stock_entries columns (idempotent).
- **pharma-service** `db/migration/V2__pharma_rebase.sql` — `CREATE TABLE IF NOT EXISTS` for medicine_profile +
  medicine_clinical + 16 guarded `ADD COLUMN`s across the 4 rebased tables (idempotent).

## Tests
- **Fresh-DB full-diff (run here, non-destructive):** apply each full Flyway chain to a throwaway DB; the complete
  table+column diff vs live is **empty** for both → prod `validate` passes. ✅
- **Dev safety (Cypress):** after rebuild, migrations no-op on dev → services start → smoke:
  `pharmacy/quarantine-return.cy.js` (inventory) + `pharmacy/safety.cy.js` + `pharmacy/dispense.cy.js` (pharma).

## Status
- [x] Design (this doc)
- [x] inventory `V4` + pharma `V2` + **fresh-DB full-diff verified empty for both (2026-06-27)**
- [x] **Dev verified (2026-06-27):** inventory V4 + pharma V2 applied `success=1` (flyway_schema_history); smoke
      quarantine-return 1/1 + safety 3/3 + dispense 2/2.

## Remaining in the P0 series
- **business-service** (biggest): 6 commerce tables (payment, cashier_shift, cash_movement, tax_setting, parked_sale,
  item_catalog_map) + the INSURANCE enum (manual migration #4) + verify saga columns — next slice, via fresh-DB diff.
