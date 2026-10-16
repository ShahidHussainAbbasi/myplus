# Slice MKT-5a — consignment agreements: the terms come first

**Status:** VERIFIED 2026-10-16. Migration V40 (`mkt_consignment_agreement`). Unit 6 new (marketplace-service 471).
Gate `mkt-5a-consignment-agreements.cy.js` **6/6 on a live stack**; manual case M-5-01 recorded (it replaces the design
case of the same id, whose sale step moved to the design case M-5-03).

Requirements: **MKT-R4.6** (every consignment term configurable **before** consignment is enabled), **MKT-R12.2** (count
frequency, tolerance, proof, claim window), **MKT-R2.1/R2.2** (the consignor owns the stock; the consignee holds and
sells it).

**Ruling, 2026-10-15 ("Warehouse first", decision card):** the consignee is the MaxTheService warehouse; the consignor
is an approved seller. Shops as holders are a later slice.

## 1. What changes

| | Before | After |
|---|---|---|
| Operator | no way to agree consignment terms | Platform → **Consignment agreements**: choose a seller, set every term, **Offer these terms**. "Offered to <seller> as CA-…. Nothing can be consigned until they accept." Withdraw an offer not yet answered; end an active one with a reason |
| Seller (owner of the stock) | nothing | Sale → Marketplace → **Consignment agreements**: every term in words; **Accept these terms**, or Decline with a reason; End an active one with a reason |
| The warehouse | nothing | reads the agreements it holds stock under; answers none (MaxTheService offers them through its operator) |
| New terms | — | never an edit: a new agreement; accepted, it **replaces** the active one, whose terms stay as accepted |

The terms (all required, each refusal names the term): when ownership passes (on sale / on delivery), who sets the
selling price, MaxTheService's commission (0–50%), settle every 1–90 days (after the return period), reconcile every
1–365 days, count every 1–365 days, shrinkage tolerance 0–10%, who bears damage, who bears expiry, who insures (or
nobody), claim window 1–90 days, whether a claim needs proof, return unsold stock after 1–730 days, and who pays that.

## 2. Trace (RULE 0)

- **The new table: 5 readers, 3 writers, all in `ConsignmentAgreementService`.** Readers: the operator's list (all, or
  by status), a business's own list (as consignor **or** consignee), the pair-and-status lookup (one waiting offer, one
  active agreement; also `activeFor`), and `findById` for every decision (with the screen's version). Writers: `offer`,
  `decide` (each status move), and the replace inside Accept. No other service reads or writes it.
- **`activeFor(consignor, consignee)`: 0 callers today.** It is the switch 5b reads before any stock is consigned.
- **Column types against the entity under `ddl-auto=validate`:** BIGINT ids, VARCHAR(16) statuses and party values,
  DECIMAL(5,2) percentages, INT days, BIT(1) `proof_required`, DATETIME(6) times. Shown: the service started on V40
  and the gate wrote and read every column.
- **Routes: 5** in marketplace-service (operator list, offer, decision; seller list, decision), each behind a monolith
  proxy (`/platform/mkt/consignments`, `/platform/mkt/consignmentOffer`, `/platform/mkt/consignmentDecision` with
  ROLE_ADMIN; `/mkt/consignments`, `/mkt/consignmentDecision`). Operator routes also check `assertOperator`; the
  seller's decision checks owner or admin and that the business is the consignor.
- Nothing about stock, offers, orders or settlement changes in this slice.

## 3. Tests

- Unit `ConsignmentAgreementServiceTest` (6): every term held and normalised; terms refused by name; who can be a
  consignor; accept and replace; who answers what (reasons required); stale screen and role guards.
- Gate (6): offer through the operator's screen; refusals by term and a second waiting offer; the seller reads the
  terms and accepts on its screen; replacement keeps old terms; another seller and the warehouse cannot answer; end and
  decline need reasons, the operator reads them.
- Walk M-5-01, 4 steps and a cleanup.
- The operator form and both lists are in all six languages (58 keys).

## 4. Next

- **5b**: a seller consigns stock to the warehouse under its active agreement; the offer is CONSIGNMENT stock (owner
  the seller, custodian and seller the warehouse); the consigned stock ledger.
- Then sell-through settlement (R15.4), counts and shrinkage claims (R12.1–12.3), unsold returns.
- Ending an agreement while consigned stock is held will need a rule in 5b (today nothing can be held under one).
