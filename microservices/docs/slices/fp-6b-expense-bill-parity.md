# FP-6b-parity — expense bills in the daily payables check (E11)

**Status:** DONE 2026-10-09 — gate 3/3, unit 4 business + 1 expense new (business 514/514, expense 56/56, finance 83/83),
guide case 6a-5. Programme: [`../expense-management-design.md`](../expense-management-design.md) §11 **E11**;
[`fp-6-retire-business-source.md`](fp-6-retire-business-source.md) §4 "Open".

## 1. Document
FP-6a's daily check compared business's purchases with finance's PURCHASE documents, then aligned GL 2000 to finance's
whole ledger. Expense bills in that ledger were trusted as reported — nothing compared them with expense-service.

**Found while tracing (measured, not inferred):** a bill's subledger document was sent when the bill was SAVED, whether
or not its journal ever landed. Org 6 held 2 bills refused by the books (posting FAILED, 55.00 open): finance's ledger
held EXPENSE_BILL 3,575 open = expense's 3,520 in-the-books + those 55. The ledger was ahead of GL 2000 by money the
books never recorded — and the check would "repair" that by moving GL 2000 onto it through 2990.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Ledger follows the books | A bill's document is OPEN only once its journal has landed (`postingStatus = POSTED_GL`); still posting or refused → sent as not owed (VOID). Sent when the posting lands or is refused (`stampVoucher`), not at save |
| Parity | expense-service's own figure — Σ(total − paid) of posted bills in the books — against finance's `sumNet(EXPENSE_BILL)`. They measure the same set |
| Repair order | Documents first (purchases re-reported, bills re-sent), measure EVERYTHING again, and only when purchases AND bills agree is GL 2000 aligned — never onto a ledger whose documents disagree with their source |
| History | `payables_recon_day` gains expense_owed, finance_expense, expense_diff, bills_resent (business V80). A day with a bills difference is not clean |
| Coverage | Every tenant with suppliers is checked (a bill needs one), not only tenants with supplier purchases |

## 1c. RULE 0 trace
| What | Count | Finding |
|---|---|---|
| Senders of a bill's document (`enqueuePayable`) | 5 → 5 | save (removed), void, pay confirm, Pay Supplier apply, reversal; + posting landed/refused (new). Pay/apply/reverse all require POSTED_GL already |
| finance intake | 1 | status recomputed per snapshot; version = the voucher's `@Version`, which every posting stamp bumps → VOID → OPEN in order |
| Readers of finance's EXPENSE_BILL docs | 4 | statement, aging, balance stamp, the check — all now see only bills that are in the books |
| `PayablesReconciliationService` callers | 3 | nightly, after start, operator "check now" |
| Constructor callers | 1 test | updated |

## 4. Gate — `cypress/e2e/finance/fp-6b-expense-bill-parity.cy.js` (owner.payables)
1. A bill refused by a closed period: finance does not hold it open; GL 2000 = ledger; the check aligns nothing.
2. Reopened and posted again: owed in both; the check's bills figures agree.
3. A stale document planted in finance (fault injection): found, bills re-sent, the document is right again, GL 2000
   NOT moved.
Red before the code (finance reported no bills figure).

## 5. As built
- The unit tests caught a real bug in the first draft: after a re-send the check re-measured only if a difference was
  still showing, so it aligned on the ledger difference measured BEFORE the repair. Any repair now re-measures everything.
- The gate first judged runs by the day's found-differences; FP-6a keeps those from the first run of the day (by design),
  so runs are judged by their live figures.
- Operator panel: each day shows "Expense bills — in the books: X · in the ledger: X"; "repaired" names bills re-sent.
- Org 6's two refused bills self-heal on the first check after deploy (the bills are re-sent as not owed).
