# EX-9b — Farm expenses converge onto Expenses (and past ones come in, with consent)

**Status:** DONE 2026-10-10: gate 5/5 (seen red first); land-scope gate 3/3 (seen red first); unit tests 5 new in expense-service (119/119) + 1 repository test in agriculture-service (3/3, real MySQL); Test Book case 2a-6. Programme: [`../expense-management-design.md`](../expense-management-design.md) EX-9, R-4, F3,
E12. Follows [`ex-9a-till-history-import.md`](ex-9a-till-history-import.md) (the same consent flow).

## 1. Document
A farm has had two expense screens since EX-2a:
- **Expenses**: the shared module. It reaches the books and can be tagged to a land.
- **Add Expense**: the old one (`agriculture_expense`). Its rows never reach the books and are **hard-deleted** (F3).

EX-9b makes Expenses the farm's one expense screen, on the same terms as the till (EX-3):
- **Expense management on:** the old Add Expense is hidden, and its endpoint refuses in words ("record it in
  Expenses, where it reaches your books").
- **Off:** nothing changes. The old screen is still the farm's expense screen, as before.
- **Past farm expenses** (owner/admin, farm dashboard): the old rows, previewed, ticked and imported once each, as
  EX-9a does for the till. Each is tagged to its land and stamped back on the farm side.
- **F3:** a farm row that is in the books can no longer be hard-deleted. A mistake is voided in Expenses, with a reason.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Consent (R-4) | nothing automatic; only ticked rows; owner/admin |
| Once per row | keyed `(FARM, agri_expense_id)` on the voucher's existing unique `(organization_id, source, source_ref)`; re-import skips |
| Never the browser's rows | import sends ids only; the server re-reads agriculture-service's list for the caller's farm |
| What it books | Dr the chosen category's account, Cr **Cash** (an old farm row has no paid-from); on the row's own date; payee = the expense name; the line tagged **LAND** (id and name confirmed by agriculture's tag list as the caller) when the land still exists, untagged otherwise; the crop kept in the line's description |
| A likely duplicate | same day and amount as an expense already recorded: flagged by its number, unticked (EX-9a's rule) |
| The old screen with the module on | hidden (`data-capability-off`, new in `capabilities.js`: hidden while a capability is ON; fails open like the rest) **and** refused on the server (`addAgricultureExpense`), so a hidden screen is not the only control |
| F3 | `deleteAgricultureExpense` refuses a row that carries an expense number |
| Stamp | `agriculture_expense.expense_voucher_no` (V6, VARCHAR(20) NULL; under `validate` the entity has `length = 20`) |
| The farm notice (`ui.farmBooksNote`) | was "its own Income/Expense records never reach the books … until EX-9". Expenses now do: the note names **income** only |

## 1c. RULE 0 trace
| What | Count | Finding |
|---|---|---|
| Rows in `agriculture_expense` | **0** in `myplusdb_agriculture` and 0 in the legacy `myplusdb` copy | eligibility is shown by the gate's own rows; production unknown here |
| Readers of `agriculture_expense` | 1 (its own controller: add, list, last crop, delete) | no farm report sums it, so moving rows into the books changes no other farm screen |
| Callers of the old screen | sidebar link + section select (`agricultureDashboard.html`), `agriculture.js`, the monolith proxy | the two menu entries carry the inverse capability; the endpoint refuses |
| Crop as a dimension | no crop entity: a crop is text on the land and the row | kept in the line description; a crop tag is not built |
| Readers of `expense_voucher.source` | 4 (`VoucherView`, EX-3's replay lookup, EX-9a's lookup, the void rule for DRAWER) | FARM is voidable like MANUAL; only DRAWER is corrected at the till |
| `capabilities.js` consumers | every dashboard | the new attribute is additive; 0 existing elements carry it |
| Module-on in agriculture-service | the caller's token (`CurrentUser.capabilities()`), as business's till does | unresolved = off, so the old behaviour stays |

## 4. Gate: `cypress/e2e/expense/ex-9b-farm-convergence.cy.js` (farm tenant, owner.agriculture)
1. ⭐ **Module off: unchanged.** Old farm expenses are recorded as before (two of them, on a land). The old Add Expense
   is offered.
2. ⭐ **Module on: one screen.** The old Add Expense is hidden, and its endpoint refuses in words.
3. ⭐ **Past farm expenses:** both rows are listed. Imported under a category, each is in the books on its own date,
   tagged to its land, and stamped on the farm side. Importing again books nothing.
4. ⭐ **F3:** the imported row cannot be hard-deleted ("void it in Expenses").
5. **Only owner/admin;** a likely duplicate is flagged and unticked.

## 5. As built
- **Seen red first:** cases 2–5 failed on the EX-9a build. Case 1 passed, by design: with the module off nothing
  changes, and this case proves it.
- **Green:** 5/5 on the first run after deploying agriculture-service (V6, validated and applied: "now at version v6"),
  expense-service and the monolith.
- **Regression:** every expense and agriculture gate (`cypress/e2e/expense/*.cy.js`, `cypress/e2e/agriculture/*.cy.js`) **153/153**, including EX-2c with the new farm notice and EX-9a with the shared history panel.
- **Verified in the DB:** the clean run's two farm rows carry `EXP-000080` / `EXP-000081` in
  `agriculture_expense.expense_voucher_no`.
- **Test data left on the demo farm (org 10):** the red run left 2 rows unimported: a seed, and a row the old endpoint
  accepted before it refused. They show under Past farm expenses as real history would.

### The land lookup (owner's request, done in this slice)
Found while tracing this slice: `addAgricultureExpense` **and** `addAgricultureIncome` resolved the land with an
unscoped `landService.findById`.

The red run proved both defects in the DB:
- **A cross-business leak.** Org 25 (`demo.agriculture`) saved an expense and an income carrying org 10's land
  (`land_id 1`, name "EX2B Field …").
- **A stored bogus id.** An id that is no land was not dropped, as the code comment claimed: the DTO mapping had
  already copied it, so `land_id 999999999` was stored.

The fix:
- `LandRepo.findScopedById`, with the same boundary as `findScoped`: the caller's org, or their own pre-migration row.
- `ILandService.findScopedById`.
- Both add paths refuse in words ("That land was not found, or is not one of yours.") before anything is saved.

Callers of the unscoped `findById` on a request's land id went from 2 to 0. The 2 `loadLast…CropAttached` endpoints
already filtered the caller's own rows. The 3 bad rows from the red run are in demo org 25 and were left as found.

Gate `cypress/e2e/agriculture/land-scope.cy.js`: 3/3 failed first, then 3/3 passed. Repository test
`land_findScopedById_only_the_callers_land`: own land and own pre-migration row are found; another tenant's land and
another user's pre-migration row are not.

### Changed files
- agriculture:
  - V6;
  - `AgricultureExpense`/DTO `expenseVoucherNo`;
  - controller (module-on refusal, F3, scoped land);
  - `AgricultureIncomeController` (scoped land);
  - `AgricultureExpenseRepo.notInBooks/stampImported`;
  - `InternalFarmHistoryController`;
  - `LandRepo/ILandService/LandService.findScopedById`.
- contracts: `FarmHistoryClient`, `FarmExpenseView`.
- expense: `SOURCE_FARM`, `recordFromFarm`, `ExpenseHistoryService.farmPreview/importFarm`, a client bean, two
  endpoints.
- monolith:
  - two proxies;
  - `capabilities.js` (`data-capability-off`);
  - the two old farm menu entries;
  - the farm panel; `expense.js` (one history implementation for till and farm);
  - 4 new i18n keys and `ui.farmBooksNote` (now income only) × 6 languages;
  - the EX-2c gate's and the Test Book's notice text.

### Not covered
- Farm **income** still does not reach the books (the notice says so).
- A crop as its own dimension (no crop entity).
- The old rows of a farm whose module stays off: they are imported when it switches on.
