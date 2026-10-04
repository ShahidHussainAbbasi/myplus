# Slice MKT-1f — support cases and marketplace returns, with the cost bearer

**Status:** BUILT and verified live (2026-10-03): gate 12/12, all gates 69/69 in one run, walk M-1f-01..07 recorded —
see `../marketplace/live-verification-2026-10-03.md` §7.
Rulings R-MKT-12, 13, 14 accepted by the owner 2026-10-03 (§4).

Requirements: **MKT-R8.2** (the customer contacts MaxTheService only), **MKT-R13.1** (the cost bearer follows the
cause), **MKT-R13.2** (the return flow), **MKT-R13.3** (order-time snapshots decide), **MKT-R13.4** (expired or
unsafe goods escalate at once). Depends on MKT-1e (orders, snapshots) and MKT-1e2 (account, payments, refunds).

## 1. Document

A delivered marketplace order can go wrong in ten ways (source §13). The customer must have **one** place to say
so — MaxTheService — and never be sent to a shop. MaxTheService opens a **support case**, tasks the party that can
fix it, and relays the answer. When the answer is "return it", the return runs through MaxTheService too: the money
goes back through the channel it came in, the goods go back to the party that owns them, and the **cost lands on
the party whose fault it was**, read from what the order recorded when it was placed — never from today's
policies.

### 1a. Trace (RULE 0)

**The delivered state lives in the seller's store order, not in the marketplace order.** After Accept, the seller's
store order (`orders`, same service) runs fulfilment (O2/O5b); `mkt_seller_order.store_order_id` links them.
`MarketplaceStatus.SellerOrder` stops at the hand-over on purpose (no second copy of the fulfilment rules).

**Writers of the store order's return states — 4:**

| Writer | Sets | MARKETPLACE-source order today | 1f |
|---|---|---|---|
| `DeliveryService` (:160) | DELIVERED | the seller delivers as for any order | **reader added**: the marketplace order shows Delivered and starts the return window |
| `ShipmentService.applyProjection` | header from parcels | unchanged | none |
| `OrderService.requestReturn` (public `/storefront/return`, store order id + phone) | RETURN_REQUESTED | **reachable**: store ids are sequential and the shopper knows the phone — a return that bypasses MaxTheService (breaks R8.2) | **refused** for source MARKETPLACE, with a sentence pointing to My orders |
| `OrderService.processReturn` (seller's Orders screen) | RETURNED + voids the invoice | the seller's books and stock reverse, but the customer's **online payment is never refunded** (store order `paymentStatus` is PENDING, so `isCardRefundable` is false), and the marketplace order never learns of it | **refused** for source MARKETPLACE unless driven by the marketplace return (it becomes the return's last step) |

**Readers of the marketplace order's status — 3:** `MarketplaceAccountService.accountView` (My orders),
`MarketplaceCheckoutService.view` (public tracking), `SellerOrderService` (seller's Incoming). All three today show
CONFIRMED forever after Accept. 1f derives Delivered / Return requested / Returned from the store order and the
return record; no column is duplicated (the store order stays the one truth for fulfilment).

**Writers of money back — 2 today:** `MarketplacePaymentService.refundIfCancelled` (whole order, on cancel paths)
and the store order's `doRefund` (store card orders only). A return is a **partial** refund (one line, or a
deduction), which neither does: 1f adds `refundReturn(returnId)`, keyed `return:` + return id, written as a fact
before the provider is called (the 1e2 pattern), so a retried approval refunds once.

**Snapshots available (V28 `mkt_order_line`):** seller, stock owner, custodian, fulfiller org; warranty provider,
months, covers, excludes; `return_days`; commission. **Missing for §13:** the carrier and the platform org (needed
by DELIVERY_FAILURE and ROUTING_ERROR). Phase 1 has one fulfiller who delivers (the seller), and the platform is
the operator org — both resolvable without a new snapshot; recorded in the return row at the time it is opened.

**Audit (MKT-R22.4) — 0 writers.** No MKT code writes an audit row: marketplace-service does not use
`common-audit` at all (5 other services do: auth, business, catalog, education, expense — `AuditEmitter`: the row is
written in the caller's transaction, delivered after commit, re-driven if audit-service is down; a refused action
leaves no row). R22.4 was counted as covered only by an unbuilt gate case and a manual case. 1f adopts it: an
`audit_outbox` table (V30), `MarketplaceAuditService extends AuditEmitter`, and a row for every support and return
action, filed under the **seller's** org when the operator acts on a seller's order (the subject, never the actor).
The built slices' actions (agreement acceptance, account and offer approval, match decisions, accept/reject,
customer cancel, policies, settings) were wired in the same pass — G-16 closed.

**Domain already built (MKT-1a):** `ReturnCostPolicy` (10 reasons → bearer role → org from the snapshot;
`requiresUrgentEscalation`). Unit-tested; nothing calls it yet.

## 2. Design

### 2.1 Data (V30, VARCHAR statuses, `ddl-auto=validate`)

- `mkt_support_case` — id, case_no (`SC-`, common-docnum), mkt_order_id, customer_id (nullable: anonymous orders
  open cases by number + phone), topic (ORDER_PROBLEM, RETURN, WARRANTY, OTHER), status (OPEN, WAITING_SELLER,
  WAITING_CUSTOMER, RESOLVED, CLOSED), urgent BIT, assigned_org_id (the party tasked), created/updated, version.
- `mkt_support_message` — case_id, author_kind (CUSTOMER, OPERATOR, SELLER), author_ref, body (VARCHAR 2000),
  visible_to_customer BIT, created_at. The customer never sees a seller's phone or an internal note.
- `mkt_return` — id, return_no (`RT-`), case_id, mkt_order_line_id, quantity, reason (ReturnCostPolicy.Reason),
  bearer_role, bearer_org_id (resolved at open from the snapshot), status (REQUESTED, APPROVED, REJECTED,
  RECEIVED, INSPECTED, REFUNDED, CLOSED), outcome (RESTOCK, QUARANTINE, WRITE_OFF), refund_amount DECIMAL(19,2),
  deduction DECIMAL(19,2), opened_at, decided_by, version. Unique (mkt_order_line_id, status-open) by service rule.

### 2.2 Flows

1. **Customer:** My orders → a delivered order → "Get help" → topic + note → a case `SC-…`. "Return this item" asks
   the reason (the 10 causes in plain words) and is refused after `return_days` from delivery (the snapshot, not
   today's policy). Expired/unsafe → the case is **urgent** and on top of the operator's queue at once.
2. **Operator:** Support cases (queue: urgent first, then oldest) → read → reply to the customer, or **task the
   seller** with a note. Approve or reject the return with a sentence.
3. **Seller:** Marketplace → "Tasks from MaxTheService" → answer; for an approved return, "Item received" →
   inspection outcome (restock / quarantine / write off).
4. **Money:** on RECEIVED + INSPECTED, the refund is computed (line total × quantity − deduction by policy), paid
   through the order's channel (card → provider refund once; COD → per ruling R-MKT-12), and the cost is recorded
   against `bearer_org_id` for settlement (MKT-1g reads it; 1f only records it).
5. **The seller's books:** the store order's existing reversal runs as the return's last step (stock back or
   quarantined, revenue reversed) — called by the marketplace, never directly by the seller for a marketplace order.

### 2.3 Endpoints

Customer (session or number + phone): `POST /public/mkt/account/orders/{no}/cases`, `GET …/cases`,
`POST /public/mkt/cases/{caseNo}/messages`. Operator (`ROLE_ADMIN`): `/mkt/operator/cases…`, decide return.
Seller (own org): `/mkt/seller/tasks…`, `POST /mkt/seller/returns/{no}/received`.

### 2.4 Screens

Public page: "Get help" on a delivered order; the case thread. Platform dashboard: Support cases. Seller's
Marketplace tab: Tasks from MaxTheService. All sentences from the server; six bundles.

## 3. Plan

1. V30 (support, return, `audit_outbox`) + entities + repositories (FlywayMigrationTest); `MarketplaceAuditService`.
2. Delivered/return reader on the three status readers; return window from the snapshot.
3. Case service (open, message, task, resolve; urgent ordering) + tests (tenant isolation: a seller sees only
   tasks for its own org; the customer never sees internal notes).
4. Return service (open with bearer from `ReturnCostPolicy`, approve/reject, received/inspected, refund once,
   seller-books reversal) + tests; `requestReturn` and `processReturn` refusals for MARKETPLACE-source orders.
5. Monolith relays, screens, i18n.
6. Gate `mkt-1f-support-returns.cy.js`; walk cases; RTM; manual page.
7. Live run, fix, commit, push.

## 4. Rulings (accepted 2026-10-03)

- **R-MKT-12 — ACCEPTED: the seller's rider hands the cash back at pickup**; the refund is recorded on the return,
  no money moves through MaxTheService in Phase 1.
- **R-MKT-13 — ACCEPTED: the seller's rider collects** the item from the customer's address.
- **R-MKT-14 — ACCEPTED: change of mind per the offer's return policy**, within the snapshotted return days; the
  customer bears the return cost, deducted from the refund.

Options considered:

- **R-MKT-12 — refunding a cash-on-delivery return.** The customer paid the seller's rider in cash. Options:
  the seller hands the cash back at pickup (recommended for Phase 1: no money moves through MaxTheService), or
  MaxTheService refunds and recovers it from the seller's next settlement (needs MKT-1g).
- **R-MKT-13 — return shipping.** Who arranges the pickup in Phase 1: the seller's own rider (recommended — the
  seller already delivers), or the customer drops it at the shop.
- **R-MKT-14 — change-of-mind returns.** Allowed only where the offer's return policy says so (recommended), with
  the customer bearing the cost (deducted from the refund), or never in Phase 1.
