# EX-2e — Owners manage their own expense categories (E6)

**Status:** DONE 2026-10-09 — gate 5/5, unit 6 new (expense module 46/46), guide case 0b-2.
Programme: [`../expense-management-design.md`](../expense-management-design.md) §11 finding **E6**.

## 1. Document
Owners had the eight seeded categories and no way to change them: the API could add and edit, but the monolith
proxied GET and POST only and no screen called either.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Business | A category points at an EXPENSE account, never 5000 (goods for stock are a purchase) — the server's existing rule, now also the screen's list. Names are unique per business. At least one category stays on. Categories are never deleted: past expenses point at them |
| History | A rename or a new account never rewrites a recorded expense — lines snapshot `categoryName` and `accountCode` (checked: `ExpenseVoucherService.build`, `recordFromDrawer`) |
| Security | Owner/admin only: `@PreAuthorize` on POST/PATCH/accounts in expense-service; the button is `sec:authorize`d |
| UX | Till → Expenses → **Categories**: one row per category (name, account, On, Save); an Add row; the server's words shown; the form's Category list re-read after each change |

## 1c. RULE 0 trace (done before code)
| What | Count | Finding |
|---|---|---|
| Readers of categories | 4 | the Expenses form (filters `active`), the till pay-out form `till.js` (filters `active`), voucher build (`activeCategory`), drawer receiver (`activeCategory`) |
| Writers | 3 | seed, create, update — update had no name-uniqueness or keep-one-on rule |
| **Drawer path** | 1 | a till pay-out is chosen on screen and delivered later by business-service's outbox; if the owner switched its category off in between, `recordFromDrawer` refused it **permanently** — cash out of the drawer, never in the books. Now `categoryForDrawer` accepts an existing category even if switched off |
| History readers | 2 | `LineView` and posting read the line snapshot, not the category — a rename cannot rewrite history |
| Proxy | 1 | PATCH and `GET /categories/accounts` added (JDK HttpClient carries PATCH) |

## 2. Design
expense-service: `GET /categories/accounts` (owner/admin) → `ExpenseAccountView(code,name)`; create derives the code from
the name (numbered if taken); unique names on create/rename; keep-one-on; audit says what changed. Monolith: PATCH +
accounts proxy. `fragments/expense.html` `#expCatPanel`; `expense.js` `expenseCategoriesToggle/Save/Add`.

## 4. Gate — `cypress/e2e/expense/ex-2e-categories.cy.js` (farm tenant)
1. The panel lists every category; the account list equals finance's EXPENSE accounts minus 5000.
2. Add → the form offers it → an expense with it posts to the chosen account.
3. Rename + switch off → the form drops it; the past expense keeps its name and account.
4. Duplicate name and a non-expense account refused in the server's words.
5. A user: no button; PATCH refused; accounts 403; nothing changed.
Red before the code (no screen).

## 5. As built
- The gate first assumed "expense accounts start with 6"; finance's chart has **5100 Purchases / Expenses** (EXPENSE).
  The gate now compares with the chart itself.
- EX-2d consequence found by the regression: specs and guide case 1-8 looked for a yesterday-dated row on page 1 of a
  newest-first list that now holds 110+ of today's rows — they now filter to that day, as a person would; the guide's
  leftover sweep reads every page.
- Regression: all 11 expense gates green.
