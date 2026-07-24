# Domain Activity-Lifecycle Audit — all verticals, all layers

**Purpose:** for every vertical, walk the domain activity lifecycle (the real business use-cases end to end) across the three layers — **front-end UI/UX → back-end service/API → database** — grade each step against the *current* codebase, flag where it falls short of SaaS / domain / microservice-industry standards, and give special attention to **per-org / admin configurability**. Ends with a phase-wise sequence.

**Method.** Grades are evidence-based (controller/endpoint surface, entities, Flyway, Cypress specs, this session's tenancy/authz/money/config work). Confidence is high for business/education/finance (deeply worked); medium for pharma/marketplace/appointment/welfare/agri (surface-verified). Legend:

| Mark | Meaning |
|---|---|
| ✅ | Implemented to standard |
| 🟡 | Partial / works but below standard (gap noted) |
| ⬜ | Missing |
| 🔒 | Configurability gap specifically (per-org/admin setup absent or hard-coded) |

---

## 1. Cross-cutting foundations scorecard (the SaaS/microservice base every vertical inherits)

| Foundation | business/POS | catalog | inventory | finance | pharma | marketplace | education | welfare | agriculture | appointment |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| Multi-tenancy (org_id + scoped reads + stamped writes + anti-IDOR) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Method-level authz (`@PreAuthorize` on mutations) | ✅ | ✅ | ✅ | ✅ | 🟡 | 🟡 | ✅ | ✅ | ✅ | 🟡 |
| Money = BigDecimal(19,2) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ (int fees by design) | ✅ | ✅ | n/a |
| Schema via Flyway (deploy-reproducible) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Cypress lifecycle gate | ✅ (73) | ✅ | ✅ | ✅ | 🟡 (10) | 🟡 | ✅ (13) | ⬜ (1) | ⬜ (1) | 🟡 (3) |
| Per-org **configuration store** (`common-settings`: catalog + overrides + Config screen) | ✅ | 🔒 | 🔒 | 🔒 | 🔒 | 🔒 | ✅ | 🔒 | 🔒 | 🔒 |
| Reliability (outbox / idempotency on money ops) | ✅ | – | ✅ (saga) | ✅ | inherits | 🟡 | – | – | – | – |

**Headline foundation gaps:**
- 🔒 **Configurability — now a shared library, rollout in progress.** As of 2026-07-24 the settings capability is extracted into a shared **`common-settings`** module (SPI: `SettingsCatalogProvider` + `SettingsStore`, shared `SettingsService`/`SettingsController`, `@AutoConfiguration`) with **two live consumers — business/POS and education** (education migrated off its in-service copy onto the lib; both own their own `org_setting` table, zero logic duplicated). Business has an owner **Configuration screen**; each vertical now adds config in ~40 lines (a catalog list + a 3-method store). The **remaining gap** is rolling it to the other 6 services (catalog/inventory/finance/pharma/marketplace/welfare/agri/appointment) and folding each vertical's scattered ad-hoc settings (business still has separate Tax/Stores/Team/period-lock screens) into the store.
- 🟡 **Authz tail:** pharma/marketplace/appointment have targeted gates (marketplace refund, appointment doctor/hospital) but not a full pass; their *operational* deletes are intentionally open pending a per-vertical role model (receptionist, seller).
- ⬜ **Test coverage cliff:** welfare/agriculture have 1 spec each; appointment 3. These verticals have no lifecycle gate — a regression there is invisible.

---

## 2. Retail / POS (business + catalog + inventory + finance)  — the reference vertical

The most mature lifecycle; the commerce backbone the other commerce verticals reuse.

| Lifecycle stage | UI/UX | Service/API | DB | Grade |
|---|---|---|---|---|
| Master data — product | Catalog product form, barcode field | catalog `/addProduct`, `/products/lookup` (barcode\|sku, scoped) | catalog `product` (+tax_code, barcode) | ✅ |
| Master data — customer/vendor | Register screens, chip pickers | business Customer/Vender CRUD (scoped, DELETE gated) | `customer`, `vender` | ✅ |
| Opening stock / stock-in | Purchase form, Add-stock | purchase saga → inventory reserve/confirm; `/stock/adjust` | inventory `stock_level` + FEFO `stock_entry` | ✅ |
| Sell / checkout | Barcode-scan cart, tender, insurance split | `addSell` → SagaSellService (catalog price + FEFO reserve + tax + GL) | `sell`, `customer_history` (+store_id, invoice_no) | ✅ |
| Payment / tender | Multi-tender, store-credit redeem, change | PaymentService.settle (server-derived); STORE_CREDIT tender | `payment` (method enum, store_id) | ✅ |
| Returns / void | Return dialog, Void button (VOID_INVOICE) | saleReturn (stock+AP+GL reversal), voidSell | `sale_return`, GL reversal journals | ✅ |
| Store credit | Return→credit, checkout redeem | store_credit_txn ledger + cached balance; GL 2200 liability | `store_credit_txn`, `customer.credit_balance` | ✅ |
| Multi-store | Store switcher, per-store scoping | store grants → JWT → store_id scoping | `store`, store_id on txns | ✅ |
| Reports | Sale Detail (KPIs+13 cols), receipts, day-close | scoped report endpoints | – | ✅ |
| Finance / GL | owner Finance page (P&L/BS/trial/tax register) | finance GL auto-post + journal (gated) + statements | finance per-service DBs | ✅ |
| Period close | owner Period-Close tab | finance period_lock (single source) + PeriodLockGuard gates 10 ops | `period_lock` | ✅ |
| Tax | Tax Settings, multi-rate tax codes | tax_code master → ProductRef.taxRate; per-rate breakdown | catalog `tax_code` | ✅ |
| **Org configuration** | **Configuration screen** (self-rendering, Settings ▸ Configuration) | `common-settings` (`/settings` + monolith `/getBusinessConfig`/`/saveBusinessConfig` proxy) | `org_setting` (business DB, Flyway V26) | ✅ (screen live; migrate legacy Tax/Stores/period-lock in) |

**POS gaps:** the owner Configuration screen now exists on `common-settings` (starter flags: `pos.receipt.showTaxBreakdown`, `pos.sale.negativeStockAllowed` — **behaviour-wiring of the flags is the remaining follow-on**). Legacy Tax/Stores/period-lock remain bespoke screens to fold in. Plus the standing GL-reversal-drift items tracked in `pos-retail-standards-audit.md`.

---

## 3. Pharmacy (pharma-service + commerce core)

Reuses the entire commerce lifecycle above (PHARMA userType → business dashboard), adding Rx.

| Stage | UI/UX | Service/API | DB | Grade |
|---|---|---|---|---|
| All commerce stages (sell/stock/returns/GL…) | inherited (relabelled) | inherited business/catalog/inventory/finance | inherited | ✅ |
| Insurance / co-pay | `#sellInsured` (vertical-only field), INSURANCE tender | tender split; counts as paid | payment.method INSURANCE | 🟡 (settled as paid; **no insurer-side AR / claim reconciliation**) |
| Prescription | Rx screens | `/prescriptions`, `/{id}/dispense` | pharma Rx entities (org-scoped) | 🟡 (verify UI depth) |
| Clinical / safety / interactions | Clinical & Safety screen | `/clinical`, `/safety/check`, `/interactions` | – | 🟡 |
| Controlled-drug register | – | `/controlled-register` | – | 🟡 (regulatory register — verify completeness) |
| Authz on Rx/dispense | – | ⬜ no `@PreAuthorize` on pharma mutations | – | 🟡 |
| **Configuration** (which safety checks, insurers, controlled schedules per org) | ⬜ | ⬜ hard-coded | – | 🔒 |

**Pharma gaps:** insurer AR/claims (revenue leak risk), pharma-local authz, and **regulated-config** (controlled-drug schedules, insurer master, interaction rulesets) are prime configurability candidates.

---

## 4. E-commerce / Marketplace (marketplace-service + commerce core)

Public storefront + back-office order handling.

| Stage | UI/UX | Service/API | DB | Grade |
|---|---|---|---|---|
| Browse / catalog | storefront (catalog public browse) | catalog `/public/...` | catalog | ✅ |
| Cart | guest cart | `/public/cart` add/update/remove | cart entities | ✅ |
| Checkout / quote | guest checkout | `/public/checkout`, `/quote` | order | ✅ |
| Guest account | register/login/track | `/public/customer` register/login/orders, `/public/order/track` | – | ✅ |
| Order back-office | Orders screen | `/orders`, `/{id}/status` | `order`, `order_item` (org-scoped) | ✅ |
| Stock reserve on order | – | storefront reserve/confirm via SAME inventory saga (runAs user 0) | inventory | ✅ |
| Refund / return | back-office | `/{id}/refund`, `/{id}/return` (both **ADMIN-gated** this session) | – | ✅ |
| Coupons | – | `/coupons` | coupon | 🟡 (verify UI + per-org config) |
| Payment gateway (real card) | – | ⬜ (refund logic exists; live PSP integration?) | – | 🟡 |
| **Configuration** (shipping rules, coupon policy, storefront terms per org) | ⬜ | ⬜ | – | 🔒 |

**Marketplace gaps:** live payment-gateway integration depth, coupon/shipping **per-org config**, order-event reliability (🟡).

---

## 5. Education (education-service)

Most mature non-commerce vertical; the **only** vertical with the configuration store (pilot).

| Stage | UI/UX | Service/API | DB | Grade |
|---|---|---|---|---|
| Master — school/branch | Campus screen, branch switcher | School CRUD, `/getMySchools` | `school` (=branch) | ✅ |
| Master — student/guardian/grade/staff/subject/vehicle | Register screens (modal pattern) | scoped CRUD, DELETE gated, N+1 batched | per entity | ✅ |
| Multi-branch role×location | branch switcher, grants | user_location_access (EDUCATION module) → school_id scoping | – | ✅ |
| Attendance | class-roster marking | branch-scoped roster + bulk-mark (teacher marks own branch only) | `attendance` | ✅ |
| Fees / vouchers | Fee Collection/Voucher/Ledger/Report | FeeService (int math), aged vouchers, opening dues | `fee_collection`, `fee_setting` | ✅ |
| Fee/branch policy | **Configuration screen** (self-rendering) | `common-settings` engine (migrated 2026-07-24; `/getConfig`/`/saveConfig` GenericResponse adapter → shared `SettingsService`) | `org_setting` (Flyway V5) | ✅ **(2nd consumer of the shared lib)** |
| Alerts (SMS/email) | Public/System Alerts | Alerts + channels, real SMTP | alert entities | ✅ |
| Analytics | education dashboard (Chart.js) | `/getDashboardAnalytics` (org-scoped) | – | ✅ |
| Guardian/staff branch scope | Config toggles | guardian/discount via student-derive; **staff/subject need school_id** | – | 🟡 (staff/subject deferred) |

**Education gaps:** staff/subject branch-scoping (needs a `school_id` column — deferred, register in the same catalog), and folding the older `feeCollectionBranchScoped` flag into the unified store.

---

## 6. Welfare (welfare-service)

| Stage | UI/UX | Service/API | DB | Grade |
|---|---|---|---|---|
| Master — donator | Register | `/addDonator`, list, delete (gated) | `donator` (BigDecimal amount) | ✅ |
| Donation record | form | `/addDonation`, list, delete | `donation` | ✅ |
| Receipts / statements | ⬜ | ⬜ | – | ⬜ |
| Campaigns / pledges / recurring | ⬜ | ⬜ | – | ⬜ |
| Reports / analytics | ⬜ | ⬜ | – | ⬜ |
| Cypress lifecycle gate | ⬜ (1 spec) | | | ⬜ |
| **Configuration** | ⬜ | ⬜ | – | 🔒 |

**Welfare gaps:** it's a thin CRUD skeleton — no receipts, no campaigns/pledges, no reporting, near-zero test coverage. Below SaaS-product standard for a donations domain.

---

## 7. Agriculture (agriculture-service)

| Stage | UI/UX | Service/API | DB | Grade |
|---|---|---|---|---|
| Master — land | Register | Land CRUD (gated) | `land` | ✅ |
| Income / expense | forms | add/list/delete (BigDecimal amount) | `agriculture_income/expense` | ✅ |
| Per-crop / per-season P&L | ⬜ | ⬜ | – | ⬜ |
| Reports / analytics | ⬜ | ⬜ | – | ⬜ |
| Cypress gate | ⬜ (1) | | | ⬜ |
| **Configuration** | ⬜ | ⬜ | – | 🔒 |

**Agriculture gaps:** same shape as welfare — CRUD skeleton, no analytical layer (crop/season P&L is the core value), no gate.

---

## 8. Appointment (appointment-service)

| Stage | UI/UX | Service/API | DB | Grade |
|---|---|---|---|---|
| Master — hospital/doctor | screens | CRUD; doctor/hospital delete **DELETE-gated** this session | org-scoped entities | ✅ |
| Patient | – | Patient controller (thin?) | patient | 🟡 |
| Public booking | public request form | `/public/appointment-request` | appointment | ✅ |
| Booking lifecycle (confirm/reschedule/cancel/no-show) | 🟡 | delete open (operational) | – | 🟡 |
| Slot/availability management | ⬜ | ⬜ | – | ⬜ |
| Reminders (SMS/email) | ⬜ | ⬜ | – | ⬜ |
| **Configuration** (working hours, slot length, booking rules per org) | ⬜ | ⬜ | – | 🔒 |

**Appointment gaps:** slot/availability engine, booking-status lifecycle, reminders, and heavy **configurability** need (clinic hours, slot rules) — all missing.

---

## 9. The configurability standard (the through-line)

The user's recurring requirement — *"owner sets up anything from configuration, works per customer"* — is now **first-class shared infrastructure**, the industry-standard SaaS shape, with rollout in progress:

- **Shared library (`common-settings`, built 2026-07-24):** code-defined catalog (`SettingEntry` key/label/type/default/group) + per-org override table + self-rendering Config screen + `getBool()` override-else-default. Adding a policy = one catalog entry, no schema change. **Design:** SPI (`SettingsCatalogProvider` = *what* is configurable; `SettingsStore` = *where* overrides persist) so the lib carries **no `@Entity`** — each service owns its own `org_setting` table (data ownership stays with the service; no cross-module `@EntityScan`). The engine (`SettingsService`) + REST (`SettingsController`, `/settings`) + wiring (`@AutoConfiguration`, inert until a service supplies a `SettingsStore`) are written **once**.
- **Consumers live:** business/POS (owner Configuration screen) and education (migrated off its in-service copy — the duplication is deleted, not forked). A new consumer is ~40 lines: a `SettingsCatalogProvider` + a JPA `SettingsStore` + a Flyway `org_setting` table.
- **The remaining gap:** roll to the other services and turn each hard-coded policy (POS tax/receipt/rounding, pharma safety/insurer, marketplace shipping/coupon, appointment hours/slots) into an owner toggle; behaviour-wire the two POS starter flags; fold business's bespoke Tax/Stores/period-lock screens into the store.

**Candidate settings to migrate/introduce once the lib exists** (illustrative, per vertical): POS — receipt footer, rounding mode, default tax code, low-stock threshold, negative-stock allowed; fold in existing Tax/Stores/period-lock. Pharma — controlled-drug schedules, insurer master, which safety checks are hard-stops. Marketplace — shipping/coupon policy, storefront terms, guest-checkout on/off. Education — staff/subject branch scope (+ existing fee flags). Appointment — working hours, slot length, cancellation window. Welfare/Agri — receipt/category masters.

---

## 10. Database selection per use-case (are we using the right store for each job?)

**Current state:** every service is **MySQL 8, database-per-service** (`myplusdb`, `myplusdb_education`, finance/audit/party each own theirs), schema owned via **Flyway**, plus **Redis** (optional, for the gateway rate-limiter and the demo-quota compose). This is a sound, boring default — one well-understood engine, strong consistency for money, DB-per-service isolation. The audit is *where a single OLTP relational store is below the industry-standard fit for the workload*:

| Workload | Today | Industry-standard fit | Verdict |
|---|---|---|---|
| Transactional core (sell, payment, fees, GL, stock) | MySQL (ACID, FK, row locks) | Relational OLTP — exactly this | ✅ **Right store.** Money + inventory need ACID; MySQL is correct. Keep. |
| Sessions / rate-limit / ephemeral counters | Redis (opt-in) | Redis / in-memory | ✅ Right store; make it standard, not opt-in, before scale-out. |
| **Analytics** (`/getDashboardAnalytics`, sale-detail KPIs, org performance) | MySQL aggregate queries on the OLTP tables | Read replica + materialized/summary tables, or a columnar/OLAP store (ClickHouse / DuckDB / BigQuery) for heavy roll-ups | 🟡 **Gap.** Ad-hoc `GROUP BY` on live OLTP tables competes with transactions and won't scale. Standard: CQRS read-model (summary tables refreshed by outbox events) or a separate analytical store. |
| **Audit log** (immutable financial audit, audit-service) | MySQL append rows | Append-only / WORM (ledger table with hash-chain, or QLDB-style verifiable ledger) | 🟡 **Partial.** It's immutable-by-convention; a **hash-chain** (each row hashes the prior) would make tampering detectable — the industry standard for financial audit. |
| **Catalog browse / storefront search** (marketplace, barcode/name lookup) | MySQL `LIKE`/index | Full-text engine (OpenSearch / Elasticsearch / MySQL FULLTEXT) for faceted product search | 🟡 **Gap at ecommerce scale.** Fine for POS barcode exact-match; a real storefront needs relevance/facets/typo-tolerance. |
| **Notifications / outbox / campaign queue** | MySQL polled by `OutboxRelay` | MySQL outbox + a broker (Kafka/RabbitMQ/SQS) for fan-out; or keep DB-outbox at current volume | 🟡 **Acceptable now, ceiling later.** Polling an outbox table is a legitimate pattern at low volume; async fan-out needs a broker (see §12). |
| Documents / receipts / attachments (PDFs, CSV imports) | (in-DB / filesystem) | Object storage (S3) with signed URLs | 🟡 Verify; large blobs do not belong in MySQL rows. |
| Config / feature flags (`org_setting`) | MySQL via `common-settings` | Relational KV is fine; a cache layer if read-hot | ✅ Right store; add a per-org cache if `getBool` lands on a hot path. |

**Principle:** the DB-per-service + Flyway discipline is correct and should be preserved. The refinements are **CQRS read-models for analytics** (biggest correctness/scale win), a **hash-chained audit** (compliance win), **object storage for blobs**, and a **search engine only when the storefront justifies it** — introduce each *per workload*, never a blanket "switch databases."

---

## 11. Design patterns & pure-software-development principles

**Patterns already in play (and correctly applied):**

| Pattern | Where | Note |
|---|---|---|
| **Saga (orchestration)** | sell ↔ inventory reserve/confirm, storefront order (runAs user 0) | Cross-service atomicity without distributed tx — the microservice-correct choice. |
| **Transactional outbox + relay** | finance `OutboxRelay`, GL post-events | At-least-once delivery without 2PC; idempotency keys de-dup. |
| **SPI / Strategy + plugin auto-config** | `common-settings` (`SettingsCatalogProvider`/`SettingsStore`), `common-security` `@AutoConfiguration` | Open/Closed: add a consumer without touching the lib. |
| **DIP via contracts + clients** | `commerce-contracts`, `FinanceClient`/`PartyClient`/`ProductRef` (`@HttpExchange`) | Callers depend on an interface, not the remote service. |
| **Anti-corruption layer / DTO mapping** | ModelMapper DTO↔entity, monolith proxy DTOs | Entities never cross the controller boundary. |
| **Repository, Adapter (envelope), Facade (gateway)** | Spring Data repos; education `SettingsController` GenericResponse adapter; API gateway | — |
| **Policy object** | `common-security/LocationScope`, `PeriodLockGuard` | Single place for a cross-cutting rule. |

**Where pure-development principles are strained (gaps):**
- 🟡 **DRY at the UI layer.** Backend duplication is largely gone (settings engine shared; `main.js` vs `<module>.js` split enforced). But the **self-rendering Config screen is duplicated per front-end** (business.js vs education.js — same three functions, differing only by response envelope). Extract a shared `config-screen.js renderConfigScreen(endpoint, ids)` once a third vertical needs it.
- 🟡 **SRP drift in fat controllers.** Some monolith controllers (Sell/Fee) carry assembly logic that belongs in a service; the microservices are cleaner. Watch controller growth.
- 🟡 **Two response envelopes** (`GenericResponse` in the monolith/education vs `ApiResponse` in common-web services) — a consistency seam. Standardise new services on `ApiResponse`; adapters bridge the legacy surface (as education now does).
- ⬜ **No API versioning** (`/v1`) — additive changes are fine today, but a breaking contract change has no migration path. Adopt URI or header versioning before external API consumers exist.
- 🟡 **Inconsistent authz depth** (§1) — the `@PreAuthorize` spine is ~90% but pharma/marketplace/appointment tails remain (Liskov-style substitutability of "any mutation is gated" not yet total).

---

## 12. Microservices-level design & development

**Sound foundations (industry-standard):** bounded-context services (auth, catalog, inventory, business, finance, audit, party, education, welfare, agri, pharma, marketplace, notification, campaign, analytics, appointment); **DB-per-service** (no shared schema); **API gateway** with per-route JWT filter + **per-service circuit breakers** (Resilience4j) + explicit CORS; **service discovery** (Eureka) + **centralised config** (config-server) + **bootstrap**; **stateless JWT** identity forwarded as `X-*` headers into a `HeaderAuthFilter`; **OpenTelemetry** (traces+logs, self-hosted Grafana/Loki/Tempo overlay built); **Flyway `validate`-ready** per service.

**Gaps vs the microservices standard:**
- 🟡 **Synchronous coupling dominates.** Almost all inter-service calls are **sync HTTP through the gateway**; the only async is the finance **outbox relay (DB-polled)**. Industry-standard for cross-context events (sale → analytics read-model, order → notification, fee-paid → receipt) is an **event broker** (Kafka/RabbitMQ/SQS) with the outbox as the *source*. This unblocks the §10 CQRS analytics read-model and decouples notification fan-out. **Highest-leverage architectural gap.**
- 🟡 **Saga only in commerce.** sell↔stock and storefront orders use the saga; other multi-service writes rely on controller-level `@Transactional` spanning one service. Fine while writes are single-service; revisit if a new flow spans contexts.
- ⬜ **No async event bus / schema registry** — a corollary of the above; contracts are compile-time (`commerce-contracts`) but there's no runtime event contract governance.
- 🟡 **Observability is traces+logs, not metrics.** OTel traces/logs are built; **Prometheus metrics + per-tenant baggage** are noted as not done (`project_log_management_observability`). SLO dashboards need metrics.
- 🟡 **Distributed-tx boundary is the internal-secret trust model.** Internal service-to-service calls bypass user authz by an `INTERNAL_SECRET` (correct), but that secret is a single shared boundary — rotate/scope per service as it grows.
- 🟡 **Monolith still in the topology.** The Thymeleaf monolith proxies to services (correct interim), and residual reads remain (appointment reads the `user` table). The direction — thin the monolith to a pure UI/proxy — is right; track the residuals (`project_myplusdb_removal`, `project_monolith_auth_decommission`).
- ✅ **Deploy/infra** (POS Docker subset, ECS/Fargate Terraform, OIDC, Secrets Manager, Trivy, non-root images) is at standard for the piloted subset; extend to all services on the AWS rollout.

---

## 13. Proposed phase-wise sequence

Ordered by **risk-reduction × leverage** (shared foundations first, then the thin verticals, then per-vertical depth). Each phase is Document→Design→Implement→**Cypress-gate** per the slice cadence.

**Phase A — Shared configuration foundation (highest leverage; unblocks every vertical). — 🟢 IN PROGRESS.**
✅ Done: extracted `common-settings` (SPI + shared `SettingsService`/`SettingsController` + `@AutoConfiguration`); business/POS Configuration screen live; education migrated onto the lib (in-service copy deleted). Gates: `business/org-config.cy.js` (new), `education/owner-config.cy.js` (unchanged — regression guard on the migration).
▶ Remaining: behaviour-wire the two POS starter flags; roll the lib to the next consumers (welfare/agri are cheapest — pure toggles); fold business's bespoke Tax/Stores/period-lock into the store where clean. *Gate: extend each vertical's config spec.*

**Phase B — Authz completion (finish the security spine).**
Pharma Rx/dispense gates; marketplace coupon/order-status gates; appointment booking role model (define receptionist vs admin, then gate). Closes the 🟡 authz tail. *Gate: extend method-authz.cy.js per service.*

**Phase C — Thin-vertical lifecycle + gates (welfare, agriculture).**
Raise both from CRUD skeletons to a real domain lifecycle: welfare receipts + campaigns/pledges + a donations report; agriculture crop/season P&L + reports. Add the missing Cypress lifecycle gates (currently 1 spec each). *Gate: welfare.cy.js, agriculture.cy.js.*

**Phase D — Appointment domain engine.**
Slot/availability management, booking-status lifecycle (confirm/reschedule/cancel/no-show), reminders (reuse notification-service), and clinic-hours/slot **configuration** (via Phase-A store). *Gate: appointment lifecycle spec.*

**Phase E — Pharma regulated depth.**
Insurer master + claim/AR reconciliation (close the co-pay revenue-tracking gap), controlled-drug register completeness, interaction rulesets as **config**. *Gate: pharma insurance-AR + controlled-register specs.*

**Phase F — Marketplace commerce depth.**
Live payment-gateway (PSP) integration, coupon/shipping **config**, order-event reliability (outbox parity with POS). *Gate: marketplace checkout+refund reliability specs.*

**Phase G — Platform/architecture (cross-cutting; parallelisable with C–F).**
The structural findings from §§10–12, ordered by leverage: (1) **event broker + outbox → CQRS analytics read-model** (fixes the biggest DB-fit and coupling gaps at once — sale/fee/order events feed summary tables, off the OLTP hot path); (2) **hash-chained audit** (compliance); (3) **Prometheus metrics + per-tenant baggage** (SLOs); (4) **shared `config-screen.js`** + standardise on `ApiResponse` + introduce **API versioning**; (5) object storage for blobs; search engine only when the storefront justifies it. *Gate: read-model consistency spec; audit hash-chain verify spec.*

**Rationale for the order:** Phase A is first because *every* other phase's configurability gap resolves through it (one lib, many consumers — the DRY/reuse standard). B closes the security spine already 90% done. C/D lift the weakest verticals to product-grade. E/F are domain-depth investments that assume the foundations (A/B) are in place. **G is the architectural spine** — its event-broker/CQRS item (G1) is the single highest-leverage structural change and unblocks analytics scale for every vertical; sequence G1 before E/F if analytics load is already a pain point.

---

*Grades reflect the codebase as of 2026-07-24 (updated: `common-settings` shared library live with business + education consumers; added §10 database-selection, §11 design-patterns/principles, §12 microservices-design lenses, §13 phase G). Ties to [`SAAS-BUILD-STANDARDS.md`](SAAS-BUILD-STANDARDS.md), [`ARCHITECTURE-MULTITENANCY.md`](ARCHITECTURE-MULTITENANCY.md), [`commerce-verticals-blueprint.md`](commerce-verticals-blueprint.md), [`pos-retail-standards-audit.md`](pos-retail-standards-audit.md), [`multi-location-stores-branches-design.md`](multi-location-stores-branches-design.md).*
