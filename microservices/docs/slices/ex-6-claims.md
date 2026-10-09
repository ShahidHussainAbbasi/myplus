# EX-6 — Expense claims

**Status:** DONE 2026-10-09: gate 8/8 (seen red first, twice), unit tests 12 new (expense-service 77/77, common-settings
70/70), expense regression 95/95,
Test Book cases 6-1 to 6-5. Programme: [`../expense-management-design.md`](../expense-management-design.md) §5.3 (claim),
§6.1 `EXPENSE_CLAIMS`, §10 EX-6. Reimbursement and advances are EX-7.

## 1. Document
Staff pay for small things out of their own pocket: fuel, a taxi, printer paper. Until now the business had no way to
record that it owes the money back. A claim records it. An owner or admin approves it, and from then on it is in the
books as an expense the business owes to that member (Cr **2300 Employee Reimbursements Payable**). EX-7 pays it back
(Dr 2300 / Cr cash or bank).

## 1b. Standards
| Dimension | Rule |
|---|---|
| Capability | `EXPENSE_CLAIMS` (`org.cap.expenseClaims`), opt-in (off until switched on), needs Expense management. **Not in FREE**: under the pricing rule ("without it, can the shop complete a sale?") a shop can sell without claims. TRIAL, DEMO and PRO include it |
| One aggregate, not two (**deviation from §5.3**) | A claim is an `ExpenseVoucher` with `paidFrom = EMPLOYEE` and its own `claim_status` (SUBMITTED, then APPROVED, REJECTED or WITHDRAWN). The design sketched a separate claim aggregate that "produces" a voucher. It would have duplicated every rule an expense already has: lines, categories, what it was for, the date window, receipts and the receipt rule, numbering, posting, the outbox, void. The voucher stays DRAFT while it waits; **approving posts it through `postInTx`**, the one path every expense takes. Payment state will live on the reimbursement (EX-7), as §5.3 says |
| Who decides | Anyone may submit. An **owner or admin** approves or rejects, **never their own claim** (refused in words). The platform operator does not decide: `canApprove` checks ROLE_OWNER / ADMIN_PRIVILEGE, not SUPER. Rejecting needs a reason the claimant sees. The claimant (or an owner or admin) may withdraw a claim while it waits. A decided claim cannot be decided again |
| No other door | Recording or posting EMPLOYEE through `/vouchers` is refused ("Money you paid yourself is a claim…"). `/vouchers/{id}/post` on a claim is refused ("…when an owner or admin approves it"). Pay is for bills only. A claim is withdrawn, never deleted, so its trail is kept |
| Money | A waiting, rejected or withdrawn claim is not money spent: the list total counts POSTED only. An approved claim is Dr the category / Cr 2300, with no cash or bank moved. finance's `ExpensePostingRules` already allowed 2300 (EX-0b), so no finance change |
| Approver's queue | An owner or admin sees "N claims are waiting for approval" above the list, with **Show them** (the list filtered to `claim=SUBMITTED`) |
| Screen | Paid from offers **Me — claim it back** only while the capability is on (read from the session's capabilities). Save then reads **Send for approval**, with a hint under it. A claim's row shows "Claim · who", the waiting, rejected (with reason) or withdrawn chip, and Withdraw, Approve or Reject as fits |

## 1c. RULE 0 trace
| What | Count | Finding |
|---|---|---|
| Queries over `expense_voucher` | 5 | `search` **wants** claims (it gains `:claim`, for the approver's queue); `totals` counts POSTED only, so a waiting claim is **correctly excluded** and an approved one counted; `findOpenBills`, `billsInBooks` and `findBillsOfOrg` are AP only, so **unaffected** |
| Writers | 5 | `post` and `deleteDraft` refuse a claim (added); `voidWith` refuses a DRAFT, so a waiting claim cannot be voided, while an approved one can be and its reversal clears 2300. **EX-7 must refuse that void once reimbursed.** `postAgain` is generic; `pay` refuses anything that is not a bill |
| Capability on the request | 1 | read from the **token** (`CurrentUser.capabilities()`), resolved at mint by auth-service, so **auth-service must carry the new enum value** (deployed with this slice). A member's session picks it up at their next sign-in or re-mint |
| Wire | 2 | the monolith proxies `/expense/claims` (Idempotency-Key as a parameter, like the other proxies) and `/expense/claims/{id}/{approve or reject or withdraw}`; the list passes `claim` through |
| Column types against the entity | 5 | `claim_status VARCHAR(16)`, `claimant_name VARCHAR(160)`, `decided_by BIGINT`, `decided_at DATETIME`, `decision_note VARCHAR(255)`, all matching String/Long/LocalDateTime under `ddl-auto=validate` (V9 applied, service started) |
| Keyboard chain | 0 | the expense form has none (no other file names its fields) |
| Who the page is | 0 | the page does not know the signed-in member's id, so Approve and Reject show on every waiting claim for an owner or admin. The server refuses their own claim in words (gate case 3) |

## 4. Gate: `cypress/e2e/expense/ex-6-claims.cy.js` (school tenant)
The plan is lifted to PRO if it is FREE, and put back after the run.
1. A user chooses **Me — claim it back**: Save reads Send for approval and the hint shows. The row is waiting, with
   "Claim · user", no number and Withdraw. The period total is unchanged.
2. The owner sees "1 claim is waiting", filters with Show them, and approves. The row gets EXP-nnnnnn and reaches In the
   books. Trial balance: 2300 −35, 6000 +35, cash and bank 0.
3. The admin's own claim: approve and reject refused in words; a user gets 403 or 404; the owner approves it.
4. The owner rejects with a reason. The claimant reads it on their row. Trial balance unchanged.
5. The claimant withdraws; approving afterwards is refused ("already decided").
6. EMPLOYEE through `/vouchers` is refused; `/post` on a claim is refused; pay is refused.
7. Claims switched off: the choice is gone, and the server refuses a claim in words.
8. The same PDF on a claim still waiting is warned about ("a claim waiting for approval"); once the claim is withdrawn,
   it is not. (Case 4 also checks that a rejected claim offers no "Add a receipt" and the server refuses one.)

## 5. As built
- **Seen red first:** before the deploy the gate stopped in `before()`, because the running auth-service did not know
  `org.cap.expenseClaims` and refused the switch. After deploying auth, expense and the monolith it passed 7/7.
- **Found by looking at the recorded screens, not by the first gate:** a rejected or withdrawn claim still offered
  **Add a receipt**, and the server accepted it. Tracing the receipt code found a second, quieter gap: the duplicate
  warning names other expenses **by number**, and a waiting claim has none, so **the same bill on a waiting claim was
  never warned about** (it could be claimed and also recorded in cash). Both fixed in `ReceiptService` (`writable`,
  `alsoOn`; an ordinary unposted draft is still not named, as before), the screen hides the button, and the gate gained
  case 8 plus a check in case 4. Both were seen red on the old build, then green.
- **Not built here (said plainly):** the amount threshold in R-8 and the USER-tier post limit `userPostLimit` (§6.2),
  which the E5 row had pointed at EX-6. It limits **direct** cash or bank expenses by a user, not claims, so it is now
  its own slice, **EX-6b**. Today a user posts any amount directly, as before this slice.
- Kept for EX-7: a claim approved and then **reimbursed** must not be voidable (today nothing is reimbursed, so a void of
  an approved claim is a correct reversal of 2300).
