# Slice 59 — P12: pharmacy insurance / co-pay

A dispense is often **split between an insurer and the patient's co-pay**. The G5 tender model already supports
multiple tenders per sale and counts any non-CREDIT tender as paid, so this is small: add an `INSURANCE` payment
method and an optional "Insurance covered" amount on the sell/dispense screen that emits a second tender. The
patient pays the co-pay via the chosen method; insurance covers the rest; the sale settles with no customer due.

## Changes
- **business-service** — `PaymentMethod.INSURANCE` (counts as paid like CASH/CARD; it's an insurer receivable, not
  customer credit). `settle`/`record`/`mode` already handle any method — no logic change.
- **monolith UI** — `#sellPayMethod` gains an **Insurance** option; a new optional **Insurance covered** field
  (`#sellInsured`). `main.js` adds an `INSURANCE` tender for the covered amount (in addition to the patient tender),
  so a co-pay becomes a 2-tender SPLIT.

## Tests
- `PaymentServiceTest` (unit, always-runs): `settle([INSURANCE 80, CASH 20], 100)` → paid 100, due 0, mode SPLIT —
  proves INSURANCE is treated as paid, not credit.
- Cypress `pharmacy/insurance-copay.cy.js` (headed): the sell screen offers **Insurance** + the covered field; an
  `addSell` with INSURANCE + CASH tenders for the full total succeeds (settles, no due).

## Status
- [x] Design (this doc)
- [x] `PaymentMethod.INSURANCE` + sell-screen Insurance option & `#sellInsured` field + `main.js` INSURANCE tender +
      `calculateChange` accounts for covered amount + `PaymentServiceTest` insurance+copay case
- [x] Cypress `pharmacy/insurance-copay.cy.js` authored
- [x] **Cypress green (headed, 2026-06-26): insurance-copay 2/2 + dispense 2/2 + sell 28/28 regression.**
- **Migration #4 required + applied (dev):** Hibernate maps `@Enumerated(STRING)` → native MySQL `enum`; ddl-auto
  won't add `INSURANCE`. `ALTER TABLE payment MODIFY method enum('BANK_TRANSFER','CARD','CASH','CREDIT','INSURANCE','REFUND','WALLET')`.
  See microservices/docs/migrations.md #4.

## Deferred
- Insurer master (payer list, claim numbers, reimbursement reconciliation) + a claims report. This slice records the
  split on the sale; claim management is a later module.
