# EX-2d — The expense list pages and shows its total (E4)

**Status:** DONE 2026-10-09 — gate 5/5, unit 4 new (expense module 40/40), guide case 1-9.
Programme: [`../expense-management-design.md`](../expense-management-design.md) §11 finding **E4**. Branch
`feature/expense-management`.

## 1. Document
The Expenses list asked for `size=200` and drew what came back: past 200 rows it stopped without a word, there was no
"showing N of M", no way to the next rows, and no total — the first question an owner asks of a list of expenses.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Business | The total is **money spent**: posted expenses only (a void or a draft spent nothing). It covers the whole filter, not the page on screen |
| Security | The total has the **same scope as the list**: a user (cashier) gets their own expenses only (`ExpenseAccess.visibleUserId`) |
| Wire | Additive: a new `GET /vouchers/totals`; the list's `PageResponse` is unchanged (7 readers rely on `data.content`) |
| UX | 50 a page; "Showing a–b of N"; Previous/Next; a void or a payment redraws the page you are on; Search and a new expense go to page 1 |

## 1c. RULE 0 trace (done before code)
| What | Count | Finding |
|---|---|---|
| Readers of `GET /vouchers` | 1 screen + 9 spec calls | the screen (`expenseLoad`) and specs reading `data.content` with `size=200`/`50` — the response shape is kept, so none breaks |
| Callers of `expenseLoad` | 5 + 1 | Search button, open, save → page 1; void, pay → current page |
| Writers that change what the total sums | 3 | record+post (adds), void (removes), FAILED posting (status stays POSTED — still money spent; its row says Not posted) |
| `data-dp-iso` companions (date boxes feeding a hidden ISO field) | 9 | **found by the gate:** a date TYPED into any of them never reached the hidden field — only a calendar click did. Expenses date + From/To + due date, and 5 on the business dashboard. 8 have no listener; 1 (`instFirstDueDate`) is read by the installment preview on the visible box's change, which now sees the typed date |

## 2. Design
- expense-service: `ExpenseVoucherRepo.totals(org, userId, from, to)` — `COUNT`, `COALESCE(SUM(total),0)` where status is
  POSTED; `ExpenseVoucherService.totals` with the list's scope; `GET /vouchers/totals`.
- Monolith proxy `GET /expense/vouchers/totals`.
- `expense.js`: page state, `pager()`, `loadTotals()`, `expensePage(±1)`; `fragments/expense.html` footer `#expPager`.
- `date-picker.js` `mirrorTyped`: a complete, real typed date (it formats back to exactly what was typed — `31-02` is not
  read as 3 March) is mirrored to the companion; an emptied box empties it; a half-typed value leaves it alone.

## 4. Gate (written first) — `cypress/e2e/expense/ex-2d-list-paging.cy.js` (school tenant)
1. 55 expenses today: "Showing 1–50 of N", Next → "51–…", Previous back; row ids match the API's pages.
2. The footer total equals the sum of every posted row on every page (read page by page).
3. Voiding a row on page 2 keeps page 2 and takes it out of the total.
4. A typed date filters; an impossible typed date does not; an empty period says so with no footer.
5. Security: a user's total = their own posted rows; the owner's is larger.

Red before the code for the right reasons (74 rows on one page; no totals endpoint; the typed From ignored).

## 5. As built
- As designed. Guide case 1-8 had worked around the typed-date defect in the spec (`invoke('val', y)`); the workaround is
  removed and the case now asserts the typed day reaches the form.
- Regression: all 10 expense gates, pos-sale-endtoend, installment-down-payment, pos-checkout-chain green.
  Two failures **not caused by this slice**, recorded: `education-modal-keyboard` (demo.education lacks `class.edit` →
  403 on `/addGrade`; demo accounts carry no permission set — same class as demo.business/`product.create`) and
  `installment-screen` (owner.business's plan does not include installments). Queued as a separate task.
