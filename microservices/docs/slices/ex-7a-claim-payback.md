# EX-7a — Paying a claim back

**Status:** DONE 2026-10-09: gate 4/4 (seen red first), unit tests 9 new (finance 87/87, expense-service 82/82), Test
Book cases 7-1 to 7-3. Programme: [`../expense-management-design.md`](../expense-management-design.md) §5.3, §6, §10 EX-7,
finding **F6**. EX-7 is split: **7a** pays approved claims back; **7b** is advances (give one, settle claims against it,
take back what is unused).

## 1. Document
EX-6 put an approved claim in the books as money the business owes the member (Cr 2300). Nothing could pay it: Pay was
for supplier bills only, and finance's disbursement always debited **2000 Accounts Payable**, whoever it paid (F6). So
2300 could only grow, and paying a member through Pay Supplier would have shrunk AP for a debt AP never held.

## 1b. Standards
| Dimension | Rule |
|---|---|
| One payment path (as the design says) | Paying back reuses FP-3's bill payment exactly: **reserve, call finance, confirm**, one Idempotency-Key per opening of the panel, find-before-send on a lost answer, the reconciler, and FP-3b's reversal. A claim is paid as finance's new **`EMPLOYEE` party** (`partyId` = the claimant), with no allocation, because the claim has no document in the supplier subledger |
| The ledger (fixes F6) | finance's `paymentLines` choose the debit by **party**: EMPLOYEE → **Dr 2300 / Cr 1000 or 1010**; anyone else is unchanged (Dr 2000). The reversal is the exact mirror. Money received **from** a member is refused until EX-7b ("not supported yet") rather than credited to customers' 1100 |
| Who pays | **An owner or admin**, never for their own claim (refused in words). The platform operator does not pay, as with approval. A user sees what is owed to them, with no Pay button; the server refuses them (403) |
| What may be paid | an **approved** claim **in the books** (`POSTED_GL`), up to what is still owed (pending reservations included); a waiting claim is refused |
| Void | a claim with money paid back is not voidable ("Reverse the payment first, then void the claim"), the same rule as a paid bill. The EX-6 note is closed here |
| Screen | An approved claim reads **Owed to the member 40.00** and gets **Pay back** (owner/admin). The panel reads "Pay back claim EXP-… — Owed to the member …". Fully paid, it reads **Paid back**. **Payments** lists each PV- with Reverse (FP-3b) |
| Schema | none. finance `payments.party_type` is `varchar(20)` (checked on the live database), so `EMPLOYEE` fits under `ddl-auto=validate` with no migration |

## 1c. RULE 0 trace
| What | Count | Finding |
|---|---|---|
| Readers of finance `payments` | 5 | `findByPartyScoped` and `sumByPartyScoped` filter by party type (VENDOR or CUSTOMER at their callers), so EMPLOYEE rows are **excluded**; the count, the clientRef lookup and the id lookup are **unaffected**. Supplier statement, aging and set-off never see a member's payment |
| Callers of `postPayment` / `postPaymentReversal` | 2 + 2 | `PaymentService.record` and `reverse` now pass the party; the 3- and 5-argument forms remain and delegate with no party (unchanged behaviour). **Three finance tests mocked the old signatures**: left alone, a `never()` would have passed vacuously and a stubbed failure would never have fired. Updated to the new ones |
| Exhaustive switches over `PartyType` | 0 | `PartyType.valueOf(type)` in the payable statement only ever receives VENDOR |
| `isBill()` in expense-service | 6 kept, 5 widened, 1 new | **Kept bills-only (6):** Pay Supplier's `applyExternal`, the void's subledger send, the paid-bill void guard, `reserve`'s "only a bill" branch, and `confirm` and `reversePayment` telling the supplier subledger (a claim has no document there). **Widened to `isOwed()` (5):** `reserve`'s claim branch, the void's row lock, and on the entity `openAmount`, `applyPayment`, `reversePayment`. **New (1):** the paid-back-claim void guard in `voidWith` |
| Readers of `openAmount` | 3 | `VoucherView` (the screen, wanted); `findOpenBills` and E11 `billsInBooks` are AP only by query, so **unaffected** |
| The daily payables check (FP-6a/6b) | 1 | compares the payables subledger with GL 2000: a member's payment touches neither, which is exactly the F6 fix |

## 4. Gate: `cypress/e2e/expense/ex-7a-claim-payback.cy.js` (school tenant)
1. The owner pays an approved claim of 40 back as 15 in cash, then 25 by bank. Owed 25, then **Paid back**. Not
   voidable once paid in part. Trial balance: 2300 +40 (cleared), 1000 −15, 1010 −25, **2000 unchanged**.
2. A paid-back claim's void is refused in words. The payment is reversed from **Payments**, it is owed again, then
   voided: 2300, 6000, 1000, 1010 and 2000 all back where they were.
3. An admin's own claim: paying it back is refused; a user is refused (403 or 404); the owner pays it. A member sees
   "Owed to the member", with no Pay back button, and the server refuses them 403.
4. A waiting claim cannot be paid; 9 against 8 owed is refused; 8 is paid.

## 5. As built
- **Seen red first:** on the EX-6 build all four cases failed ("Only a bill can be paid…"), then 4/4 after the deploy.
- **A teardown that could strand a tenant:** in the red run the gate's `after()` could not sign the owner in (the login
  stayed on `/login`; the cause is **unexplained**: the logs show nothing refused, and the next run's sign-ins worked).
  Because the plan restore came after that sign-in, the school was left on **PRO**. Verified on the database, restored to
  FREE as the operator (the audit trail shows FREE → PRO as its last change), and the EX-6 and EX-7a gates and the Test
  Book spec now restore the plan **first**, with the operator only.
- **The gateway 503 after a deploy, explained:** for about 25 seconds after a service reports healthy, the gateway still
  answers 503 for it (polled: five 503s, then 200). That was EX-6's first-run failure. Wait for 200 before a gate.
