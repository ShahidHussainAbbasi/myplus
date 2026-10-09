# EX-7b — Advances to staff

**Status:** DONE 2026-10-09: gate 5/5 (seen red first), unit tests 11 new (finance 91/91, expense-service 88/88), Test Book cases 8-1 to 8-3. Programme: [`../expense-management-design.md`](../expense-management-design.md) §5.3,
§6 (`1300 Employee Advance`), §10 EX-7. Follows [`ex-7a-claim-payback.md`](ex-7a-claim-payback.md).

## 1. Document
A member is often given cash **before** they spend it (fuel for a trip, a market run). Today that money leaves the till
with nothing in the books to say who holds it. An **advance** records it: the business is owed that money back by the
member (1300 Employee Advance, an asset) until the member either spends it on the business (an approved claim
**settled against the advance**) or hands back what is left.

| Event | Journal | Money moves? |
|---|---|---|
| Give an advance | Dr **1300** / Cr 1000 or 1010 | yes, out |
| Settle an approved claim against it | Dr **2300** / Cr **1300** | no: the member already holds the cash |
| Take back what is unused | Dr 1000 or 1010 / Cr **1300** | yes, in |

## 1b. Standards
| Dimension | Rule |
|---|---|
| One payment ledger | All three are finance payments to the **EMPLOYEE** party (the member's user id), through the same reserve, call and confirm path as EX-7a: one Idempotency-Key per opening, find-before-send by reference on a lost answer, a reconciler for PENDING rows. A settlement is the claim's payment with method **`ADVANCE`**, a non-cash method like set-off's `SETOFF` |
| finance chooses the accounts | `PaymentRecordRequest.purpose` (new, additive): `ADVANCE` on a give or a take-back. finance resolves the lines and **stores them on the payment row** (`debit_account`, `credit_account`, nullable columns present since V1 and never used: 0 readers, 0 writers). A reversal **mirrors the stored accounts** rather than recomputing them. `cashAccount(method)`, shared with sale and purchase posting (5 call sites), is **not** touched: `ADVANCE` is resolved inside the EMPLOYEE branch only |
| Balance per member | `expense_advance_balance (organization_id, user_id)` holds what the member owes back, **row-locked** for every change. A settlement or take-back **reserves** (balance down) before finance is called, and a refusal releases it, so two pay-backs at once can never spend the same advance twice. A give adds to the balance only once finance has **confirmed** it |
| Who | Owner or admin gives, settles and takes back, never for themselves (as EX-7a). Recipients are the business's **staff** (OWNER, ADMIN, USER from auth's `/api/auth/org/users`), never guardians or students; the server re-checks membership |
| Capability | under **Expense claims** (`EXPENSE_CLAIMS`): advances are its second half (design §6.1) |
| Screen | **Advances** (owner/admin): each member's balance, a **Give advance** form (member, amount, cash or bank) and **Take back** per member. The claim's Pay back panel offers **From advance (balance X)** when the claimant holds one. A member sees their own balance |

## 1c. RULE 0 trace (before code)
| What | Count | Finding |
|---|---|---|
| Readers of finance payment `getMethod()` | 12 (PostingService 5, PaymentService 6, PayableStatementService 1) | PostingService's 5 are the sale/purchase postings through `cashAccount` (their callers never send ADVANCE); PaymentService's 6 are the record/reverse posting calls, the reversal's SETOFF guard and DTO mapping; the payable statement's one labels SETOFF and is VENDOR-only. **None** maps an unknown method to an account except `cashAccount`, which ADVANCE never reaches (it is resolved in the EMPLOYEE branch first) |
| `debit_account` / `credit_account` | 0 readers, 0 writers | free to carry the posted accounts |
| Member list | 1 | auth `GET /api/auth/org/users` (owner or `ADMIN_ROLE`; the school's admin token carries `ADMIN_ROLE`, checked): fields `userId, email, name, role`; 8 members in the school, of which 2 are not staff (guardian, student) |
| Writes to finance payments | internal only | `POST /internal/finance/payments` has **no gateway route** (`/internal/**` is not routed), so a browser cannot post an EMPLOYEE payment or an advance directly; it goes through expense-service's rules |
| `ExpenseBillService` constructor | 1 main (Spring) + 2 tests | gains the balance repository; both tests updated |
| Column types against the entities | 2 new tables, 23 columns | VARCHAR/DECIMAL(19,2)/DATE/DATETIME/BIGINT/INT as each field (`Integer @Version` ↔ `INT`); V10 applied and the service started under `ddl-auto=validate` |

## 4. Gate: `cypress/e2e/expense/ex-7b-advances.cy.js` (school tenant)
1. The owner opens **Advances**: staff only are offered (no guardian, no student). Gives User Education 50 in cash: Holds
   50.00, "Advance given — PV-". Trial balance: 1300 +50, 1000 −50, 2300 and 2000 unchanged. The member reads "You hold
   an advance of 50.00" and has no Advances button.
2. An approved claim of 30 settled **From their advance (holds 50.00)**: Paid back. 2300 cleared by 30, 1300 −30, cash
   and bank unchanged. 20 still held.
3. **Take back** the 20 by bank (prompt pre-filled 20.00): "RCPT-", "Nobody holds an advance". 1300 −20, 1010 +20.
4. Refused: to yourself; to a guardian or student; by a user (403); taking back with nothing held; settling 15 when 10 is
   held. Then 10 settled from the advance and 5 in cash: nothing held.
5. A settlement from an advance **reversed**: held again (12); 1300 +12, 2300 −12, cash unchanged (finance mirrored the
   stored accounts).

## 5. As built
- **Seen red first:** on the EX-7a build the gate stopped in `before()` (the advance endpoints did not exist), then 5/5 on
  the first run after deploying finance, expense and the monolith.
- That red run's teardown stopped at its sweep (the same missing endpoint) **before** resetting the switches: the plan
  was restored (it runs first) but two switches were left on. Reset through auth's API, and the sweep made tolerant so
  the reset always runs. Verified after the green run: FREE, no expense switches.
- Not built (said plainly): reversing a give or a take-back from the screen. A mistaken advance is taken back; a mistaken
  take-back is given again. Both are recorded payments in finance and can be reversed there by the operator if needed.
- **Regression (27 specs: every expense and finance gate, set-off, GL posting): 172 of 173 passed.** The one failure is
  fp-4b case 2, "demo.business carries the known 100", the known data-dependent gate defect (design §11.3 item 5),
  untouched here. EX-6, EX-7a and EX-7b passed in the same run, back to back.
