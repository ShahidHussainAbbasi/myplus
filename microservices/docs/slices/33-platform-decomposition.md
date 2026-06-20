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
  - [ ] 6b trade-service shell · 6c saga+outbox (needs D1–D3) · 6d cut over + delete local Stock.
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
`@Transactional` in `myplusdb_business`. Sell rate/discount/batch all come from the **local** `Stock`.
business-service's stock is **completely disconnected** from inventory-service's `StockLevel`/`StockEntry`.
Purchase also stocks-in to the local `Stock`. So Phase 6 is not "add a saga to a remote call" — it is
**rewiring trade's stock from local to inventory-as-system-of-record**, for both sell (stock-out) and
eventually purchase (stock-in). This is the largest, highest-risk phase — hence sub-phased with checkpoints.

**Open decisions for review (do NOT implement before these are settled):**
- **D1 — pricing source.** Today sell rate/discount come from local `Stock`. If inventory owns stock, does
  sell price come from catalog (`sellingPrice`) or from an inventory batch rate? (Leaning: catalog
  `sellingPrice` as list price; per-batch cost stays inventory.) Affects the reserve response shape.
- **D2 — strangler vs big-bang.** Keep business's local `Stock` during transition (dual-write/feature-flag,
  then cut over) vs switch sell straight to the saga. (Leaning: strangler — add saga path behind the new
  reservation API, verify, then delete local `Stock`.)
- **D3 — purchase scope.** Migrate purchase→inventory stock-in in this phase or defer? (Leaning: defer to
  6-later; first prove sell↔stock with inventory stock seeded directly, to bound risk.)

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
