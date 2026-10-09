# EX-8d — Recoverable input tax on expenses

**Status:** DONE 2026-10-09: gate 4/4 (seen red first), unit tests 6 new (expense-service 98/98, finance 93/93), Test
Book case 9-5. Programme: [`../expense-management-design.md`](../expense-management-design.md) §5.5 ("Tax (EX-8)"), §6.2
`expense.tax.inputRecoverable`. Owner's ruling 2026-10-09: **one expense setting for every business type** (not the
shop's purchase-tax switch, which schools, welfare and farms do not have).

## 1. Document
A business that is registered for tax can reclaim the tax it pays on many expenses. Until now an expense was always a cost
**including** its tax. Switched on, the form asks how much of each amount is tax; that part goes to the tax account
(Dr 2100) and is netted against output tax in the tax register, and only the rest is an expense.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Off by default | `expense.tax.inputRecoverable` BOOL, default **false** (the design's default: most small tenants cannot reclaim input tax). While off, a tax part is **refused** in words, so nothing reaches 2100 by accident |
| The line | `amount` stays what was paid; `tax_amount` (V12, default 0) is the tax inside it, `0 ≤ tax < amount`. `netAmount()` = amount − tax is the cost |
| The journal | Dr each category's account with the **net**, Dr **2100** with the tax, Cr the paid-from account with the **whole** amount. A voucher with no tax posts exactly as before (no 2100 line) |
| finance's rule | an EXPENSE event may debit 2100 (the one non-expense debit allowed), but still needs at least one **cost** line: tax alone is refused |
| The tax register | counted as **input tax** (source EXPENSE, debit) and its void as an **input adjustment** (source EXPENSE_REVERSAL, credit), beside purchases |
| The report (EX-8a) | counts the **net**, as the P&L does; the CSV gains an "Input tax" column before Amount (Amount and Kind stay last) |
| What does not change | the list's "Total spent", a bill's amount owed, a claim's amount owed back, the duplicate check: all the amount **paid** |

## 1c. RULE 0 trace
| What | Count | Finding |
|---|---|---|
| Readers of 2100 by source | 1 (`GlService.taxRegister`) | it counted 4 sources (SALE, SALE_RETURN, PURCHASE, PURCHASE_RETURN): an expense's input tax would have **lowered 2100 and been missing from the register**. EXPENSE and EXPENSE_REVERSAL added |
| finance's expense rule | 1 (`ExpensePostingRules.check`) | debits had to be EXPENSE-type: 2100 (LIABILITY) would have been refused. Now allowed, with "at least one cost line" kept |
| Readers of a line's amount | 4 | `VoucherPostings.post` (now net + 2100), the report (now net), `VoucherView`/`LineView` (amount and taxAmount both shown), the voucher total (stays the amount paid: what left the till or bank) |
| `LineRequest` constructions | 3 (all tests) | a 5-argument constructor kept, so none changed; the JSON field is additive |
| Settings catalog pin | 1 (`ExpenseSettingsTest`) | pins the keys the code reads: failed until the new key was added, as it should |
| 2100 in the chart | 1 | "Tax Payable" in finance's defaults: present for every tenant |

## 4. Gate: `cypress/e2e/expense/ex-8d-input-tax.cy.js` (school tenant)
1. Off by default: no Tax field on the form; a tax part is refused ("switched off").
2. The owner ticks **Recover input tax on expenses**; the form shows **Tax included**. Rent 115 with 15 tax, in cash:
   6000 +100, 2100 +15 (Dr), 1000 −115; the register's input tax +15 and net payable −15; the report +100 and the P&L
   +100.
3. Voided: 6000 −100, 2100 −15, 1000 +115; the register's net input −15. The line kept its tax part (15).
4. Tax equal to the amount, or negative: refused.

## 5. As built
- **Seen red first:** 4/4 failed on the EX-8b build (the tax part was ignored), then 4/4 on the first run after deploying
  expense (V12), finance and the monolith.
