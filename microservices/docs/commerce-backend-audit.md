# Commerce backend audit — vs POS/retail + pharmacy industry standards (slice 33)

Done before the "UI follows the backend" redesign, so the UI is built on a **verified** base.
**Verdict: the architecture is genuinely industry-standard. It is not yet 100% complete — there are specific,
mostly-additive gaps (a few compliance-critical for pharmacy) to close first.**

---

## ✅ Solid — already at industry standard

| Area | What's there |
|---|---|
| **Decomposition** | catalog (product master) · inventory (stock) · trade (sell/purchase + saga) · pharma (clinical) — correct bounded contexts |
| **Inventory depth** | multi-batch `StockEntry` (batch_no, **lot_no, expiry_date**, warehouse, supplier, reserved_qty); `StockLevel` (reorder_point, min/max, cost); `StockAdjustment`, `StockTransfer`, `StockAlert`; `findExpiringScoped` (near-expiry query) |
| **FEFO allocation** | reservation allocator picks **earliest-expiry first**, null-expiry (non-perishable) last, stable by id |
| **Atomic sale (saga)** | reserve → confirm → release + **recovery relay** + **idempotency key** — correct cross-service transaction |
| **Money** | `BigDecimal(19,2)` everywhere (slice 23) — correct for currency |
| **Multi-tenancy** | org-scoping on every read/write (`findScoped` NULL-fallback) |
| **Invoicing** | per-org sequential invoice numbers (slice 22) |
| **Credit/receivables** | customer paid/due tracking on `CustomerHistory` |
| **Sale returns** | `/saleReturn` endpoint exists |
| **Pharmacy clinical model** | `Medicine` (generic/brand/strength/manufacturer/side-effects/contraindications/storage), `Prescription` (patient/doctor/license/validity/diagnosis/dispensed-by), `PrescriptionItem` (dosage/frequency/duration), `Dispensing`, `DrugInteraction`, hierarchical `DrugCategory` |

---

## ⚠️ Gaps to close (prioritised) — verified in code

| # | Gap | Why it matters | Where | Fix (small slice) |
|---|---|---|---|---|
| **G1** | **FEFO does NOT exclude expired batches** — `findForFefo` orders by expiry but has no `expiry_date >= today` filter, so a sale can allocate **already-expired** stock | **Pharmacy compliance — must never dispense expired medicine** | `StockEntryRepository.findForFefo` / `ReservationService.reserve` | add `(se.expiryDate IS NULL OR se.expiryDate >= :today)` to the FEFO query + reject if no non-expired stock |
| **G2** ✅ | **Returns are not saga-aware** — `saleReturn` reverts **local `Stock`** only; it does not release the reservation / add stock back to **inventory** | with the saga on, a return leaves inventory under-counted (stock drift) | `SellController.saleReturn` | **DONE (slice 34, awaiting build):** saga sells return via `InventoryClient.returnStock` → `POST /reservations/{id}/return`; inventory restores the sale's original batches (reservation picks, per-pick capped via `returnedQuantity`) + bumps `StockLevel`, fallback to a fresh batch; legacy sells keep the local-`Stock` path. See `docs/slices/34-commerce-gap-g2-returns-inverse-saga.md` |
| **G3** | **Tax is captured but never applied** — `Product.taxRate` + `ProductRef.taxRate` exist, but `SagaSellService` never computes/records tax on the sale or invoice | GST/sales-tax is mandatory for retail + pharmacy receipts | `SagaSellService` / `Sell` / `CustomerHistory` | compute tax from catalog `taxRate`, store tax per line + invoice total |
| **G4** | **No catalog price management; migrated products had no price** | saga prices from catalog → sells at 0 without a price | catalog-service | price-edit endpoint/UI; backfill from `Stock.bsell_rate` (done this session — script + Java fix) |
| **G5** | **No payment method** — nothing records cash/card/credit/wallet (or split) on a sale | standard POS requirement; pharmacy insurance/co-pay later | `CustomerHistory` | add `paymentMode` (+ optional split tenders) |
| **G6** | **Sell read-path was legacy-shaped** — `getUserSell`/JS read item+rate via the old `Stock` ref; saga sells (productId, no Stock) needed patches | this is exactly what the UI redesign fixes — make it productId-native | `getUserSell` + `business.js` | superseded by the UI-follows-backend redesign |

---

## ⬜ Pharmacy-specific to build (the vertical's own value — additive on the trade core)

- **Rebase `pharma-service`** onto catalog/inventory — today `Medicine` duplicates `Product` and `PharmacyStock` duplicates `StockEntry`; the clinical layer should *compose* catalog/inventory, not re-store them (decomposition Phase 7).
- **Dispense = sale + Rx reference** — wire `Prescription`/`Dispensing` to the saga sale (a dispense is a sale that also records the prescription + dispensed-by), not a parallel store.
- **Rx-required + controlled-substance** — per-product `rxRequired` / schedule flag; enforce a prescription (and log) when dispensing scheduled/narcotic drugs.
- **Drug-interaction check at dispense** — `DrugInteraction` exists; check the cart against the patient's items and warn.
- **Surface alerts** — near-expiry (`findExpiringScoped`) + low-stock (`StockAlert`/reorder_point) on the dashboard; show **batch + expiry** on the dispense screen (from the FEFO pick).

---

## Recommendation / order
The backend is a sound, industry-standard base — the gaps are completeness, not redesign. Suggested sequence (each a small, testable slice; **G1 first** because it's a compliance/safety issue):

1. **G1** expired-stock block (FEFO) — safety/compliance.
2. **G2** returns → inventory (inverse saga) — data integrity.
3. **G3** tax on the sale/invoice — legal requirement.
4. **G5** payment method — POS completeness.
5. **Then the UI redesign** (sell screen on catalog/inventory/saga, white-labelled pharmacy) on the now-verified base.
6. **Pharmacy vertical** — pharma rebase + Rx/dispense/interaction/controlled-substance (its own slice).
