# Slice 58 — Quarantine register (ops view for quarantined stock)

P11 (slice 55) keeps returned medicines out of sale (`restockable=false`) but there's no way to *see* or *clear*
them. This adds a back-office **Quarantine register**: list quarantined lots (batch/expiry/qty) and **dispose** them
(physically destroyed / returned to supplier → row removed). Closes the returns loop.

## Changes
- **inventory-service**
  - `StockEntryRepository.findQuarantinedScoped` (restockable=false, org-scoped).
  - `StockService.listQuarantine()` → `{items:[{id, productId, batchNo, expiryDate, quantity}]}`;
    `disposeQuarantine(id)` — anti-IDOR (must be the caller's org AND restockable=false), deletes the entry.
  - `StockController` `GET /stock/quarantine`, `POST /stock/quarantine/{id}/dispose`.
- **monolith** `CatalogController` proxies `GET /quarantineList`, `POST /disposeQuarantine`; a **Quarantine** screen
  (`QuarantineDiv` + `quarantine.js`) lists lots with a Dispose action (product names resolved from `/catalogProducts`).
- Menu: a "Quarantine" item under Register.

## Tests
- Cypress `pharmacy/quarantine-register.cy.js` (headed): create+stock+saga-sell a product → quarantine-return some →
  the lot appears in `/quarantineList` → dispose it → it's gone; on-hand stays correct (dispose doesn't change sellable).

## Status
- [x] Design (this doc)
- [x] inventory `findQuarantinedScoped` + `listQuarantine`/`disposeQuarantine` + `GET /stock/quarantine` & `POST
      /stock/quarantine/{id}/dispose`; monolith `/quarantineList` & `/disposeQuarantine`; QuarantineDiv + menu + quarantine.js
- [x] Cypress `pharmacy/quarantine-register.cy.js` authored
- [x] **Cypress green (headed, 2026-06-26): quarantine-register 1/1 + quarantine-return 1/1 regression.**
- Note: no contract/business change; inventory + monolith only.

## Deferred
- "Return to supplier" as a distinct action (vs destroy) + a disposal audit log; this slice removes the lot.
