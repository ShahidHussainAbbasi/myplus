# Slice 66 — P0c: Flyway owns the business commerce schema

The biggest P0 backfill. business V1 baseline (91 legacy myplusdb tables) + V2 (org/money/invoice) predate the
commerce slices, so a fresh prod deploy (`ddl-auto: validate`) would be missing them. A full fresh-build-vs-live diff
found, missing from Flyway:
- **6 tables** — `payment`, `cashier_shift`, `cash_movement`, `tax_setting`, `parked_sale`, `item_catalog_map`
  (G5 payments/shift, G3 tax, park/hold, item↔product map). `payment.method` enum includes `INSURANCE` (was manual
  migration #4 — now in Flyway).
- **16 columns** — `customer_history` (saga + G3/G5: grand_total, sub_total, tax_total, tendered_amount,
  change_amount, payment_mode, reservation_id, saga_status, shift_id, idempotency_key), `sell` (product_id,
  tax_amount, tax_rate), `stock` (batch_purchase_discount, batch_sale_discount), `purchase` (updated).
- **4 stale baseline types** — `customer_history.change_amount`, `stock.batch_*_discount` (FLOAT→DECIMAL, slice 23),
  `purchase.updated` (DATE→DATETIME) realigned via idempotent `MODIFY`.

## Change
- **business-service** `db/migration/V3__commerce_slices.sql` — CREATE TABLE IF NOT EXISTS (6) + information_schema-
  guarded ADD COLUMN (16) + idempotent MODIFY (4 type fixes). Mirrors live Hibernate DDL so `validate` passes.

## Tests
- **Fresh-DB diff (run here):** V1+V2+V3 on a throwaway DB → the commerce table+column diff vs live is **empty**. ✅
- **Dev safety (Cypress):** after rebuild, V3 no-ops on dev → business starts → smoke `sell.cy.js` + `day-close.cy.js`
  + `insurance-copay.cy.js` + `park-hold.cy.js`.

## Status
- [x] Design (this doc)
- [x] V3 + **fresh-DB diff verified empty (2026-06-27)**
- [x] **Dev verified (2026-06-27):** V3 applied `success=1`; smoke sell 28/28 + day-close 3/3 + insurance-copay 2/2 + park-hold 3/3.

## P0 series status
marketplace (64) ✅ · inventory + pharma (65) ✅ · business (66, this). Remaining: education, welfare, agriculture,
auth, catalog, campaign, analytics, appointment — full-diff each (likely smaller drift) to finish validate-readiness.
