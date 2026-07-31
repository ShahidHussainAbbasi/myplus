# maxtheservice — Platform Domain Lifecycles, OMS & SaaS Engineering Standards
### Master reference for review & implementation

**Prepared:** 2026-07-31 · **Verified against branch:** `feature/education-review` (the most advanced tree; item→product / stock→inventory convergence complete).
**Consolidates & supersedes:** `domain-lifecycle-audit.md` (per-vertical, full-stack audit) + `order-management-design.md` (OMS design) into one authoritative document, updated to current-branch-verified state. Companions kept for depth: `SAAS-BUILD-STANDARDS.md`, `ARCHITECTURE-MULTITENANCY.md`, `commerce-verticals-blueprint.md`, `pos-retail-standards-audit.md`.
**Accuracy basis:** grades are evidence-based (entities, endpoints, Flyway, Cypress, and this session's direct code verification). Facts newly verified in this pass are marked **(verified 07-31)**. Confidence: high for business/POS, catalog, inventory, finance, education; medium for pharma, marketplace, appointment, welfare, agriculture (surface-verified).

**Legend:** ✅ implemented to standard · 🟡 partial / below standard · ⬜ missing · 🔒 configurability gap (per-org setup absent/hard-coded).

---

## 0. Executive verdict

1. **The commerce backbone is industry-standard and largely built** — not greenfield. Multi-tenant, BigDecimal money, Flyway everywhere, a reserve→confirm→release inventory saga with idempotency + recovery relay, per-org sequential invoicing, GL/AR/period-close in `finance-service`, and a productId-native catalog/inventory model. **(verified 07-31: `Item`/`Stock` deleted; `SagaSellService` productId-native, slice 101.)**
2. **The single most important correctness gap is the order money-path (OMS-1):** storefront (public checkout) orders never become a trade sale, so online revenue, tax, AR, payments and GL are silently missing for that channel. POS-originated orders are fine (they carry `invoiceNo`). **(verified 07-31.)**
3. **The largest structural opportunity is a cross-vertical Order domain** — today there is one storefront fulfilment tracker in `marketplace-service` and no configurable order lifecycle; six other verticals collapse order-shaped use cases into an invoice or lack them.
4. **The through-line requirement — "the owner configures anything, per tenant" — is now shared infrastructure** (`common-settings`) with 4 live consumers; the work is rolling it to the remaining 6 services and folding bespoke screens in.
5. **Two genuinely 0-today domains remain:** the **B2B commercial layer** (contract/tiered pricing, quotes→approval, credit limits & terms, account hierarchy) and the **logistics layer** (carrier/shipping, pick/pack, partial/multi-shipment, DOM routing, backorders).

---

# PART I — SaaS platform foundations (inherited by every vertical)

## 1.1 Foundations scorecard

| Foundation | business/POS | catalog | inventory | finance | pharma | marketplace | education | welfare | agri | appointment |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| Multi-tenancy (org_id + scoped reads + stamped writes + anti-IDOR) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Method authz (`@PreAuthorize` on mutations) | ✅ | ✅ | ✅ | ✅ | 🟡 | 🟡 | ✅ | ✅ | ✅ | 🟡 |
| Money = BigDecimal(19,2) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ (int fees by design) | ✅ | ✅ | n/a |
| Flyway schema (deploy-reproducible) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Cypress lifecycle gate | ✅ | ✅ | ✅ | ✅ | 🟡 | 🟡 | ✅ | 🟡 | 🟡 | 🟡 |
| Per-org **config store** (`common-settings`) | ✅ | 🔒 | 🔒 | 🔒 | 🔒 | 🔒 | ✅ | ✅ | ✅ | 🔒 |
| Reliability (saga / outbox / idempotency on money ops) | ✅ | – | ✅ | ✅ | inherits | 🟡 | – | – | – | – |

## 1.2 Per-org configurability — the SaaS through-line (`common-settings`)

The recurring product requirement — *the owner sets up anything from configuration, per tenant* — is first-class shared infrastructure:

- **SPI design (Open/Closed, DRY):** `SettingsCatalogProvider` = *what* is configurable (code-defined `SettingEntry` key/label/type/default/group); `SettingsStore` = *where* overrides persist (each service owns its own `org_setting` table — no cross-module `@EntityScan`, data ownership stays local). Engine (`SettingsService`), REST (`SettingsController /settings`) and wiring (`@AutoConfiguration`, inert until a `SettingsStore` is supplied) are written **once**. A new consumer ≈ 40 lines; a new policy = one catalog entry, no schema change.
- **Live consumers (4):** business/POS, education, welfare, agriculture — each ships **behaviour-wired** toggles (no dead toggles).
- **Gap (🔒):** roll to catalog/inventory/finance/pharma/marketplace/appointment; fold business's bespoke Tax/Stores/period-lock screens in; turn each hard-coded policy (pharma safety/insurer, marketplace shipping/coupon, appointment hours/slots) into owner toggles.

## 1.3 Security, money, reliability standards

- **Authz** privilege-based (roles hold privileges; code checks privileges) at HTTP (`hasAuthority('LOGIN_PRIVILEGE')`), method (`@PreAuthorize`) and view (`sec:authorize`) layers. Internal service calls use a shared `INTERNAL_SECRET` trust boundary + `runAs` identity forwarding.
- **Money** `BigDecimal(19,2)` everywhere (education fees deliberately integer). **Quantities** should be `BigDecimal(19,3)` for fractional pharma/agri units (order lines today mix Integer/Float — a target correction).
- **Reliability** the sell↔inventory **saga** (reserve→confirm→release) + **recovery relay** + **idempotency keys**; finance **transactional outbox** for GL; **anti-IDOR** by-id reads; **optimistic locking** where concurrent edits occur (missing on marketplace `Order` — OMS-4).

---

# PART II — Per-vertical domain activity lifecycles
*(each stage graded across **front-end UI/UX → back-end service/API → database**, with gaps and configurability)*

## 2.1 Retail / POS — the reference vertical (business + catalog + inventory + finance)

| Lifecycle stage | UI/UX | Service/API | DB | Grade |
|---|---|---|---|---|
| Product master | Catalog product form, barcode | catalog `/addProduct`, `/products/lookup` (barcode\|sku, scoped) | catalog `products` (+tax_code, barcode) | ✅ |
| Customer / vendor master | Register screens, chip pickers | scoped CRUD, DELETE-gated | `customer`, `vender` (party-bridged) | ✅ |
| Stock-in / opening stock | Purchase form, add-stock | purchase → inventory reserve/confirm; `/stock/adjust` | inventory `stock_levels` + FEFO `stock_entries` (multi-batch) | ✅ |
| Sell / checkout | Barcode-scan cart, tender, split | `addSell` → `SagaSellService` (catalog price + FEFO reserve + **tax** + GL); **productId-native** | `sell`, `customer_history` (+store_id, invoice_no, tax_total) | ✅ **(verified 07-31)** |
| Payment / tender | Multi-tender, store-credit redeem, change, **SPLIT** | `PaymentService.settle` (server-derived); STORE_CREDIT + INSURANCE tenders | `payment` (method enum), `payment_method` | ✅ **(G5, verified 07-31)** |
| Returns / void | Return dialog, Void (VOID_INVOICE) | `saleReturn` → **inventory restock via `returnStock(reservationId)`** (original batches) + credit note + store credit; pharmacy **quarantine** flag; `voidSell` | `sale_return`, `store_credit_txn`, GL reversal | ✅ **(G2 + quarantine, verified 07-31)** |
| Cash drawer / shifts | Shift open/close, cash movements | shift lifecycle | `cashier_shift`, `cash_movement` | ✅ **(verified 07-31)** |
| Park / hold sale | Park & resume | parked-sale endpoints | `parked_sale` | ✅ **(verified 07-31)** |
| Multi-store | Store switcher, per-store scoping | store grants → JWT → store_id scoping | `store`, store_id on txns | ✅ |
| Tax | Tax Settings, multi-rate codes | tax_code master → ProductRef.taxRate; per-rate breakdown | catalog `tax_code` | ✅ **(G3)** |
| Reports / finance / period-close | Sale Detail, receipts, owner Finance page, Period-Close tab | scoped reports; finance GL auto-post + statements; `PeriodLockGuard` (10 ops) | finance per-service DBs, `period_lock` | ✅ |
| Reliability | – | expired-batch exclusion in FEFO; idempotency; GL/audit outbox | `idempotency_record`, `gl_outbox`, `audit_outbox` | ✅ **(G1 this session; verified 07-31)** |
| **Org configuration** | **Configuration screen** (self-rendering) | `common-settings` (`/getBusinessConfig`/`/saveBusinessConfig`) | `org_setting` (Flyway V26) | ✅ |

**POS gaps:** fold legacy Tax/Stores/period-lock into the settings store; GL-reversal-drift items in `pos-retail-standards-audit.md`. **No order layer** (instant-invoice only) — advance/layaway/backorder handled by the OMS in Part III.

## 2.2 Pharmacy (pharma-service + commerce core; PHARMA userType → business dashboard)

| Stage | UI/UX | Service/API | DB | Grade |
|---|---|---|---|---|
| All commerce stages | inherited (relabelled Medicine/Supplier/Patient/Dispense) | inherited business/catalog/inventory/finance | inherited | ✅ |
| Clinical flags | – | `MedicineClinical` (rxRequired/controlledSubstance/drugCategory), keyed by productId | `medicine_clinical` | ✅ (composes catalog, no duplicate master) |
| Prescription / dispense | Rx screens | `/prescriptions`, `/{id}/dispense` | pharma Rx entities | 🟡 (no partial dispense / substitution / backorder) |
| Insurance / co-pay | `#sellInsured`, INSURANCE tender | tender split; counts as paid | payment.method INSURANCE | 🟡 (**no insurer-side AR / claim reconciliation** — revenue-tracking gap) |
| Controlled-drug register | – | `/controlled-register` | – | 🟡 (verify regulatory completeness) |
| Return quarantine | quarantine flag on return | `returnStock` no-restock path | – | ✅ **(P11, verified 07-31)** |
| Authz on Rx/dispense | – | ⬜ no `@PreAuthorize` on pharma mutations | – | 🟡 |
| **Configuration** (safety checks, insurers, schedules) | ⬜ hard-coded | ⬜ | – | 🔒 |

## 2.3 E-commerce / Marketplace (marketplace-service + commerce core)

| Stage | UI/UX | Service/API | DB | Grade |
|---|---|---|---|---|
| Browse / catalog | storefront | catalog `/public/...` | catalog | ✅ |
| Cart / coupons | guest cart | `/public/cart`, `/coupons` | `cart`, `cart_item`, `coupon` | ✅ / 🟡 (coupon per-org config) |
| Checkout | guest checkout (reserve→charge→confirm) | `/public/checkout` via **same inventory saga** (runAs) | `orders` (source POS\|STOREFRONT) | ✅ |
| **Order → books** | – | ⬜ **no trade sale / invoice / GL for storefront orders** — `MarketplaceClientsConfig` wires no Trade/Finance client; `placePublic` never records a sale | – | ⬜ **OMS-1 (verified 07-31)** |
| Fulfilment lifecycle | Orders screen, status buttons | `/orders/{id}/status` — **accepts any transition, no state machine, status change not `@PreAuthorize`d** | `order` + `FulfilmentStatus` NEW→…→DELIVERED, `order_event` | 🟡 **OMS-2** |
| Payment gateway | – | sandbox charge only; no PSP webhook; refunds bypass GL | `payment_status/ref` on order | 🟡 |
| Refund / return | back-office | `/{id}/refund`, `/{id}/return` (ADMIN-gated) + stock-back | – | 🟡 (no line-level RMA / credit note / GL reversal) |
| Idempotency / concurrency | – | ⬜ `UUID` per call (double-charge); no `@Version` | – | ⬜ **OMS-3/4** |
| Order identity | – | ⬜ tracking uses raw auto-increment id; unscoped `findById` | – | ⬜ **OMS-8** |
| **Configuration** (shipping, coupon, terms) | ⬜ `ShippingOption` enum with literal fees; no `org_setting` | ⬜ (Flyway V1–V9 none) | – | 🔒 **(verified 07-31)** |

*Marketplace is the OMS focus of Part III.*

## 2.4 Education (education-service) — most mature non-commerce vertical

| Stage | UI/UX | Service/API | DB | Grade |
|---|---|---|---|---|
| School/branch master | Campus screen, branch switcher | School CRUD, `/getMySchools` | `school` (=branch) | ✅ |
| Student/guardian/grade/staff/subject/vehicle | Register modals | scoped CRUD, DELETE-gated, N+1 batched | per entity | ✅ |
| Multi-branch role×location | branch switcher, grants | `user_location_access` → school_id scoping | – | ✅ |
| Attendance | class-roster marking | branch-scoped roster + bulk-mark | `attendance` | ✅ |
| Fees / vouchers | Collection/Voucher/Ledger/Report | `FeeService` (int math), aged vouchers, opening dues | `fee_collection`, `fee_setting` | ✅ |
| Alerts / analytics | Public/System Alerts; Chart.js dashboard | Alerts + channels (real SMTP); `/getDashboardAnalytics` | alert entities | ✅ |
| **Configuration** | **Configuration screen** | `common-settings` (2nd consumer) | `org_setting` (Flyway V5) | ✅ |
| Order-shaped gaps | ⬜ | ⬜ no admission application, transport/hostel/book request lifecycle | – | ⬜ (Part III REQUEST_ORDER) |

**Gap:** staff/subject branch-scoping (needs `school_id`); fold legacy fee flags into the store.

## 2.5 Welfare & 2.6 Agriculture — thin CRUD skeletons

| Vertical | Built | Missing (below SaaS-product standard) | Config |
|---|---|---|---|
| **Welfare** | donator + donation CRUD (BigDecimal, DELETE-gated) | ⬜ receipts, ⬜ campaigns/pledges/recurring, ⬜ reporting/analytics; near-zero test coverage | ✅ Config screen (`requireDonor`, `allowDuplicateNames`) |
| **Agriculture** | land + income/expense CRUD (BigDecimal) | ⬜ **crop/season P&L (the core value)**, ⬜ reporting/analytics; near-zero coverage | ✅ Config screen (`requireLand`) |

## 2.7 Appointment (appointment-service)

| Stage | UI/UX | Service/API | DB | Grade |
|---|---|---|---|---|
| Hospital/doctor master | screens | CRUD, DELETE-gated | org-scoped | ✅ |
| Public booking | request form | `/public/appointment-request` | `appointment` | ✅ |
| Booking lifecycle | 🟡 | ⬜ **`Appointment` has no status field; `fee` is String, `dateTime` is String** | – | ⬜ |
| Slot/availability, reminders | ⬜ | ⬜ | – | ⬜ |
| **Configuration** (hours, slot length, rules) | ⬜ | ⬜ | – | 🔒 |

## 2.8 Procurement (cross-vertical)

`Purchase` is a **received vendor bill** — one row per line, no header, no PO, no approval, no goods receipt. ⬜ requisition → PO → approval-above-threshold → send-to-vendor → **partial goods receipts** → 3-way match → payment. (Part III PURCHASE_ORDER.)

---

# PART III — The Order Management System (cross-vertical order domain)

## 3.1 The problem & sequencing

The platform has **no order domain** — one storefront fulfilment tracker in `marketplace-service` and order-shaped use cases elsewhere collapsed into invoices. A grep for `StateMachine`/`canTransition`/`purchase order`/`goods receipt`/`backorder`/`split ship`/`carrier`/`tracking number`/`pick list` across all 30 modules returns essentially nothing.

**Verified defects in what exists (marketplace order path, verified 07-31):**

| # | Defect | Impact |
|---|---|---|
| **OMS-1** | Storefront revenue never reaches the books — no Trade/Finance client wired; `placePublic` never records a sale/invoice. POS orders carry `invoiceNo`, hiding the asymmetry. | P&L, trial balance, tax register, period close silently wrong for online sales. |
| **OMS-2** | No state machine — `updateStatus()` accepts any transition, no `@PreAuthorize` on status change. | Unauditable history; any user can mark delivered. |
| **OMS-3** | No idempotency on placement (`UUID` per call). | Double-charge on double-submit. |
| **OMS-4** | No optimistic locking (`Order` has no `@Version`). | Concurrent packers overwrite each other. |
| **OMS-5** | POS order `record()` never persists lines; browser-driven with client total. | Silent loss; unreturnable orders. |
| **OMS-6** | Reservations never expire (no TTL). | Abandoned checkout holds stock forever. |
| **OMS-7** | Unbounded reads (`findScoped` returns all orders). | Breaks at first real merchant (perf). |
| **OMS-8** | Public tracking uses raw auto-increment id + unscoped `findById`. | Cross-tenant id enumeration; no merchant-usable reference. |

**Sequencing decision — repair in place first, extract second.** Slices O1–O5 land inside `marketplace-service` (OMS-1 is a live ledger defect and must not wait for a service migration); O6 extracts the corrected aggregate into a new `order-service` (port 8097, `myplusdb_order`) with a dual-read window. Extracting first would carry all eight defects into a new service.

## 3.2 Target domain model

Separate the two axes today's code conflates: **fulfilment state** and **payment state**. Header status is a **derived projection** of line quantities (`qty_ordered/allocated/fulfilled/cancelled/returned`) — the quantities are the single source of truth, so a partially-shipped order can't disagree with its lines. Core tables: `orders`, `order_line`, `order_allocation` (location + reservation handle + `expires_at` TTL), `order_fulfilment` + `order_fulfilment_line` (carrier/tracking/label), `order_payment_ref`, `order_event` (tamper-evident hash chain), `order_number_sequence`, `org_setting`, `order_outbox`. All money `BigDecimal(19,2)`; **quantities `BigDecimal(19,3)`** (fractional pharma/agri). Full ERD/class/sequence diagrams in `order-management-design.md` §2.2, §3.

## 3.3 Configurable lifecycle (state machine as a policy object)

The lifecycle is a policy object (`OrderLifecycle.allowedTransitions(orderType, from)` + `requiredPrivilege(...)`), **not** scattered `if`s — declared per `orderType`, selectable per org via `order.flow.<type>`. Illegal transition → **409**; unauthorised → **403**. The API returns `allowedTransitions` with every order so the UI renders buttons from the server, not a hard-coded JS map.

**Order types & lifecycles (one aggregate, many verticals, config-driven):**

| Order type | Verticals | Lifecycle |
|---|---|---|
| `SALES_ORDER` | retail/POS, e-commerce, agri | DRAFT→PLACED→CONFIRMED→ALLOCATED→PICKED→PACKED→DISPATCHED→DELIVERED (+PARTIALLY_FULFILLED, BACKORDERED, ON_HOLD, RETURN_REQUESTED→RETURNED→CLOSED) |
| `PURCHASE_ORDER` | all (procurement) | DRAFT→SUBMITTED→APPROVED(threshold `APPROVE_PO`)→ORDERED→PARTIALLY_RECEIVED→RECEIVED→BILLED(3-way)→CLOSED |
| `DISPENSE_ORDER` | pharmacy | Rx intake → dispense → substitution/partial/backorder → FEFO dispense → insurer claim + co-pay AR |
| `SERVICE_ORDER` | appointment | booking → slot allocate → confirm → deposit → reschedule/cancel/no-show → visit → invoice |
| `REQUEST_ORDER` | education, welfare | application/pledge → offer/schedule → acceptance → allocation → voucher/receipt |

**Transition authority (the `@PreAuthorize` matrix that doesn't exist today):** `CREATE_ORDER` (place/confirm), `FULFIL_ORDER` (allocate/pick/pack/dispatch/deliver), `CANCEL_ORDER`, `AMEND_ORDER` (blocked after any fulfilment), `APPROVE_PO`, `RECEIVE_GOODS`; refund/return stay `ADMIN_PRIVILEGE`.

## 3.4 The money path — one invoice, one revenue journal (fixes OMS-1)

An order **never posts to the ledger itself.** When an order becomes revenue (per `order.invoice.trigger`), the OMS calls `business-service`'s existing sale path through a new `TradeClient` contract and stores the returned `invoiceNo`. `business-service` remains the sole author of trade sales (invoice #, tax, COGS, GL outbox, audit, period-lock, store-credit, AR, payment rows); `finance-service` remains the sole author of journals. `SagaSellService.addSell` already takes an `idempotencyKey` and replays the same invoice — **no new money logic is written; it is a wiring change.** The parallel storefront saga + duplicated relay are then deleted (a DRY win). COD becomes a real `COD_COLLECTION` payment on delivery; refunds/returns post a reversal through the same path.

## 3.5 Allocation & fulfilment

- **Location-aware holds:** `StockReservationRequest` gains optional `locationId` (FEFO within location, org-wide fallback — backwards-compatible); sourcing via `order.fulfilment.sourcingStrategy` (`FIXED_STORE`/`MOST_STOCK`/`NEAREST`).
- **Hold expiry (OMS-6):** `Reservation.expires_at` + scheduled sweeper; Redis for short-lived checkout holds.
- **Partial/split/backorder:** a fulfilment is a subset of lines×qty; header status recomputes after each change; backorders when `order.fulfilment.allowBackorder`.
- **Pick/pack/dispatch** with carrier, tracking, label URL (object storage); each transition emits an order event feeding notifications + the customer timeline.

## 3.6 Per-org order configuration (`order.*` — the 5th `common-settings` consumer)

Representative keys (all owner-settable, no dead toggles): `order.number.format`, `order.flow.salesOrder` (RETAIL\|ECOM\|SIMPLE), `order.cancel.allowedUntil`, `order.return.windowDays`, `order.return.restockingFeePct`, `order.payment.codEnabled`, `order.payment.prepayRequired`, `order.payment.autoCancelUnpaidHours`, `order.invoice.trigger` (ON_PLACEMENT\|ON_PAYMENT\|ON_DISPATCH\|ON_DELIVERY), `order.fulfilment.allowPartial/allowBackorder/sourcingStrategy`, `order.shipping.mode` (FLAT\|WEIGHT\|ZONE — replaces the hard-coded enum), `order.approval.requiredAbove` (PO threshold), `order.hold.ttlMinutes`.

## 3.7 B2B commercial layer (0 today — the biggest additive gap)

Beyond the order mechanics, B2B needs (none exist today): **contract & tiered pricing + price lists** (resolve base→contract→volume-tier→promotion, off the hot path); **quotes & drafts → approval → convert to order**; **approval workflows** (threshold/item-based routing); **credit limits & terms (Net 30/60) + credit-check gate + credit hold** (extend party + finance AR); **company→branch→contact account hierarchy with roles** (buyer/approver/accountant). Applying the decision rule, these are **extensions + libraries**, not new services — except **logistics** (carrier/shipping + pick/pack), which is one justified new bounded context (`logistics-service`).

---

# PART IV — Database selection per workload

| Workload | Today | Industry-standard fit | Verdict |
|---|---|---|---|
| Transactional core (sell, payment, fees, GL, stock, orders) | MySQL 8, DB-per-service, Flyway | Relational OLTP with ACID | ✅ **Right store — keep.** Money+inventory need ACID. |
| Sessions / rate-limit / **checkout holds / idempotency / slot locks** | Redis (opt-in) | Redis / in-memory with TTL | ✅ Right store; make standard, not opt-in (fixes OMS-6). |
| **Analytics** (dashboards, KPIs, funnel, fill-rate, on-time%) | ad-hoc `GROUP BY` on OLTP tables | **CQRS read-model** (summary tables refreshed by outbox events) or columnar/OLAP | 🟡 **Gap** — biggest scale/correctness win. Never `GROUP BY` the live order table. |
| **Audit log** | MySQL append rows | Append-only + **hash chain** (tamper-evident) | 🟡 Immutable-by-convention only; add a hash chain. |
| **Storefront search** | MySQL `LIKE`/index | Full-text (OpenSearch / MySQL FULLTEXT) | 🟡 Fine for POS exact-match; a real storefront needs relevance/facets. |
| Event fan-out | MySQL outbox polled by relay | outbox + **broker (Kafka/RabbitMQ/SQS)** | 🟡 Acceptable now, ceiling later. |
| Documents (receipts, invoices, labels, PDFs) | in-DB / filesystem | **Object storage + signed URL** | 🟡 Blobs don't belong in MySQL rows. |
| Config / flags (`org_setting`) | MySQL via `common-settings` | Relational KV + cache if read-hot | ✅ Right store. |

**Principle:** preserve DB-per-service + Flyway; refine **per workload** (CQRS analytics read-model → hash-chained audit → object storage → search only when the storefront justifies it) — never a blanket database switch.

---

# PART V — Design patterns & pure-development principles (SOLID / DRY)

**Correctly applied today:** Saga (orchestration) for sell↔inventory + storefront orders; Transactional Outbox + relay (finance GL); SPI/Strategy + auto-config (`common-settings`, `common-security`); DIP via contracts + `@HttpExchange` clients (`commerce-contracts`, `FinanceClient`/`PartyClient`); Anti-corruption/DTO mapping (ModelMapper, entities never cross controllers); Repository/Adapter/Facade (Spring Data, GenericResponse adapter, gateway); Policy object (`PeriodLockGuard`, `LocationScope`).

**Where principles are strained (gaps):**
- 🟡 **DRY at the UI layer** — the self-rendering Config screen is duplicated per front-end (business.js vs education.js); extract a shared `js/common/config-screen.js` (a 3rd+ vertical now justifies it). The hard-coded JS transition map (`ecommerce.js`) violates server-authority — the OMS design fixes it by returning `allowedTransitions`.
- 🟡 **SRP drift** in fat monolith controllers (Sell/Fee carry assembly logic that belongs in a service).
- 🟡 **Two response envelopes** (`GenericResponse` monolith/education vs `ApiResponse` common-web) — standardise new services on `ApiResponse`, adapters bridge legacy.
- ⬜ **No API versioning** (`/v1`) — adopt URI/header versioning before external consumers.
- 🟡 **Inconsistent authz depth** — `@PreAuthorize` spine ~90%; pharma/marketplace/appointment tails remain.

---

# PART VI — Microservices-level design

**Sound (industry-standard):** bounded-context services (auth, catalog, inventory, business, finance, audit, party, education, welfare, agri, pharma, marketplace, notification, campaign, analytics, appointment, + libraries commerce-contracts/commerce-domain/common-*); **DB-per-service**; **API gateway** (per-route JWT + Resilience4j circuit breakers + CORS); **Eureka** discovery + **config-server**; **stateless JWT** → `X-*` headers → `HeaderAuthFilter`; **OpenTelemetry** traces+logs (Grafana/Loki/Tempo overlay); Flyway `validate`-ready.

**Gaps vs standard (ordered by leverage):**
1. 🟡 **Synchronous coupling dominates** — almost all inter-service calls are sync HTTP; only finance uses async (DB-polled outbox). An **event broker** (with the outbox as source) is the **highest-leverage architectural change** — it unblocks the CQRS analytics read-model and decouples notification/analytics fan-out.
2. ⬜ **No async event bus / schema registry** — contracts are compile-time only.
3. 🟡 **Observability is traces+logs, not metrics** — Prometheus + per-tenant baggage needed for SLOs.
4. 🟡 **Internal-secret trust boundary** is a single shared secret — rotate/scope per service as it grows.
5. 🟡 **Monolith still in topology** (correct interim UI/proxy; residual: appointment reads `user` table) — keep thinning.
6. ✅ **Deploy/infra** (POS Docker subset, ECS/Fargate Terraform, OIDC, Secrets Manager, Trivy, non-root) at standard for the piloted subset; extend on AWS rollout.

---

# PART VII — Consolidated gap register & phased roadmap

**Gap register (priority order):**

| Pri | Gap | Type | Home |
|---|---|---|---|
| P0 | **OMS-1 storefront→books** (online revenue not in ledger) | correctness | marketplace → `business-service` via new `TradeClient` |
| P0 | OMS-2/3/4/8 (state machine, idempotency, `@Version`, order identity) | correctness/security | marketplace |
| P1 | Order lifecycle depth (partial/split/backorder, pick/pack, carrier/tracking, TTL holds) | capability | marketplace → `order-service` (O5/O6) + new `logistics-service` |
| P1 | Config rollout to catalog/inventory/finance/pharma/marketplace/appointment | configurability | `common-settings` consumers |
| P2 | **B2B commercial** (contract/tiered pricing, quotes→approval, credit limits/terms, account hierarchy) | capability | catalog + party + finance + `commerce-pricing` lib |
| P2 | Pharma insurer AR/claims; controlled-register; safety-config; pharma authz | capability/compliance | pharma-service |
| P2 | Appointment slot/lifecycle/reminders/config | capability | appointment-service |
| P3 | Welfare receipts/campaigns/reporting; Agri crop-season P&L/reporting | capability | welfare/agri |
| P3 | **Event broker + CQRS analytics read-model**; hash-chained audit; metrics; object storage; API versioning; shared `config-screen.js` | architecture | platform-wide |

**Phased sequence (each Document→Design→Implement→**Cypress gate**→next; you run the gate):**

- **Phase O (OMS repair-in-place):** O1 storefront→books (`TradeClient` + `/internal/sales`) · O2 lifecycle/authority/idempotency/`@Version`/order-no · O3 `order.*` config · O4 back-office UI (paginate/filter/detail/timeline/surface refund+return) · O5 fulfilment engine (partial/split, `locationId` FEFO, hold TTL, carrier/tracking).
- **Phase O6:** extract `order-service` (8097, `myplusdb_order`) with dual-read; delete marketplace order entities + duplicate relay.
- **Phase O7–O8:** second/third channels (POS sales order, procurement PO→GRN→3-way, quotes) + vertical adapters (pharmacy dispense-order, appointment service-order, education/welfare requests) + `order_daily_summary` CQRS read-model.
- **Phase Config/Authz (parallel):** roll `common-settings` to remaining 6 services; finish `@PreAuthorize` tail (pharma/marketplace/appointment).
- **Phase B2B:** account hierarchy + roles → contract/tiered pricing + `/price/calculate` → quotes→approval→order → credit limits/terms + credit-check.
- **Phase Platform (parallel):** event broker + CQRS analytics read-model (highest structural leverage) → hash-chained audit → metrics/SLOs → object storage → API versioning → shared `config-screen.js`.

---

## Appendix A — reuse map (what NOT to rebuild — DRY)

| Concern | Existing asset | Location |
|---|---|---|
| Stock hold / FEFO / release / **expired-exclusion** | reserve–confirm–release saga + `InventoryClient` | inventory-service, commerce-contracts |
| Sale, invoice #, **tax**, COGS, audit, period-lock, **store credit**, **returns→inventory+quarantine** | `SagaSellService.addSell` + `saleReturn`/`StoreCreditService` | business-service |
| GL, AR/AP, statements, tax register, period close | `FinanceClient`, `PostingEventRequest` | finance-service |
| Reliable delivery | `OutboxEntry`/`OutboxRelay` | common-outbox |
| Idempotency | `IdempotencyService`/`IdempotencyRecord` (extract → `common-idempotency`) | business-service |
| Per-org settings | `SettingEntry`/`SettingsStore`/`SettingsService`/`SettingsController` | common-settings |
| Customer/account identity | `PartyClient`/`PartyRef` | party-service |
| Number formatting | `InvoiceNumbers` | commerce-domain |
| Identity forwarding (background/anonymous) | `GatewayIdentityForwarding.runAs` | common-security |
| Envelope/exceptions/pagination | `ApiResponse`/`ValidationException`/`PageResponse` | common-web |
| UI primitives | `confirm-dialog.js`, `date-picker.js`, `responsive-tables.js`, `dom-safe.js`, `focus-flow.js`, `crud-modal.js` | monolith js/common |

## Appendix B — open decisions for review

1. **First-cut scope** — commerce OMS only (Phase O–O6), or full cross-vertical (through O8)?
2. **B2B-first or B2C/OMS-first?** (B2C storefront + POS are largely built; B2B commercial is 0-today, higher-value; OMS-1 is a live ledger defect.)
3. **Invoice-trigger defaults** — `ON_PAYMENT` (e-commerce) vs `ON_DELIVERY` (retail sales orders); both configurable.
4. **Legacy storefront orders** — mark `LEGACY_UNPOSTED` and leave the books, or back-post at original dates (touches closed periods)?
5. **Extraction timing** — extract `order-service` at O6 (after correctness, before 2nd channel), as designed?

---
*This master reference consolidates the prior `domain-lifecycle-audit.md` + `order-management-design.md`, updated to `feature/education-review` verified state (2026-07-31). It changes no code; it sequences the additive work against the existing, verified build.*
