# EX-8b — The duplicate warning

**Status:** DONE 2026-10-09: gate 4/4 (seen red first), unit test 1 new (expense-service 95/95), Test Book case 9-4.
Programme: [`../expense-management-design.md`](../expense-management-design.md) §10 EX-8 ("duplicate warning (same
payee+date+amount)"). Follows [`ex-8a-expense-report.md`](ex-8a-expense-report.md).

## 1. Document
The same bill gets recorded twice: once by the cashier, once by the owner from the paper; or twice by one person after a
dropped connection that the Idempotency-Key does not cover (a second form fill). Before saving, the screen now asks when
the **same payee, date and amount** is already recorded in the business.

## 1b. Standards
| Dimension | Rule |
|---|---|
| A warning, never a refusal | two identical taxi fares on one day are real. Asked **every time** Save is pressed (the EX-5 lesson: a cached answer skipped the second warning); Cancel saves nothing and keeps the form |
| What matches | `voucher_date` equal, `total` equal (2 decimals), payee **trimmed and case-blind**. Not a match: a **voided** expense, a claim **rejected or withdrawn**. A claim still waiting is named "a claim waiting for approval" (it has no number yet) |
| Whose | the whole business, a colleague's included: the case it exists for is two people recording one bill. Named **by number only**, so a member learns no more about a colleague's expense than that it exists |
| No payee | nothing to compare on: no question, no request |
| Wire | `GET /vouchers/duplicates?date&amount&payee` → numbers; the monolith proxies it. The check runs before the receipt upload, so a cancelled save uploads nothing. If the check itself fails, the save is **not** blocked by it |

## 1c. RULE 0 trace
| What | Count | Finding |
|---|---|---|
| Save paths on the screen | 2 | `expenseSave` posts to `/vouchers` or (a claim) `/claims`: both go through the one check |
| Route clash | 1 | `/vouchers/duplicates` beside `/vouchers/{id}`: Spring matches the literal first (as `/vouchers/totals` already does) |
| Payee column | 1 | `payee_name VARCHAR(160)`, stored as typed: compared with `LOWER(TRIM(…))` in the query, not changed on save |

## 4. Gate: `cypress/e2e/expense/ex-8b-duplicate-warning.cy.js` (school tenant)
1. Rent 40 for a payee; again with the payee in small letters and padded: the warning names the first; Cancel saves
   nothing and keeps the form; Save asks again; Confirm saves.
2. Another amount, the day before, another payee: no match. Both voided: no match.
3. user.education records a water bill; the owner records the same: the warning names the colleague's EXP- number.
4. No payee: no request is made and the save goes straight through.

## 5. As built
- **Seen red first:** on the EX-8a build cases 1–3 failed (no check); case 4 passed, as it should on either build.
- **One failure not reproduced:** the first run straight after deploying, case 4 failed before testing anything (the
  Expenses screen's category list did not load within 20 s). Two reruns passed 4/4 and the screenshot was cleared by the
  next run before it was read. **Cause unproven**; recorded here rather than called a flake.
