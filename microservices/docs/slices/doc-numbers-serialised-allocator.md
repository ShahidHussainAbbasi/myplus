# Per-org document numbers — a serialised allocator

Follow-on from the sequence-collision fix. Parent: `../../../.claude` memory `project_per_org_document_numbers`.
Status: **DONE + GREEN 2026-08-23.** business-service **172 / 0 fail, 0 skipped**; all four allocation sites
regression-green — `sale-return-credit` 2/2, `purchase-return` 1/1, `b2b-quote-to-order` 6/6,
`installment-repossession` 10/10 (run headless; the suite's convention is headed).

**Seeding verified against the live database**, which is the one thing a green spec would not reveal: every
counter matches its table's existing maximum exactly — CREDIT_NOTE 21/6/58/37, DEBIT_NOTE 12/1, QUOTE 1/37/3 —
so no tenant's next document reuses a number.

---

## 1. What was wrong

Every per-org running number was `SELECT MAX(seq) + 1`. That is not an allocation, it is a guess that is
usually right. Two tills read the same maximum, both take it, the UNIQUE constraint refuses the loser, and
the loser's operation died as *"Transaction silently rolled back because it has been marked as rollback-only"*.

Invoice and plan were fixed by **retrying** (`SequenceRetry`). That works only where the retried unit has no
effects outside the database — true of the invoice and plan writes, **false of a sale return**, which calls
inventory to put stock back *before* it allocates its credit-note number. Retrying there restocks twice.

## 2. What replaced it

A counter row per `(organization_id, doc_type)`. The row lock taken by the increment is what makes the second
caller **wait** instead of collide. Same shape as SAP number ranges and Odoo's `ir.sequence`, for the same
reason: MySQL has no per-tenant sequence, and these numbers are per tenant by definition — two shops both have
a credit note 42, and must.

**Prevention, not recovery.** `SequenceRetry` stays where replay is safe; this removes the collision where it
is not. The two are complementary layers, not duplicates.

### `MANDATORY` is load-bearing

The increment joins the **caller's** transaction, so a return that fails after taking number 42 rolls the
counter back with it and 42 goes to the next caller. Credit notes are tax documents; an unexplained gap is a
question somebody answers at an audit. `MANDATORY` refuses to run outside a transaction rather than quietly
starting its own — which would commit independently and reintroduce exactly those gaps.

### Allocate LATE

The row lock is held from allocation until commit, so every other till for that tenant queues behind it. Call
it immediately before the insert that needs the number, **never before a remote call**, or one slow inventory
round trip stalls the whole tenant.

## 3. ⚠ Three shapes. The first two were broken, and only a concurrent test could show it

| Shape | What happened |
|---|---|
| `INSERT IGNORE` then `UPDATE` | **Deadlock.** On a duplicate key the insert takes a SHARED lock; the update then needs EXCLUSIVE. Concurrent callers deadlock upgrading against each other. |
| `UPDATE` then create-if-missing | **Lock wait timeout — deterministic, on every tenant's FIRST document.** An UPDATE matching **zero rows still takes a GAP LOCK**, so the caller's own transaction blocked the separate connection trying to create the row. Fifty seconds, then failure. |
| **read → ensure → bump** ✅ | A non-locking consistent read takes no gap lock, so the counter is created before the caller holds anything. The bump is then a plain exclusive lock on a row that exists. |

The second shape is the one worth remembering: it was **not** a concurrency bug. It failed the same way every
single time, on a code path (a tenant's first credit note) that no existing test exercised and that a
developer would only hit in production, once per tenant, silently blaming the network.

**Counter creation runs in its own committed transaction** (`REQUIRES_NEW`) and sets **zero**, so it hands out
no number and cannot create a gap by committing separately. It is reached through the proxy
(`ObjectProvider<DocumentNumberService>`), because `this.ensureCounter(...)` would be a self-invocation and the
annotation would be decorative — the same trap INST-3a's scanner hit.

## 4. Why the test had to be concurrent, and what it cost

`MAX(seq) + 1` passes every single-threaded test ever written. It fails the moment a shop opens a second till.
**A test that never runs two threads could not have caught it — which is precisely why nobody did.**

`OrgDocumentSeqConcurrencyTest` runs 20 threads off one latch so they contend in the same instant, and asserts
they receive exactly 1..20: **no duplicates** (the corruption) and **no gaps** (the audit problem). It found
both defects above within minutes of being written, and it only runs at all because the parent pom now pins
`docker.api.version` — before today every Testcontainers test on this machine skipped silently inside a green
build.

Two false trails on the way, both worth recognising next time:

- **Connection-pool starvation reads exactly like a database deadlock.** Twenty threads each holding one
  connection and briefly taking a second, against a default pool of ten, produced lock-wait timeouts that
  looked like InnoDB and were not. The pool is sized in the test.
- The raw-JDBC helper **must mirror the service statement for statement**, including transaction boundaries.
  It is duplication, and it is the price of testing what the database does rather than what a Spring proxy
  does. If the service changes shape and the helper does not, the test passes while proving nothing.

## 5. What changed

| | |
|---|---|
| Schema | `V45__org_document_seq.sql` — counter table, **seeded from each tenant's existing MAX** (a counter starting below it would reissue a number some document already carries) |
| Service | `DocumentNumberService` (`MANDATORY`), `OrgDocumentSeqRepo` (all native — JPA read-modify-write would be the same race in a different costume) |
| Call sites | sale return, repossession credit note, purchase return debit note, sales quote |
| Converged 2026-08-23 | **invoice + plan moved onto the allocator too** (V46, seeded from each tenant's existing MAX — verified live: INVOICE 315/55/275/10/259/157, PLAN 183, every one matching). One named pattern now answers the whole concern. |
| `SequenceRetry` | Kept as a **backstop**, and as the record of which constraint means what — `SagaSellService` still has to tell an idempotency-key duplicate (return the winner's invoice) from a lost-number race, and that knowledge holds whatever allocates the numbers. Its retry branch should no longer fire. |
| Tests | `OrgDocumentSeqConcurrencyTest` 5/5 against real MySQL; suite **172 / 0 fail, 0 skipped** |

---

## 6. Convergence (2026-08-23) — all six sequences on one mechanism

V46 seeds `INVOICE` and `PLAN` counters and both call sites now allocate through `DocumentNumberService`.

**Safe because both sit in DB-only transactions.** The lock is held from allocation to commit, so allocating
before a remote call would stall a whole tenant behind one slow round trip. `invoice_seq` is allocated inside
`writePending`, which runs AFTER the inventory reserve; `plan_seq` inside `create`, which is `REQUIRES_NEW` and
touches nothing but the database. Both were checked before the change, not after.

**Seeding verified against the live database** — every counter equals its table's existing maximum, so no
tenant's next document reuses a number. A green spec cannot show that; only reading the counters can.

### ⚠ A build trap this surfaced

`mvn -pl business-service test` compiles against the **installed** `commerce-contracts` jar. That jar was
stale and missing a class, and Maven's incremental compilation hid it — an entire untracked test class
(`PolicyCheckTest`, 7 tests) had therefore never run. `mvn install -am` fixed it and the tests appeared, one
failing on margin arithmetic. **That failure belongs to unfinished work by someone else and has been left
alone.** The lesson stands on its own: *building with `-pl` alone can silently test against yesterday's
libraries.*
