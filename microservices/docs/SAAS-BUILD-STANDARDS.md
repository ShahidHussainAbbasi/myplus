# MyPlus Commerce SaaS — Build Standards & North Star

One place that ties together the standards we build the **3 commerce verticals** by — **Retail/POS, Pharmacy,
E-commerce** — as a single **multi-tenant SaaS** on a **shared commerce core**. Companion detail docs are linked.
(Consolidated 2026-06-23 after a regroup.)

---

## 0. The one-line strategy
**One multi-tenant SaaS platform; one shared commerce core (catalog + inventory + trade saga); each vertical is the
same core white-labelled by user type + its own thin differentiating layer. Reuse the whole codebase; do not
reinvent.**

---

## 1. Governing standards (the rules every slice follows)

1. **Multi-tenancy (SaaS).** Every read/write org-scoped: `organization_id` + `findScoped` NULL-fallback; identity
   flows JWT → gateway `X-Org-Id`/`X-User-*` → service `CurrentUser`. Per-org plan/trial/quota; signup provisions a
   tenant. _→ `ARCHITECTURE-MULTITENANCY.md`._
2. **Microservices decomposition — compose, don't duplicate.** Bounded contexts: catalog (product master),
   inventory (stock/FEFO), trade (sale saga/invoice/returns), pharma (clinical), marketplace (storefront) + platform
   services (auth, gateway, config, eureka, notification…). A vertical **composes** the core via clients; it never
   re-stores products/stock. _→ `slices/33-platform-decomposition.md`._
3. **Reuse-first.** Reuse the existing POS/business screens, the sell↔stock **saga**, tax (G3), payments (G5),
   receipts (G6), day-close, returns. New code only for genuinely net-new capability. _Current bridge:_ verticals
   reference **`itemId`** (the saga already maps itemId→catalog `Product`); the full Item→Product convergence is
   **deferred tech-debt** (`slices/42-item-product-convergence.md`).
4. **One dashboard, vertical-aware.** A single `businessDashboard.html` on `/businessDashboard`, white-labelled by
   the logged-in user's type (BUSINESS=POS, PHARMA=Pharmacy, ECOMMERCE=Store) via `module-theme.js`
   (labels + `data-vertical-only` features + theme). No per-vertical templates/routes. _→ `slices/36-…`._
5. **Engineering standards / design patterns.** `BigDecimal(19,2)` money; DTOs at the boundary (never entities);
   **saga** for cross-service atomic writes + recovery relay + idempotency; gateway per-route Resilience4j circuit
   breakers + timeouts; Bean Validation; common-web `ApiResponse` envelope (business-service keeps its monolith-
   facing `GenericResponse`); Flyway forward migrations; Hikari pools + `open-in-view:false`; FEFO + never-expired.
6. **Process cadence (per slice).** **Document → Design (Mermaid UML, `DESIGN-STANDARD.md`) → Implement (UI/UX →
   service/API → DB) → Test (`mvn`, Testcontainers) → headed Cypress GREEN → next.** Mark each step against the
   codebase. **A slice is not done until its headed Cypress passes.** _→ slice-cadence + Cypress-gate._

---

## 2. Per-vertical activity lifecycle (UI → API → DB)
The full industry-standard lifecycle for each vertical, graded ✅/🟡/⬜ against the codebase, lives in
**`commerce-verticals-blueprint.md`** (the master map) and **`commerce-backend-audit.md`** (backend gaps G1–G6).
Summary: all three share the core lifecycle (catalog → stock-in → counter/checkout sale → tax → payment → receipt →
returns → day-close → reporting); pharmacy adds Rx/dispense/safety; e-commerce adds storefront/cart/checkout/
fulfillment.

---

## 3. Where we are (status, 2026-06-27)

**Phase 1 — POS / shared core: DONE, all headed-Cypress green.**
G1 expired-stock block · G2 returns→inventory · G3 tax engine · G5 payments/tender · G6 receipts · day-close
(shift/cash-drawer/X-Z) · park/hold · single vertical-aware dashboard.

**Phase 2 — Pharmacy: largely DONE on the itemId bridge (reuse-first), headed-Cypress green.**
- pharma-service is a full mesh participant; medicine registration = the existing Item screen (relabeled).
- P5 prescription intake · P6 dispense (reuses Sell screen) · P7 safety (rxRequired/controlled + drug-interaction)
  · P8 alerts · P10 FEFO batch/expiry on dispense · P11 quarantine returns + register · P12 insurance/co-pay split.
- Remaining: deeper clinical/reporting; convergence cleanup (below).

**Phase 3 — E-commerce: IN PROGRESS (reuse catalog/inventory/trade).**
- Done (slices 46–61): storefront browse + **name search**, public orders, **stock-reserve saga** (same inventory
  saga as POS) + recovery relay, order management/fulfilment, order tracking + status timeline + notification seam,
  sandbox online payment (PSP swap deferred), **self-contained customer accounts**.
- Remaining, in priority order: **cart (E3), checkout (E5), real PSP + refunds (E6), shipping (E9), returns/RMA
  (E10), coupons (E13), variants/media (E2)**.

**Cross-cutting — prod-readiness: DONE this cycle.**
- **P0 Flyway series (slices 64–67):** all 12 microservices now own their schema via versioned migrations and are
  `ddl-auto: validate`-ready; dev flipped `update → ${DDL_AUTO:validate}`. See [[project_flyway_validate_ready]].

**Convergence status (Item↔Product, slice 42):** M1 ✅ Product master screen · M3.1 ✅ Stock list reads inventory
on-hand · M3.2 ✅ purchases auto-map + reach inventory · slice 53 ✅ product-master sync (Product→Item). M3.3/M3.4
(retire local `Stock`/`Item`) and full M2/M4 rewire **parked** — master-sync covers "one product master" for now.

**Deferred tech-debt:** M3.3/M3.4 local-`Stock`/`Item` retirement; business-service `ApiResponse`/`GenericResponse`
dedup + OpenAPI; storefront auth hardening (token expiry/rotation/rate-limit/email verify); quantities
`Float→BigDecimal`; real PSP + money-side refunds; landing this 34-slice branch (master SB 4.1.0 doesn't compile).

---

## 4. Step-by-step path from here (regrouped)

**Consolidation (in progress):** P0 Flyway done + dev `validate` flip; planning docs refreshed; branch housekeeping.
Then decide the **branch-landing path** — `feature/commerce-gaps` is ~34 slices ahead of a master that doesn't
compile (SB 4.1.0); fix master to the 3.5.0 line (or cut a release) and merge before piling on more.

**Finish Phase 3 — E-commerce** (reuse catalog/inventory/trade/tax/saga), in order:
1. **E3 Cart** — add/update/remove, persisted for guest + account (reuses customer accounts from slice 61).
2. **E5 Checkout** — address + shipping method + tax (reuse C3/G3) + totals → order placement via the saga.
3. **E6 Real PSP + refunds** — swap the sandbox gateway for Stripe; webhooks; money-side refund (extends G5/G2).
4. **E9 Shipping**, **E10 Returns/RMA** (reuse G2 inverse saga), **E13 coupons**, **E2 variants/media**.

**Cross-cutting / tech-debt** (fold in deliberately): `ApiResponse`/`GenericResponse` dedup + OpenAPI; storefront
auth hardening; M3.3/M3.4 local-`Stock`/`Item` retirement; quantities `Float→BigDecimal`; audit log; reporting suite.

> Every numbered step ships UI/UX + service/API + DB together, with a passing headed Cypress, before the next.

---

## 5. Product roadmap & cross-cutting shared services

**The landing page `svcMarquee` (`SVCS` array, `maxtheservice_dashboard.html`) is the product catalog — 11 services, each a domain on the SAME shared core (reuse-first, plug-and-play):**
School Management (education ✅) · Inventory (inventory ✅) · **ERP (⬜ planned)** · POS (business ✅) · **HRM (⬜ planned)** · Campaign (campaign ✅) · Analytics (analytics 🟡) · Appointment (appointment ✅) · Pharmacy POS (pharma ✅) · **Pharmaceutical / distribution (⬜ planned)** · Online Marketplace (marketplace ✅).

**Cross-cutting shared services — build deliberately, never per-domain:**
- **finance-service** = the money spine (AR ✅ → AP ✅ (F1) → **GL ⬜**). The GL is the seed of the **ERP** offering. Party-agnostic ledger (references parties by `partyType+partyId`, never owns them).
- **party/contact master service** = the future shared CRM/identity. **Decision: defer** — not a "customer god-service"; converge on a thin party master + `partyId` bridge (like Item→Product), as its own initiative **after** the finance stack. Rationale in `finance-ap-vendor-payments-design.md` companion + memory. The shared `SubledgerService`/`OpenDoc` (business-service) is already party-agnostic and is the enabler.

**Standing constraint — performance is a priority** in every design/impl choice: set-based over per-row queries (watch N+1), index scoped/FIFO columns via Flyway, keep inter-service calls off hot paths (best-effort like the finance ledger call), reuse shared components. Raise perf/architecture concerns **early**, before a design ships.
