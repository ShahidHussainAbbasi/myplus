# FP-3 = EX-4 — Expense bills: owed to a supplier, paid later, in the one payables subledger

**Status:** GREEN 2026-10-02 22:05, STAGED. expense V4 live. Tests: expense 26, finance 53, business 396 (0 skipped, in a worktree without DR-4 WIP). Gate `fp-3-expense-bills.cy.js` 9/9 headed; regressions FP-1/2 5/5, EX-1 api 7/7 + ui 4/4. One red run (case 9) was the SPEC: it looked for the note, which the list does not show — fixed to the payee; the screenshot showed the bill row correct. Programme: [`../finance-payables-subledger-design.md`](../finance-payables-subledger-design.md).
Follows FP-1/FP-2 (`0a04ac99`).

## 1. Document
A shop gets the electricity bill today and pays it next week. It must be in the books today (Dr Utilities /
Cr 2000 Accounts Payable), owed to that supplier, and settled when paid (Dr 2000 / Cr cash·bank). QuickBooks
"Bill" / Xero "Bills". With FP-1/FP-2 live, the bill lands in finance's payables subledger beside the supplier's
purchases, so the GL's 2000 and the subledger move together.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Business | A bill is an expense voucher **paid from AP**: posted at once (accrual), owed until paid. Paying it is a DISBURSEMENT recorded in finance's payment ledger (PV- voucher) allocated to the bill. A bill with any payment cannot be voided (reverse the payment first); an unpaid bill voids by reversing journal and its subledger document goes VOID |
| Tenancy | The supplier is CONFIRMED with the module that owns it (business, via the expense-tag SPI: type `SUPPLIER`), with the caller's identity — a foreign or invented supplier id is refused |
| Boundaries | expense-service owns the bill; finance owns the journal, the payment and the subledger; business owns the supplier. Business's Pay Supplier still settles purchases only — bills are paid from Expenses until FP-5 unifies settlement |
| Patterns | Strategy `PaidFrom.AP → 2000` · SPI reuse (`ExpenseTagClient`, source `business`, type `SUPPLIER`) — no new contract · payload-JSON outbox with a second event type `PAYABLE` (snapshot to finance) beside `EXPENSE` · payment idempotency in expense-service (finance's payment write has none): UNIQUE `(organization_id, idempotency_key)` on a bill-payment record |
| Performance | Supplier list read once per form; the payment is one synchronous call to finance (the PV number is shown to the person who paid) |
| Testing | Unit: AP strategy, snapshot from a bill, pay/void rules; Cypress: bill → GL 6300 +, 2000 +, subledger EXPENSE_BILL open; pay part → 2000 −, cash −, open reduced; replayed payment key → one payment; void paid bill refused; void unpaid bill → reversal + VOID; foreign supplier refused |

## 1c. RULE 0 trace (done before code)
| What | Count | Finding |
|---|---|---|
| Readers of finance VENDOR payments | 3 | business `FinanceReportService.addPaymentLines` (supplier statement) **must EXCLUDE** bill payments — it lists business's purchases only, so a PV against a bill would show as a credit with no bill (understating what is owed). GL journal from `postPayment` **wants** it (Dr 2000 / Cr cash). `GET /payments/summary` has **no caller**. Fix: `PaymentView.sourceModule` (finance already emits it) + skip `EXPENSE` |
| Readers of the payables subledger | 2 | `summary` gains `bySource`; the FP-1/2 gate now reconciles business to `bySource.PURCHASE` (a bill is not a business balance). `reconciliation` (subledger vs GL 2000) includes bills, which is right: both credit 2000 |
| Writers of a bill's paid amount | 1 | `ExpenseBillService.confirm` only, after finance answered — stamped, never summed on read |
| Finance payment write idempotency | none | `PaymentService.record` has no dedup ("the caller de-duplicates") → expense-service owns it: reserve row with UNIQUE key, finance `reference = EXPB-<org>-<id>`, look up before any resend |
| Expense outbox `stampVoucher` | 1 | would have stamped a PAYABLE failure as "Void not yet in the books" → PAYABLE rows skip it |
| Voucher `@Version` bumps | post · pay · void · `stampPosting` | monotonic → used as the snapshot's `sourceVersion`; the snapshot is READ at send time (latest truth wins) |

## 2. Design

```mermaid
sequenceDiagram
    actor U as Owner / cashier
    participant E as expense-service
    participant B as business-service
    participant F as finance-service
    U->>E: record bill (AP, supplierId)
    E->>B: GET /expense-tags (caller identity)
    B-->>E: SUPPLIER list (findScoped)
    E->>E: voucher POSTED, EXP- no., outbox EXPENSE + PAYABLE (one tx)
    E-)F: post-event EXPENSE: Dr 6xxx / Cr 2000
    E-)F: upsertPayables EXPENSE_BILL (read at send, @Version)
    U->>E: pay 200, Idempotency-Key
    E->>E: tx1 lock bill, check open − pending, row PENDING, ref EXPB-org-id
    E->>F: listPayments(VENDOR) — ref already there? (a retry)
    E->>F: recordPayment DISBURSEMENT, ref, alloc EXPENSE_BILL → PV-
    F-->>E: PV-000123 (Dr 2000 / Cr 1000, same tx)
    E->>E: tx2 row RECORDED, paid_amount += 200, outbox PAYABLE
    E-)F: upsertPayables (paid 200)
    Note over E,F: answer lost → row stays PENDING; same key or the 1-min reconciler<br/>finds the ref in finance (confirm) or not (release FAILED)
```

- **business**: `ExpenseTagController` `GET /expense-tags` → the caller's suppliers as `SUPPLIER` tags (`findScoped`).
- **expense-service V4**: `expense_voucher.supplier_id BIGINT`, `due_date DATE`, `paid_amount DECIMAL(19,2) NOT NULL
  DEFAULT 0`; `expense_bill_payment` (org, voucher_id, amount, method, paid_on, receipt_no, idempotency_key UNIQUE per
  org, created_at). `PaidFrom.AP("2000")`; `ExpenseTagService` registry gains source `business` / type `SUPPLIER`.
  Record with `paidFrom=AP` → supplier required + confirmed; on post: `EXPENSE` + `PAYABLE` snapshot. `POST
  /vouchers/{id}/pay {amount, method, paidOn}` + Idempotency-Key → finance `recordPayment(DISBURSEMENT, VENDOR,
  allocation EXPENSE_BILL)` → stamp `paid_amount`, enqueue `PAYABLE` snapshot. Void: refused if paid; else
  reversal + `PAYABLE` snapshot voided.
- **finance**: summary gains `bySource` (PURCHASE / EXPENSE_BILL) so business's purchase figure still reconciles
  exactly while bills are visible.
- **monolith**: Paid from → **Bill (pay later)** shows Supplier + Due date; list shows Due / Paid; **Pay** action
  (amount, cash/bank) on an open bill.

## 4. Gate — `cypress/e2e/expense/fp-3-expense-bills.cy.js` (gateway-direct, owner.business)
1. Bill 500 Repairs to a seeded supplier → GL 6300 +500, 2000 credit +500; finance EXPENSE_BILL open +500.
2. A supplier id that is not this business's → refused.
3. Pay 200 cash → 2000 −200, 1000 −200; bill paid 200, finance open 300; PV- number returned.
4. Same payment key again → one payment (2000 unchanged by the replay).
4b. More than is owed → refused.
5. Void the paid bill → refused ("Reverse the payments first").
6. A second, unpaid bill voids → expense and 2000 back; finance open back to 300.
7. Finance holds the PV for the supplier (`sourceModule` EXPENSE) — and business's supplier statement does NOT list it.
8. Business purchases still reconcile: finance `bySource.PURCHASE` net = business Σ supplier due.
9. On screen: "Bill (pay later)" offered, supplier required, the row shows "Owes 120.00", Pay (bank) → PV-, row "Paid", no Void.

## 3. Known limits (stated, not hidden)
- **Suppliers come from business-service.** A school, welfare or farm tenant has no business suppliers, so the
  screen offers Cash and Bank only (the "Bill" option appears only when the supplier list is non-empty). A shared
  supplier list for every module is party-service's job (roadmap), not this slice's.
- **No "reverse a payment" yet.** A paid bill cannot be voided (refused with the reason). Reversing a bill payment
  needs finance's payment reversal, which belongs with FP-5 (one settlement path). Until then: pay the rest, or ask
  an accountant to post a correcting journal.
- **Business's Pay Supplier settles purchases only** (unchanged). A supplier owed both a purchase and a bill is paid
  in two places until FP-5.
