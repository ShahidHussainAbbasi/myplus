# EX-8e — The expense report by branch

**Status:** DONE 2026-10-09: gate 5/5 (seen red first), unit tests 5 new (expense-service 104/104), Test Book case 9-7. Programme: [`../expense-management-design.md`](../expense-management-design.md) §8 EX-8, E8.
Owner's ruling 2026-10-09: **the branch is taken automatically**. An expense carries the branch the person is working in
(the active branch in their sign-in), with no picker. Nobody can record an expense against another branch, even one
they also hold.

## 1. Document
A business with several branches (a shop's stores, a school's branches) wants to see what each branch spent. The voucher
has always had a `store_id`, but only the till's pay-outs filled it. Since EX-8a (E8) a branch sent by the browser is
refused, because nothing could check it.

EX-8e:
- **Stamps** every expense recorded by a person with their active branch: direct expenses, bills and claims.
- **Groups** the report by branch, with the branch's own name.
- **Adds** a Branch column to the CSV.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Where the branch comes from | `LocationScope.active()`: the signed token's `activeLocationId`, stamped by the gateway as `X-Location-Id`. Auth sets it to the one branch a person holds, or to the one they switched to; it is null when they hold none, or hold several and have not chosen. A client cannot widen it (auth honours a switch only to a granted branch) |
| A branch in the request | ignored if it equals the active branch; otherwise **refused in words** ("An expense is recorded for the branch you are working in…"). Never stored from the browser (E8 stays closed) |
| No active branch | the expense has no branch (an owner without grants, a single-branch business). That is what every expense was before EX-8e. The report shows it as **No branch** |
| The till | unchanged: business-service stamps the drawer's own store (`recordFromDrawer`) |
| Branch names | from the module that owns them, as the caller: a school's branches from education (its SCHOOL rows on the expense-tag SPI, already scoped to the caller); a shop's stores from business-service (new STORE rows on the same SPI, **never offered as a line tag**, like SUPPLIER). A branch neither module names for this caller reads **Branch #id**. Names are best effort: a module down leaves the report adding up, with numbers for names |
| Who sees what | unchanged from EX-8a: an owner or admin sees the business; a member sees their own. Grouping by branch does not widen anyone's view |
| The CSV | gains **Branch** before Input tax; Amount and Kind stay the last two cells |

## 1c. RULE 0 trace
| What | Count | Finding |
|---|---|---|
| Writers of `expense_voucher.store_id` | 2 | `build()` (direct expenses, bills and claims: refused any request value since E8; now stamps the active branch); `recordFromDrawer` (the till: trusted, unchanged) |
| Callers of `build()` | 2 | `ExpenseVoucherService.create`, `ExpenseClaimService.submit`: both should carry the person's branch |
| Readers of `store_id` | 1 → 2 | `VoucherView` (shown nowhere); the report (new) |
| Who knows a caller's branches | 1 rule | `LocationScope` (common-security). `canAccess` alone could not validate a branch id: it answers **true for any id** for an owner, or for anyone without grants, including another tenant's. "Automatic only" never takes an id from the client, so there is nothing left to validate |
| Consumers of `/expense-tags` | 1 (`ExpenseTagService`) | business rows reach only `suppliers()` (SUPPLIER filter) and the new `branchNames()`. The line picker (`bySource`) never asks business, so a STORE row can never become a line tag |
| Demo tenants with branches | **school (org 7): 3 branches** (schools 2, 3, 4); stores: **0 in every tenant** | the gate runs on the school; business STORE names are unit-tested only |
| Existing grants | 1 (user 75 → school 4) | the gate grants `user.education` one branch and revokes it in teardown (`replace: true`, empty list) |
| Leftover data | org 7 has 1 voucher stamped `store_id = 999999` (accepted before E8 was fixed) | the report names it **Branch #999999**: shown, never dropped |

## 4. Gate: `cypress/e2e/expense/ex-8e-report-by-branch.cy.js` (school tenant)
1. ⭐ **Automatic:** `user.education`, granted one branch (school 3), records an expense with no branch in the request.
   It carries branch 3.
2. ⭐ **Never from the browser:** the same user sending another branch (school 4, or 999999) is refused; sending their
   own branch is accepted. The owner (no grants) records an expense with no branch.
3. ⭐ **The report by branch:** the owner groups by Branch. The user's amount is under that branch's name (from
   education), the owner's under **No branch**, and 999999 reads **Branch #999999**. The total equals the total by
   category.
4. ⭐ **On screen:** Report → Group by **Branch** → Show lists the branch rows.
5. The CSV has a Branch column whose rows add up to the report.

## 5. As built
- **Seen red first:** 5/5 failed on the EX-8d build. In case 1 the member, granted one branch, recorded an expense that
  carried `null`; the report refused `by=branch`; the screen had no Branch option.
- **Green:** 5/5 after deploying business-service, expense-service and the monolith.
- **Real data afterwards (school, today):** No branch 18,174.00 (2,517 lines, everything before EX-8e); **Branch
  #999999** 3.00 (EX-8a's red-run voucher from before E8 was fixed: shown, not dropped); CY Branch 1 0.00 (the gate's
  expenses and their voids). The total equals the report by category.
- **Teardown verified in the DB:** `user_location_access` again holds only user 75 → school 4, as before.
- **Changed files:**
  - expense: `ExpenseVoucherService.build` (stamps `LocationScope.active()`; refuses another branch);
    `ExpenseTagService.branchNames`; `ExpenseReportService` (`branch` grouping, CSV Branch column, `Entry.branchId`).
  - business: `ExpenseTagController` STORE rows.
  - monolith: the Branch option in `fragments/expense.html`, plus `ui.expReportBranch` in 6 languages.
- **Unit tests:** `ExpenseReportServiceTest.byBranch` and `branchIsTheActiveOne`; `ExpenseTagServiceTest.branchNames`,
  `branchNamesBestEffort` and `storeIsNeverALineTag`. The E8 test still holds: with no active branch, any id is refused.
- **Build note:** business-service needed `commerce-contracts` re-installed locally. The jar in `~/.m2` predated HMS's
  `PartyCustomerRef`. This is a local-cache issue, not a code change.
- **Not covered:**
  - Seeing only one's own branches in the report and the list: visibility stays as EX-8a has it (owner/admin
    everything, member their own).
  - A branch on the expense list's rows.
  - A shop's store names on a real gate: 0 tenants have stores; they are unit-tested through the SPI.
