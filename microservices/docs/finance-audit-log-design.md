# Audit #6 — Immutable audit trail (standalone audit-service)

**Status:** ✅ DONE - standalone `audit-service` (:8095) shipped with the append-only trail.

Companion to `pos-retail-standards-audit.md` (#6). Built to microservice standards: a **standalone, plug-and-play
audit-service** any service can emit to, fed by a **transactional outbox** so capture is atomic and delivery is
reliable + decoupled. Cadence: Document → **Design** → Implement → headed Cypress → next.

## 1. Problem
A mutating row carries only `user_id` + `updated` for its *current* state — no **append-only** record of *what
happened* (who voided INV-42, when, for how much). A commerce/finance platform needs a forensic, tamper-evident trail,
and every vertical (POS, pharmacy, e-commerce, education) will need the same — so it must be a **reusable capability**,
not a business-service feature.

## 2. Decision — a standalone audit-service (not a shared lib, not in-service)
Audit/activity logging is a genuine **bounded context** with its own storage, query/reporting, and retention — a
cross-cutting capability consumed by many services. Per our microservice standards it is its **own service**, behind a
thin contract, so any producer plugs in without coupling.

**Reconciling "atomic" with microservices:** we do NOT share a DB or use distributed transactions. Instead each
producer captures the event in its **local transactional outbox in the same tx** as the business change (atomic
*capture* — never lost, never logs a rolled-back event), then a relay delivers it to audit-service **asynchronously +
reliably** (eventually-consistent *delivery*). This is exactly the #4 GL-outbox pattern, reused.

```mermaid
flowchart LR
    subgraph business-service op (one @Transactional)
      W[do the work: sale / void / payment] --> O[(audit_outbox — enqueue in-tx)]
    end
    O -->|AFTER_COMMIT + @Scheduled relay, runAs tenant| C[AuditClient.record]
    C -->|POST /api/audit/record  idempotent on eventKey| AS[audit-service]
    AS --> DB[(audit_db — append-only)]
    AS -->|GET /api/audit org-scoped| UI[dashboard Audit Log view]
```

## 3. Components
### 3.1 audit-service (new microservice)
- Standalone Spring Boot service (service-parent), own **audit_db**, Flyway, Eureka + config-server, gateway route
  `/api/audit/**`. Follows the DB/tz/validate-ready standards the other services do.
- `AuditEvent` entity: `id, organizationId, userId, sourceService, action, entityType, entityRef, amount, details,
  occurredAt, receivedAt, eventKey`. **Append-only** (insert + read only; no update/delete surface).
- `POST /api/audit/record` — accepts `AuditEventRequest`; **idempotent** on `(organizationId, eventKey)` (unique) so a
  retried delivery is a no-op (reuses the #5 dedup shape).
- `GET /api/audit` — org-scoped, newest-first, filterable (action / entityType / date), paginated.

### 3.2 commerce-contracts (the plug)
- `AuditEventRequest` DTO + `AuditClient` (RestClient, `GatewayIdentityForwarding` interceptor) — the ONLY thing a
  producer depends on (DIP: producers know the contract, not audit internals).

### 3.3 business-service (first producer)
- `audit_outbox` table + `AuditOutboxService` (**enqueue in the caller's tx** → `@TransactionalEventListener
  AFTER_COMMIT` deliver via `AuditClient` + `@Scheduled` relay via `runAs`), a direct reuse of `GlOutboxService`.
- Each of the 10 money/stock ops emits one event: `SALE, SALE_EDIT, SALE_RETURN, VOID_SALE, PURCHASE, PURCHASE_EDIT,
  PURCHASE_RETURN, VOID_PURCHASE, RECEIPT, PAYMENT` (ref = invoiceNo / purchaseInvoiceNo / voucher; amount + short details).
- `eventKey` (UUID) per event for idempotent delivery.

### 3.4 Reads
- Dashboard **Audit Log** view → monolith proxy → gateway → `GET /api/audit` (org-scoped, read-only table).

## 4. SOLID / patterns
- **SRP / bounded context:** audit-service owns the trail; producers own their business logic.
- **DIP / low coupling:** producers depend on `AuditClient` + DTO only; audit-service internals are private.
- **Transactional outbox + relay:** atomic capture, reliable async delivery (no shared DB, no distributed tx).
- **Idempotent consumer:** `eventKey` dedup.
- **Open/closed & plug-and-play:** finance/inventory/education adopt by adding the client + an `audit_outbox` emit — no
  change to audit-service.

## 5. Test plan (headed Cypress — `audit-log.cy.js`)
1. A credit **sale** → (after the relay) `GET /audit` has a `SALE` row: right `entityRef`, `amount`, `userId`, `sourceService=business`.
2. **Void** it → a `VOID_SALE` row appears; the `SALE` row is unchanged (append-only).
3. A **receivePayment** → a `RECEIPT` row with the voucher ref.
4. Idempotency: the same delivery twice → one row (eventKey dedup).

> Delivery is async (AFTER_COMMIT + relay), so the read step polls briefly for the row — same as the GL-outbox test.

## 6. Build surface
NEW `audit-service` (+ Eureka/config/gateway/compose wiring, audit_db, Flyway) · `commerce-contracts`
(`AuditEventRequest` + `AuditClient`) · business-service (`audit_outbox` V19 + `AuditOutboxService` + 10 emits;
REPLACES the in-service `AuditLog`/`AuditService` drafted earlier) · monolith (`/getAuditLog` proxy + dashboard view).

## 7. Phasing (each a checkpoint)
1. **Scaffold audit-service** (entity/repo/controller/idempotent record + reads) + contracts (DTO+client) + register on Eureka/gateway/compose.
2. **business-service producer**: `audit_outbox` + `AuditOutboxService` + wire the 10 ops (swap the drafted `AuditService` calls).
3. **UI + Cypress**: dashboard Audit Log view + monolith proxy + `audit-log.cy.js`.
4. Later: finance + inventory adopt (add client + outbox emit) — proof of plug-and-play.
