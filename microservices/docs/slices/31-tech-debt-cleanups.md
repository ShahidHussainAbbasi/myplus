# Slice 31 — Tech-debt cleanups (quality / correctness pass)

Status: **DONE + VERIFIED** for the items below; remaining items are **PAUSED** (recorded as TODO).
Tracker: `docs/tech-debt.md`. Most items landed on branch `chore/tech-debt-plan`; the ModelMapper
refactor on `refactor/modelmapper-typemaps` (PR → `chore/tech-debt-plan`).

## Done + verified (this pass)
- **N+1 in `getUserSell`** — distinct `stock.itemId`s batch-fetched via `findAllById` into a map; one
  item query per request. Cypress sell/flow 47/47.
- **`printStackTrace()` → SLF4J** — removed all 52 in business-service controllers + 1 in `AppUtil.init`;
  the existing log lines now carry the throwable (`LOGGER.error(..., e)` / `AppUtil.le → log.error(msg,e)`).
  Cypress 47/47.
- **Hardcoded receipt identity (last 🔴)** — the "Haider Garments" code was already gutted; removed the
  dead `createReport` stub + interface decl + commented caller + 4 unused imports. Cypress 47/47.
- **Dead classes** — deleted `Stock_back` (a duplicate `@Entity` on the `stock` table) + `ItemDTcopy`.
  Cypress 66/66.
- **ModelMapper TypeMaps** — one STRICT, TypeMap-configured `@Bean` (`config/ModelMapperConfig`) replaces
  13 `new ModelMapper()` + ~30 per-request `addConverter` mutations; converters property-scoped (safe on a
  singleton). Full business Cypress 160/160. _Branch `refactor/modelmapper-typemaps`._
- **`Example.of` dup-check audit** — welfare/agri probes safe (fresh, user-scoped). Fixed: (1) a fragile
  `CustomerService` stale-probe; (2) **BusinessDashboard counts** were `userId`-only (inconsistent with the
  org-scoped lists) → now `findScoped(orgId,userId)`. Cypress 61/61.
- **welfare/agri Cypress** — new `cypress/e2e/welfare/welfare.cy.js` (6/6) + `agriculture/agriculture.cy.js`
  (8/8). Surfaced + fixed **two real welfare-dashboard bugs**: wrong header fragment path (→500) and a
  missing `header-js` include (→`$ is not defined`). Both fixed + rebuilt + verified.

(Earlier in the tracker, also done: BigDecimal money #1, NPE-safe #3, pagination #4, gateway resilience
#14, Bean Validation #15, org-scoping all 4 domains #18, Testcontainers+CI #12, Flyway V2 #13,
Dependabot #19.)

## TODO — PAUSED (not started; pick up later)
- 🟡 **Service-layer JpaRepository passthrough boilerplate** — each `*Service` re-implements ~30
  passthrough methods. Inject repo directly / thin generic base. Behaviour-sensitive → do on a branch
  with full Cypress (like the ModelMapper refactor).
- 🟢 **Inconsistent service-interface pattern** — some interfaces extend `JpaRepository` + parallel impl,
  some extend the repo directly. Standardise one pattern (overlaps with the above).
- 🟢 **Large commented dead blocks** — e.g. commented `toDTO` / `updateStock` blocks in business-service.
- 🟡 **Observability** — no distributed tracing; add Micrometer Tracing + OTel/Zipkin (new deps + infra).

## Operator / merge follow-ups
- Merge order: `refactor/modelmapper-typemaps` → `chore/tech-debt-plan` →
  `feature/monolith-myplusdb-removal`.
- Enable "Dependabot security updates" in repo Settings → Code security (targets the 234 alerts).
- Dependabot + CI fully activate from the default branch once this line reaches `master`.
- welfare/agri specs run locally against the full stack (not yet wired into a CI job needing the stack).
