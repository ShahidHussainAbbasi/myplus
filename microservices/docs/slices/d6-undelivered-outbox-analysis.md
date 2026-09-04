# D-6 — analysis: 65 events are permanently lost, and PKR 137,510 of them is money

**Status:** ANALYSIS, shared for review. No design, no code — `SAAS-BUILD-STANDARDS.md` §0 / `CLAUDE.md` RULE 0.
**Origin:** E5 ruling **D-6** — *"an operator-visible count of undelivered audit events, and a re-drive
control"* — the one ruling from that slice that did not reach the code.
**Predecessors:** E1..E5 ✅ green.

Every figure below was read from the running databases on 2026-09-05.

---

## 1. Verdict up front

**D-6 was scoped as a convenience for audit records. The end-to-end review found something else: the same
mechanism has silently dropped 57 GENERAL-LEDGER events, and the books are missing them.**

> `myplusdb_education.gl_outbox` holds **56 rows, every one of them FAILED, none POSTED**. They are 40 fee
> charges, 12 fee credits issued and 4 applied — **PKR 137,510** of education accounting that never reached
> finance-service. Every one is at `attempts = 20`, the dead-letter ceiling, so none will ever be retried.
>
> Nothing on any screen says so. It was found by going looking.

That reframes the slice. A missing audit row is an accountability gap; a missing GL event is a **trial balance
that does not include a school's fees**. The visibility D-6 asks for is the same build either way — but the
justification, the priority and the first screen it belongs on all change.

---

## 2. Every outbox, measured

Seven outbox tables across four services, all driven by the same `OutboxRelay` (dead-letters at
`MAX_ATTEMPTS = 20`, after which nothing looks at the row again):

| Database | Table | POSTED | **FAILED** |
|---|---|---:|---:|
| `myplusdb` (business) | `audit_outbox` | 3,044 | 0 |
| `myplusdb` (business) | `gl_outbox` | 2,906 | **1** |
| `myplusdb_auth` | `audit_outbox` | 184 | **8** |
| `myplusdb_catalog` | `audit_outbox` | 7 | 0 |
| `myplusdb_education` | `audit_outbox` | 88 | 0 |
| `myplusdb_education` | `gl_outbox` | **0** | **56** |
| `myplusdb_education` | `notify_outbox` | 12 | 0 |

**65 dead-lettered rows in total.** The eight in auth are E5's own gate runs and are noise. The other 57 are
not.

### What the 57 were worth

| Event | Count | Amount |
|---|---:|---:|
| `FEE_CHARGE` | 40 | 91,510 |
| `FEE_CREDIT_ISSUED` | 12 | 38,000 |
| `FEE_CREDIT_APPLIED` | 4 | 8,000 |
| `SALE` (business) | 1 | — |
| | **57** | **137,510** |

`gl_processed_event` in finance holds 2,894 rows — matching business's POSTED count. **Not one education GL
event has ever been processed.**

---

## 3. Two causes, and only one of them is fixed

Splitting the 56 by the error they died on:

| Cause | Count | Dates | Status |
|---|---:|---|---|
| `Query did not return a unique result: 2 results were returned` | 27 | 7 Aug, 15:58–16:00 | ✅ **fixed** |
| `500 — "Something went wrong. Please try again."` | 29 | 16 Aug, 09:45–09:52 | ❌ **undiagnosed** |

**The first** is `AccountRepository.findByOrganizationIdAndCode` returning `Optional<Account>` while two rows
matched. `finance-service` `V5__accounts_unique_org_code.sql` added the unique constraint on
**2026-08-16 06:07:43**, and there are no duplicate `(organization_id, code)` pairs today. That cause is
closed.

**The second is not, and it happened AFTER that fix** — the 29 rows were created between 09:45 and 09:52 on
16 August, three hours after the constraint landed. So a second, unrelated failure exists and nobody has ever
looked at it.

### What I ruled out, and what I could not

Checked and **not** the cause:

* `paid_amount` is NULL on the failing rows, but `postFeeCharge` reads only `nz(r.getGrandTotal())` — the
  amount is null-safe and `paidAmount` is not used.
* The accounts it posts to exist: `FEE_CHARGE` is `Dr 1100 AR = Cr 4100 Fee Income`, and org 14 holds both.
* Org 14 is missing account `4300` (Delivery Income) where org 13 has it — but no fee event posts to 4300.

**Not established:** the actual exception. `finance-service`'s advice returns a generic sentence, and the
container has restarted since (its current log starts 2026-09-04 21:43), so the 16 August stack traces are
gone. **The cause of 29 lost GL events is unknown, and I am reporting it as unknown rather than guessing.**

The decisive next step is to replay one event and read the live exception — the endpoint is idempotent on
`event_key` and these were never processed, so a replay is safe in that sense. **It is still a write to the
ledger, so I have not done it.**

---

## 4. Why nothing surfaced this

`OutboxRelay.deliver` increments `attempts`, records `lastError`, and at 20 sets `status = FAILED`. That is
the whole of it:

* no count is exposed anywhere — not on the operator console, not on any dashboard, not in any endpoint;
* nothing can re-drive a `FAILED` row: `deliver` returns immediately for it, by design;
* the tenant is never told;
* the row's `lastError` is the only record of why, and it is only readable by someone with database access.

For a **sale** this is a defensible trade: the money is in business-service's own books either way, and the GL
is a second projection. For an **education fee** it is not — the charge exists in `fee_collection` and nowhere
in the ledger, so the school's receivables and revenue are understated by 137,510 with nothing indicating it.

And for an **access record** (E5) there is no second copy at all.

---

## 5. What I would build

Small, and in this order:

1. **A count, per service, that a person sees.** One endpoint per outbox-owning service returning
   `{pending, failed, oldestPending}`; the operator console shows a strip when any `failed > 0`. This is what
   D-6 asked for, and it is what would have surfaced all 57 on 16 August.
2. **A re-drive control.** Reset `FAILED` → `PENDING` with `attempts = 0` for a named set, so the relay picks
   them up. ⚠ Must be `ROLE_ADMIN`, must record who and why (E2's rule), and must go through the audit trail
   E4 built — a re-drive is a control-plane action.
3. **A dead-letter reason on the strip**, so the operator sees *"29 · Something went wrong"* rather than a
   bare number. Fifty-seven events sharing two distinct messages is exactly the shape that makes one
   diagnosis fix everything.

### What I would NOT build

* **No automatic infinite retry.** Twenty attempts against a genuinely broken payload is already generous; the
  problem is not the ceiling, it is that hitting it is invisible.
* **No alerting/monitoring stack.** `project_log_management_observability` records that metrics/Prometheus are
  not done. This is a count on a screen, not the beginning of that.

---

## 6. Rulings needed

**D6-1 — do we replay the 57?** They are real accounting events that never posted. Replaying is idempotent on
`event_key`, but it writes to the ledger and it will fail again for the 29 until their cause is found.
**Recommendation:** diagnose first (replay ONE, read the exception), fix, then replay the rest — and do the
whole thing through the re-drive control from §5 so it is recorded.

**D6-2 — is the count per service or aggregated?** Four services own outboxes and each owns its own database.
**Recommendation:** per service, aggregated for display by the console's BFF — the same shape as everything
else in the control plane, and no service gains a dependency on another.

**D6-3 — does the tenant see it?** A school whose fees are missing from the ledger arguably should.
**Recommendation:** not yet. The count is meaningless without the ability to act on it, and only an operator
can re-drive.

---

## 7. Gate, sketched — `cypress/e2e/platform/undelivered-outbox.cy.js`

| # | Case | Guards |
|---|---|---|
| 1 | ⭐ The console shows a non-zero failed count when one exists | the whole slice — this is what was missing on 16 Aug |
| 2 | The strip is **absent** when everything is delivered | a permanent banner is one people stop seeing |
| 3 | ⭐ A re-drive moves `FAILED` → delivered, and the count falls to zero | the round trip, not just the warning |
| 4 | A re-drive **requires a reason** and is refused without one | E2's rule; it is a control-plane action |
| 5 | ⭐ The re-drive itself appears in the audit trail as `PLATFORM_OPERATOR` | reuses E4 |
| 6 | A tenant owner cannot re-drive | `ROLE_ADMIN`, never `ADMIN_PRIVILEGE` |

⚠ Case 3 needs a genuinely failed row to work on. **Seed one** rather than relying on the 57 — they are real
lost accounting and a spec must not be the thing that replays them (`feedback_fixture_eligibility`).
