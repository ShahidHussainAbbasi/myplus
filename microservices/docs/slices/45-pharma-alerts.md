# Slice 45 — Pharmacy P8: alerts & controlled-substance register

Closes the pharmacy vertical. Two compliance/ops views, reuse-first:
1. **Controlled-substance register** — every controlled dispense (from `Dispensing.controlled`, set at dispense in
   P7), org-scoped, newest first. Net-new pharma read.
2. **Stock alerts (near-expiry / low-stock)** — **reuses inventory-service's existing `StockAlert` system**
   (`/api/inventory/alerts`); generic across verticals.

## Backend
- pharma-service: `DispensingRepository.findControlledScoped(org,user)` + `SafetyService.controlledRegister(...)` +
  `GET /controlled-register` → `ControlledDispenseDTO[]`.
- monolith: `PharmaSafetyController` `/controlledRegister` proxy; new `InventoryRestClient` (`/api/inventory`, no
  StripPrefix) + `StockAlertController` `/getStockAlerts` → inventory `/alerts`.

## UI (PHARMA-only)
- **Alerts & Register** panel (Pharmacy menu): stock-alerts table (reused inventory alerts) + controlled-register table.

## Tests
- Cypress (headed): flag an item controlled → prescribe → dispense → it appears on `/controlledRegister`;
  `/getStockAlerts` returns successfully; panel renders.

## Status
- [x] Design (this doc)
- [x] Backend (`findControlledScoped` + `controlledRegister` + endpoint) + monolith proxies (`/controlledRegister`,
  `/getStockAlerts` via `InventoryRestClient`)
- [x] UI Alerts & Register panel + nav
- [x] Cypress `pharmacy/alerts.cy.js` written
- [x] Build + restart + **Cypress GREEN (headed, 2026-06-26): `alerts.cy.js` 3/3** — controlled dispense on the register; stock-alerts reuse; panel renders. **P8 DONE — pharmacy vertical complete.**
