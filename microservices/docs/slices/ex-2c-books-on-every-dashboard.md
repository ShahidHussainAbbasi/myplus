# EX-2c — The books on every dashboard (E2), and an honest welfare notice (E3)

**Status:** DONE 2026-10-09 — gate 6/6, guide case 2a-5. Programme: [`../expense-management-design.md`](../expense-management-design.md) §11
findings **E2**, **E3**. Branch `feature/expense-management`.

## 1. Document
EX-2a put Expenses on the school, welfare and farm dashboards. Their expenses post to the books — and nobody there can
see the books: Trial Balance, Profit & Loss, Balance Sheet and Period Close exist only on the business dashboard. The
module's whole value ("see it in your profit and loss", §1) is invisible to three of the four business types.

Welfare is told "Each expense is posted to your books", but donations never reach the books yet (§4b F5): its P&L would
show only spending. Until welfare's books are built, the screen must say so.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Business | The same statements for every business type; Tax Register only where the business trades (a school or a trust has no sales tax to register). Welfare's statements carry a notice until donations post |
| Security | Financial statements are **owner/admin** (+ the platform operator, support only). Enforced in **finance-service**, the owner of the data, not only by hiding a menu |
| DRY | One fragment `fragments/finance.html :: financeSection(withTax, booksNote)` and one script `/js/common/finance-reports.js`, moved out of the business dashboard and `business.js` unchanged — every dashboard runs the same code |
| UX | One menu entry per dashboard, in that dashboard's own navigation idiom; the view opens on Profit & Loss for this month |

## 1c. RULE 0 trace (done before code)
| What | Count | Finding |
|---|---|---|
| finance read endpoints with no authority check | 5 | `trial-balance`, `accounts/{id}/ledger`, `pnl`, `balance-sheet`, `tax-register` — **any member (a cashier) could read the P&L and balance sheet** by calling the monolith's `/gl/*` or the gateway; the menu was only hidden. Fixed: `@PreAuthorize` owner/admin/super |
| Internal callers of those 5 | 0 | none (grep over every service). `accounts` and `period-lock` GET stay open: expense-service and business-service read them with the caller's identity |
| Gates reading them | 30 | every one reads as an owner (checked file by file) — none breaks |
| Readers of the moved code | 1 + 3 | `business.js` callers (`showFinance`, the shims, menu `onclick`s) — globals kept; the 3 new dashboards. Dependencies: `escHtml` (dom-safe.js), `t` (header → i18n.js), `uiConfirm`/`uiAlert` (confirm-dialog.js) are on every dashboard; **`dateToYMD` (main.js) is not** → the script carries its own |
| `window.canClosePeriod` | 1 | set inside the markup (ADMIN_PRIVILEGE) — moves with it |
| `expenseSection` includes | 4 | each gains the `booksNote` argument |

## 2. Design
- `fragments/finance.html :: financeSection(withTax, booksNote)` — the `#FinanceDiv` markup, `sec:authorize` owner/admin.
- `/js/common/finance-reports.js` — `FIN_REPORTS` … `finReopenPeriod` and the `open*` shims, moved verbatim from
  `business.js`; `finYmd` replaces `dateToYMD`; a report whose tab is absent falls back to P&L.
- Menus: business keeps its Finance dropdown; education gets a Finance dropdown; welfare and farm get a sidebar link.
- `expenseSection(tagSource, booksNote)`; welfare passes `'welfare'` to both fragments → `ui.welfareBooksNote`.
- finance-service `GlController`: owner/admin/super on the 5 read endpoints.

## 4. Gate (written first) — `cypress/e2e/expense/ex-2c-books-everywhere.cy.js`
1. School owner: Finance → P&L opens; an expense recorded on the school dashboard is in it (6000) and the Trial Balance
   says Balanced. No Tax Register tab.
2. Farm owner: the same, from the sidebar.
3. Welfare owner: P&L opens with the notice; the Expenses screen shows the notice instead of "posted to your books".
4. Business owner: unchanged — Finance menu, all six tabs including Tax Register.
5. Security: a user-tier token reading P&L / Trial Balance / Balance Sheet → 403 (gateway) and refused (monolith); an
   admin → 200; a user sees no Finance menu.

## 5. As built
- As designed (§2). `openPeriodClose` stays in `business.js` (it is the business sidebar's own dialog); the Finance
  fragment's Period Close tab works on every dashboard through `finance-reports.js`.
- Gate `ex-2c-books-everywhere.cy.js`: **6/6** on the deployed build. It was red before the code for the right reasons
  (no Finance entry on 3 dashboards; the welfare screen said "posted to your books"; a user token read P&L → 200).
- Unit: finance-service tests pass with the `@PreAuthorize` change.
- **Found while recording (Rule 0: looked at the screen):**
  1. The farm P&L said "Income: None" — agriculture-service posts nothing to finance (grep: 0 outbox/finance
     references), so the farm's own Income/Expense records are absent. Same dishonesty as E3, unflagged → a farm notice
     `ui.farmBooksNote` (`booksNote='farm'`), lifted by EX-9.
  2. The heading promised a "tax register" on dashboards without one → `ui.generalLedgerStatementsAuditTrail` when
     `withTax` is false. Both are asserted in the gate and in guide case 2a-5.
- Regression: 78/78 across finance-report-dialogs, period-close, gl, finance-statements, fees-to-gl and all 9 expense
  gates; re-run of ex-2c + ex-2a after the two fixes 19/19.
- Guide: case **2a-5** (recorded) in the Expense Test Book.
