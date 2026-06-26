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

## 3. Where we are (status, 2026-06-23)

**Phase 1 — POS / shared core: DONE, all headed-Cypress green.**
G1 expired-stock block · G2 returns→inventory · G3 tax engine · G5 payments/tender · G6 receipts · day-close
(shift/cash-drawer/X-Z) · park/hold · single vertical-aware dashboard.

**Phase 2 — Pharmacy: IN PROGRESS (on the itemId bridge, reuse-first).**
- P0a ✅ pharma-service is a mesh participant (catalog/inventory clients, eureka/gateway, health verified).
- Medicine registration = the **existing Item screen** (relabeled "Medicine") — no separate screen.
- P5 prescription intake (net-new): backend + PHARMA-only screen built; references `itemId`; **awaiting build +
  headed Cypress**.
- M1 ✅ (additive) catalog **Product master** register/list screen (strangler for the future convergence).

**Phase 3 — E-commerce: not started.**

**Deferred tech-debt:** Item→Product convergence (M2–M4 in slice 42); business-service `ApiResponse`/`GenericResponse`
dedup; local `Stock` vs inventory `StockEntry` dedup.

---

## 4. Step-by-step path from here (regrouped)

**Finish Pharmacy on the bridge (reuse POS):**
1. **P5** — build + headed Cypress green for prescription intake (`pharmacy/prescription.cy.js`).
2. **P6 — Dispense = reuse the Sell screen** (relabeled "Dispense") + attach a Prescription reference to the sale
   (no new dispense screen). Reuses saga + tax + payment + receipt.
3. **P7 — Safety**: `rxRequired`/controlled-substance enforcement at dispense + drug-interaction check (clinical
   data keyed by `itemId`).
4. **P8 — Pharmacy alerts/reports** (near-expiry, controlled-substance register) reusing inventory alerts.

**Then Phase 3 — E-commerce** (reuse catalog/inventory/trade): storefront → cart → checkout (reuse tax) → online
payment (PSP, extends G5) → order placement via the saga → fulfillment/shipping → RMA (reuse G2).

**Cross-cutting / tech-debt** (fold in deliberately): Item→Product convergence; audit log; reporting suite;
notifications wiring.

> Every numbered step ships UI/UX + service/API + DB together, with a passing headed Cypress, before the next.
