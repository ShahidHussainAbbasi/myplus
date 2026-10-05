# EX-1b — A refused posting says why at once, and can be posted again

**Status:** BUILT 2026-10-05 — gate `ex-1b-post-again.cy.js` 6/6 on the deployed build; unit expense 36/36 (9 new), finance 78/78, business/education outbox tests 21/21. Programme: [`../expense-management-design.md`](../expense-management-design.md) §5.4
(FAILED → PENDING "redrive", never built) and §11 finding **E1**. Branch `feature/expense-management`.

## 1. Document
An owner records rent dated last month; last month is closed in Finance → Period Close. Today the row shows
**"Posting…" for about five minutes**, then **"Not posted"** for ever, with the reason only in a tooltip — and the
reason is "Something went wrong", not "the period is closed". Nothing can send it again. The money was paid; the books
never learn of it unless the owner voids and re-types the expense.

The same refusal, met while **paying an expense bill** into a closed period, is worse: the payment is told "The books
did not answer in time" and stays PENDING for the reconciler, though finance refused it outright.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Business | A refusal is final until something changes (the period reopened); retrying it 20 times is noise. "Post again" re-sends the **same** posting (same event key) — it never creates a second journal. A voided voucher's expense is never re-sent |
| Tenancy | Post again is scoped like every read: another business's voucher, or (for a user) a colleague's, is "not found" |
| Live modules | No schema change. Every other outbox channel keeps its behaviour exactly (default: retry everything) |
| Patterns | **Strategy** on the shared relay: a channel may say an error is *permanent* (`OutboxDelivery.permanent`) and how to *describe* it; the relay dead-letters a permanent error at once. Idempotent consumer unchanged (finance `processed_event`). `@Version` on the voucher orders a concurrent void and post-again |
| UX | The reason is shown on the row, in the books' own words; the button says what it does; after a second refusal the row says what to do (reopen the period, or void and record it in an open one) |

## 1c. RULE 0 trace (done before code)
| What | Count | Finding |
|---|---|---|
| Who raises a closed period in finance | 1 | `PeriodLockService.assertOpen` → `PeriodClosedException extends RuntimeException` → the catch-all → **HTTP 500** with a generic body. Fix: extend `ValidationException` → 400 with the message |
| Callers that branch on finance's status | 2 | `ExpenseBillService.pay` — a 4xx is "refused: release", anything else "answer lost: keep PENDING". A closed period took the **wrong** branch (500). With 400 it takes the right one. `common-web` handler only logs by status. Every other caller treats any exception as retry → unchanged |
| `OutboxDelivery` implementations | 13 | 12 keep the default (`permanent` = false → today's 20 retries). Only the expense channel opts in: 400/409/422 from finance are permanent; 5xx, timeouts, 401/403 (a misconfigured secret is transient) keep retrying |
| Readers of `posting_status` | 8 | open-bills query and bill-pay guard want POSTED_GL only (a re-posted voucher joins them once it lands ✓); `voidWith` refuses PENDING (post-again → PENDING blocks a void until the books answer ✓); `voidNeedsReversal` (FAILED → no reversal ✓); 4 on screen (chip, Void, Pay, poller) |
| Writers of `posting_status` | 5 + 1 | `post()`; `stampVoucher` ×4. **Defect:** a reversal that once dead-lettered stamps "Void not yet in the books" and nothing ever clears it — the POSTED branch ignored reversals. New writer: `postAgain` |
| Concurrency | — | post-again and void both save the voucher entity (`@Version`): whichever commits second gets the conflict message. Post-again re-sends EXPENSE only while the voucher is POSTED; REVERSAL only while VOIDED; PAYABLE always (idempotent snapshot) |

## 2. Design
- **finance:** `PeriodClosedException extends ValidationException` (400 + "This period is closed (locked through …). Reopen it to make changes.").
- **common-outbox:** `OutboxDelivery.permanent(Exception)` (default false) and `describe(Exception)` (default the message);
  `OutboxRelay.deliver` dead-letters a permanent error at once and stores `describe(ex)`.
- **expense-service:** channel overrides both (finance's `message` from the JSON body); `stampVoucher` clears the error
  when a reversal lands; `ExpenseVoucherService.postAgain(id)` + `ExpenseOutboxService.redrive(voucher)`;
  `POST /api/expense/vouchers/{id}/post-again`.
- **monolith:** proxy; Expenses row: FAILED shows the reason under the chip and **Post again**; "Void not yet in the
  books" shows the same button. i18n × 6.

## 4. Gate (written first) — `cypress/e2e/expense/ex-1b-post-again.cy.js` (owner.lifecycle; the period lock is restored)
1. Books closed through yesterday; an expense dated yesterday → **Not posted within seconds** (not five minutes), the
   reason names the closed period; trial balance unchanged.
2. Post again while still closed → refused again at once, same reason; nothing in the books.
3. Reopen → Post again → In the books; 6000 +7 / 1000 −7 exactly once; a further Post again → "Nothing is waiting".
4. Paying an expense bill dated into the closed period → **refused** ("The books refused this payment …"), nothing
   pending, the bill still owes all of it.
5. Another tenant's voucher → not found; a user's Post again on a colleague's voucher → not found.
6. On screen: the reason is visible on the row; **Post again** is offered on a FAILED row only.

## 3. As built (and what the build itself found)

| Change | Where |
|---|---|
| `PeriodClosedException extends ValidationException` → 400 + the reason | finance |
| `OutboxDelivery.permanent` / `describe` (defaults: retry everything, the exception message); the relay dead-letters a permanent error at once | common-outbox |
| Expense channel: finance 400/409/422 permanent, finance's `message` stored; **an EXPENSE posting is never sent for a voucher that is not POSTED** (`NotSent`, permanent) | expense `ExpenseOutboxService` |
| A landed reversal clears its error (the POSTED branch used to ignore reversals) | expense `stampVoucher` |
| `postAgain` (POSTED → EXPENSE + PAYABLE; VOIDED → EXPENSE_REVERSAL + PAYABLE; nothing waiting → refused) + `redrive(voucher, kinds)` | expense |
| `POST /api/expense/vouchers/{id}/post-again`; monolith proxy | expense, monolith |
| Row: the reason under the chip, **Post again**, the hint; only the pressed button says Sending… | `expense.js`, 5 keys × 6 languages |

**Found while building (RULE 0):**
- **The operator's re-drive could book a voided expense.** `/outbox-health/redrive` resets every FAILED row of a table;
  a FAILED posting voids with no reversal, so re-driving it afterwards would put a voided expense in the books with
  nothing to take it out. Now impossible from either path: the channel refuses to send an EXPENSE posting for a voucher
  that is not POSTED.
- **A bill payment refused for a closed period was treated as a lost answer** (500) — "the books did not answer",
  reservation left PENDING. Fixed by the 400 (gate case 4).
- **Finance dates every payment's journal today, whatever its `paidOn`** (`PostingService.postPayment` →
  `TenantClock.today()`; design §4b F6). A payment "paid on" a closed day is therefore booked today and accepted. Not
  changed here (finance's payment model, used by every module); recorded as still open.
- The first gate runs were red for the spec's own reasons: `cy.its()` refuses a null lock ("open"); a failed case left
  the next one on open books (now every case sets the lock itself); the other tenant had the module off (refused before
  the lookup) — now switched on, so the 404 proves the scope.

