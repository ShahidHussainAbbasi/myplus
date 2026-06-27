# Slice 41 — Pharmacy P0: rebase pharma-service onto catalog/inventory (compose, don't duplicate)

> 🔄 **RESUMED on the itemId bridge (2026-06-24).** Convergence (Item→Product, slice 42) is **paused tech-debt**;
> the saga already bridges itemId→Product, so pharmacy builds on `itemId` now and reuses POS screens. The
> catalog-medicine model + parallel Medicine screen (P0b) were deleted as reinvention. Medicine registration =
> the existing Item screen (relabeled "Medicine"). pharma-service mesh standup (P0a) stays.
>
> **P5 prescription intake DONE + Cypress-green (headed, 2026-06-24): `pharmacy/prescription.cy.js` 2/2** —
> records a prescription referencing an existing Item (`itemId`) + PHARMA-only Pharmacy nav/Prescription panel render.
> Next: **P6 dispense = reuse the Sell screen + Rx link.**

Phase 2 (pharmacy) foundation. Decision (confirmed): **a medicine = a catalog `Product` + a pharma
`MedicineProfile` (clinical extension, keyed by `productId`); pharmacy stock = inventory `StockEntry`; dispense =
the shared trade saga sale + a `Prescription` reference.** This lets pharmacy reuse everything Phase 1 built
(FEFO/expiry, tax, payment, receipt, day-close) instead of re-storing products/stock.

## Current state (grounded)
- `pharma-service` is a **standalone scaffold**: entities (`Medicine`, `PharmacyStock`, `Prescription`,
  `PrescriptionItem`, `Dispensing`, `DrugCategory`, `DrugInteraction`) + repos + `SecurityConfig` only. **No
  controllers/services.** Deps: `common-web` + JPA/security/validation/actuator. **Missing:** `commerce-contracts`
  (CatalogClient/InventoryClient/ProductRef), `common-security` (CurrentUser/AuthenticatedUser), spring-cloud
  (eureka/config), gateway route, org-scoping.
- `Medicine` duplicates catalog `Product`; `PharmacyStock` duplicates inventory `StockEntry`.

## Target
- **`MedicineProfile`** (pharma, org-scoped, keyed by `productId`): genericName, brandName, manufacturer,
  `drugCategoryId`, form, strength, unit, **rxRequired**, **controlledSubstance**, description, sideEffects,
  contraindications, storageConditions, isActive. (The sellable identity — name, SKU, price, taxRate — lives on
  catalog `Product`.)
- **Delete** `Medicine` + `PharmacyStock` (duplicates). Re-point `PrescriptionItem`/`Dispensing` from `Medicine`
  → `productId`.
- pharma-service **composes** the core: `CatalogClient` (product master/price) + `InventoryClient` (stock/FEFO).
  "Create medicine" = create a catalog `Product` (+ taxRate) then a `MedicineProfile`; "add pharmacy stock" =
  inventory `addStock`/import.

## Sub-steps (each its own build + headed Cypress gate)
- **P0a — mesh + infra:** add `commerce-contracts`, `common-security`, spring-cloud (eureka client + config) to
  pharma pom; `TradeClientsConfig`-style lb clients (catalog/inventory); gateway route `/api/pharma/**`; register
  with eureka; `application.yml` (DB `myplusdb_pharma`, ddl-auto). _Foundation only — health + a ping endpoint._
- **P0b — MedicineProfile:** entity + repo (org-scoped findScoped) + `MedicineService` (create = catalog Product +
  profile; list/get/update) + `MedicineController` (`/api/pharma/medicines`) + monolith proxy. Delete `Medicine`.
- **P0c — stock + clinical re-point:** pharmacy stock via `InventoryClient`; re-point `PrescriptionItem`/
  `Dispensing` to `productId`; delete `PharmacyStock`. Keep `DrugCategory`/`DrugInteraction`/`Prescription`.

(Rx intake, dispense-as-saga-sale, and safety — rxRequired/controlled enforcement, interaction check, quarantine
returns — are the **next** slices P5–P8, on top of this rebase.)

## Multi-tenancy / security
Org-scoping standard (per `ARCHITECTURE-MULTITENANCY.md`): `organization_id` + `findScoped` NULL-fallback, identity
from the gateway via `common-security` CurrentUser. `demo.pharma@myplus.com` already seeded (auth SetupDataLoader);
PHARMA users already land on the shared dashboard (slice 36).

## Tests
- pharma-service: `MedicineServiceTest` (compose: create Product+profile; org-scoping) — Testcontainers + mocked
  CatalogClient.
- Cypress (headed): as `demo.pharma`, create a medicine → it exists via the API; the PHARMA dashboard shows
  "Medicine" wording (already green in vertical-profile.cy.js).

## Risks
- Standing up a new mesh service (eureka/config/gateway/clients) is the bulk of P0a — verify health + registration
  before P0b.
- Deleting `Medicine`/`PharmacyStock` must follow re-pointing their references (`PrescriptionItem`, `Dispensing`,
  repos) or the build breaks — do it in P0c after the new model is in place.

## Status
- [x] Decision (compose) + design (this doc)
- [x] **P0a** — pharma pom += `commerce-contracts`; `PharmaClientsConfig` (lb Catalog/Inventory clients +
  identity forwarding); `PharmaPingController` (`/api/pharma/ping`); fixed SecurityConfig public matcher to the
  post-StripPrefix path (`/public/**`). NOTE: gateway route `/api/pharma/**`, eureka, config, `myplusdb_pharma`,
  bootstrap.yml were **already present** — no infra change needed. _Awaiting build + restart + verify._
- [x] **P0b (additive)** — `MedicineProfile` (clinical, keyed by productId, org-scoped) + repo; `MedicineService`
  (create = `CatalogClient.importProducts` → productId, then store profile; list/get/update; org/user params for
  testability) + `MedicineController` (`/medicines`); monolith `PharmaRestClient` + `PharmaMedicineController`
  (`/getMedicines`,`/getMedicine`,`/addMedicine`); `MedicineServiceTest` (Testcontainers + mocked CatalogClient);
  Cypress `pharmacy/medicine.cy.js`. _Additive — `Medicine`/`PharmacyStock` still present, removed in P0c._
  **Cypress GREEN (headed, 2026-06-23): 2/2** — create composes Product+profile (productId + rxRequired), lists back.
- [x] **P0c** — re-pointed `PrescriptionItem`/`Dispensing`/`DrugInteraction` from the `Medicine` relation to a
  `productId` (+ `medicineName` snapshot); **deleted** `Medicine`/`PharmacyStock` + their repos (the nested `Form`
  enum went with `Medicine`); `PrescriptionItemRepository.findByMedicineId` → `findByProductId`. Pharmacy stock is
  inventory `StockEntry` (via `InventoryClient`, used when dispense/stock screens land in P5+). Duplication GONE.
  _Awaiting build; existing `pharmacy/medicine.cy.js` is the regression gate._
  > ddl-auto:update won't DROP the old `medicine`/`pharmacy_stock` tables or the orphan `medicine_id` columns —
  > harmless leftovers; a drop migration is a later cleanup.
- [x] Cypress green (headed) — P0a verified (eureka/health), P0b medicine.cy.js 2/2; P0c gated on next build+rerun
