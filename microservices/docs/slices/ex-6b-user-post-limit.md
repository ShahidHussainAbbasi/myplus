# EX-6b — How much a member may post directly

**Status:** DONE 2026-10-10: gate 5/5 (seen red first), unit tests 7 new (expense-service 110/110), Test Book case 6-6. Programme: [`../expense-management-design.md`](../expense-management-design.md) §6.2
`expense.voucher.userPostLimit`, R-8, E5, E7. Owner's rulings 2026-10-09:
- **No limit by default.** Nothing changes for any business on deploy. The design's "0 = drafts only" default would
  have taken direct posting away from every member at once.
- **Above the limit, the expense is saved as a draft that waits** for an owner or admin to post it. It is not refused,
  so nothing typed is lost.

## 1. Document
Today any member posts an expense of any size straight into the books. An owner can now set **"A member may post up
to"** in Expenses → Settings:
- **Up to the limit:** a member's expense posts as before.
- **Above it:** it is saved and waits, shown as **Waiting to be posted**. An owner or admin presses **Post**, or anyone
  allowed **Discards** it.
- **0:** every member expense waits.
- **Blank:** no limit (the default).

## 1b. Standards
| Dimension | Rule |
|---|---|
| Who is limited | a member: anyone who is not an owner or admin (`ExpenseAccess.canApprove()` false). Owners and admins post any amount |
| What is limited | an expense recorded with "post" (`POST /vouchers?post=true`), bills included, and posting a draft (`POST /vouchers/{id}/post`), on the voucher's **total** (what was paid) |
| What is not | a claim (approved by an owner or admin already: EX-6); a till pay-out (the money already left the drawer: EX-3); post-again (it re-sends something already posted) |
| Above the limit, on save | saved as a DRAFT, no number, nothing to the books; the answer says so: "Saved. It is above the {limit} a member may post, so it waits for an owner or admin to post it." |
| Above the limit, posting a draft | refused in words for a member; an owner or admin posts it. It gets its number then, and goes to the books dated as the member dated it |
| Discard | the recorder (their own) or an owner/admin deletes a waiting draft (E7's DELETE, now reachable). A posted expense is voided instead, as always |
| The setting | `expense.voucher.userPostLimit`, MONEY, default **blank = no limit**; 0 or more is a limit; negative refused in words; Reset (or a blank box) = no limit |
| Seen on screen | a waiting draft is in the list (the member's own; everyone's for an owner/admin), chip **Waiting to be posted**, with Post (owner/admin) and Discard. It is not in "Total spent", the report or the P&L |
| Duplicate warning (EX-8b) | a waiting draft is a possible duplicate (the same bill typed twice), named "an expense waiting to be posted". It was named "a claim waiting for approval", which is wrong for it |

## 1c. RULE 0 trace
| What | Count | Finding |
|---|---|---|
| Paths that put an expense in the books | 5 | `record(post=true)` and `post(id)`: **limited**. `ExpenseClaimService.approve`, `recordFromDrawer`, `postAgain`: not, as above |
| Readers of `expense_voucher`, classified for a waiting DRAFT | 11 | **want it (2):** `search` (the list) and `sameExpense` (the duplicate warning). **exclude it (9):** `postedInRange`, `voidsInRange` (report), `totals` (Total spent), `billsInBooks`, `findBillsOfOrg`, `findOpenBills` (payables), the claim queues (claim status), bill pay (requires POSTED), void (requires POSTED). None needed a change; 1 label was wrong (the duplicate warning) |
| The list's chip for a non-claim DRAFT | 1 | fell through to **"Posting…"**, which would be false for a waiting draft |
| Monolith proxy for `/post` and `DELETE` | 0 | **E7: drafts were unreachable.** Both added, plus `/settings/reset` (blank = no limit) |
| Settings catalog | 1 key added | the catalog's own note had deferred it: "its design default would take posting away from users while drafts are unreachable on screen (E7)". Both causes are answered here |
| Gates that post as a member | 8+ specs (`user.education` / `user.business`) | the default is no limit, so all unchanged; the regression proves it |

## 4. Gate: `cypress/e2e/expense/ex-6b-user-post-limit.cy.js` (school tenant)
1. ⭐ **No limit by default:** a member's 500 posts directly and is in the books.
2. ⭐ **The owner sets 50 on screen.** The member's 80 is saved and waits: no number, not in the P&L, chip "Waiting to be
   posted", and the save says why. Their 30 posts.
3. ⭐ **A member cannot post the waiting one; the admin can.** The member's `/post` is refused in words. The admin
   presses Post: it gets a number, and the P&L rises by 80.
4. ⭐ **Discard:** the member discards their own waiting draft, and it is gone. A waiting draft is named in the duplicate
   warning as "an expense waiting to be posted".
5. **The setting's edges:** 0 makes every member expense wait; a negative limit is refused; Reset means no limit again.

## 5. As built
- **Seen red first:** cases 2–5 failed on the EX-8e build: no setting, no waiting draft, no Post or Discard on screen. Case 1
  passed already, by design: no limit is today's behaviour, and this case proves it stays.
- **Green:** 5/5 after deploying expense-service and the monolith. Two reruns were needed for **test** bugs, not the
  code. In case 4 a Cypress `.then` that returns `undefined` passes the previous subject on, so "the expense is gone"
  was asserted against the whole list; the check now looks for the payee in the list.
- **Found while writing the tests:** `SettingWriteGuard.checkReset` judges a RESET as a write of the default. The
  limit's default is blank, so the new guard's `new BigDecimal("")` would have **refused "back to no limit"**. The guard
  lets blank pass; `ExpenseSettingsTest.postLimitGuard` pins it.
- **Teardown verified:** `org_setting` holds 0 `userPostLimit` rows for the school afterwards (no limit, the default).
- **Changed files:**
  - expense: `ExpenseSettingsCatalog` (key, blank default, guard); `ExpenseSettings.userPostLimit`;
    `ExpenseVoucherService` (`aboveMemberLimit`, `record` keeps a draft, `post` refuses a member, the duplicate label);
    the controller's waiting sentence.
  - monolith proxy: `POST /vouchers/{id}/post`, `DELETE /vouchers/{id}`, `POST /settings/reset`.
  - `expense.js`: Waiting chip, Post and Discard, the limit box (blank → reset).
  - `fragments/expense.html` and 7 i18n keys × 6 languages.
- **E7 closed:** drafts are reachable on screen (Post and Discard).
- **Not covered:**
  - A notice to owners that something is waiting. The list shows it; a waiting count or alert is a later slice.
  - A limit per member: one limit covers every member.
