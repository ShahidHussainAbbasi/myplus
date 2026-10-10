# EX-9a — Bring past till pay-outs into the books (owner-run, previewed)

**Status:** DONE 2026-10-10: gate 4/4 (seen red first), unit tests 4 new (expense-service 114/114), Test Book case 3-4. Programme: [`../expense-management-design.md`](../expense-management-design.md) EX-9, R-4.
Owner's ruling R-4 (2026-10-09): past till pay-outs and farm expenses **are back-posted, with the owner's consent**:
- an owner-run, previewed import;
- each source row posts once, keyed by its source id;
- a row that matches an expense already recorded is flagged for the owner, never posted twice.

EX-9 is split. **EX-9a** (this slice) covers the till, which has real history. **EX-9b** covers the farm: its old
expense screen converges onto Expenses, and its rows come in through the same import (0 rows in every tenant here).

## 1. Document
Before Expense management was switched on, money paid out of the till (Till → Pay out) never reached the books. Since
EX-3, a pay-out made while the module is on becomes an expense automatically. The ones from before are still missing
from the profit and loss.

Expenses → **Past till pay-outs** (owner/admin, shop dashboard) lists them:
- every pay-out with no expense yet, with its date, amount and reason;
- a pay-out with the same day and amount as an expense already recorded is **flagged** (named by its number) and
  left unticked.

The owner chooses a category, ticks the rows, and presses **Import**. Each becomes a till expense on its own date, as
if it had gone through EX-3 that day. Running it again finds nothing new.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Consent | nothing is imported automatically; only rows the owner ticks, after seeing them; owner/admin only |
| Once per row | keyed `(DRAWER, movementId)`: the same key EX-3 uses (`recordFromDrawer`'s replay). A row already an expense is never listed or imported again, whichever path made it |
| Never trust the browser's rows | Import sends only ids. The server re-reads business-service's list for the caller's business and imports only ids that are still unbooked there, with the server's own date, amount and reason |
| A likely duplicate | same day and same amount as an expense recorded in this business (not voided, not a rejected or withdrawn claim). It is flagged with the expense's number and **unticked**. The owner may still tick it, on purpose |
| Dated | each on its own pay-out date. A date in closed books is refused by finance at posting: the row shows "Not posted" with the reason and Post again (EX-1b), as any expense would |
| Category | one category for the rows imported together (a pay-out made while the module was off has none); required |
| What it books | exactly an EX-3 pay-out: Dr the category's account, Cr the till (paid from DRAWER), the drawer's store as its branch (EX-8e) |
| Source of truth | business-service owns pay-outs. A new internal read lists a business's unbooked pay-outs (`/internal/business/drawer/unbooked-payouts`); expense-service never reads business's tables |

## 1c. RULE 0 trace
| What | Count | Finding |
|---|---|---|
| Pay-outs with no expense, per tenant | org 6: **16** (of 64); org 18: 0 (of 2) | made while the module was off (no category); eligible |
| Existing till vouchers in expense-service | 50, 50 distinct `source_ref` | one per pay-out. The import uses the same key, so a row from either path is booked once |
| Who re-sends pay-outs | 1 (`ShiftService.recordMovement`, at creation, only while the module is on) | history rows were never queued and nothing re-sends them, so the import cannot double them. A late EX-3 delivery would replay |
| Readers of `cash_movement.expense_voucher_no` | 1 writer (`stampVoucherNo`), 0 readers | the import does not stamp it; "unbooked" is decided by expense-service's key, not by that column |
| Writers of a DRAWER voucher | 1 (`recordFromDrawer`) | reused as is: categories may be switched off for a pay-out (EX-2e); the backdate window does not apply to the till |
| Farm rows (`agriculture_expense`) | 0 in both schemas | EX-9b |

## 4. Gate: `cypress/e2e/expense/ex-9a-till-history-import.cy.js` (shop tenant, owner.business)
1. ⭐ **Preview:** with the module off, two pay-outs made at the till (`Pay out`). With it on, both are listed under Past
   till pay-outs with their date, amount and reason; nothing is in the books yet.
2. ⭐ **Import once:** tick both, choose Rent, Import. Two till expenses in the books on their own dates, with 6000 +
   both, the till − both. The rows leave the list. Importing the same ids again books nothing.
3. ⭐ **A likely duplicate is flagged:** a third pay-out whose day and amount match an expense already recorded is
   listed **flagged** with that expense's number and unticked.
4. **Only owner/admin:** a member is refused (403); an id that is not one of this business's unbooked pay-outs is
   skipped, never booked.

## 5. As built
- **Seen red first:** 4/4 failed on the EX-6b build: `/api/expense/history/till` did not exist.
- **Green:** 4/4 after deploying business-service, expense-service and the monolith. One rerun was needed for a
  **test** expectation: the panel scrolls, and this shop has many past pay-outs, so the row is now scrolled into view
  before it is checked.
- **Found in the trace of my own design:** business-service's list holds the pay-outs **without an expense number**,
  oldest first, at most 500. An imported pay-out that was not stamped there would have stayed in that window forever,
  and newer history could never be reached. expense-service now stamps each import back
  (`POST /internal/business/drawer/payouts/{id}/expense-voucher`: only a PAY_OUT of the caller's business with no number
  yet). Best effort: the expense side's key already keeps it single, and a later import re-tries a missing stamp.
- **Regression:** all 23 expense gates **133/133** (run before EX-9b existed).
- **Verified in the DB:** each clean run's two pay-outs carry their EXP- numbers in `cash_movement`. The flagged
  "paid twice" pay-out stays unbooked, by design. The shop's own 16 older pay-outs are untouched.
- **Test data left on the demo shop (org 6):** each gate run leaves one flagged "EX9A paid twice" pay-out unbooked, and
  the red run left its three. They show in that shop's Past till pay-outs as real history would.
- **Changed files:**
  - contracts: `DrawerHistoryClient`, `DrawerPayoutView`.
  - business: `InternalDrawerHistoryController`; `CashMovementRepo.payoutsWithoutExpense/stampImported`.
  - expense: `ExpenseHistoryService`, `ExpenseVoucherRepo.sameDayAmount`, the `drawerHistoryClient` bean, two endpoints.
  - monolith: two proxies; the panel in `fragments/expense.html` and `expense.js`; 10 i18n keys × 6 languages.
- **Not covered:**
  - A date in closed books is refused by finance at posting and shows Post again (EX-1b). The preview cannot see the
    lock date, because finance's client has no period-lock read.
  - Choosing a category per row: one category per import, run again for another.
- **Next: EX-9b**, the farm. Its old expense screen converges onto Expenses (F3's hard delete goes), and its past rows
  come in through this same consent flow (0 rows in every tenant here).
