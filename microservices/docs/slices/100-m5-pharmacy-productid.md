# Slice 100 — M5: migrate the pharmacy domain off itemId → catalog productId

Unblocks the final M4e teardown. Pharmacy was built on the business `itemId` bridge; this migrates the whole domain to
the catalog `productId` (single Product master), so `Item`/`ItemCatalogMap` can be deleted (M4e.3–.5).

## pharma-service (model → productId)
- **Entities**: `PrescriptionItem`, `Dispensing`, `MedicineClinical` (`item_id`→`product_id`), `DrugInteraction`
  (`item_id1/2`→`product_id1/2`); `MedicineClinical` unique constraint column fixed to `product_id`.
- **DTOs**: `PrescriptionItemDTO`, `ClinicalDTO`, `ControlledDispenseDTO`, `DispenseRequest.Line`, `InteractionDTO`,
  `SafetyReportDTO` — `itemId*`→`productId*`.
- **Services/repos**: `PrescriptionService`, `DispenseService`, `SafetyService`, `PrescriptionItemRepository`,
  `MedicineClinicalRepository`, `DrugInteractionRepository` — call-sites + JPQL renamed.
- **`SafetyController`**: reads `productIds` (accepts legacy `itemIds` too during cutover).
- **`V3__pharma_itemid_to_productid.sql`** — idempotent `RENAME COLUMN` for all pharma tables (Hibernate `validate`).

## monolith
- `PharmaPrescriptionController`/`PharmaSafetyController` are raw pass-through (`Map`) — **no change**.
- **`pharma.js`**: the medicine pickers list **catalog Products** (`/catalogProducts`, value=productId) via a shared
  `loadMedicineOptions`; all submissions (`addRxItem`, `saveClinical`, `addInteraction`, `checkSafety`,
  `dispensePrescription`) send `productId*`.

## specs → productId
`prescription`, `dispense`, `alerts`, `safety`, `insurance-copay`, `quarantine-register`, `quarantine-return`,
`dispense-batch` (loadStock/productStock by productId).

## Data note
`RENAME COLUMN` preserves values — existing dev pharma rows keep old itemId numbers in the `product_id` columns (wrong,
since itemId≠productId). Pharmacy is pre-production; **re-seed clinical/prescription/interaction data** via the
productId-native screens (the specs create fresh data, so they pass).

## Build + test (user)
- Rebuild **pharma-service** (V3 migration runs on restart) **and the monolith** (`pharma.js` static).
- Cypress (pharmacy): `prescription`, `dispense`, `dispense-batch`, `alerts`, `safety`, `insurance-copay`,
  `quarantine-register`, `quarantine-return`. Business POS unaffected.

## Status
- [x] pharma-service model + V3 · pharma.js pickers/submits · pharmacy specs → productId
- [ ] **Awaiting pharma-service + monolith rebuild + (user-run) pharmacy Cypress**

## Next — M4e.3/.4/.5 now unblocked
With pharmacy on productId, the itemId bridge is dead: retire the item/stock screens (M4e.3), `itemCount`→Products
(M4e.4), delete `Item`/`ItemCatalogMap`/`ProductSyncService`/`CatalogMigrationService`/`ItemRepo` + the write-path
fallbacks + drop the tables (M4e.5, destructive).
