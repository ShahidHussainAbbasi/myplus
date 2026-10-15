# PERF-R1 — measured performance review (2026-10-15) · standards §1f, §1d

Status: **REVIEW — findings measured, nothing changed. Awaiting the user's order of work.**

Source: `performance_schema.events_statements_summary_by_digest` on the Docker `myplus-mysql`, 15.7 h of uptime
(56,692 s). Rows with timer values near 2^64 (18,446,743 s) are counter overflow from interrupted statements and are
discarded.

## Findings, ranked by what grows with tenant data

| # | Finding | Evidence | Grows with | Fix class (§1f P2) |
|---|---|---|---|---|
| R1 | **Legacy scope predicate defeats indexes, platform-wide**: `organization_id = ? OR (organization_id IS NULL AND user_id = ?)` | customer_history 3,392 rows read per call (462 of 515 calls used no index; 1.75 M rows); stock_entries expiry count 2,377/call; stock_levels 1,531/call ×4 queries; products count 1,665/call; marketplace orders 1,205–1,339/call | every tenant's data, linearly | **0 NULL-org rows** in 8 hot tables (≈39k rows) on dev → prove on prod, make `organization_id NOT NULL`, drop the OR branch in 21 repository files → index seeks |
| R2 | **`ddl-auto: update` pays at every boot** | `ALTER TABLE customer_history ADD CONSTRAINT FK…` 7.2 s; schema-metadata scans 24.4 s (business), 9.1 s (education) | number of tables | `validate` per service, each proven to start (D14 rule 1; the same mechanism caused the inventory DECIMAL drift) |
| R3 | **auth re-seeds privileges on every start** | `INSERT INTO roles_privileges` 4,648 calls | restarts × roles | SetupDataLoader to insert only what is missing (check before write) |
| R4 | **auth user lookup reads ~51 rows per call** | 7,942 calls, 405,808 rows examined | number of users | index on the looked-up column (to be traced: email / username) |
| R5 | **finance document counter waits** | `UPDATE org_document_seq … next_val + ?` avg 663 ms (18 calls) | concurrent receipts per tenant | measure under load first — DOC-INT's six-concurrent gate case runs in this window; may be test-induced |
| R6 | marketplace orders list: 1 call scanned with no index | `orders` filter on fulfilment/status | orders | index on (organization_id, fulfilment_status, created_at) after R1 |

COMMITs, `SET autocommit` and pool chatter are normal for the call volume.

## Proposed order
1. **R1** — highest yield, one pattern: preflight count on PROD (read-only) → migrations `NOT NULL` per service →
   repositories drop the OR. Gate: query count/rows-examined before → after on the same screens.
2. **R3 + R4** — auth, small and contained.
3. **R2** — service by service, each boot-tested under `validate` (Warehouse.capacity float/REAL is a known blocker).
4. **R5, R6** — after R1, re-measured.
5. Front-end and cache levels (K8): browser waterfall of the five busiest screens; static asset versioning.

## R1 design (2026-10-15)

**Scope, measured:** ~240 occurrences of the legacy predicate in ~85 files, 15 modules — education 34 files, business 16,
catalog 8, inventory 6, pharma 4, marketplace 3, campaign 3, agriculture 3, welfare 2, party/finance/analytics 1 each,
plus three shared builders (`commerce-domain TenantSpecifications` — unused; `common-security CurrentUser`;
`common-web TenantPagedCache`).

**Data, measured (dev):** 188 scoped tables, 202,041 rows. Rows with NO organization: `notification_broadcast/
delivery` 436 (system mail — to be confirmed per query), `auth.permission_set` 12 (built-in sets — BY DESIGN, keep the
null), `inventory.stock_adjustments` 34 (genuine legacy, pre-V11). Everything else: 0.
**Production: unknown** — `docs/deploy/preflight-r1-null-org.sql` (read-only, tested on dev) must be run there first.

**One slice per service, in order of measured cost** (business → inventory → catalog → marketplace → education → rest):
1. Preflight on prod shows 0 legacy rows for the service's tables (or a backfill first — e.g. stock_adjustments from
   its product's stock entry org).
2. Flyway: `organization_id NOT NULL` on the service's scoped tables — a NULL that slipped through makes the
   migration FAIL LOUDLY rather than hide a row; idempotent guard per D7.
3. Entities `@Column(name = "organization_id", nullable = false)`.
4. Every query drops `OR (organizationId IS NULL AND userId = :userId)` → `organizationId = :orgId` — an index seek.
   Built-in/shared rows that are null BY DESIGN (permission sets) keep their explicit `IS NULL` branch, documented.
5. Gate: the same screens' rows-examined per call from `events_statements_summary_by_digest`, before → after
   (expected e.g. customer_history 3,392 → tens), plus the service's regression specs.

## ⚠ Correction (2026-10-15, same day) — R1's premise was WRONG; measured before building

Tested on a session-only TEMPORARY copy of `customer_history` (3,788 rows): the legacy predicate
`organization_id = ? OR (organization_id IS NULL AND user_id = ?)` already uses the tenant index — plan `ref_or_null`
on `ix_ch_org_doctype`, 961 rows (= that tenant's rows). With `organization_id NOT NULL` the plan becomes `ref`, 957
rows: a marginal gain. **The OR does not defeat the index.** The thousands of rows per call are WHOLE-TENANT reads
(counts/sums over a tenant's products, stock levels, entries — dashboard aggregates), read through the index.
Dropping the OR from 61 business queries would also force every `@Param userId` out of the signatures and callers
(Spring Data refuses an unused named parameter) — a large ripple for almost nothing. **R1 is downgraded to hygiene**
(NOT NULL for integrity, later, no query rewrite).

### Re-ranked findings
| # | Finding | Evidence | Fix |
|---|---|---|---|
| **R7** | **Saga recovery poller full-scans every tenant's sales every minute** | `findPendingSagaSales()` — `EXPLAIN`: `type ALL`, 3,788 rows + filesort, to return **3**; 520 calls, 467 without an index | index `customer_history (saga_status, dated)` → reads only the pending rows |
| **R8** | 3 sagas stuck forever | relay retries INV-000100/117/122 every minute: "Reservation not found" (404) — never resolves | functional defect: a 404 reservation must end the retry (mark FAILED / needs review), not loop forever |
| R9 | Expiry-window count on stock entries | 2,377 rows/call, 378 calls, 81 without index | index (organization_id, expiry_date) — EXPLAIN first |
| R10 | Dashboard aggregates read the whole tenant per load | stock_levels ×4 queries 1,531 rows each; products count 1,665; orders count 1,339 | measure the screen's total; then covering indexes / one combined query; caching only per K5 ruling |
| R2–R6 | as above | | |
