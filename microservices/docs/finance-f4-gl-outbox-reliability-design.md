# finance — Audit #4: GL posting reliability (transactional outbox + relay) — Design

**Status:** ✅ DONE - transactional outbox + `OutboxRelay` shipped; GL posting no longer depends on a live finance call at write time.

**Branch:** `feature/finance-ledger` · **Slice:** posting reliability · **Audit ref:** `pos-retail-standards-audit.md` §2/#4

## 0. Problem
Every GL post (business-service → finance `postEvent` for SALE/PURCHASE/returns/edits) is **best-effort
fire-and-forget**. If finance-service is momentarily down / the call throws, the journal is **silently dropped** and
the books drift — with no retry, no reconcile. We just made returns+edits post to the GL, so this now guards a lot.

## 1. Solution — transactional outbox + retry relay
The standard reliable-messaging pattern, and we already have the exact precedent: `SagaRecoveryRelay`
(`@Scheduled` + `GatewayIdentityForwarding.runAs(userId, orgId, …)` to impersonate the tenant from a background
thread). Reuse it.

```mermaid
flowchart LR
    subgraph tx[business write TX]
      W[sale / purchase / return / edit] --> OB[(gl_outbox: PENDING)]
    end
    W -. afterCommit .-> F1[relay.tryDeliver now]
    S[["@Scheduled relay (retry)"]] --> Q{PENDING rows}
    F1 --> POST[runAs(org,user) → finance postEvent]
    Q --> POST
    POST -->|ok| M[mark POSTED]
    POST -->|fail| A[attempts++ , retry next tick]
```

- **Producer** writes a `gl_outbox` row (PENDING) **in the same transaction** as the business write. If the sale
  commits, the GL event is durably queued — it can never be lost. (Replaces the inline fire-and-forget call.)
- **Immediate flush:** a `TransactionSynchronization.afterCommit` hook attempts delivery right away (best-effort),
  so the common case posts to the GL within the same request (books stay fresh; existing Cypress asserts still see
  it). Delivery only happens **after commit**, so there's no dual-write orphan (no journal for a rolled-back sale).
- **Relay** (`@Scheduled`, e.g. 30s) re-drives any still-PENDING rows via `runAs(userId, orgId, …)` → `postEvent`,
  marks POSTED, or `attempts++` + `last_error` on failure. Idempotent enough (a rare duplicate journal is a
  reconciling entry, not corruption; a later slice can add per-event dedup on the finance side).

## 2. Schema — business-service Flyway **V16**
```sql
CREATE TABLE gl_outbox (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_type   VARCHAR(20)  NOT NULL,   -- SALE | PURCHASE | SALE_RETURN | PURCHASE_RETURN
  ref          VARCHAR(255) NULL,       -- invoiceNo / purchaseInvoiceNo
  grand_total  DECIMAL(19,2) NULL,
  sub_total    DECIMAL(19,2) NULL,
  tax_total    DECIMAL(19,2) NULL,
  cost         DECIMAL(19,2) NULL,
  paid_amount  DECIMAL(19,2) NULL,
  method       VARCHAR(30)  NULL,
  status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',   -- PENDING | POSTED
  attempts     INT          NOT NULL DEFAULT 0,
  last_error   VARCHAR(500) NULL,
  organization_id BIGINT    NULL,       -- for runAs on the relay
  user_id      BIGINT       NULL,
  created_at   DATETIME     NULL,
  updated_at   DATETIME     NULL,
  KEY idx_outbox_pending (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```
Structured columns (not a JSON blob) → queryable + maps straight to `PostingEventRequest`. Additive, fresh/prod-safe.

## 3. Components (business-service)
- `GlOutbox` entity + `GlOutboxRepo` (`findTop100ByStatusOrderByIdAsc("PENDING")`).
- `GlPostingOutbox` service: `enqueue(eventType, PostingEventRequest fields, org, user)` — saves a PENDING row and
  registers an `afterCommit` best-effort `tryDeliver(id)`.
- `GlOutboxRelay` (`@Component`): `tryDeliver(id)` (runAs → postEvent → mark POSTED / attempts++) and
  `@Scheduled(fixedDelayString="${gl.outbox.relay-delay-ms:30000}") flushPending()`.
- **Producers switch** from `financeClient.postEvent(...)` (best-effort) to `glPostingOutbox.enqueue(...)`:
  `SagaSellService` (SALE), `PurchaseService` (PURCHASE add + PURCHASE_RETURN/PURCHASE on edit), `SellController`
  (`saleReturn` SALE_RETURN, `updateSell` SALE_RETURN+SALE).

Finance's **payment→GL hook** (`PaymentService.record`) is **intra-service** (same JVM/DB, no network) → far lower
risk; left as-is this slice (a finance-side flag/retry is a small follow-up if we want belt-and-braces).

## 4. Decisions to confirm
1. **Delivery timing** — **afterCommit immediate flush + relay retry** (recommended: books stay real-time, tests
   unchanged, still durable) vs **pure async** (relay-only; eventual, would need tests to poll/flush).
2. **Payload** — **structured columns** (recommended) vs JSON blob.
3. **Scope** — **business→finance `postEvent` only** now (the fragile cross-service hop); finance intra-service
   payment hook deferred (recommended).
4. **Dedup** — accept rare duplicate journals for now (reconciling), add finance-side per-event idempotency later
   (ties to audit #5) — vs build dedup now.

## 5. Test plan
- **Unit:** relay marks POSTED on success, `attempts++`/PENDING on a thrown client (mock `FinanceClient`).
- **Cypress `gl-outbox.cy.js`:** a sale posts to the GL (afterCommit flush) AND leaves a POSTED outbox row
  (`/gl/outbox` debug read, tenant-scoped); trial balance balanced. (Failure-injection/retry is the unit test's job.)

## 6. Cadence
Document → **Design (this)** → confirm → Implement (V16 + entity/repo/outbox/relay + switch producers) → Test
(`mvn` unit + Cypress) → headed green → next (#3 void, then #5 idempotency, #6 audit log).
