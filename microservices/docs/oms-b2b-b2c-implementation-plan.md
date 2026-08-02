# OMS (B2B + B2C) — implementation plan, mapped to the existing maxtheservice build

**Status:** ANALYSIS, PARTLY SUPERSEDED (noted 2026-08-02). Still the right reconciliation of the OMS
blueprint against this codebase, **except for B4 credit limits, which has since been BUILT differently** —
see the note on that row below. The plan of record for the B2B rollout is
[`b2b-b2c-rollout-plan.md`](b2b-b2c-rollout-plan.md); where the two disagree, that one wins.

**Purpose.** The pasted OMS reference describes an idealised Order Management System as if it were greenfield
(≈12 new services: `order-`, `pricing-`, `payment-`, `shipment-`, `fulfillment-`, `billing-`, `returns-`,
`customer-`, `workflow-`, `product-`, `analytics-`…). maxtheservice is **not** greenfield: ~70–75 % of that
backbone already exists across `catalog / inventory / business(trade) / finance / marketplace / party /
analytics / notification` plus shared libraries. This document reconciles the blueprint against the code and
turns it into a **step-by-step plan that extends what exists** instead of rebuilding it.

**Governing standards:** [SAAS-BUILD-STANDARDS.md](SAAS-BUILD-STANDARDS.md) — reuse-first, compose-don't-duplicate,
one saga (`trade↔inventory` reserve→confirm→release + outbox + idempotency; returns = inverse saga),
vertical slice + Cypress gate per slice. Decision rule for "new service vs extension":
**owns data + lifecycle + external integration → service; stateless logic → library; cross-cutting filter → autoconfig lib.**

---

## 0. Verdict

> The OMS is mostly built. Do **not** spin up parallel `order-service`, `product-service`, `pricing-service`,
> `payment-service`, `billing-service`, `customer-service`, or `analytics-service` — each already has a home.
> The genuine, additive work is **(A)** finish the two in-flight commerce gaps, **(B)** the **B2B-commercial**
> layer (contract/tiered pricing, quotes→approval, credit limits & terms, account hierarchy), and **(C)** a
> **logistics** layer (carrier/shipping + pick/pack + multi-shipment). One new bounded context is clearly
> justified (**logistics-service**); the rest are extensions of existing services/libraries.

---

## 1. Blueprint → what exists today (mapping)

| OMS capability (blueprint) | Blueprint's proposed service | Where it lives **today** | Status |
|---|---|---|---|
| Product catalog / SKUs / attributes | `product-service` | **catalog-service** (`Product`: sku, name, category, manufacturer, unit, `sellingPrice`, `taxRate`, `isActive`, image); multi-rate tax classes | ✅ built |
| Inventory, multi-location, reservation | `inventory-service` | **inventory-service** (`StockEntry` multi-batch/lot/expiry/warehouse/supplier + `StockLevel` roll-up + `Warehouse`/`Supplier`/`StockTransfer`/`StockAdjustment`/`StockAlert`); reservation **saga** `reserve/confirm/release`, FEFO, idempotency, recovery relay; multi-location stores/branches | ✅ built |
| Order capture — **B2C storefront** | `order-service` | **marketplace-service**: `Cart`/`CartItem`/`Coupon`, `Order` (source `POS\|STOREFRONT`, `paymentMode`, `paymentStatus`, `paymentRef`, `refundRef/refundedAmount`, `reservationId/reservationStatus`, `total/subTotal/taxTotal/shippingFee/shippingMethod`), `OrderItem`, `OrderEvent`, `StorefrontCustomer`; checkout saga reserve→charge→confirm; reserves via the **same** `InventoryClient` POS uses | ✅ built (B2C) |
| Order capture — **POS / retail** | `order-service` | **business-service**: `Sell` + `CustomerHistory` (per-org sequential `invoiceNo`, `paid/due/dueDate`, **`paymentMode` incl. `SPLIT`**, **per-line `taxRate/taxAmount` + `taxTotal`**, `saga_status`, `status ACTIVE\|VOID`); saga sell path | ✅ built (POS) |
| Order lifecycle / statuses | `order-service` | marketplace `FulfilmentStatus` (`NEW→PACKED→SHIPPED→DELIVERED`, `CANCELLED`, `RETURN_REQUESTED→RETURNED`) + `OrderEvent` log; POS `CustomerHistory.status`/`saga_status` | ⚠️ storefront-only; POS/B2B have no fulfilment lifecycle |
| Payment (B2C online) | `payment-service` | marketplace `paymentStatus/paymentRef` (sandbox charge; **PSP not integrated**); POS tenders (`paymentMode`, split, store-credit tender) | ⚠️ real PSP integration missing |
| Billing / invoicing / AR / credit notes | `billing-service` | **finance-service** (double-entry `Account`/`JournalEntry`/`JournalLine`, `Payment`/`PaymentAllocation`/`PaymentDirection`, `PeriodLock`, outbox `ProcessedEvent`); AR/AP, statements, aging, tax register, period close | ✅ built (accounting core) |
| Customer & accounts | `customer-service` | **party-service** (shared `Party`/contact master, module bridges: business `Customer`, education `Student`, welfare `Donator`, marketplace shopper); business `Customer` with `dueAmount` | ⚠️ no B2B account **hierarchy** / roles / credit limit |
| Promotions / coupons (B2C) | `pricing-service` | marketplace `Coupon` | ✅ built (B2C promos) |
| Pricing — base + tax | `pricing-service` | catalog `sellingPrice` + `taxRate`; tax **applied** on sale (slice 35) | ✅ built |
| Analytics / dashboards | `analytics-service` | **analytics-service** + education `/getDashboardAnalytics` + POS Sale-Detail report | ✅ built (extend for OMS KPIs) |
| Notifications | (implied) | **notification-service** (only SMTP owner; `common-notify` client) | ✅ built |
| Store credit / credit ledger | (implied) | **common-credit** library (`CreditService`/`CreditStore`) + POS store-credit tender | ✅ built (store credit) |
| Returns / RMA | `returns-service` | marketplace `RETURN_REQUESTED→RETURNED` (stock back + refund, slice 71); POS `saleReturn` | ⚠️ POS return not saga-aware (**G2**) |

**Reusable libraries already present:** `commerce-contracts` (client interfaces = DIP boundary), `commerce-domain`
(Money, InvoiceNumbers, FEFO, tenant specs), `common-outbox`, `common-subledger`, `common-credit`,
`common-security`, `common-web`, `common-settings`, `common-notify`, `common-captcha`.

---

## 2. Do **not** rebuild these (anti-duplication callouts)

| Blueprint says create… | Because it doesn't exist? | Reality — use instead |
|---|---|---|
| `order-service` | ❌ | **marketplace-service** (B2C) + **business-service** (POS). Unify later behind one order view — don't fork a third store. |
| `product-service` | ❌ | **catalog-service** is the product master. |
| `pricing-service` | partial | Base price + tax already in catalog; add **B2B contract/tiered pricing** as a pricing module/library on catalog (see §3-B1), not a new master. |
| `payment-service` | partial | POS tenders in business-service + AR payments in finance; add a **PSP adapter** (see §3-C0), don't re-model payments. |
| `billing-service` | ❌ | **finance-service** already does AR/invoicing/credit-notes/period-close. |
| `customer-service` | ❌ | **party-service** is the party/account master; extend with hierarchy + credit (see §3-B4). |
| `analytics-service` | ❌ | exists; add OMS KPI queries. |

---

## 3. Genuine gaps (what's actually additive)

### A. Finish the in-flight commerce gaps (blocking, do first)
- **G1 — FEFO excludes expired stock.** *In progress this session* (`feature/commerce-gaps`, commit `492601d`;
  awaiting green test). Compliance-critical for pharmacy.
- **G2 — POS returns are not saga-aware.** `business.saleReturn` restores only the legacy local `Stock`; a saga
  sale (productId, no local Stock) leaves inventory under-counted. Route POS returns through inventory as the
  **inverse saga** (restore the original batches via the recorded reservation picks). *(marketplace already does
  stock-back on `RETURNED`; this brings POS to parity.)*
- ~~G3 tax on sale~~ ✅ **done** (slice 35). ~~G4 catalog price~~ ✅ done. ~~G5 payment method/split~~ ✅ **done** (slice 37).
  → `commerce-backend-audit.md` is stale on G3/G5; reconcile it.
- **G6 — sell screen productId-native + white-label** = the UI/UX redesign (own track).

### B. B2B commercial layer (the biggest real gap — 0 today)
- **B1 — Contract & tiered pricing + price lists.** `ContractPrice/TieredPrice/PriceList = 0`. Add customer-/
  group-specific price lists and volume breaks; resolve price = base → contract → volume tier → promotion.
  Keep the **hot path fast** (cache; resolve off the sell critical path per the performance standard).
- **B2 — Quotes & drafts.** No quote/draft workflow. Save draft → request quote → approve → **convert to order**.
- **B3 — Approval workflows.** Threshold- and item-based (e.g. controlled products) approval routing; approver
  notify/approve/reject; on approve → order proceeds. Reuse notification-service.
- **B4 — Credit limits & terms + account hierarchy.** `CreditLimit = 0`; `Customer` has only `dueAmount`.
  Add credit limit + payment terms (Net 30/60), a **credit-check gate** at order validation (against finance AR
  balance), credit hold; and a **company → branch → contact** account hierarchy with roles (buyer / approver /
  accountant) on party-service + auth privileges.

### C. Logistics / fulfillment layer (0 today; serves both B2B & B2C)
- **C0 — PSP payment adapter.** marketplace charges are sandbox; add a real payment-gateway adapter (auth/capture,
  webhooks) behind a clean port. B2C need.
- **C1 — Carrier / shipping integration.** `Shipment = 0`. Shipment records, label creation, tracking, carrier
  APIs; **multiple shipments per order** (partial fulfilment).
- **C2 — Fulfilment (pick/pack) workflow.** `Fulfillment = 0`. Pick lists, pack, warehouse tasks; extend the
  order lifecycle to POS/B2B (marketplace already has `PACKED/SHIPPED/DELIVERED`).
- **C3 — DOM sourcing + backorders.** `Backorder = 0`. Multi-node order routing (availability / cost / speed /
  customer priority) as a **stateless allocation library**; backorder/preorder states.

### D. Cross-channel OMS view
- **D1 — Unified order model/status** spanning POS + storefront (+ B2B) — one status vocabulary and one
  operational order list, rather than two stores that only share `invoiceNo`.
- **D2 — B2B ingestion channels** (portal / sales-rep entry / EDI/API), normalised into the common order model.
- **D3 — OMS analytics**: B2B fill rate, SLA adherence, backorder ratio, credit usage; B2C AOV, fulfilment speed,
  returns rate.

---

## 4. New service vs extension (decision rule applied)

| Gap | Verdict | Home |
|---|---|---|
| B1 contract/tiered pricing | **library + tables on catalog** (stateless resolution; extract to `pricing-service` only if it grows external feeds) | catalog-service + `commerce-pricing` lib |
| B2 quotes/drafts, B3 approvals | **extend the order owner** (statuses + approval state); approval routing as a small rules component | marketplace/business order + notification |
| ~~B4 credit limit/terms~~ **BUILT 2026-08-01, differently** | Rules in the existing **`common-credit`** lib (`CreditLimitPolicy`, pure); limit/terms as columns on business-service `Customer`/`Vender`; the check is **LOCAL — no finance call on the sell path**. `dueAmount` is already maintained locally by `recomputeDue`/`recomputePayable`, so calling finance AR at checkout would add a network hop to the hot path to re-derive a number we already hold. Party/finance ownership remains right for the ACCOUNT HIERARCHY half of B4, which is not built. | `common-credit` + business-service |
| B4 account hierarchy/roles | **extend party** + auth privileges | party-service |
| C0 PSP adapter | **library/port + config** (autoconfig adapter) | marketplace + `common-payments` |
| C1 shipping + C2 fulfilment | **NEW service** — owns data + lifecycle + external carrier integration = a real bounded context | **logistics-service** |
| C3 DOM sourcing | **stateless library** used by the order path | `commerce-sourcing` lib |
| C3 backorders | **extend inventory + order state** | inventory + order |
| D1 unified order view, D3 analytics | **extend analytics + a read model** | analytics-service |
| D2 B2B ingestion | **extend the B2B order owner** (adapters per channel) | order owner |

→ **Exactly one new service (`logistics-service`)** is justified; everything else is extension or library. This
is the compose-don't-duplicate standard in action.

---

## 5. Phased, step-by-step plan (each phase = vertical slices, Document→Design→Impl→Test→Cypress)

**Phase 0 — finish commerce gaps (now).** G1 (done, verifying) → **G2 POS returns → inventory inverse saga**.
Small, high-integrity, unblocks everything. *(Current branch `feature/commerce-gaps`.)*

Then the order forks on **B2B-first vs B2C-first** (see §6). Recommended tracks:

**If B2B-first** (B2C storefront is already strong; B2B is the 0-today value):
- **Phase 1 — B2B accounts & pricing:** account hierarchy + roles (party) · contract/tiered price lists +
  `/price/calculate` (catalog + `commerce-pricing`).
- **Phase 2 — B2B ordering:** quotes/drafts → approval workflow → order · mandatory PO number · credit limit +
  terms (Net 30/60) + credit-check/hold (finance AR).
- **Phase 3 — B2B billing:** invoice-on-fulfilment, credit notes, partial/line-level payments (finance extension).
- **Phase 4 — Logistics (serves both):** `logistics-service` — shipping/carrier + pick/pack + partial/
  multi-shipment; extend the order lifecycle to POS/B2B.
- **Phase 5 — Cross-channel + intelligence:** unified order view (D1) · DOM sourcing (C3) · backorders · OMS
  analytics (D3) · B2B ingestion channels (D2).

**If B2C-first** (polish the revenue path you already have):
- **Phase 1 — Storefront completion:** real PSP (C0) · promotions/bundles/kits · BOPIS/ship-from-store on
  multi-location stock.
- **Phase 2 — Logistics:** `logistics-service` (shipping/carrier + fulfilment) — the biggest B2C gap.
- **Phase 3 — Returns/RMA polish** (labels, exchange, restock rules; pharmacy quarantine policy).
- **Phase 4+ — B2B** as the B2B-first phases above.

---

## 6. Open decisions (needed before Phase 1)

1. **B2B-first or B2C-first?** (drives §5 track order). Recommendation: **B2B-first** — the storefront/POS B2C
   path is largely built; B2B commercial is the true 0-today gap and higher-value.
2. **Unified order model now, or keep POS + storefront separate and unify at Phase 5?** Recommendation: keep
   separate through Phase 0–3, unify deliberately at Phase 5 (D1) once B2B order shapes are known.
3. **Pharmacy fit:** fold pharmacy's Rx/controlled-substance + return-quarantine into the same order/return
   lifecycle, or keep as the pharma vertical's additive layer? (Affects B4 approvals + returns.)

---

*Reconciliation note:* this plan supersedes the greenfield reading of the pasted OMS reference and corrects
`commerce-backend-audit.md` (G3/G5 are implemented, not open). It does not change code; it sequences the additive
work against the existing build.
