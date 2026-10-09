# EX-8a — The expense report (and E8: no unvalidated branch)

**Status:** DONE 2026-10-09: gate 6/6 (seen red first), unit tests 6 new (expense-service 94/94). Programme: [`../expense-management-design.md`](../expense-management-design.md) §10 EX-8,
finding **E8**. EX-8 is split: **8a** the report (by category, member, month, paid from; CSV), **8b** the duplicate
warning (same payee, date and amount), **8c** the analytics `finance.expenses` producer (F2), **8d** recoverable input
tax. Reporting **by branch** waits for a validated branch picker (**8e**), because of E8 below.

## 1. Document
An owner can see each expense, and finance's P&L shows totals per account, but nothing answers "what did we spend on, who
spent it, and how is it moving month to month". EX-8a adds a **Report** to the Expenses screen, grouped the way the owner
asks, with a CSV of every line behind it. Its total for a period must be **the P&L's expense total for that period**, so
nobody has to explain why two screens disagree.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Reconciles with the P&L | The P&L sums journals by **journal date**. So the report counts an expense **in the books** (`POSTED_GL`) on its own date, and a **void as a negative on the day its reversal is dated**, not "voided rows left out". An expense whose posting failed, or is still posting, is not in the books and not in the report |
| The void's date | the reversal is dated `TenantClock.today()` (the person's own day, TZ-2), but `voided_at` is the server's clock: near midnight they differ. **V11 `void_posted_on DATE`** keeps the reversal's own date, written in the one place the reversal is made. Older voids are back-filled from `voided_at` (stated, not hidden) |
| Scope | owner/admin: the business; a user: their own expenses (the list's rule). Grouping by member names members from auth's staff list (owner/admin); a user sees "You" |
| Groupings | **category** (the category and account each line was recorded with), **member**, **month**, **paid from** (cash, bank, till, bill, claim). Branch waits for 8e |
| CSV | `GET /reports/expenses.csv?from&to`: one row per line (date, number, category, account, paid from, payee, member, amount; a void is its own row with a negative amount on its void date). Same scope and rule as the report, so the CSV sums to the report |
| E8 | a branch (`storeId`) is **refused on the user path** ("not available yet") until 8e validates it against the caller's own branches. The till's pay-outs keep theirs: business-service sets it from the drawer that paid |

## 1c. RULE 0 trace (before code)
| What | Count | Finding |
|---|---|---|
| Writers of `expense_voucher.store_id` | 2 | `build()` from the request, **unvalidated (E8)**; `recordFromDrawer` from business-service's till (trusted: the drawer's own store). The screen never sends it (0 references in `expense.js`); no gate sends it (the one `storeId` in the Test Book spec is a location grant, unrelated) |
| Readers of `store_id` | 1 | `VoucherView` (shown nowhere on screen) |
| Writers of the void | 1 | `ExpenseVoucherService.voidVoucher` → `voidWith(…, LocalDateTime.now())` and `reversal(v, TenantClock.today())`: the two clocks the report must not mix |
| P&L | 1 | `GlService.profitAndLoss`: debit − credit over every EXPENSE-type account by journal date, so 5000 COGS and 5100 purchases are in its total too. The gate compares the **change** in each total around its own expenses, never the absolute totals |

## 4. Gate: `cypress/e2e/expense/ex-8a-expense-report.cy.js` (school tenant)
1. Rent 40 (cash) and Fuel 25 (bank) recorded: the report for today +65 and the P&L +65. The rent voided: both +25.
2. The period closed through yesterday; an expense dated yesterday is refused by the books (FAILED): the report and the
   P&L for yesterday are both unchanged. The lock is put back as it was.
3. On screen: Report → Category shows rows (Fuel and transport among them); grouped by member, month and paid from the
   totals are the same and each grouping's rows add up to its total; Paid from shows Bank.
4. The CSV: text/csv, a header with Date and Amount, rows sum to the report, the voided rent a `-40.00` row.
5. user.education's report is their own ("You"), equal to their own expenses in the books; the owner's names
   "User Education".
6. E8: a voucher with `storeId: 999999` is refused ("…branch…"). On the old build it was **accepted**.

## 5. As built
- **Seen red first:** on the EX-7b build all six cases failed (no report endpoints; case 6 showed the made-up branch
  accepted), then 6/6 on the first run after deploying expense (V11) and the monolith. Teardown verified: the period lock
  back to open as found, no expense switch left on.
- **Beyond the gate's deltas, the whole school reconciles:** with the report and the P&L asked for the same periods
  over all of the school's real data, October 1–9 is **18,822.00 in both**, September 0 in both, the year to date
  18,822.00 in both. (The school's only journals on expense accounts come from expense-service; a tenant with purchases
  will also have 5000/5100 in its P&L, which this report, by design, does not include.)
- V11 back-filled `void_posted_on` for every earlier void (0 voided rows without it afterwards).
- Not here (said plainly): reporting **by branch** (EX-8e, with a branch picker checked against the caller's branches);
  a void whose reversal is still on its way to the books counts as reversed for that minute.
- Regression: every expense gate, 19 specs, **111/111**.
- **Found while recording:** the Test Book's `categoryByName` silently fell back to the first category when a name was not
  found, so a case recorded "Utilities 25" as **Rent** (this business calls 6100 "Electricity, gas and water"). The
  helper now fails with the business's own category names; the case uses the real one. And a CSV cell holding a comma
  is quoted (correctly), which broke a naive `split(',')` in the case: amounts are now read from the row's end (Amount
  and Kind are always last and never quoted), in the gate too.
- Test Book cases 9-1 to 9-3 recorded and published.
