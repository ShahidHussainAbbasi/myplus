# EX-2f — Expense settings (E5)

**Status:** DONE 2026-10-09 — gate 4/4, unit 3 new (expense module 49/49), guide case 1-10.
Programme: [`../expense-management-design.md`](../expense-management-design.md) §6.2, §11 finding **E5**.

## 1. Document
§6.2's settings were never built. The date window was a constant `BACKDATE_DAYS = 365` in code (design: a setting,
default 30), and the New Expense form always started on Cash.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Engine | The shared `common-settings` engine (catalog + typed reads + write guards + cache eviction), backed by expense-service's own `org_setting` table (Flyway V6) — the same shape as welfare/education/business |
| Registered = read | Only keys a behaviour READS are registered: `expense.voucher.backdateDays` (server, `build`) and `expense.voucher.defaultPaidFrom` (the form, on open and after each save) |
| Bounds | Guard on write (0–3650, refused in words) AND clamp on read (a stored value outside the range cannot widen the window) |
| Security | Read: every member (the form needs the default). Change/reset: owner/admin (`@PreAuthorize`) |
| Not yet | `userPostLimit` — its design default "users record drafts only" would remove posting from users while drafts are unreachable on screen (E7); lands with claims/approval (EX-6). `receipt.requiredAbove` → EX-5. `tax.inputRecoverable` → EX-8 |

## 1c. RULE 0 trace
| What | Count | Finding |
|---|---|---|
| Readers of `BACKDATE_DAYS` | 1 | `ExpenseVoucherService.build` (manual vouchers). The drawer receiver is dated by business-service (today) and has no window — unaffected |
| Specs depending on the 365 window | 0 | grep over expense gates + guide |
| Gateway path | 1 | the shared `SettingsController` answers at `/settings`; the gateway forwards only `/api/expense/**` unchanged → a thin `ExpenseSettingsController` at `/api/expense/settings` delegates to the same `SettingsService` |
| Constructor callers of `ExpenseVoucherService` | 2 tests | gain the `ExpenseSettings` argument |
| Form paths that set Paid from | 3 | open (`showExpenses`, after suppliers rebuild the list), after a save, the Bill toggle — the default is applied after the supplier list is rebuilt so it is not overwritten |

## 4. Gate — `cypress/e2e/expense/ex-2f-settings.cy.js` (welfare tenant)
1. Defaults 30 / CASH; 30 days back accepted, 31 refused in words; the form starts on Cash.
2. Owner sets 5 on the panel (-1 refused in words): 6 back refused, 5 accepted.
3. Default Bank reaches the form on opening and again after a save.
4. A user: the form starts on Bank; no Settings button; a change is refused; the value is unchanged.
Red before the code (no settings endpoint).
