# Slice 43 — Pharmacy P6: Dispense = reuse the Sell sale + prescription link

Phase 2 pharmacy, after P5 (prescription intake). **A dispense IS a normal sale** through the existing Sell screen
(relabeled "Dispense" for PHARMA) — so stock decrements via the saga, tax/payment/receipt all reuse Phase 1. The
**only net-new** part is linking the sale to a `Prescription` and recording the dispensed quantities. Reuse-first,
no new sale path. References `itemId` (the bridge), per `SAAS-BUILD-STANDARDS.md`.

## Flow
```mermaid
sequenceDiagram
    actor Pharmacist
    participant Rx as Prescriptions screen
    participant Sell as Sell screen (reused, "Dispense")
    participant Trade as business-service (addSell saga)
    participant Pharma as pharma-service /dispense
    Pharmacist->>Rx: click "Dispense" on a pending Rx
    Rx->>Sell: set dispensingPrescriptionId + banner; switch to Sell
    Pharmacist->>Sell: add the prescribed items + Complete Sale
    Sell->>Trade: addSell (saga: reserve→confirm, tax, payment, receipt)
    Trade-->>Sell: SUCCESS + invoiceNo
    Sell->>Pharma: POST /prescriptions/{id}/dispense {invoiceNo, items[{itemId,qty}]}
    Pharma->>Pharma: bump PrescriptionItem.dispensedQuantity (capped) + Dispensing record + recompute status
```

## Backend (pharma-service, net-new only)
- `POST /prescriptions/{id}/dispense` (DispenseRequest `{invoiceNo, List<{itemId, quantity}>}`): for each line, find the
  matching `PrescriptionItem` (by `itemId`), increment `dispensedQuantity` (capped at prescribed `quantity`), write a
  `Dispensing` row (itemId, qty, invoiceNo ref, dispensedBy, patientName), recompute `Prescription.status`
  (PENDING → PARTIALLY_DISPENSED → FULLY_DISPENSED). Org-scoped (anti-IDOR via `findByIdScoped`).
- `DispenseService.dispense(id, req, org, user)` (pure-ish, testable) + `DispenseRequest`/line DTOs.

## UI (reuse the Sell screen)
- **Prescriptions list → "Dispense" button**: sets `window.dispensingPrescriptionId`, shows a banner, switches to the
  Sell section. The pharmacist adds the prescribed items and completes the sale normally.
- **Post-sale hook** (`main.js`, after `addSell` success): if `dispensingPrescriptionId` is set, call
  `/dispensePrescription` with the invoiceNo + cart items, then clear it. (Guarded — POS sales unaffected.)
- Monolith proxy `/dispensePrescription` → pharma `/prescriptions/{id}/dispense`.

## Tests
- pharma `DispenseServiceTest` (Testcontainers): dispensing bumps `dispensedQuantity` (capped), writes `Dispensing`,
  sets status FULLY_DISPENSED when all items dispensed; PARTIALLY when some; org-scoped.
- Cypress (headed): create Rx → `/dispensePrescription` → status + dispensedQuantity updated; "Dispense" button on the
  list switches to Sell + sets the banner.

## Status
- [x] Design (this doc)
- [x] `DispenseService` + `PrescriptionController.dispense` (`/prescriptions/{id}/dispense`) + `DispenseRequest` +
  `Dispensing.invoiceNo/organizationId` + `DispenseServiceTest` (Testcontainers) + monolith `/dispensePrescription` proxy
- [x] UI: Dispense action on the prescriptions list → switches to the (reused) Sell screen + banner; `cancelDispense`;
  post-sale hook in `main.js` records the dispense; `pharma.js` `dispensePrescription`
- [x] Build + restart + **Cypress green** (headed, 2026-06-24): `pharmacy/dispense.cy.js` 2/2 (FULLY_DISPENSED + qty; Dispense→Sell+banner). prescription.cy.js still green (no regression). **P6 DONE.**
