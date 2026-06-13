# MyPlus — Tech Debt & Industry-Standards Tracker

Tracked outcome of the 2026-06-13 whole-codebase review. Items are grouped by category and ordered by
priority. Check off as completed; link the implementing slice/PR next to each. Scope of the review went
deepest on **business-service** (org-scoping + invoice work) and cross-cutting concerns (config, gateway,
security); education/auth/gateway were assessed at architecture + spot-check level — drill-downs welcome.

Severity: 🔴 critical · 🟠 high · 🟡 medium · 🟢 low

---

## 🐞 Bugs & correctness / security (fix first)

- [x] 🔴 **Money stored as `Float`/`Double`** — DONE (code) 2026-06-13, slice 23: business-service money
  fields/DTOs → `BigDecimal` (`@Column(precision=19, scale=2)`, HALF_UP intent); arithmetic in
  CustomerService/SellController converted; dead `createReport` gutted; dashboard sums use `doubleValue()`.
  **Pending: build + migration #3 (`ALTER … DECIMAL(19,2)`) + headed Cypress.** Quantities kept `Float`
  (follow-up). _Design: `microservices/docs/slices/23-bigdecimal-money.md`._
- [ ] 🔴 **Hardcoded shop identity on every receipt** — `SellService.createReport()` hardcodes
  "Haider Garments" / address / phone (SellService.java:257-263). Multi-tenant: every tenant's receipt
  shows that shop. Source it from the org/`Company` profile.
- [x] 🟠 **`e.getCause().toString()` NPE pattern** — DONE 2026-06-13: replaced all 22 occurrences across
  the 9 business-service controllers with NPE-safe `e.getMessage()`. (No occurrences remain in any service.)
- [ ] 🟠 **Unbounded reads** — `findScoped` / `getAll*` return the whole tenant table (only 1 controller
  uses `Pageable`). Add server-side pagination to list endpoints.
- [ ] 🟠 **N+1 query in `getUserSell`** — per-row `itemService.findById` (SellController.java:179). Batch
  fetch or join.
- [ ] 🟡 **Audit legacy `Example.of(obj)` dup-check probes** — partly cleaned in slice 21 (business);
  verify the same stale-probe pattern isn't present in welfare/agriculture/other services.

## 🔧 Improvements (quality / maintainability / perf)

- [ ] 🟡 **`printStackTrace()` (11×) → SLF4J** — route through the logger so levels/aggregation apply.
- [ ] 🟡 **`new ModelMapper()` per controller (9×)** — make a single `@Bean` (thread-safe, expensive to build).
- [ ] 🟡 **Service-layer boilerplate** — each `*Service` re-implements ~30 `JpaRepository` passthrough
  methods. Inject the repository directly or use a thin generic base.
- [ ] 🟢 **Dead code on classpath** — `Stock_back`, `BaseEntity`, stray `@Entity` DTOs, large commented
  blocks. Remove.
- [ ] 🟢 **Inconsistent service-interface pattern** — some interfaces extend `JpaRepository` with a parallel
  `@Service` impl, some extend the repo directly. Standardise one pattern.

## 🏛️ Industry-standard gaps still to implement

- [ ] 🔴 **No unit/integration tests in microservices** — business/education/auth/gateway have **0 JUnit
  files**; coverage is monolith Cypress E2E only. Add service + slice tests with **Testcontainers-MySQL**
  (real dialect, not H2). Biggest standards gap.
- [ ] 🟠 **Real schema migrations** — dev uses `ddl-auto: update` + manual `migrations.md`; prod is
  `validate` but there are **no full Flyway scripts** (only `baseline-on-migrate`). Author versioned
  **Flyway** migrations per service so prod-on-`validate` boots from a known schema.
- [ ] 🟠 **API gateway resilience** — no rate limiting / circuit breaking. Add `RequestRateLimiter`
  (Redis) + Resilience4j circuit breakers + timeouts so one slow service can't cascade.
- [ ] 🟠 **Dependency vulnerabilities** — GitHub Dependabot reports **234** on the default branch
  (42 critical / 103 high / 77 moderate / 12 low). Triage & upgrade; add OWASP Dependency-Check +
  SpotBugs to CI and fail builds on new criticals (complements the existing Trivy image scan).
- [ ] 🟡 **Bean Validation thin** — DTOs largely lack `@NotNull/@Size/@Email/@Positive`; only ~9
  controllers use `@Validated`. Validate consistently at the edge.
- [ ] 🟡 **Observability** — no distributed tracing (add Micrometer Tracing + OTel/Zipkin).
  _(Correction: actuator surface is already hardened in prod — `application-prod.yml` sets
  `show-details: never` + `include: health,info` + probes; the `always` is dev-only shared config. So
  only the tracing gap remains.)_
- [ ] 🟡 **Finish org-scoping** — welfare + agriculture services are still `userId`-only (deferred); same
  cross-tenant `getAll*` leak class slice 21 fixed for business. (education + business now done.)
- [ ] 🟢 **Finance hardening** — beyond `BigDecimal`: immutable audit trail + a proper invoice/ledger model
  (org-scoping + `user_id` audit added in slices 21–22 is the start).

## ✅ Already in good shape (no action)
Per-service DB isolation · token-derived tenant scoping (no client-supplied org id) · right-sized Hikari
pools + `open-in-view:false` · atomic transactional writes · prod-hardening (Secrets Manager, TLS, OIDC,
non-root images, Trivy) · broad Cypress E2E · disciplined slice docs + migrations log.

---

## Suggested execution order
1. 🔴 `BigDecimal` money migration + receipt identity from org — financial correctness.
2. 🟠 NPE-safe error handling + pagination — stability under load.
3. 🔴/🟠 Microservice tests (Testcontainers) + Flyway migrations — foundational standards.
4. 🟠 Gateway rate-limit/circuit-breaker + Bean Validation + Dependabot triage.
5. 🟡 Finish welfare/agriculture org-scoping; then cleanup (logging, ModelMapper bean, dead code).

_Source review: conversation 2026-06-13. Update this file as items land._
