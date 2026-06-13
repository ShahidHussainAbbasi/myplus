# MyPlus — Tech Debt & Industry-Standards Tracker

Tracked outcome of the 2026-06-13 whole-codebase review. Items are grouped by category and ordered by
priority. Check off as completed; link the implementing slice/PR next to each. Scope of the review went
deepest on **business-service** (org-scoping + invoice work) and cross-cutting concerns (config, gateway,
security); education/auth/gateway were assessed at architecture + spot-check level — drill-downs welcome.

Severity: 🔴 critical · 🟠 high · 🟡 medium · 🟢 low

---

## 🐞 Bugs & correctness / security (fix first)

- [x] 🔴 **Money stored as `Float`/`Double`** — DONE + VERIFIED 2026-06-13, slice 23: business-service
  money fields/DTOs → `BigDecimal` (`@Column(precision=19, scale=2)`); arithmetic in
  CustomerService/SellController converted; dead `createReport` gutted; dashboard sums use `doubleValue()`.
  Build ✓, migration #3 (`ALTER … DECIMAL(19,2)`) ✓, headed Cypress sell/flow/purchase/stock 85/85 ✓.
  Quantities kept `Float` (follow-up). _Design: `microservices/docs/slices/23-bigdecimal-money.md`._
- [ ] 🔴 **Hardcoded shop identity on every receipt** — `SellService.createReport()` hardcodes
  "Haider Garments" / address / phone (SellService.java:257-263). Multi-tenant: every tenant's receipt
  shows that shop. Source it from the org/`Company` profile.
- [x] 🟠 **`e.getCause().toString()` NPE pattern** — DONE 2026-06-13: replaced all 22 occurrences across
  the 9 business-service controllers with NPE-safe `e.getMessage()`. (No occurrences remain in any service.)
- [x] 🟠 **Unbounded reads** — DONE + VERIFIED 2026-06-13, slice 24: backward-compatible optional
  `page`/`size` on `getUserCustomer`/`getUserItem`/`getUserSell` (paged `findScoped(...,Pageable)`
  through repo→service→controller). Absent params = full list (preserves client-side DataTables UI).
  Build ✓, headed Cypress sell/customer/item/flow 73/73 (no regression). **Follow-up:** wire monolith to
  server-side DataTables to bound default UI loads. _Design: slices/24-pagination.md._
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
- [x] 🟠 **API gateway resilience** — DONE + VERIFIED 2026-06-14 (Cypress green through the gateway):
  per-route Resilience4j **circuit
  breaker** (own name each → per-service isolation, `forward:/fallback` → 503 JSON) + **httpclient
  timeouts** (connect 5s, response 20s; timelimiter raised 1s→20s). **Follow-up:** per-user **rate
  limiting** (deferred — all gateway traffic is one monolith IP, so it must key on `X-User-Id` after the
  JWT filter; Redis dep present but not guaranteed in local dev). _slices/27-gateway-resilience.md._
- [ ] 🟠 **Dependency vulnerabilities** — GitHub Dependabot reports **234** on the default branch
  (42 critical / 103 high / 77 moderate / 12 low). Triage & upgrade; add OWASP Dependency-Check +
  SpotBugs to CI and fail builds on new criticals (complements the existing Trivy image scan).
- [x] 🟡 **Bean Validation** — DONE + VERIFIED 2026-06-14 (Cypress green): `@NotBlank` on the required name of
  Customer/ItemType/ItemUnit/Vender/Company DTOs; `GlobalExceptionHandler` now maps form-bind/validation
  failures to the flat `GenericResponse("ERROR", …)` (200) so the monolith shows them (was falling to a
  500). `ItemDTO.iname` left unconstrained (shared by addStock) — groups follow-up. _slices/26-bean-validation.md._
- [ ] 🟡 **Observability** — no distributed tracing (add Micrometer Tracing + OTel/Zipkin).
  _(Correction: actuator surface is already hardened in prod — `application-prod.yml` sets
  `show-details: never` + `include: health,info` + probes; the `always` is dev-only shared config. So
  only the tracing gap remains.)_
- [~] 🟡 **Finish org-scoping** — slice 28 (awaiting build): welfare (Donation/Donator) + agriculture
  (Income/Expense/Land) org-scoped via the slice-21 recipe (org_id, findScoped NULL-fallback, scoped
  reads incl. getAll* leak, stamped writes, anti-IDOR deletes). All 4 business domains now multi-tenant.
  **No Cypress for welfare/agri** → compile-gated; adding those specs is its own tech-debt. _slices/28._
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
