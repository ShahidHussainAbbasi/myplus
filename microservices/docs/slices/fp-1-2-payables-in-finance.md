# FP-1 + FP-2 — finance holds every supplier payable (shadow), fed by business, backfilled automatically

**Status:** GREEN 2026-10-02 21:00, STAGED. Deployed: finance V9 + business V71. Automatic backfill: 7 tenants, 747 documents. Tests: business 387 (incl. PayableOutboxIntegrationTest 6/6 on real MySQL, Skipped 0), finance 53. Gate 5/5 + AP regression 9/9.

**Findings (all tenants reconciled, not just the gate's):** business Σ supplier due = finance per-supplier net in 6/7 tenants exactly. Org 6 differs by exactly **100** — supplier `GLPVEN_…` carries a balance with NO purchase documents (`addVender` maps the form's `dueAmount` on a NEW supplier; how this one got 100 is unverified). **Supplier advances** (paid beyond the bills) that business hides by flooring each supplier at zero: org 6 9,620; org 41 25,200 — finance now reports them (`supplierAdvances`). Decide at FP-4 how the supplier screens present advances.

**Design correction found by the integration test:** Spring `beforeCommit` runs BEFORE Hibernate's commit flush, so a change first flushed at commit (every purchase — TABLE id generator) registered too late and wrote nothing. Rows are now written in a Hibernate `BeforeTransactionCompletionProcess` (the Envers pattern). Programme: [`../finance-payables-subledger-design.md`](../finance-payables-subledger-design.md).
Rulings (2026-10-02): phase plan FP-1..FP-6 approved; backfill **automatic for all tenants**.

## 1. Document
Finance gets the open payable documents next to the payments and allocations it already holds. Business keeps
being the source of truth in these two phases: every change to a supplier purchase is reported to finance as a
**snapshot** (amount, paid), so finance's subledger mirrors business exactly — the shadow the later switches stand
on. Existing purchases arrive through the same path, replayed once per tenant automatically.

FP-1 and FP-2 ship together because a document store nothing writes to is not a slice ("a slice is not done until
something calls it") and the automatic backfill IS the FP-2 emitter run over history.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Business | A payable document per supplier purchase line (the unit business already pays FIFO against). Open = amount − paid. A voided purchase is a VOID document (kept, open 0) |
| Live modules | Shadow only: nothing reads finance's subledger for a decision yet. Every existing AP screen and spec is untouched |
| Boundaries | business reports, finance stores. Finance never reads business tables; the backfill replays events, it does not copy across databases |
| Patterns | Hibernate entity listener collects changed purchases → `BEFORE_COMMIT` writes ONE outbox row per purchase into the same transaction (no EntityManager calls inside lifecycle callbacks) → `AFTER_COMMIT` delivery · payload JSON · idempotent upsert keyed `(org, source, source_ref)` with a **monotonic `source_version`** so a late redelivery never overwrites newer figures · backfill marker per tenant |
| RULE 0 | 7 writers of purchase amounts (add, edit, return, opening AP, supplier payment, recompute, **void**) all go through JPA `save` → the listener sees all of them. Bulk deletes (`deleteInBatch`…) exist but have **no live caller** (purchases are voided, not deleted); demo purge deletes per organization in each service — finance's rows go with it |
| Testing | Unit: upsert idempotency + version ordering, snapshot mapping; Testcontainers Flyway; Cypress: business Σ supplier due = finance Σ open for the tenant, before and after a credit purchase, a payment and a void |

## 2. Design
- **contracts**: `PayableSnapshot{source, sourceRef, sourceVersion, partyType, partyId, partyName, docNo, docDate, amount, paid, voided}`; `FinanceClient.upsertPayables(List<PayableSnapshot>)` → `POST /internal/finance/payables`.
- **finance V9** `payable_doc` (org, party_type, party_id, party_name, source, source_ref, source_version, doc_no, doc_date, amount, paid, status OPEN|SETTLED|VOID, created/updated) UNIQUE `(organization_id, source, source_ref)`, index `(organization_id, party_type, party_id, status)`. `PayableService.upsert` (version-guarded), `GET /api/finance/payables/summary` (open total + by supplier), `GET /api/finance/payables/reconciliation` (subledger open vs GL 2000 balance — reported, not gated: GL drift that predates this slice is a finding, not this slice's defect).
- **business V71** `payable_outbox` (payload JSON) + `payable_backfill(organization_id PK, done_at)`. `PurchasePayableListener` (`@PostPersist/@PostUpdate` → tx-scoped set) + `PayableOutboxService` (`BEFORE_COMMIT` enqueue, `AFTER_COMMIT` deliver, schedule retry). `PayableBackfillRunner` on startup: for each org with supplier purchases and no marker → enqueue snapshots in batches of 200 → marker.

## 4. Gate — `cypress/e2e/finance/fp-payables-shadow.cy.js`
1. owner.business: Σ supplier due (business) = finance open total (the backfill reached it).
2. A credit purchase (paid 0) → finance open total up by the bill; still equal.
3. Pay that supplier part of it → both down by the payment; still equal.
4. Void a purchase → its document VOID, both totals still equal.
5. Reconciliation endpoint answers with subledger open, GL 2000 and their difference (shown, not asserted 0).
