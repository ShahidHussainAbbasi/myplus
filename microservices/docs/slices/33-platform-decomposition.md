# Slice 33 — Platform Decomposition (SOLID / DRY service split + sell↔stock saga)

Status: **DESIGN — APPROVED, IMPLEMENTING** 🟡 (started 2026-06-20, branch `feature/decomposition`).
Governing architecture doc for breaking the codebase into independently-runnable, plug-and-play
services + shared libraries, so new domains (pharmacy first) reuse code **and** logic instead of
re-implementing it. Builds on [`ARCHITECTURE-MULTITENANCY`](../ARCHITECTURE-MULTITENANCY.md).

> User goals (verbatim intent): SOLID, don't reinvent the wheel, break into small pieces that run
> individually for plug-and-play, develop pharmacy with less effort by reuse, and use **sagas** (or any
> mechanism) for atomicity across services.

---

## Document — what & why

A whole-codebase analysis found the same capabilities **built multiple times**. Decomposing into
microservices has so far *multiplied* duplication rather than removed it. This slice defines the target
architecture and the migration that makes each capability live in exactly one place.

### Audit findings — duplication that exists today

**Vertical (commerce) duplication — same aggregate built 3×:**

| Concept | `business-service` | `inventory-service` | `pharma-service` | Winner (system of record) |
|---|---|---|---|---|
| Product / Item | `Item` | `Product` (sku, reorder, cost/sell, taxRate) | `Medicine` (= Product + clinical) | `Product` (base) + `Medicine` as **extension** |
| Stock + batch/expiry | `Stock` (1 row/item) | `StockEntry` (**multi-batch**, warehouse, lot) ✅ | `PharmacyStock` | **`StockEntry`** |
| Supplier | `Vender` | `Supplier` (contact, taxId, terms) ✅ | `String supplier` ⚠️ | **`Supplier`** |
| Sell / Purchase / Customer / Invoice | mature: BigDecimal, org-scoped, per-org invoice #, dues ✅ | — | — | **`business-service`** → `trade-service` |

**Horizontal (use-case) duplication — same cross-cutting concern built N×:**

| Use case | Lives in | Duplication | Verdict |
|---|---|---|---|
| Signup / login / JWT / org-switch / tenant provision | `auth-service` (+ monolith delegates) | mostly **already one service** ✅ | keep; finish monolith cleanup |
| Email / SMTP | `auth-service`, `education-service`, `campaign-service`, monolith | **3–4×** ❌ | **new `notification-service`** |
| SMS / push | education `sendPA`, future appointment reminders | fragmented | fold into `notification-service` |
| 2FA / OTP | `auth-service.TwoFactorService` **and** monolith `com.security.google2fa.*` | **2×** ❌ | consolidate into `auth-service` |
| Captcha / reCAPTCHA | monolith only (`CaptchaService`, `ReCaptchaAttemptService`, …) | stuck in monolith | **library** `common-captcha` (or fold into auth) |
| Alerts | education `AlertController` (notify) vs inventory `AlertController` (low-stock) | name-collision, two meanings | keep domain triggers local; **deliver** via `notification-service` |
| Demo quota / purge | `common-service.DemoPurgeController` + `campaign-service` | already centralized ✅ | keep |
| CSV import | `education-service` only | not yet duplicated | **library** `common-import` (not a service) |
| `ApiResponse` / `PageResponse` / exceptions | inventory, pharma, analytics, marketplace | **4× identical** ❌ | **library** `common-web` |

---

## Design — target architecture

Two reuse mechanisms, used deliberately (this is the core rule):

- **Shared library (JAR)** = reuse of *code/logic* at compile time. For cross-cutting + **stateless** logic.
- **Capability service** = reuse of a *capability + its data* at runtime. For things that own data,
  a lifecycle, or an external integration (mail server, SMS gateway, reCAPTCHA endpoint).
- **Saga** = atomicity across the one boundary that must cross services (sell ↔ stock).

### Decision rule (apply to every "should this be a service?" question)

```
Owns data + lifecycle + external integration?  → SERVICE   (auth, notification, catalog, inventory, trade, pharma)
Stateless logic / parsing / validation?        → LIBRARY   (commerce-domain, common-captcha, common-import, common-web)
Cross-cutting request filter?                  → AUTOCONFIG LIBRARY (common-security — already done)
```

So: *signup → service (already `auth-service`)*; *CSV import → library*. Same DRY goal, different tool.

### Component map

```mermaid
flowchart TB
    subgraph LIB[Shared Libraries — compile-time reuse]
        CS[common-security]
        CW[common-web]
        CD[commerce-domain<br/>money · FEFO · invoice# · findScoped]
        CC[commerce-contracts<br/>DTOs + Feign client ifaces]
        CAP[common-captcha]
        CIMP[common-import]
    end

    subgraph PLAT[Platform Services — used by every module]
        AUTH[auth-service<br/>signup·login·JWT·2FA·org-switch]
        NOTIF[notification-service<br/>email·SMS·push]
        ANALYTICS[analytics-service]
        COMMON[common-service<br/>demo purge]
    end

    subgraph COMM[Commerce Services — composed per domain]
        CATALOG[catalog-service<br/>Product·Category·Unit·Manufacturer]
        INV[inventory-service<br/>StockEntry·batch·expiry·FEFO·alerts]
        TRADE[trade-service<br/>Purchase·Sell·Customer·Invoice]
        PHARMA[pharma-service<br/>Medicine clinical·Prescription·Dispensing]
    end

    GW[api-gateway] --> AUTH & NOTIF & ANALYTICS & CATALOG & INV & TRADE & PHARMA
    PHARMA -->|Feign| CATALOG
    PHARMA -->|Feign| INV
    TRADE -->|Feign + SAGA| INV
    TRADE -->|Feign| CATALOG
    PHARMA -->|Feign| TRADE
    TRADE & INV & PHARMA & EDU -.email/SMS.-> NOTIF
    COMM & PLAT -.depend on.-> LIB
```

### The sell↔stock saga (the only cross-service transaction)

`trade-service` records the sale; `inventory-service` owns the stock. "Make a sale" therefore crosses two
DBs. Use an **orchestration saga: reserve → confirm, with the outbox pattern + idempotency keys.**

```mermaid
sequenceDiagram
    participant POS as trade-service (orchestrator)
    participant INV as inventory-service
    participant DB as trade DB

    POS->>INV: 1. ReserveStock(items, FEFO)  [idempotent → reservationId]
    INV-->>POS: reserved(batch picks) | OUT_OF_STOCK
    POS->>DB: 2. write Sell+Invoice (PENDING) + outbox event (same tx)
    POS->>INV: 3. ConfirmReservation(reservationId)
    INV-->>POS: committed (stock decremented)
    POS->>DB: 4. Invoice → CONFIRMED
    Note over POS,INV: fail at 2/3 → CompensateReservation(reservationId) releases held stock
```

- **Reserve/confirm** holds stock without losing it until the invoice is safely written.
- **Outbox**: saga step + event written in the *same* local tx → a relay publishes → no lost messages, no XA/2PC.
- **Idempotency** (reservationId) makes retries safe. Returns = the inverse saga (restock + credit note).
- This is the **only** saga. Catalog reads, pharma clinical data, prescriptions, analytics = plain calls.

### Why pharmacy then costs little

| Pharmacy need | Source | Effort |
|---|---|---|
| Drug master (name/code/manufacturer) | `catalog-service` | reuse |
| Stock, batch, expiry, FEFO, low-stock alerts | `inventory-service` | reuse |
| Purchase, sell, invoice, receivables, sell↔stock saga | `trade-service` | reuse |
| Money / FEFO / invoice logic | `commerce-domain` JAR | reuse |
| Signup / login | `auth-service` | reuse |
| Rx-ready SMS, near-expiry emails | `notification-service` | reuse |
| **Salt, schedule/narcotics, Rx-required, interactions** | `pharma-service` | **build** |
| **Prescription, dispensing, partial fill, controlled-substance register, MRP rule** | `pharma-service` (+ `commerce-domain` rule) | **build (small)** |

---

## Implement — phased strangler migration (each phase leaves the system runnable)

> User builds/restarts; assistant writes code and requests a build checkpoint after each phase.

- [x] **Phase 1 — `common-web` library.** Extracted the 4× identical `ApiResponse`/`PageResponse`/
  `GlobalExceptionHandler`/`ResourceNotFoundException`/`ValidationException`/`DuplicateResourceException`
  into `common-web` (opt-in via `CommonWebAutoConfiguration`, `@ConditionalOnMissingBean`). Wired as a
  direct dependency into inventory/pharma/analytics/marketplace (NOT `service-parent`, so `business`/
  `education`/etc. which use `GenericResponse` are untouched); rewrote 22 files' imports to
  `com.myplus.common.web.*`; deleted the 24 duplicated local files. Verified 18 bodies byte-identical
  pre-deletion + no same-package usages + zero residual references. *Pure DRY, zero runtime risk.*
  Phase 1a build (common-web alone) ✓ green; Phase 1b build (4 services) ✓ **BUILD SUCCESS** (2026-06-20). **DONE.**
- [x] **Phase 2 — `commerce-domain` library (pure primitives).** Created `commerce-domain` with
  `InvoiceNumbers.format()` (the `INV-%06d` formatter, slice 22), `Money` (null-safe 2dp-HALF_UP BigDecimal
  math), and `TenantSpecifications.scoped/ownScoped` (the org NULL-fallback contract as a reusable JPA
  `Specification` for new entities). `business-service` adopts `InvoiceNumbers.format()` as proof-of-use
  (behavior-identical one-line swap). *Audit reframed the original scope:* `findScoped` is per-entity JPQL
  (offered as a Specification, not a copy-move); there is no existing central money helper (introduced, not
  extracted); **`AppUtil`/`RequestUtil` are copied 4× but all 4 have DIVERGED** → reconciling them is
  deferred to **Phase 2b** (own slice, per-service diff, after commerce services exist — welfare/agri have
  no Cypress so it needs care). Build ✓ **BUILD SUCCESS** (2026-06-20). **DONE.**
- [ ] **Phase 2b — `common-core` util reconciliation (deferred).** Merge the 4 diverged `AppUtil`/`RequestUtil`
  copies into a shared `common-core`; diff every behavioral difference for review first.
- [x] **Phase 3 — `commerce-contracts` library.** **Decision (no prior inter-service pattern existed):
  `@HttpExchange` + load-balanced `RestClient`** — Spring's current direction, not maintenance-mode Feign;
  interfaces live here = clean DIP boundary. **Scope: contracts now, client beans in Phase 6.** Shipped the
  stable saga DTOs (`StockReservationRequest`/`Line`, `StockReservationResponse`/`StockPick`,
  `ReservationStatus`, `ProductRef`) + interface signatures `InventoryClient` (reserve/confirm/release) and
  `CatalogClient` (getProduct). No proxy beans yet (wired where trade/pharma call out, Phase 6). Quantity is
  `BigDecimal` in contracts (avoid float drift); org/actor propagate as headers, never in body. Build ✓
  **BUILD SUCCESS** (2026-06-20). **DONE.**
- [x] **Phase 4 — pick the stock winner (decision + gap analysis).** See the dedicated section below.
  **Decision: `inventory-service.StockEntry` is the single stock system-of-record.** No code change to
  running services in this phase — it ratifies the target and lists the gaps StockEntry must absorb in
  Phase 5/6. `business-service.Stock` + `pharma.PharmacyStock` are **frozen for deletion** (deleted once
  trade/pharma read inventory, Phases 6–7).
- [ ] **Phase 4.5 — org-scope inventory-service (prerequisite for system-of-record).** Bring inventory to
  the multi-tenant standard before it owns everyone's stock. **Foundation:** new reusable
  `common-security.CurrentUser` accessor (kills the future diverged-`RequestUtil` problem). **Template (this
  step): Product** end-to-end — entity gains `organizationId`/`userId`/`userType`; `ProductRepository` every
  read scoped (`org OR (org IS NULL AND user)`); `ProductService` reads scoped, writes stamp tenant, `getEntity`
  scoped (anti-IDOR); kept unscoped `findLowStockProducts`/`findOutOfStockProducts` for the `@Scheduled`
  cross-tenant `AlertService`. Flyway `V2__org_scoping.sql` (idempotent guarded adds for products/categories/
  suppliers/warehouses/stock_entries). **Rollout DONE:** Category, Supplier, Warehouse, StockEntry all
  org-scoped (entity + repo `findScoped`/`findByIdScoped` + service scoped reads, stamped writes, anti-IDOR
  `getEntity`); `StockService` writes (addStock/adjust/transfer) now resolve Product/Warehouse via
  `findByIdScoped` (anti-IDOR) and stamp StockEntry; `getSummary` uses `countScoped`/`findLowStockScoped`/
  `findOutOfStockScoped`/`findAllScoped` (leak closed). `@Scheduled` `AlertService` keeps the unscoped
  cross-tenant `findLowStock/OutOfStockProducts`. **Deferred follow-up (audit records, lower risk):**
  `StockAdjustment`/`StockTransfer`/`StockAlert` entities don't yet carry `organization_id` (the Product they
  touch is already scoped, so no cross-tenant mutation; only the movement-log rows lack a tenant tag). —
  **awaiting build** (`mvn -pl inventory-service -am clean install -DskipTests`).
- [ ] **Phase 5 — `catalog-service` (DESIGN DONE; see section below).** Split decided. Field-ownership split:
  catalog owns descriptive Product/Category; inventory keeps quantity state in a new `StockLevel`. Sub-steps:
  5a scaffold catalog-service (Product/Category moved + Phase-4.5 scoping) → 5b inventory `StockLevel` +
  `StockEntry.productId` + StockService rewrite + drop inventory Product/Category → 5c wire `CatalogClient`
  for display names → 5d gateway route + start-all + retire inventory `ProductController`. Build checkpoint each.
  - [x] **5a DONE (awaiting build).** New `catalog-service` (port 8092, DB `myplusdb_catalog`, no Flyway —
    fresh DB via ddl-auto): `Product` (descriptive only — quantity fields omitted), `Category`, scoped
    repos/services/controllers at `/api/catalog/**`, DTOs, SecurityConfig, `catalog-service.yml`, parent-pom
    module. Org-scoping (CurrentUser + findScoped + stamped + anti-IDOR) carried over. SKU index made
    **non-unique** (per-org uniqueness via `existsBySkuScoped`) — fixes inventory's global-unique-SKU
    multi-tenant bug. Additive: inventory still has its own Product, both build. ✓ **BUILD SUCCESS**. **DONE.**
  - [x] **5b DONE (awaiting build).** inventory stops owning the product master. New `StockLevel` (per-org,
    per-product: currentStock/min/max/reorderPoint/costPrice) + `StockLevelRepository` (scoped). `StockEntry`/
    `StockAdjustment`/`StockTransfer`/`StockAlert` `product` FK → `productId` (Long). `StockService` rewritten
    onto `StockLevel` (add/adjust upsert the level; transfer unchanged; getCurrentStock/getHistory/getSummary
    scoped). `AlertService` (@Scheduled, cross-tenant) scans `StockLevel.findLowStock()`; alert message uses
    productId (name lives in catalog). **Deleted** inventory `Product`/`Category` + their controllers/services/
    repos/DTOs (catalog owns them). V2 migration trimmed (products/categories removed; stock_levels via
    ddl-auto). `/api/inventory/products` retired → use `/api/catalog/products`. **Follow-ups:** (i) `StockLevel`
    thresholds (min/max/reorder/costPrice) have no setter endpoint yet → low-stock alerts dormant until set
    (add a StockLevel admin endpoint); (ii) product-existence validation against catalog comes in 5c
    (CatalogClient). ✓ **BUILD SUCCESS**. **DONE.**
  - [x] **5c DONE (awaiting build).** Wired the `CatalogClient` proxy: `CatalogClientConfig` builds it from a
    `@LoadBalanced RestClient` (base `lb://catalog-service`) + an interceptor that re-propagates the gateway
    identity headers (X-User-Id/X-Org-Id/X-Internal-Secret/…) onto the outbound call (service-to-service
    bypasses the gateway, so catalog's HeaderAuthFilter needs them to authenticate+scope). `StockService.addStock`
    now calls `assertProductExists(productId)` — a catalog 404 → `ValidationException`; other failures (catalog
    down) propagate, unmasked. Couples addStock to catalog uptime (the accepted cost of the split).
    (`mvn -pl inventory-service -am clean install -DskipTests`) ✓ **BUILD SUCCESS**. **DONE.**
  - [x] **5d DONE.** Runtime wiring: gateway route `/api/catalog/**` → `lb://catalog-service` (CircuitBreaker
    `catalog-service` + JwtAuthenticationFilter + StripPrefix=2); `start-all.ps1` + `stop-all.ps1` entry
    `catalog-service = 8092` (started before inventory so product validation works); `docker-compose.yml`
    `catalog-service` (DB `myplusdb_catalog`). **Runtime reminder:** rebuild+restart `config-server` (it serves
    the new `catalog-service.yml`) before starting catalog. **Phase 5 COMPLETE — catalog/inventory split landed.**
- [~] **Phase 6 — `trade-service` + saga.** See "Phase 6 design" section. Sub-phased.
  - [x] **6a DONE (awaiting build+test).** inventory reservation API: `Reservation`/`ReservationPick` entities,
    `StockEntry.reservedQuantity` (held qty; available = quantity − reserved), `ReservationRepository`,
    `StockEntryRepository.findForFefo` (earliest-expiry-first, null-expiry last), `ReservationService`
    (two-pass FEFO so OUT_OF_STOCK holds nothing; reserve idempotent on key; confirm decrements StockEntry +
    StockLevel; release returns hold; both idempotent), `ReservationController` at `/api/inventory/reservations`
    returning the raw `StockReservationResponse` (matches the Phase-3 `InventoryClient` contract). org/user are
    method params (controller reads CurrentUser) → unit-testable. Also fixed a latent 5c bug: `CatalogClient`
    base URL must be `http://catalog-service/api/catalog` (was missing the prefix → every product lookup 404'd).
    Test: `ReservationServiceTest` (Testcontainers) — FEFO+confirm decrement, release, OUT_OF_STOCK, idempotency.
  - [x] **6b DONE (awaiting build).** Wired the saga clients into business-service: extracted the gateway
    identity-forwarding interceptor to **`common-security.GatewayIdentityForwarding`** (DRY — inventory's
    `CatalogClientConfig` now reuses it too); `TradeClientsConfig` builds load-balanced `@HttpExchange` proxies
    for `InventoryClient` (`lb://inventory-service/api/inventory`) and `CatalogClient`
    (`lb://catalog-service/api/catalog`), each over a cloned RestClient + the shared interceptor;
    `TradeSagaProperties` (`trade.saga.enabled`, default **false**) scaffolds the strangler flag. Fully
    additive — no sell behavior changes (clients lazy, flag off). Tests deferred to 6c (the saga path is what
    needs an integration test; wiring config alone doesn't). (`mvn -pl business-service,inventory-service -am clean install -DskipTests`)
  - [ ] 6c saga+outbox (flagged path) · 6d cut over + delete local Stock + rename.
- [ ] **Phase 7 — rebase `pharma-service`.** Keep only Medicine clinical + Prescription/Dispensing; delete its
  `PharmacyStock`/supplier duplicates; compose catalog+inventory+trade.
- [ ] **Phase 8 — `notification-service`.** Single email/SMS/push service; migrate auth/education/campaign
  senders to call it; delete duplicate `EmailService`s.
- [ ] **Phase 9 — auth consolidation.** Move 2FA out of monolith `com.security.google2fa.*`; extract
  `common-captcha`; every dashboard calls `auth-service` for signup.

## Phase 4 decision — stock system-of-record (field-level)

**Winner: `inventory-service.StockEntry`** — it is the only one of the three with the right *structure*:
real multi-batch (one row per batch, not one-row-per-item like `business.Stock`), a `Product` FK, plus
`warehouse` and `lotNo`. `pharma.PharmacyStock` is structurally inferior (supplier as a free-text String)
and adds nothing reusable → delete in Phase 7.

But StockEntry is the best *shape*, not yet feature-complete. Gaps it MUST absorb before it can replace
`business.Stock` (these are why we don't just delete business.Stock today):

| Capability | `business.Stock` | `inventory.StockEntry` | Action for Phase 5/6 |
|---|---|---|---|
| Multi-batch grain | ❌ one row/item (`item_id` unique) | ✅ one row/batch | keep StockEntry's grain |
| Product link | item id (loose) | ✅ `Product` FK | keep |
| Warehouse / lot | ❌ | ✅ | keep (bonus) |
| Batch no / expiry | ✅ `batchNo`,`bexpDate` | ✅ `batchNo`,`expiryDate` | keep |
| **Mfg date** | ✅ `bmfgDate` | ❌ | **add** `mfgDate` (pharmacy needs it) |
| Purchase price | ✅ `bpurchaseRate` | ✅ `purchasePrice` | keep |
| **Sale rate** | ✅ `bsellRate` | ❌ | **add** `saleRate` |
| **Per-batch discounts (+types)** | ✅ 4 fields | ❌ | **add** (or move to pricing later) |
| **Org-scoping (multi-tenant)** | ✅ `organizationId`,`userId`,`userType` | ❌ **none** | **add** `organizationId`+audit; apply `TenantSpecifications` (Phase 2) |

> ⚠️ Key risk surfaced: **inventory-service is NOT multi-tenant today** — neither `StockEntry` nor `Product`
> carries `organization_id`. Promoting it to system-of-record REQUIRES bringing it to the
> [`ARCHITECTURE-MULTITENANCY`](../ARCHITECTURE-MULTITENANCY.md) standard first (org_id + findScoped). This is
> folded into Phase 5 (catalog) and the inventory hardening that precedes the Phase 6 saga — it is not a
> trivial "flip a switch." Migration adds nullable `organization_id` (ddl-auto/Flyway), no destructive change.

**Freeze (not yet delete):** `business.Stock` and `pharma.PharmacyStock` stay until trade (Phase 6) and
pharma (Phase 7) read inventory; deleting earlier would break the running services.

## Phase 5 design — catalog/inventory split (DECIDED: split, per user)

Splitting Product out of inventory only works if we split it along the **bounded context**, not down the
middle of one entity. Today `Product` mixes *descriptive* attributes with *stock-quantity* state — and
`StockService` mutates the quantity fields. Naively moving `Product` whole to catalog would force inventory
to update catalog over HTTP on every stock change (chatty, breaks the stock transaction). So:

**Field ownership split:**

| Field | Owner | Why |
|---|---|---|
| sku, name, description, categoryId, unit, sellingPrice, taxRate, imageUrl, isActive | **catalog-service** `Product` | "what is this product" — descriptive master data |
| currentStock, minStockLevel, maxStockLevel, reorderPoint, costPrice | **inventory-service** `StockLevel` (NEW, per productId) | "how much do we have + thresholds + valuation" — stock state |
| batchNo, lotNo, expiryDate, quantity, warehouse, supplierId | **inventory-service** `StockEntry` | per-batch movements (unchanged) |

**Consequence — the split is cleaner than it first looks:** inventory's low-stock / out-of-stock /
summary / value calcs run entirely on its own `StockLevel` (no catalog call for quantities). Catalog is
only called to resolve **display info** (name/sku) when composing a DTO → via `CatalogClient` (Phase 3),
base `lb://catalog-service`. `StockEntry.product` (JPA FK) becomes `productId` (Long reference).

**Mechanics:**
- New module `catalog-service` (port 8092, DB `myplusdb_catalog`, gateway route `/api/catalog/**` +
  StripPrefix=2, start-all entry). Carries `Product`+`Category` (entity/repo/service/controller/DTO) **with
  the org-scoping from Phase 4.5 preserved** (CurrentUser + findScoped + stamped writes + anti-IDOR).
- inventory-service: introduce `StockLevel{productId,currentStock,minStockLevel,maxStockLevel,reorderPoint,
  costPrice,+org/audit}`; `StockEntry.product`→`productId`; rewrite `StockService`/`ProductRepository` low-
  stock/summary onto `StockLevel`; **remove** inventory's `Product`/`Category` (now catalog's). Inventory's
  `ProductController` (`/api/inventory/products`) is retired in favour of catalog's `/api/catalog/products`.
- `commerce-contracts.CatalogClient.getProduct` already matches; add a batch `getProducts(ids)` if N+1 shows.
- Migration: catalog gets `products`/`categories` (fresh), inventory gains `stock_levels` and drops the
  product/category tables. **Dev = fresh DBs, trivial. Prod = a real data move** (copy product rows to
  catalog, derive stock_levels) — flagged as a prod-cutover task, not auto-applied.

> This is the heaviest structural phase. Recommend implementing in sub-steps with a build checkpoint each:
> 5a scaffold catalog-service (Product/Category moved + scoped) → 5b inventory StockLevel + productId
> reference + StockService rewrite → 5c wire CatalogClient where display names are needed → 5d gateway/
> start-all/route + retire inventory ProductController.

## Phase 6 design — trade-service + sell↔stock saga (GROUNDED in the real code)

**Reality check (from reading `SellService.addSell`):** business-service owns its **own local `Stock` table**;
`addSell` calls `stockService.updateStock(dto)` (local) then saves `Sell` + `CustomerHistory` — one local
`@Transactional` in `myplusdb`. Sell rate/discount/batch all come from the **local** `Stock`.
business-service's stock is **completely disconnected** from inventory-service's `StockLevel`/`StockEntry`.
Purchase also stocks-in to the local `Stock`. So Phase 6 is not "add a saga to a remote call" — it is
**rewiring trade's stock from local to inventory-as-system-of-record**, for both sell (stock-out) and
eventually purchase (stock-in). This is the largest, highest-risk phase — hence sub-phased with checkpoints.

**Decisions — SETTLED (user, 2026-06-20):**
- **D1 = catalog `sellingPrice`.** Sell at catalog's list price (discount applied at sale); cost/margin from
  the inventory FEFO batch `purchasePrice` in the reserve picks. catalog = price, inventory = cost.
- **D2 = strangler behind a feature flag.** Keep business's local `Stock` working; add the saga sell-path
  alongside behind a flag (e.g. `trade.saga.enabled`, default off); verify against real inventory; then cut over.
- **D3 = defer purchase → NOW DONE (awaiting build).** `PurchaseService.addPurchase` dual-writes the
  purchased quantity into inventory when `trade.saga.enabled` (`pushPurchaseToInventory`: translate
  itemId→productId, `InventoryClient.importStock` an opening StockEntry; best-effort so a purchase never
  fails on inventory error; unmapped items skipped). Keeps local Stock too (legacy path during strangler).
  So inventory accumulates stock for all items over time — the prerequisite for saga-as-default and the
  U4.3+U5 picker cutover. Mockito `PurchaseStockInTest` (pushes when on / no-op off / no-op unmapped).
  (Items that never get purchased stay OUT_OF_STOCK on the saga — correct; there is nothing to bulk-seed for
  the 118 stockless items, they gain stock as purchased.)

**D4 (the big one) — item↔product identity: SETTLED = UNIFY ON CATALOG NOW (user, 2026-06-20).**
Discovered while grounding 6c: a sell line uses business-service's **local `Item` id**; local `Stock` is keyed
by `itemId`. business `Item` is a *separate product master* from catalog `Product` — different id spaces, no
mapping. The saga can't decrement inventory stock without a catalog `productId`. User chose to **unify**:
business `Item` is retired; catalog `Product` becomes the single master; sells reference catalog `productId`.
This re-scopes Phase 6 into a unification program (touches data + the monolith sell UI), sequenced below.

**Unification sequence (re-scopes 6c; each a checkpoint):**
- **U1 — catalog field parity (additive, low-risk): DONE (awaiting build).** catalog `Product` gained
  `manufacturer` (entity + DTO + toDto/fromDto), parity with business `Item.company`; `icode`→`sku`,
  `iname`→`name`, `idesc`→`description`, `unit` already align; `category` string resolves to a `Category`
  during U2; batch/stock/venderId are inventory/purchasing, not catalog. Nullable; ddl-auto adds the column
  (catalog has no Flyway). No behavior change. (`mvn -pl catalog-service -am clean install -DskipTests`)
- **U2 — item→product migration:** one-time per-org ETL copying business `Item` rows into catalog `Product`,
  recording an `itemId → productId` map for the transition window. **Design (decisions baked, industry-standard):**
  - **Through the API, not raw SQL.** catalog-service gains a bulk endpoint `POST /api/catalog/products/import`
    (role-gated) taking `List<ProductImportDTO>` (each carries `clientRef` = source itemId) and returning
    `List<{clientRef, productId}>`. Enforces catalog's scoping, per-org `sku` uniqueness, and `Category`
    find-or-create — no bypassing invariants. Transactional batch.
  - **Trigger = admin endpoint** `POST /api/business/admin/migrate-catalog` (SUPER/ADMIN, per-org or all),
    not a CommandLineRunner — controlled, observable, returns counts. **Idempotent / re-runnable.**
  - **Map storage = new `ItemCatalogMap`** entity in the business DB (`itemId, productId, organizationId`);
    re-runs skip already-mapped items. Survives until U5 (then dropped with `Item`).
  - **Field rules:** `sku` ← `icode`, fallback `ITEM-<itemId>` when blank, dedup within org by suffix;
    `name`←`iname`; `description`←`idesc`; `unit`←`unit`; `manufacturer`←`Item.company.name`;
    `category` (String) → catalog `Category` find-or-create by name per org.
  - **Stock NOT migrated here (recommended):** U2 is items→products only. A paired **U2b** migrates each
    Item's local `Stock` → inventory (`StockLevel` + an opening `StockEntry`) so migrated products are
    sellable — kept separate to bound risk and because it crosses into inventory. (U3 verification can also
    seed stock directly.) **Tests:** catalog import endpoint (Testcontainers: dedup, category resolve, idempotency);
    business migration service (Mockito on CatalogClient + map persistence).
  - **U2b DONE (awaiting build).** commerce-contracts `StockImportLine` + `InventoryClient.importStock`.
    inventory: `StockImportService` (upsert StockLevel currentStock+=qty/costPrice, create opening StockEntry),
    `POST /api/inventory/stock/import` (raw Integer for the client) + Testcontainers test. business:
    `ItemCatalogMap.stockMigrated` flag + `findByOrganizationIdAndStockMigratedFalse`, `StockMigrationService`
    (reads local Stock by itemId, builds lines, calls inventory, marks migrated — idempotent), admin `POST
    /api/business/admin/migrate-stock` (`@PreAuthorize ADD_ITEM`); pure-Mockito always-run test.
    (`mvn -pl inventory-service,business-service -am clean install -DskipTests`, then `… -am test`)
  - **U2 DONE (awaiting build).** Cross-service import DTOs put in **commerce-contracts** (`ProductImportLine`,
    `ProductImportResult`) so both sides share them; `CatalogClient.importProducts` added to the contract.
    Catalog side: `ProductRepository.findBySkuScoped`, `CategoryRepository.findByNameScoped`,
    `ProductImportService` (idempotent sku-reuse, blank→`ITEM-<ref>`, batch+tenant dedup, category
    find-or-create), `POST /api/catalog/products/import`; test `ProductImportServiceTest` (Testcontainers).
    Business side: `ItemCatalogMap` entity + repo, `CatalogMigrationService` (per-org, skips already-mapped =
    idempotent, maps Item→ProductImportLine incl. company→manufacturer/category), admin `POST
    /api/business/admin/migrate-catalog` (`@PreAuthorize ADD_ITEM`); pure-Mockito `CatalogMigrationServiceTest`
    (always-run). `item_catalog_map` table via ddl-auto. (`mvn -pl catalog-service,business-service -am clean install -DskipTests`, then `mvn -pl catalog-service,business-service -am test`)
- **U3 — sell flow on productId:** SellDTO carries catalog `productId`; the flagged saga path resolves price
  from catalog (D1), reserves inventory by `productId`, writes Sell/Invoice PENDING + outbox, confirms; release
  compensation; idempotency. (Inventory stock seeded directly for now — purchase migration still deferred, D3.)
- **U4 — monolith sell UI:** item picker reads catalog (`/api/catalog/products`), submits `productId`.
  - [x] **U4.1 stock display DONE (awaiting build) — VERIFIED LIVE saga works (2026-06-21):** end-to-end live
    test passed — sold item→product 12, inventory `stock_levels` decremented 132→130, saga reservation
    CONFIRMED. The UI showing stale 132 was the expected gap (it read local `Stock`). Fix: `InventoryClient.
    getStockLevel(productId)` + inventory raw `GET /api/inventory/stock/level/{productId}`; business
    `getStock` now overrides the displayed quantity with inventory's on-hand when `trade.saga.enabled`
    (translate itemId→productId via ItemCatalogMap; best-effort with local fallback so the screen never
    breaks). Live notes: business-service DB = **myplusdb**; org=16; only 9 of 127 items had local Stock to
    seed; stale `product_id→products` FKs in myplusdb_inventory dropped (V3 migration added).
  - [x] **U4.2 price from catalog DONE (awaiting build).** business `getStock` also overrides the sell rate
    with catalog `sellingPrice` when `trade.saga.enabled` (same best-effort/fallback block as U4.1, via
    `CatalogClient.getProduct`). So the sell screen's "Stock In Hand" = inventory on-hand and "Sell Amount" =
    catalog price, both data-driven (no businessDashboard.html template edit needed). **Cypress:**
    `cypress/e2e/business/saga-sell.cy.js` — request-based: scans for a stocked+migrated item, sells it,
    asserts on-hand drops by 1 (run headed with the saga flag on).
  - [ ] **U4.3** item picker reads catalog + submits productId (then drop the itemId translation) — the
    bigger picker rewrite; do with Cypress once U4.1/U4.2 verified.
- **U5 — cutover:** flip `trade.saga.enabled`, delete business `Item`+`Stock`, rename business→trade.

Recommended first step: **U1** (additive catalog parity) — 100%-confident, no behavior change, unblocks U2.
U2–U5 each get their own design/checkpoint; U3 carries the saga integration test.

**Refinement these imply:** under strangler (D2) we do NOT stand up a parallel `trade-service` module now
(that would duplicate a large service). Instead the saga sell-path is added **inside business-service behind
the flag**; the rename business-service → `trade-service` is a cosmetic cutover step (6d), not a fork. So:
- **6b** = wire `InventoryClient` + `CatalogClient` into business-service (RestClient @HttpExchange proxies,
  like inventory's `CatalogClientConfig`). Additive, low-risk.
- **6c** = new flagged sell path: resolve price from catalog (D1), `reserve`→write Sell/Invoice PENDING +
  outbox→`confirm`; compensation `release` on failure; idempotency. The hard part.
- **6d** = cutover: flip flag on, delete local `Stock`, rename business→trade. Purchase deferred (D3).

**Saga (reserve → confirm + outbox), unchanged in principle:**
1. trade orchestrates: `InventoryClient.reserve(idempotencyKey, lines)` → FEFO picks or OUT_OF_STOCK.
2. trade writes `Sell`+`Invoice` (PENDING) **and** an outbox row in the same local tx.
3. trade calls `confirm(reservationId)` → inventory decrements; trade marks invoice CONFIRMED.
4. failure after step 2 → `release(reservationId)` compensation. Idempotency via reservationId/key.

**To build on the inventory side (does not exist yet):** a `Reservation` aggregate + the
`/reservations`, `/reservations/{id}/confirm`, `/reservations/{id}/release` endpoints (matching the Phase-3
`InventoryClient` contract), with **FEFO allocation over `StockEntry`** and idempotency on the key.

**Proposed sub-phases (build+test checkpoint each):**
- **6a — inventory reservation API** (isolated, testable alone): `Reservation`/`ReservationLine` entities,
  FEFO over `StockEntry`, reserve/confirm/release endpoints, idempotency, org-scoped. Unit + Testcontainers
  tests for reserve→confirm→stock-decrement and reserve→release→no-decrement and OUT_OF_STOCK. **I am
  confident to implement this one in isolation.**
- **6b — trade-service shell**: rename/derive trade-service from business-service (mechanical), keep behavior.
- **6c — saga orchestration + outbox** in trade: new sell path calls the reservation API; outbox + relay;
  compensation; behind a switch (D2 strangler).
- **6d — cut over + delete local Stock** once verified; (later) purchase→inventory stock-in.
- Integration test: reserve→confirm happy path + compensation on injected failure (Testcontainers).

> Recommendation: settle D1–D3, then implement **6a only** (isolated, low-risk, fully testable), checkpoint,
> and reassess before touching the trade sell path. This keeps each step inside a 100%-confidence boundary.

## U3 design — flagged saga sell path (the distributed transaction)

**Current flow:** `SellController.addSell(CustomerHistoryDTO)` (one local `@Transactional`) saves Customer +
CustomerHistory (invoice, per-org invoiceNo) + the `Sell` lines; each `Sell` decrements local `Stock` and
takes `sellRate` from `Stock`. **Saga path (when `trade.saga.enabled`):** replace local stock with the
inventory reservation saga + catalog price. Existing path stays as the default (strangler).

**Orchestration (a new `SagaSellService`; the controller branches on the flag):**
1. **Translate** each line's `itemId` → `productId` via `ItemCatalogMap` (fail clearly if unmapped).
2. **Price** from catalog: `CatalogClient.getProduct(productId).sellingPrice` (D1), apply discount.
3. **Reserve**: `InventoryClient.reserve(idempotencyKey, lines[productId, qty])`. OUT_OF_STOCK → reject the sale.
4. **Write** (local `@Transactional`): Customer + CustomerHistory + Sell lines, invoice **saga-state = PENDING**,
   carrying `reservationId` + `idempotencyKey`.
5. **Confirm**: `InventoryClient.confirm(reservationId)` → inventory decrements; mark invoice **CONFIRMED**.
6. **Compensate**: any failure after step 4 → `InventoryClient.release(reservationId)`; invoice stays PENDING/marked FAILED.

**Model changes:** `Sell` gains `productId` (nullable; set for saga sells, `stock` FK null then);
`CustomerHistory` gains `reservationId` + `sagaStatus` (PENDING/CONFIRMED/FAILED) + `idempotencyKey`.

**Reliability — UD1 SETTLED = (A) invoice-as-saga-state + scheduled relay** (assistant's call per the
"make industry-standard decisions" steer; right fit for a single downstream confirm). The confirm step can be
lost if trade crashes between 4 and 5; the two options were:
- **(A) Invoice-as-saga-state + scheduled relay (recommended):** the invoice already carries
  `reservationId` + PENDING; a `@Scheduled` relay re-drives `confirm` for stuck-PENDING invoices. Simpler,
  no new table, sufficient for a single downstream call. State machine + polling.
- **(B) Transactional outbox table:** a `SaleOutbox` row (invoiceId, reservationId, status) written in the
  same tx, a relay publishes/【confirms】. More standard when fanning out events to multiple consumers — which
  we don't have here. More moving parts.

**Idempotency:** one `idempotencyKey` (UUID) per sale, stored on the invoice → reserve/confirm/release are
all idempotent on it / reservationId, so the relay and retries are safe.

**Sub-steps:**
- [x] **U3a DONE (awaiting build).** Additive model fields: `Sell.productId` (nullable; saga sells use it,
  local Stock FK null then); `CustomerHistory.reservationId` + `idempotencyKey` + `sagaStatus`
  (PENDING|CONFIRMED|FAILED). Nullable, ddl-auto adds columns, no behavior change (legacy flow ignores them).
  (`mvn -pl business-service -am clean install -DskipTests`)
- [~] **U3b** SagaSellService reserve→write→confirm + compensation. Flag branch goes at the top of
  `SellController.addSell` (delegates to SagaSellService when `trade.saga.enabled`, else existing path).
  - [x] **U3b-1 DONE (awaiting build).** Pricing prerequisites + a real latent-bug fix: `ProductRef` gained
    `sellingPrice`/`taxRate`; catalog `GET /api/catalog/products/{id}/ref` returns a **raw** `ProductRef`
    (+price); `CatalogClient.getProduct` re-pointed to `/ref` (it previously hit the ApiResponse-wrapped
    `/products/{id}` → would never have deserialized a price; this also makes inventory's `assertProductExists`
    hit a correct raw endpoint). `ItemCatalogMapRepo.findProductIdByItemId` for the itemId→productId translation.
    (`mvn -pl catalog-service,inventory-service,business-service -am clean install -DskipTests`)
  - [x] **U3b-2 DONE (awaiting build).** `SagaSaleWriter` (`REQUIRES_NEW` so the PENDING sale commits BEFORE
    `confirm`, independent of the controller's legacy `@Transactional`; wraps `saveUpdateCustomer`'s checked
    exception). `SagaSellService` orchestrates translate→price(catalog)→`reserve`→writePending→`confirm`→mark
    CONFIRMED; **failure semantics:** write-failure → `release` + abort; confirm-failure → leave invoice
    PENDING for the U3c relay (no release, hold retained — idempotent re-confirm). `SagaLine` record.
    `SellController.addSell` branches to the saga when `trade.saga.enabled` (else existing path, default).
    Mockito `SagaSellServiceTest`: happy path, OUT_OF_STOCK reject (no write), confirm-fail leaves PENDING.
    (`mvn -pl catalog-service,inventory-service,business-service -am clean install -DskipTests`, then `… -am test`)
  - [x] **U3c DONE (awaiting build).** `SagaRecoveryRelay` (`@Scheduled`, gated on the flag) re-drives
    `confirm` for `CustomerHistoryRepo.findPendingSagaSales()` (PENDING + reservationId), marking CONFIRMED;
    idempotent confirm makes retries safe. **Background-job identity:** extended `common-security.
    GatewayIdentityForwarding` with `runAs(userId, orgId, action)` (ThreadLocal override) so the relay —
    which has no inbound request — calls inventory as each invoice's tenant. Added `@EnableScheduling` to the
    app. Mockito `SagaRecoveryRelayTest` (re-confirms when enabled; no-op when disabled). **U3 COMPLETE** —
    saga sell path + recovery relay done. (`mvn -pl business-service -am clean install -DskipTests`, then `… -am test`)
    Refinement noted: a max-age/attempt cap to mark hopeless PENDING as FAILED (today they retry indefinitely).
- [ ] **U3c** the scheduled recovery relay (re-drive stuck-PENDING invoices to CONFIRMED).
**Tests:** SagaSellService happy path + OUT_OF_STOCK reject + confirm-fails→release compensation (Mockito on
the clients; Testcontainers for the invoice state transitions).

## Test
**Standard (per user): every phase ships tests that run on `mvn test`.** Pure-logic = always-run unit tests
(no Docker); repo/scoping/integration = `@DataJpaTest` + Testcontainers MySQL (`disabledWithoutDocker=true`,
skips cleanly without Docker, runs in CI `microservice-tests.yml`). Added so far:
- `commerce-domain`: `InvoiceNumbersTest`, `MoneyTest` — **always run**, no Docker (prove the shared primitives).
- `catalog-service`: `ProductRepoScopingTest` — findScoped tenant isolation + per-org `existsBySkuScoped` (5a).
- `inventory-service`: `StockLevelRepoScopingTest` — StockLevel findScoped + findByProductScoped isolation (5b).
- TODO next phases: StockService/CatalogClient service test (Mockito); the Phase 6 saga reserve→confirm +
  compensation integration test.
- Per phase: build the touched modules (user) + existing Cypress suites for affected domains (headed, Chrome).
- Saga (Phase 6): integration test reserve→confirm happy path + compensation on injected failure
  (Testcontainers, per slice 29 pattern).
- No phase merges until its build is green and prior domain specs still pass.
```
