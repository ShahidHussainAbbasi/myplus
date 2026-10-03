# Multi-seller Marketplace (MKT): end-to-end analysis and gap register

**Status:** ANALYSIS, shared for review **before** the design gate (SAAS-BUILD-STANDARDS, "The gate is written BEFORE
the implementation": *the standards analysis is shared for review before documenting or designing*). Branch base:
`feature/expense-management` @ `7b87499e`. Analysis date 2026-10-03.

**Input:** *MaxTheService Marketplace — Final Implementation Design* (stored beside this file as
[`marketplace-multiseller-source.docx`](marketplace-multiseller-source.docx), 23 sections). It says
*"Status: Design approved for phased implementation"*. That approves the **business model**. It does not approve a
technical design against this codebase, because it was written without reference to it. This file reconciles the two.

**Companions:** [`marketplace-multiseller-design.md`](marketplace-multiseller-design.md) (programme design + phases),
[`marketplace-multiseller-test-plan.md`](marketplace-multiseller-test-plan.md) (traceability matrix, 100% of
requirements mapped to a test), [`manual-test-marketplace-multiseller.md`](manual-test-marketplace-multiseller.md).

---

## 0. Verdict in five lines

1. **The source document describes a different product from today's `marketplace-service`.** Today: *one tenant = one
   store*. A shopper buys from **one** organisation's storefront (`?org=` on every public read, one org per cart).
   The source: *MaxTheService is the operator*. Many seller organisations publish **offers** against one canonical
   product, and the customer belongs to MaxTheService.
2. **That makes the tenancy boundary the main design decision, ahead of any entity.** Every rule in
   `ARCHITECTURE-MULTITENANCY.md` scopes a read to *one* org. A marketplace catalogue is, by definition, a read across
   orgs. It needs an explicit, narrow, audited **cross-tenant projection**. Loosening `findScoped` is not an option.
3. **Phase 1 of the source maps onto what already exists far better than its wording suggests.** "One seller per
   checkout, merchant stock only, existing merchants" **is** today's storefront checkout: one org per cart, the O1
   sale path, the O5a reservation TTL, O2 idempotency, O3 COD policy. So Phase 1 is a **federation layer**
   (canonical product + offers + an operator catalogue that routes into each seller's existing checkout). It is not
   a second OMS.
4. **Genuinely net-new:** the canonical product + composite identity key, offers with stock-source and party roles,
   cross-seller catalogue and ranking, operator approval, a platform-level customer, commission, an **immutable
   settlement ledger with payouts**, return cost attribution, and (later) parent/child orders, supplier, consignment
   and regulated pharmacy.
5. **Four rulings are needed before the design gate** (§6). The biggest is **R-MKT-1: who is merchant of record**.
   It decides whose books the sale lands in, who collects the money, and therefore which way settlement flows.

---

## 1. Requirement register (every requirement in the source, numbered)

Each requirement gets a stable id `MKT-R<section>.<n>`. The test plan maps **every** id to at least one test. That
mapping is what "100% end to end" means here: no requirement without an automated or manual case, and the coverage
is counted, not asserted. `Ph` = the source's phase (0–6).

### §1 Executive decision · §2 Consignment · §3 Parties

| ID | Requirement | Ph |
|---|---|---|
| MKT-R1.1 | Four stock-source models, MERCHANT · PLATFORM · SUPPLIER · CONSIGNMENT, as strategies of **one** marketplace/OMS, not four marketplaces | 1–5 |
| MKT-R1.2 | Launch order: merchant → platform → supplier → consignment → regulated pharmacy | all |
| MKT-R1.3 | Marketplace layer sits **above** the existing commerce core (products, inventory, POS, customers, suppliers, payments, accounting) and reuses it | all |
| MKT-R2.1 | Consignment: consignor = legal owner, consignee = holder/seller; ownership/payment changes on sale per agreement | 5 |
| MKT-R2.2 | Consignee may be MaxTheService, merchant, platform warehouse or supplier | 5 |
| MKT-R3.1 | Every offer identifies its parties: seller, stock owner, custodian, fulfiller, warranty provider (may all differ) | 1 |
| MKT-R3.2 | Never assume seller = owner = fulfiller = warranty provider | 1 |

### §4 Stock-source models · §5 Offer model

| ID | Requirement | Ph |
|---|---|---|
| MKT-R4.1 | MERCHANT: owner = custodian = fulfiller = merchant | 1 |
| MKT-R4.2 | PLATFORM: owner = MaxTheService, custodian/fulfiller = platform warehouse | 3 |
| MKT-R4.3 | SUPPLIER: owner = custodian = supplier; fulfiller supplier or its carrier | 4 |
| MKT-R4.4 | Supplier stock is never shown as immediately available unless the supplier gives reliable availability and accepts within a defined time | 4 |
| MKT-R4.5 | CONSIGNMENT: owner = consignor, custodian = consignee | 5 |
| MKT-R4.6 | Consignment agreement terms are configurable (ownership-transfer event, count frequency, shrinkage tolerance, damage, expiry, insurance, price, commission, settlement schedule, unsold return, reconciliation) **before** consignment is enabled | 5 |
| MKT-R5.1 | A merchant's raw POS product is **never** published directly; customers see a canonical `MarketplaceProduct` | 1 |
| MKT-R5.2 | `MarketplaceProduct` fields: canonical name, brand, category, variant attributes, GTIN, description, images, regulated status, approval status | 1 |
| MKT-R5.3 | `MarketplaceOffer` fields: product, stock-source type, owner/custodian/seller org ids, source product, seller SKU, price policy, list + marketplace price, stock status, fulfillment type, delivery area, warranty/return/commission policy ids, approval status, published/updated | 1 |
| MKT-R5.4 | Customer sees one product with N offers: "Available from 2 sellers · From Rs. 51,500" | 1 |

### §6 Matching · §7 Price/filters

| ID | Requirement | Ph |
|---|---|---|
| MKT-R6.1 | Never auto-merge products on name alone | 1 |
| MKT-R6.2 | Composite identity key (brand, model, variant, capacity, colour, size, unit, pack size, condition, warranty type, GTIN) | 1 |
| MKT-R6.3 | Medicine key: brand, product name, strength, dosage form, pack size | 6 |
| MKT-R6.4 | Match statuses PENDING_REVIEW · MATCHED · REJECTED · NEEDS_CORRECTION | 1 |
| MKT-R6.5 | An administrator can correct a bad match | 1 |
| MKT-R6.6 | Different storage (A32 64GB vs 128GB) or pack size (10 vs 20 tablets) never merge | 1 |
| MKT-R7.1 | Customer chooses an offer explicitly or via filters (price, nearest, fastest, promotion, rating, warranty, returns); the choice is visible | 1 |
| MKT-R7.2 | Offer display: seller, price, delivery promise, distance, warranty, rating | 1 |
| MKT-R7.3 | The cheapest offer is never forced | 1 |
| MKT-R7.4 | Operator configures allowed pricing policies, commission rules, max discount, promotion approval, price floor, delivery-fee, tax policy, ranking defaults | 1 |
| MKT-R7.5 | Seller configures marketplace price (if permitted), quantity, delivery area, availability, seller-funded promotion, warranty/returns within rules | 1 |
| MKT-R7.6 | Customer filters can **never** bypass approval, stock, seller eligibility, price limits, regulated restrictions | 1 |

### §8 Customer ownership · §9 Data sharing · §10 Reservation

| ID | Requirement | Ph |
|---|---|---|
| MKT-R8.1 | The marketplace customer relationship, support, communication, complaint intake and return coordination belong to MaxTheService | 1 |
| MKT-R8.2 | Customer contacts MaxTheService only; MaxTheService tasks merchant/supplier/carrier/warranty provider internally and relays the resolution | 1 |
| MKT-R9.1 | Data use is a written **data-sharing agreement**, never called "consignment" | 0 |
| MKT-R9.2 | Only the minimum stock-owner data is taken (identity, price, availability, area, time, warranty, returns, seller identity, manufacturer) | 1 |
| MKT-R9.3 | Never requested: supplier cost, internal margin, unrelated customer lists, employee data, full movement history | 1 |
| MKT-R10.1 | Reservation authority by source: merchant inventory · platform warehouse · supplier API/portal · custodian under owner policy | 1/3/4/5 |
| MKT-R10.2 | Nine-step sequence: select → validate offer → ask stock system → validate qty → temporary reservation → record id+expiry → seller accepts → allocated/consumed → release on reject/expire/cancel | 1 |
| MKT-R10.3 | `available = on_hand − reserved − allocated − unavailable`; never `stock > 0` | 1 |
| MKT-R10.4 | Reservation record carries reservation/order/line/offer ids, source type, owner, custodian, location, qty, status, created/expires, release reason, source reference | 1 |
| MKT-R10.5 | Default terms (configurable): merchant 5 min accept / 10 min hold; platform immediate / until pick; supplier 15–20 / 20–30; consignment 5 / 10 | 1 |
| MKT-R10.6 | Terms may vary by product type, order value, location, seller performance, delivery promise, source | 2 |

### §11 Shortage · §12 Shrinkage · §13 Returns

| ID | Requirement | Ph |
|---|---|---|
| MKT-R11.1 | Shortage flow: recheck → reserve → mark offer unavailable → alternates → reprice → **customer approval** if price/product/timing changes → partial / substitute / cancel line / cancel order → refund captured amount → record cause + party → SLA | 2 |
| MKT-R11.2 | Results REASSIGNED · PARTIALLY_FULFILLED · SUBSTITUTION_REQUESTED · LINE_CANCELLED · ORDER_CANCELLED · REFUND_PENDING | 2 |
| MKT-R11.3 | Never substitute strength, size, variant, colour, pack size or brand without customer approval | 2 |
| MKT-R11.4 | Shortage responsibility by cause (merchant stale stock, supplier stale, platform sync defect, custodian count, carrier loss, customer invalid combination) | 2 |
| MKT-R12.1 | Shrinkage types (theft, damage, breakage, spoilage, expiry, miscount, consumption, short shipment, delivery loss) | 5 |
| MKT-R12.2 | Agreement defines responsible party, tolerance %, count frequency, proof, claim window, approval, settlement effect | 5 |
| MKT-R12.3 | Default responsibility table by situation | 5 |
| MKT-R12.4 | Never auto-debit a stakeholder without evidence **and** a dispute process | 2 |
| MKT-R13.1 | MaxTheService coordinates returns; the cost bearer follows the cause (10-row table) | 1 |
| MKT-R13.2 | Return flow: request → policy check → notify fulfiller/owner → pickup → inspect → approve/reject refund → restock/quarantine/write-off → adjust settlement | 1 |
| MKT-R13.3 | **Order-time snapshots**: return/refund policy, cost payer, warranty, seller, owner, fulfiller, price, tax, commission, delivery fee; later policy changes never alter an old order | 1 |
| MKT-R13.4 | Expired/unsafe product → urgent escalation | 1 |

### §14 Warranty · §15–16 Settlement · §17 Multi-seller

| ID | Requirement | Ph |
|---|---|---|
| MKT-R14.1 | Display warranty provider, period, start/end, coverage, exclusions, claim process, service centre, responsibility | 1 |
| MKT-R14.2 | MaxTheService never claims to be the warranty provider unless it is | 1 |
| MKT-R15.1 | T+1 = one **business** day after the settlement trigger (weekends, holidays, holds) | 1 |
| MKT-R15.2 | Never pay the stock owner at order placement; payable = delivered + payment confirmed + no active cancellation + return/dispute conditions met | 1 |
| MKT-R15.3 | Triggers DELIVERED · DELIVERED_PLUS_RETURN_WINDOW · PICKUP_COMPLETED · MANUAL_APPROVAL; low-risk = short window, high-risk = long | 1 |
| MKT-R15.4 | Consignment settles on sell-through → return period → reconciliation | 5 |
| MKT-R15.5 | Settlement reconciles: customer amount = payable + commission + delivery + processing + tax + reserves + adjustments (worked example 5,000 → 4,150) | 1 |
| MKT-R15.6 | **Immutable** settlement ledger; never `wallet.balance += amount` | 1 |
| MKT-R16.1 | Entities SettlementAccount, SettlementLedgerEntry, Payout with the listed fields | 1 |
| MKT-R16.2 | Settlement statuses NOT_ELIGIBLE · PENDING · ELIGIBLE · ON_HOLD · APPROVED · PROCESSING · PAID · REVERSED · DISPUTED | 1 |
| MKT-R16.3 | Every payout: idempotency key, approval rules, audit log, bank/payment reference, reconciliation | 1 |
| MKT-R17.1 | Phase 1: **one seller per checkout** | 1 |
| MKT-R17.2 | Phase 2: parent order + child seller orders with separate fulfilment, promises, settlement and returns; never "just a frontend filter" | 2 |

### §18 Routing · §19 States · §20–22 Phases, entities, security

| ID | Requirement | Ph |
|---|---|---|
| MKT-R18.1 | Never call every stakeholder during checkout; two stages: projection shortlist → live reservation on ≤ 3–5 candidates | 2 |
| MKT-R18.2 | Projection carries offer, product, source, location, area, availability, price, promotion, SLA, rating, last sync, seller online | 1 |
| MKT-R18.3 | Per-stakeholder timeout 300–800 ms, ≤ 3–5 candidates, 2 s overall deadline, circuit breakers (starting points for load test) | 2 |
| MKT-R18.4 | Ranking order: approved/active → zone → projection → reservation → promise → price → promotion → rating → distance → history; customer sort re-orders | 1 |
| MKT-R18.5 | On deadline: never silently confirm; "We are checking availability" / "Please choose another offer" | 1 |
| MKT-R19.1 | **Separate** state machines: marketplace order (12 states), fulfilment order (15), payment (7), settlement (9); never one status column | 1 |
| MKT-R20.0 | Phase 0 foundation: capability/entitlement, legal entity, seller agreement, privacy/data-sharing, customer terms, returns, commission, COD, warranty, complaint process, product approval, tax/regulatory review | 0 |
| MKT-R20.1 | Phase 1 scope: one city, one seller per checkout, merchant stock, low-risk non-regulated, existing merchants, manual delivery assignment, cash + one online payment, manual settlement approval | 1 |
| MKT-R20.2 | Phase 1 excludes: multi-seller cart, dropship, consignment, prescription medicines, automatic payouts, complex dynamic routing | 1 |
| MKT-R20.3 | Phase 2 OMS maturity: parent/child orders, multi-seller cart, automatic rerouting, delivery integration, returns/refunds, merchant performance, COD reconciliation, payout approval, settlement reports | 2 |
| MKT-R20.4 | Phase 3 platform stock: warehouse, central stock, pick/pack, transfer, platform delivery, central returns | 3 |
| MKT-R20.5 | Phase 4 supplier stock: supplier API/portal, acceptance, tracking, SLA, returns, settlement · Phase 5 consignment: agreements, roles, consigned stock ledger, custodian counts, sell-through, shrinkage claims, ownership transfer, unsold return | 4–5 |
| MKT-R20.6 | Phase 6 regulated pharmacy, only after legal review: approved pharmacy sellers, pharmacist verification, prescriptions, restricted products, batch/expiry, recall/quarantine, traceability, safety escalation | 6 |
| MKT-R21.1 | Minimum entity list (22 entities) | 1–5 |
| MKT-R21.2 | `organization_id` on every tenant-owned entity, plus owner/custodian/seller/fulfiller org, branch, warehouse where applicable | 1 |
| MKT-R21.3 | Every cache key, event, file, report and job carries the org scope | 1 |
| MKT-R22.1 | Tenant from authenticated server identity; browser cannot choose the stock owner by editing JSON | 1 |
| MKT-R22.2 | Offer approval, price and stock reservation are **server-authoritative** | 1 |
| MKT-R22.3 | Payment and refund idempotency keys; settlement entries immutable | 1 |
| MKT-R22.4 | Every marketplace and support action audited | 1 |
| MKT-R22.5 | Every external supplier call has timeout + circuit breaker; every webhook/event retryable and idempotent | 4 |
| MKT-R23.1 | The core principle: MaxTheService owns the experience and coordinates; the configured owner/custodian/fulfiller/carrier/warranty provider stay accountable | all |

**Count:** 87 requirement ids. The per-phase split is computed in the test plan's matrix (§3 there), not typed here.

---

## 2. Current state, verified against the code (2026-10-03)

Each row says how it was verified. *Verified* means the file was read in this pass.

### 2a. What exists and is reused

| Capability the source needs | What exists | Evidence (verified) | Reuse verdict |
|---|---|---|---|
| Single-seller checkout, server-priced | Cart → quote → place, one org per cart, totals computed from the server cart, coupon, tax from the books | `CheckoutService.place` `:93–135`: requires `organizationId`, builds lines from the server cart (`toOrderLines(cart)`), "authoritative grand total" | **Reuse as the Phase 1 seller checkout.** MKT-R17.1 is already the shape |
| Sale → books (invoice, GL, tax, AR) | O1: storefront orders go through business-service's one sale path | `oms-program-plan.md` O1 ✅ | Reuse. The seller's sale lands in the seller's books |
| Order lifecycle whitelist | `FulfilmentStatus.ALLOWED`, derived shipping states | `entity/FulfilmentStatus.java` | Reuse as the **child (fulfilment) order** machine; map the source's 15 states onto it (§3 below) |
| Separate payment status | `Order.paymentStatus` PENDING/PAID/FAILED/REFUNDED/PARTIALLY_REFUNDED | `Order.java:147,152` | Partly. It is a `String`, not an enum with a whitelist (gap G-9) |
| Idempotent placement, optimistic lock, per-org order no. | O2 | `Order.java:49` `idempotency_key`, `:57` `@Version`, `:40` `order_no` | Reuse |
| Reservation with expiry | O5a: `expires_at`, sweeper, `EXPIRED` ≠ `RELEASED`, per-tenant `inventory.reservation.holdMinutes` | `inventory-service .../entity/Reservation.java:53`, `InventorySettingsCatalog.java:20` | **Reuse as the MERCHANT reservation authority** (MKT-R10.1) |
| Available = on hand − held | inventory publishes `onHand`, `sellable`, `held`, `expired` | O5a row; `getLevelDetail` | Reuse (MKT-R10.3) |
| Shipments, partial ship, carrier + tracking | O5b | `Shipment`, `ShipmentLine` | Reuse for delivery status |
| Pick/pack | O5d | `PackVerificationTest` | Reuse for "packing status" |
| Cash collection by rider | O7 D5 driver settlement | `DriverSettlement`, `V22` | Reuse for COD reconciliation (Phase 2) |
| Refund + return | slices 70/71, O4 made them reachable | `OrderService.refund :1357`, `processReturn :1415` | Reuse; add cost attribution (gap G-12) |
| COD policy, delivery fees | O3 `ShippingPolicy` | `ShippingPolicy.java` | Reuse per seller; operator overlay in Phase 2 |
| Per-org settings + owner screen | `common-settings` | `SettingsCatalogProvider` | Reuse for every configurable term (MKT-R10.5/6) |
| Capability on/off, opt-in modules, entitlements | C1 + EX-0a opt-in + operator entitlements | `Capability.java` (`optIn`), `EntitlementAdminController`, `cy.setEntitlement` | **Reuse for MKT-R20.0** "marketplace capability/entitlement" |
| Platform operator identity | `admin@myplus.com`, `ROLE_ADMIN`, operator console E1–E3 | `manual-test-platform-operator.md` | Reuse as the marketplace operator (approvals, matching, payouts) |
| Product flags for regulated goods | `rxRequired`, `controlledSubstance` on catalog `Product` | `Product.java:217,221` | Reuse to enforce MKT-R20.2 "no prescription medicines in Phase 1" |
| Barcode / GTIN, manufacturer, pack size | `barcode`, `ProductBarcode`, `manufacturer` (brand), `packSize` | `Product.java:28,53,166` | Reuse as identity-key inputs |
| Outbox, audit, notify | `common-outbox`, `common-audit` + `audit-service`, `common-notify` | module list | Reuse (MKT-R22.4) |
| Ledger, AP, payables | finance-service GL, AP subledger, payables, period lock | `PayableController`, `finance-payables-subledger-design.md` | **Candidate home for the settlement ledger** (ruling R-MKT-3) |
| Party master | `party-service`, marketplace shopper bridged | `PartyBridgeService` | Reuse for a platform customer's identity |

### 2b. Gaps (net-new or below the source's standard)

| # | Gap | Evidence | Source req | Severity |
|---|---|---|---|---|
| G-1 | **No cross-tenant catalogue.** Every public read is `?org=<one store>`; there is no read that lists the same product across sellers | `PublicProductController :27–33` | R5.4, R7.1, R18.2 | Foundational |
| G-2 | **No canonical product / offer split.** A `Product` is per-org (`organizationId :258`); two merchants selling the same phone are two unrelated rows | `Product.java` | R5.1–5.3 | Foundational |
| G-3 | **No identity key / match review.** `barcode` exists, but there is no brand/model/variant/colour/condition structure and no match status | grep `variant` in catalog-service: no variant field (only `manufacturer`) | R6.1–6.6 | High |
| G-4 | **Customer is per store.** `storefront_customer` is unique on `(organization_id, email)`, so a shopper is a different customer at every seller | `StorefrontCustomer.java:13–14` | R8.1–8.2 | High: contradicts "MaxTheService owns the customer" |
| G-5 | **No party roles on an order.** One `organizationId` per order; no owner/custodian/fulfiller/warranty-provider snapshot | `Order.java:23` | R3.1, R13.3 | High |
| G-6 | **No commission, no seller settlement, no payouts.** grep `commission` over all services: one unrelated hit in `CustomerController` | grep | R15.*, R16.* | High (money) |
| G-7 | **No parent/child order.** | `Order` has no parent id | R17.2 | Phase 2 |
| G-8 | **No stock location.** INV-L: no `locationId` on stock or reservation | `oms-program-plan.md` INV-L | R10.4, R18.2 (distance) | Phase 2 dependency |
| G-9 | **Payment status is an untyped String** with no transition whitelist, unlike fulfilment | `Order.java:147` | R19.1 | Medium |
| G-10 | **No marketplace order state above the store order.** The 12-state marketplace order and the 9-state settlement do not exist | — | R19.1 | High |
| G-11 | **No offer projection / routing.** Nothing ranks sellers; nothing shortlists | — | R18.* | Phase 1 (simple), Phase 2 (live) |
| G-12 | **Return cost attribution absent.** Returns restock and refund; nobody records who bears the cost | `processReturn :1415` | R13.1 | Medium |
| G-13 | **Warranty is not modelled.** | grep `warranty` | R14.* | Medium |
| G-14 | **No marketplace capability.** Nothing to switch the module on per tenant or to entitle a seller | `Capability.java` (no marketplace value) | R20.0 | Phase 0 |
| G-15 | **Redis is not a platform dependency.** The source assumes "Redis/search projections". Caching standard K7: in-process Caffeine at one replica; K5: **stock on hand is never cached without the user's ruling** | SAAS-BUILD-STANDARDS §1d | R18.2 | Ruling R-MKT-4 |
| G-16 | **Marketplace actions are not audited** (found 2026-10-03, MKT-1f trace). marketplace-service does not use `common-audit`; no MKT action writes an audit row | grep `AuditEmitter` in marketplace-service: 0 | R22.4 | High — support and return actions CLOSED by MKT-1f; the built slices' earlier actions (G-MKT-AUD) still to wire |

### 2c. Things the source says that would be defects here (and what replaces them)

| Source says | Why it is wrong *here* | Replacement |
|---|---|---|
| "Maintain Redis/search projections … stock status" | K5 forbids caching stock without a ruling; K7 has no Redis locally | A **DB projection table** owned by marketplace-service, refreshed from inventory events/outbox, with `last_sync_at` shown and the live reservation as the authority (ruling R-MKT-4) |
| One `status` per entity in the entity list, e.g. `stock_status` on the offer | Copies stock into a second store that will drift | The offer carries **no stock number**; availability comes from the projection, and authority from the live reservation |
| `merchant_wallet.balance += amount` (warned against) | Agreed | Append-only ledger entries; balances are a SUM, never a stored column |
| "Every webhook/event is retryable and idempotent" | Agreed, and the platform already has the pattern | `common-outbox` + consumer-side `processed_event` (finance idempotency design) |
| 12-state marketplace order with its own `CONFIRMED` | `FulfilmentStatus` deliberately has **no** `CONFIRMED` (`NEW` means it; two states with one meaning drift) | The marketplace order is a **separate aggregate** with its own machine; the seller's store order keeps `FulfilmentStatus`. The mapping is in the design (§5.4) |
| `stock > 0` vs `available = …` | Already satisfied: inventory's `sellable` is the source's formula minus `unavailable`; `expired` is published separately | Reuse; `unavailable` = expired + quarantined |

---

## 3. State-model reconciliation (source §19 vs code)

| Source fulfilment state | Today's `FulfilmentStatus` | Note |
|---|---|---|
| UNASSIGNED · OFFERED · ACCEPTED · REJECTED · EXPIRED | — (`PENDING_APPROVAL`/`NEW`/`REJECTED` are a *booker* approval, a different actor) | **Seller acceptance is net-new.** Lives on the marketplace `SellerOrder`, not on the store `Order`, so O7's approval semantics stay intact |
| RESERVED | inventory `Reservation` HELD | Reuse; the order does not duplicate it |
| PICKING · PACKED | `PACKED` (+ O5d workbench) | PICKING folds into the workbench; no new state |
| HANDED_TO_CARRIER · IN_TRANSIT | `SHIPPED` / `PARTIALLY_SHIPPED` (derived) | Carrier hand-off = a recorded shipment |
| DELIVERED · RETURN_REQUESTED · RETURNED | same names | Reuse |
| FAILED | — | Net-new on `SellerOrder`; triggers shortage handling (Phase 2) |

The marketplace (parent) order, payment and settlement machines are net-new in marketplace-service. They follow the
`FulfilmentStatus.ALLOWED` pattern: an explicit whitelist, refusing everything else.

---

## 4. Benchmark (standard 7a, before the decision)

| System | Taken | Deliberately different |
|---|---|---|
| **Amazon** (catalogue + offers, Buy Box) | ASIN-style canonical product with many offers; seller performance feeds ranking | **No forced Buy Box**: the source (R7.3) gives the customer the sort, and the ranking defaults are operator-configurable |
| **Mirakl** (operator marketplace platform) | Operator/seller split, offer approval, commission rules per category, **payment cycle** with a seller statement, order acceptance window with auto-refuse | Mirakl auto-refuses unaccepted orders; we expire them and release the hold (O5a mechanism), same effect, reusing what exists |
| **Daraz / Shopee** (PK market) | Seller Center, COD reconciliation, return reason → cost bearer, settlement after the return window | Settlement trigger is configurable per risk class (R15.3) rather than one global window |
| **Shopify Marketplace Connect / Odoo multi-vendor** | Seller-owned catalogue mapped to a marketplace listing | Mapping goes through a **match review**, never auto-merged (R6.1) |
| **Stripe Connect** (destination charges, transfers, payouts) | Platform collects, ledger of transfers, payout with idempotency key and bank reference | The PSP is still a sandbox (E6). The ledger is ours and PSP-agnostic, so swapping in Connect later changes the payout adapter only |
| **SAP / NetSuite consignment** | Consignment stock is a *special stock* owned by the consignor; ownership transfers on issue; settlement from a sell-through report | Phase 5 only, after agreement terms are configurable (R4.6) |

---

## 5. Risks

| # | Risk | Mitigation |
|---|---|---|
| K-1 | A cross-tenant read reached through a widened `findScoped` leaks one merchant's data to another | A dedicated **published projection** containing only the R9.2 fields; R9.3 fields (cost, margin) are structurally absent from the table. Merchant-side reads stay `findScoped` |
| K-2 | Two order aggregates (marketplace + store) drift | The store `Order` stays the **only** writer of stock/invoice/GL; the marketplace order holds references + snapshots and derives its state from the child's (O5b's "derived, not set" rule) |
| K-3 | Settlement money is wrong and invisible (standard §0b) | Immutable ledger, reconciliation identity asserted in unit tests and in the gate against the trial balance; UI shows PENDING until the server answers |
| K-4 | Shipping a capability nothing can call (seven precedents in the standards) | Every slice ships a screen and its gate drives it (first case in the spec) |
| K-5 | Projection staleness oversells | Live reservation is authoritative; projection only shortlists; `last_sync_at` is visible; stale offers (> configurable age) drop out of ranking |
| K-6 | Regulated goods slip into Phase 1 | Offer approval refuses `rxRequired`/`controlledSubstance` products server-side until MKT-6; safety flag defaults ON and fails ON (C3) |

---

## 6. Rulings needed before the design gate

| # | Question | Options | Recommendation |
|---|---|---|---|
| **R-MKT-1** | **Who is merchant of record in Phase 1?** | (A) the **seller**: the sale, invoice and tax land in the seller's books through the existing O1 path; MaxTheService earns a commission. (B) **MaxTheService** buys and resells: two sales per order | **A.** It reuses O1 unchanged, matches "merchant stock, existing merchants", and keeps tax on the party that sells. B doubles every sale and makes MaxTheService a reseller of goods it never owns |
| **R-MKT-2** | Who collects the customer's money? | (A) **platform collects** (online) and pays out net; COD collected by the seller's rider, who owes the platform the commission. (B) seller collects everything; platform invoices commission | **A for online, B for COD** in Phase 1 (one online option + cash, R20.1). The ledger supports both directions from day one |
| **R-MKT-3** | Where does the settlement ledger live? | (A) marketplace-service tables. (B) finance-service AP/AR subledgers | **A for the operational ledger** (seller statements, eligibility, holds, payouts); **posts to finance GL** through the outbox, so finance stays the only journal writer (EX-0b precedent) |
| **R-MKT-4** | Availability projection storage | Redis (source) · DB projection table · live calls only | **DB projection table** (K5/K7). Redis waits on the broadcast-eviction work K7 names |
| R-MKT-5 | Platform customer identity | New platform-level customer table · reuse party-service · keep per-store | **New `marketplace_customer` (platform-scoped) bridged to party-service**; per-store `storefront_customer` untouched (live-modules rule) |
| R-MKT-6 | Capability shape | One opt-in capability `marketplaceSelling` for sellers, plus operator entitlement | Opt-in (EX-0a mechanism), **not in `Plan.FREE`** (a sales channel is a paid feature; confirm) |
| R-MKT-7 | Pilot city / geography | Source: one city. No location model exists (INV-L) | Phase 1 uses a seller **service-area list** (city + optional areas) on the offer; distance sorting waits for INV-L |

**Answered 2026-10-03: all seven recommendations accepted** (recorded in the design's rulings table).

Before they were answered, implementation was limited to the **pure domain core** (MKT-1a). It encodes the source's
rules (identity key, availability formula, state machines, settlement arithmetic, business-day T+n, ranking,
Phase 1 guard) and does not depend on any ruling above.
