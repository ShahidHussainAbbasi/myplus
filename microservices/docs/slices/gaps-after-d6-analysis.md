# Closing what D-6 and E4 left open

**Status:** ✅ **SHIPPED AND VERIFIED** (2026-09-06). `control-plane-audit.cy.js` **10/10** and
`undelivered-outbox.cy.js` **10/10** after the change, plus the two things no gate covers, checked by hand.
**Origin:** the three gaps recorded at the end of D-6, all of them mine, none of them found by a test.
**Predecessors:** E1..E5 ✅ · D-6 ✅ green.

---

## 1. What was open, and why each mattered

| | Gap | Why it is not cosmetic |
|---|---|---|
| **G1** | E4's Activity panel ages every event by the server/browser timezone offset | A shipped screen quietly lying about *when* |
| **G2** | business and education could not record a re-drive | **D-6's own accountability argument, with a hole in it** |
| **G3** | education's producer was a third copy of one thing (D-7 debt) | G2 could not be fixed without closing it |

---

## 2. G1 — the timestamp defect, one screen along from where E5 found it

`audit-service` returned `occurredAt` as `LocalDateTime.toString()` — `2026-09-04T20:52:10`, no zone. The
services run UTC; the people using them do not. So the browser parsed it as **its own** local time and every
age on the Activity panel was out by the offset: an event from two minutes ago read as five hours old, and
nothing looked broken.

This is exactly the defect E5 hit on the support-session countdown, where it was catastrophic rather than
merely wrong — a live session read as expired four and a half hours earlier. **Same bug, screen next door,
fixed the same way.**

### The fix, and why it is at the entity rather than in a projection

`AuditController.list` returns the entity directly, and four consumers read it. Rather than reshape a shipped,
gated payload, the two timestamp fields are `@JsonIgnore`d and re-exposed under **the same names** by getters
that stamp the server's offset. Storage keeps the type it has always had; every reader gets an unambiguous
instant; no consumer changes.

`platform.js` now **refuses a zoneless value** with a console warning rather than guessing — guessing is what
produced a confident wrong answer for weeks.

### ⭐ And the second consumer got better, not just unbroken

`business.js` rendered the shop's own audit log with `(r.occurredAt||'').replace('T',' ')` — a string patch,
which would now leave `+05:00` dangling on the end of every row. A shopkeeper does not need to read an offset
two hundred times to know when a sale happened.

It now formats the instant in **the reader's own locale** (`06 Sep 2026 11:05`). That screen is their shop's
log; the crude replace was never good UX, and the wire change is what forced the improvement.

---

## 3. G2 + G3 — the re-drive was unrecorded on the two services that hold the money

D-6 shipped `OutboxRedriveAudit` on auth and catalog only. So a re-drive on **business and education — the two
services that own the GL outboxes, the ones the entire finding was about** — worked and left no record. An
accountability feature whose own control is unrecorded is the shape of problem it exists to prevent.

business was trivial: it already extends `AuditEmitter`. **Education could not be fixed at all**, because
`EduAuditService` was a third hand-written copy of the producer and its table had no `reason` and no actor
columns. That is D-7, the debt E4 recorded and deliberately did not take.

### The debt turned out not to exist

E4 deferred it because education's `details` is `VARCHAR(1000)` against the shared 500, and *"narrowing a
live column is a data decision, not a refactor."* Correct at the time, and measurable now:

```
action      max 25   (shared limit 32)      details     max  79   (limit 500)
entity_type max 18   (limit 32)             last_error  all NULL  (limit 500)
entity_ref  max 35   (limit 64)             status      max   6   (limit 20)
event_key   max 36   (limit 64)
```

Across all 88 rows — and across all 3,467 rows in the audit store — **nothing is close to a limit.** The
1000-character column has never held more than 79. `V30` reconciles the shape, `AuditOutbox` adopts
`AbstractAuditOutbox`, and `EduAuditService` becomes fifteen lines over the shared emitter.

⚠ The ten call sites are untouched: `record(action, entityType, entityRef, details)` keeps its signature, so
the migration changed no caller.

---

## 4. What could go wrong

* ⚠ **`ddl-auto=validate` and V30.** Every type in that migration must match `AbstractAuditOutbox` exactly or
  education-service will not start, and every screen behind it then fails for a reason that looks nothing
  like this file. The migration lists them against the superclass deliberately.
* **`@EnableScheduling`** — checked, present. Without it `AuditEmitter`'s relay would be inert and education's
  audit events would queue for ever.
* **`common-audit`'s five `provided` deps** — checked: all arrive via education's `starter-web`,
  `starter-data-jpa` and `starter-security`.
* **The G1 change alters a gated payload's format.** `control-plane-audit.cy.js` reads `occurredAt` only
  through `findEvent`'s matcher, which keys on `reason`, so it is unaffected — but it is the regression to
  run first.

---

## 5. Not done

* **The 1 business `SALE`** (`INV-000133`) — diagnosed completely: the event predates the delivery-fee field
  by 34 minutes, so `grand 27 ≠ sub 20 + tax 2` and finance correctly refused an unbalanced journal. The
  repair is one guarded `UPDATE` setting `shipping_fee = 5.00`, verified against order 605. **Blocked: the
  write was refused by the permission classifier, and it edits an event's payload rather than replaying it
  as-is, which is a decision for the owner rather than for me.**
* ~~**Education's dashboard has zero capability gating** — the largest remaining correctness item.~~
  ⚠ **CORRECTED** — see [`edu-gating-analysis.md`](edu-gating-analysis.md). The measurement was right and my
  conclusion was not: there are **two** education tenants and both are seeded fixtures, so this affects no
  customer. Recommendation is now **do not build**, with the trigger to revisit recorded.


---

## 6. Verified after the rebuild

**Deployment first, before spending a gate run.** Zero restarts on all six services — `ddl-auto=validate`
accepted `V30`, which was the one thing that could have crash-looped education and taken every screen behind
it down for an unrelated-looking reason.

**G1 on the wire:**

```
occurredAt   2026-09-04T22:36:13Z    receivedAt   2026-09-04T22:36:33Z
```

Unambiguous. Both gates green afterwards, `control-plane-audit` being the one that reads this payload.

**G2 + G3 — checked by hand, because no gate covers them.** A re-drive on each of the two services that
could not record one before:

```
education   OUTBOX_REDRIVEN   PLATFORM_OPERATOR   "G2 verification: education re-drive..."   POSTED
business    OUTBOX_REDRIVEN   PLATFORM_OPERATOR   "G2 verification: business re-drive..."    POSTED
```

`POSTED` is the part that matters, and it proves more than the feature: education's row went through the
**migrated** producer, so `EduAuditService` over `AuditEmitter`, the `V30` columns (`reason`, `actor_type`)
and delivery all work end to end. `audit_event` now carries `OUTBOX_REDRIVEN` from catalog, education **and**
business.

⚠ Deliberately tested against each service's `audit_outbox`, not its `gl_outbox`. Business's GL outbox holds
the one genuinely unpostable `SALE`; re-driving it would have failed again and burned its attempts back to
the dead-letter ceiling for no information — the answer is already known and the repair is a data fix, not a
replay.
