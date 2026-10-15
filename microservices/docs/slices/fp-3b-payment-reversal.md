# FP-3b — Reverse a bill payment, so a paid bill can be voided (E10)

**Status:** DONE 2026-10-09 — gate 7/7, unit 4 finance + 6 expense new (finance 83/83, expense 55/55), guide case 4-7.
Programme: [`../expense-management-design.md`](../expense-management-design.md) §11 finding **E10** (FP-3 §3 known limit).

## 1. Document
A paid bill could not be voided, and the refusal said "Reverse the payments first, then void the bill" — with no way to
reverse one. finance had no payment reversal at all (only DR-4 set-off reversals).

## 1b. Standards
| Dimension | Rule |
|---|---|
| Money | The books first. finance records a MIRROR payment (same direction, −amount, `PV-…-R`, no allocations) and the opposite journal (Dr cash·bank / Cr 2000) in ONE transaction — the DR-4 shape. Then expense-service re-opens the bill and tells the subledger, so GL 2000 and the supplier ledger move by the same amount (the FP-6a invariant) |
| Idempotency | Mirror `clientRef = REV:<paymentId>`; UNIQUE (organization_id, client_ref) (finance V13) refuses a second. A lost answer → press again → the first reversal answers. Expense row REVERSED answers with itself |
| Period | A closed period refuses the journal → nothing saved in finance, nothing changed in expense; the books' sentence is shown |
| Scope | Only a payment made from the Expenses screen (it has its own finance payment). A Pay Supplier application is one payment for several bills (business-service) → refused here in words |
| Security | Owner/admin (`@PreAuthorize` + `seesAll`); tenant-scoped lookups both sides (a foreign id is not found) |

## 1c. RULE 0 trace
| What | Count | Finding |
|---|---|---|
| finance payment queries | 4 | clientRef lookup (idempotency — now also the reversal key), party list (shows the mirror, like DR-4's), party SUM (nets it), count — none breaks on a negative mirror; set-off mirrors already exist |
| Readers of allocations | 0 in finance | allocations are only exposed; the mirror carries none, the bill is re-opened by its owner (expense) |
| business supplier statement | 1 | skips `sourceModule = EXPENSE`; the mirror copies it → excluded too (FP-3 case 7 still holds) |
| FP-6a daily check | 1 | GL 2000 − ledger; the reversal moves both by the same amount |
| Writers of a bill's paid amount | 3 + 1 | `confirm`, `applyExternal`, (void) + new `reversePayment` |
| Readers of payment status | 4 | `sumPending` (PENDING only), reconciler (PENDING), list, `applyExternal` key lookup — REVERSED affects none |
| `FinanceClient` implementors | 0 | HTTP proxies and mocks only — a new method breaks nothing |

## 4. Gate — `cypress/e2e/expense/fp-3b-payment-reversal.cy.js` (owner.business, fresh supplier)
1. Reverse 120 cash: 2000 −120 (credited), 1000 +120, finance owes 300 again, `PV-…-R`, bill paid 0.
2. Again → the same reversal; ledger unmoved.
3. Void the bill → 2000 +300, subledger 0.
4. Reason required; one payment still standing → void refused ("Reverse the payments first").
5. Closed period → refused in words, nothing moves, still RECORDED; reopened → reverses.
6. A user → 403; another business → 404/400; still RECORDED.
7. On screen: Payments → Reverse (asks why) → Reversed PV-…-R; the bill owes again; Void appears.
Red before the code (7/7 failing).

## 5. As built
- As designed. The gate's own defects (a URL built at queue time; a helper that passed on a failed save silently) fixed.
- F6 stands for reversals too: the reversal journal is dated today (the reversal's own day), as payment journals are.
