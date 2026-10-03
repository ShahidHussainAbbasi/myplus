# FP-5 — one supplier settlement: Pay Supplier settles purchases AND expense bills

**Status:** FP-5a GREEN 2026-10-03, STAGED (gate 4/4 + regressions 31/31 headed; finance 69, education 236, business 418 with the 1 foreign DR-4 error; LedgerOutboxIntegrationTest 4/4 real MySQL; live: finance V13, business V76, education V31; 16/16 outbox rows POSTED, 16 distinct client_refs in finance). Caught before deploy by the migration test: V13 first named table `payment` (it is `payments`). NEXT FP-5b. Programme: [`../finance-payables-subledger-design.md`](../finance-payables-subledger-design.md).
Follows FP-4 (`a268a3a9`).

## 1. Document
Today a supplier owed for stock and for an electricity bill is paid in two places: Pay Supplier (business) settles
purchases only; the bill is paid from Expenses. FP-5 makes one payment settle both, oldest first, for a tenant whose
supplier figures come from finance — and makes that payment reliably reach the ledger.

## 2. RULE 0 trace
**`payVendor` (VenderService:255–300):** FIFO over `findOpenPurchasesByVendor` (business rows, applied in the business
transaction) → `SubledgerService.settle` → `financeClient.recordPayment(DISBURSEMENT, allocations PURCHASE)` →
`recomputePayable`. Idempotency key recorded in the business transaction. Overpayment → `onAccountAdvance`.

**⚠ Finding FP-5-LEDGER (money, existing):** `SubledgerService.settle` calls finance **synchronously inside the
business transaction and swallows any failure** ("best-effort… reconcile later") — and **nothing reconciles** (searched:
no job, no outbox). So:
- finance down → purchases marked PAID in business, **no PV voucher, no journal** (Dr 2000 / Cr cash never posted) — GL
  2000 stays overstated against the subledger (a candidate cause of the FP-4b-GL drift; unverified);
- finance records it, then the business transaction rolls back → a **PV with no allocation applied**, and the
  idempotency row rolled back with it, so a retry **pays again** in finance.
Same call in 3 places: `payVendor`, `CustomerService` receive (AR), education `FeeArrearsService`.

**Expense bills today:** paid only through expense-service (`ExpenseBillService.pay`: reserve → finance → confirm),
which is the safe pattern (reference looked up before any resend).

**Readers a mixed payment touches:** finance statement (one PAYMENT line, full amount — right); business's own
statement on BUSINESS skips `sourceModule = EXPENSE` only — a mixed payment (`BUSINESS`) would show its bill portion
against purchases → **switching a tenant back to BUSINESS after a mixed payment misstates its statement**.

## 3. Rulings (2026-10-03)
1. **Business orchestrates**: payVendor allocates across purchases AND bills; one finance payment with mixed
   allocations; the bill side reaches expense-service by outbox. Purchase paid/due stay business-owned.
2. **FP-5-LEDGER fixed for all three callers in FP-5** (supplier payment, customer receipt, education fee arrears).
3. **Oldest first, any kind** (a bill's due date if set, else its date).
4. **Switching back to BUSINESS is refused** once a mixed payment exists (the operator sees the count).

Split: **FP-5a** = the reliable ledger record (ruling 2) — the base FP-5b builds on. **FP-5b** = mixed settlement.

## 4. FP-5a design — a payment reaches the ledger exactly once, or the settlement does not happen at all

```mermaid
sequenceDiagram
    participant S as business / education (caller tx)
    participant L as common-subledger LedgerOutbox
    participant F as finance-service
    S->>S: allocate FIFO, apply to its own documents
    S->>L: enqueue(PaymentRecordRequest + clientRef) — JDBC insert, SAME transaction
    Note over S,L: commit — documents and the ledger request are durable together
    L->>F: after commit: recordPayment(clientRef)
    F-->>L: PV/RCPT number (or the existing one if clientRef was seen)
    L->>L: row POSTED + receipt_no
    Note over L,F: finance down → row PENDING; the 1-min schedule retries;<br/>a repeat with the same clientRef returns the first payment
```

- **finance V13**: `payment.client_ref VARCHAR(100)` + UNIQUE `(organization_id, client_ref)`; `record()` returns the
  existing payment for a known clientRef (no second payment, no second journal); a concurrent duplicate loses on the
  index and is answered with the winner (DUP-1).
- **contracts**: `PaymentRecordRequest.clientRef`.
- **common-subledger `LedgerOutbox`**: table `ledger_payment_outbox` (each service's own Flyway: business V76, education
  V31); JDBC insert in the caller's transaction (no entity, so one implementation serves every service); after-commit
  delivery in the committing thread (so the screen still gets its voucher number when finance is up); the relay's
  retry/dead-letter via `common-outbox`. `settle()` enqueues instead of calling finance; `SettleOutcome` carries the
  clientRef; the caller resolves the number after commit (`voucherFor`).
- **clientRef**: `BUS-PAYV-<org>-<idempotency key>`, `BUS-RCV-<org>-<key>`, `EDU-FEE-<org>-<uuid>` — a replayed request
  reuses the same reference, so even a resend after a lost answer cannot pay twice.
- **Screens**: unchanged when finance is up (number shown); when it is down, "recorded — voucher number to follow".

**Gate `cypress/e2e/finance/fp-5a-ledger-record.cy.js`:** Pay Supplier and Receive Payment each move GL 2000 / 1100 and
cash by exactly the amount and return a number; the same Idempotency-Key again → same number, ledger unmoved;
`ledger_payment_outbox` has no PENDING/FAILED rows afterwards (operator outbox health). Unit/IT: finance clientRef
dedup; LedgerOutbox with finance failing → settlement committed, row PENDING, delivered on flush, delivered once.
