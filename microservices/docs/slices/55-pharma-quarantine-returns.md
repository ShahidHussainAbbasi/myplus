# Slice 55 — P11: pharmacy returns go to quarantine (not back on the shelf)

Returned medicines must **not** be re-dispensed (regulatory): a pharmacy sale return should put stock into
**quarantine** (`restockable=false`), not back into sellable on-hand. Layered on the existing G2 inverse-saga seam
(`ReservationService.returnPicks` / `createReturnEntry`) — retail/e-commerce returns still restock as before.

## Changes
- **inventory** — `StockEntry.restockable` (Boolean; null/true = sellable, false = quarantined). `findForFefo` +
  `availableByOrg` exclude `restockable=false` (quarantined stock is never allocated or shown available).
  `ReservationService.returnPicks(quarantine)`: when quarantine, returned units go to a **quarantine StockEntry**
  (restockable=false, lot/expiry kept for traceability) and **StockLevel is NOT bumped** (sellable on-hand
  unchanged); pick `returnedQuantity` is still tracked (over-return capping). Non-quarantine = existing restock.
- **commerce-contracts** — `StockReturnRequest.quarantine` (default false; 1-arg constructor kept so existing
  callers — marketplace cancel, retail return — are unchanged/restock).
- **business-service** — `SellController.saleReturn` reads a `quarantine` param and sets it on the return request.
- **monolith** — `saleReturn` proxy forwards `quarantine`; the Sale-Return dialog shows a "Quarantine (do not
  restock)" checkbox, **defaulted on for the pharmacy vertical** (`window.MODULE==='PHARMA'`).

## Tests
- `ReservationServiceTest` (quarantine): a quarantine return creates a non-restockable entry and does not raise
  sellable on-hand; FEFO ignores it.
- Cypress `pharmacy/quarantine-return.cy.js` (headed): stock 20 → saga sale 5 (on-hand 15) → return 3 **quarantine**
  → on-hand still **15** (not 18); a retail (non-quarantine) return of the same shape would restock to 18.

## Status
- [x] Design (this doc)
- [x] inventory `StockEntry.restockable` + FEFO/availability exclusion + `returnPicks(quarantine)`; contracts
      `StockReturnRequest.quarantine` (1-arg ctor kept); business `saleReturn` flag; monolith proxy forwards;
      return-dialog quarantine checkbox (pharma default); `ReservationServiceTest` quarantine case
- [x] Cypress `pharmacy/quarantine-return.cy.js` authored
- [x] **Cypress green (headed, 2026-06-26): quarantine-return 1/1 + commerce-gaps 9/9 + order-cancel 3/3 (restock path intact).**
- Note: `ddl-auto: update` adds `stock_entry.restockable` (nullable → existing rows sellable). Marketplace order-cancel
  (slice 51) keeps the 1-arg constructor → still restocks (quarantine=false).

## Deferred
- A back-office "quarantine register" view (list/dispose quarantined lots) — this slice keeps quarantined stock out
  of sale; managing it is a later ops screen.
