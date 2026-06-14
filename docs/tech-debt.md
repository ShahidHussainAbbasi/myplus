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
- [x] 🔴 **Hardcoded shop identity on every receipt** — RESOLVED 2026-06-14: the offending code is gone.
  The hardcoded "Haider Garments" identity lived in `SellService.createReport()`, which was already
  gutted to a no-op stub during the BigDecimal slice (its only caller was commented out). Removed the
  dead stub + its `ISellService` declaration + commented caller + the now-unused `File`/`FileOutputStream`/
  `IOException`/`DecimalFormat` imports. **When receipt/PDF generation is rebuilt, it MUST source the shop
  identity from the org/`Company` profile (not a constant)** — captured here so the multi-tenant rule isn't
  lost. (Unrelated `createReport` in analytics-service is a different feature.)
- [x] 🟠 **`e.getCause().toString()` NPE pattern** — DONE 2026-06-13: replaced all 22 occurrences across
  the 9 business-service controllers with NPE-safe `e.getMessage()`. (No occurrences remain in any service.)
- [x] 🟠 **Unbounded reads** — DONE + VERIFIED 2026-06-13, slice 24: backward-compatible optional
  `page`/`size` on `getUserCustomer`/`getUserItem`/`getUserSell` (paged `findScoped(...,Pageable)`
  through repo→service→controller). Absent params = full list (preserves client-side DataTables UI).
  Build ✓, headed Cypress sell/customer/item/flow 73/73 (no regression). **Follow-up:** wire monolith to
  server-side DataTables to bound default UI loads. _Design: slices/24-pagination.md._
- [x] 🟠 **N+1 query in `getUserSell`** — DONE + VERIFIED 2026-06-14: collect the distinct `stock.itemId`s
  up-front and batch-fetch via `itemService.findAllById(...)` into a `Map<Long,Item>`, then look up per row.
  One item query per request instead of one per Sell row (SellController.getUserSell). Headed Cypress
  sell 28/28 + flow 19/19 (no regression — identical DTO output).
- [x] 🟡 **Audit legacy `Example.of(obj)` dup-check probes** — DONE + VERIFIED 2026-06-14 (build ✓ +
  headed Cypress customer/sell/flow 61/61; dashboard stats exercised on every login). Findings:
  welfare has none; agriculture (Income/Expense/Land) builds a **fresh** `userId`-scoped probe → safe
  (intentional user-scoped dup-check, no cross-tenant match); business `ItemService`/`StockController`
  probes are user-scoped (StockController also tenant-checks the item first via anti-IDOR). Two fixes:
  (1) **`CustomerService` stale-probe** — `Example.of(customerObj)` was built *before* the setters ran
  (worked only via Example's live reference); moved it to after the object is populated.
  (2) **`BusinessDashboardController` counts** — company/vender/customer/item counts + the dues list used
  `userId`-only Example probes, inconsistent with the org-scoped `findScoped` lists (wrong after an
  org-switch / missing teammates' rows). Switched all to `findScoped(orgId, userId)`; removed 5
  `Example.of` calls + now-unused imports.

## 🔧 Improvements (quality / maintainability / perf)

- [x] 🟡 **`printStackTrace()` → SLF4J** — DONE 2026-06-14: removed all 52 `e.printStackTrace()` in
  business-service controllers (+1 in `AppUtil.init`) — business-service was the only microservice with
  any. Each catch already had a follow-up log line, so the traces now route through SLF4J: enhanced the 45
  `LOGGER.error(...+e.getCause())` lines to pass the throwable (`, e`) and `AppUtil.le(...)` (SellController's
  9 catches) to `log.error(msg, e)` — full stack trace via the logger, levels/aggregation apply. Build ✓ +
  headed Cypress sell 28/28 + flow 19/19 (no regression).
- [ ] 🟡 **`new ModelMapper()` per controller (9×)** — make a single `@Bean` (thread-safe, expensive to build).
- [ ] 🟡 **Service-layer boilerplate** — each `*Service` re-implements ~30 `JpaRepository` passthrough
  methods. Inject the repository directly or use a thin generic base.
- [x] 🟢 **Dead code on classpath** — DONE + VERIFIED 2026-06-14 (build ✓ + headed Cypress stock/sell/flow
  66/66): deleted `Stock_back` (an `@Entity`
  redundantly mapped to the **same** `stock` table as `Stock` — a duplicate Hibernate mapping; table
  unaffected, owned by `Stock`) and `ItemDTcopy` (dead DTO copy). Both had zero references anywhere.
  `BaseEntity` / stray `@Entity` DTOs from the original note don't exist in this codebase. Large commented
  blocks left for a separate pass.
- [ ] 🟢 **Inconsistent service-interface pattern** — some interfaces extend `JpaRepository` with a parallel
  `@Service` impl, some extend the repo directly. Standardise one pattern.

## 🏛️ Industry-standard gaps still to implement

- [~] 🔴 **No unit/integration tests in microservices** — foundation DONE+VERIFIED 2026-06-14:
  business-service suite = 8 green Testcontainers tests (repo scoping/paging/invoice/money + addSell
  atomicity), all ran against real MySQL. CI wired: `.github/workflows/microservice-tests.yml` runs
  `mvn test` on push/PR (ubuntu Docker → Testcontainers executes). Follow-up: tests for
  education/welfare/agriculture. slice 29: JUnit5 +
  **Testcontainers-MySQL** foundation on business-service (real dialect, not H2); first test
  `CustomerRepoScopingTest` locks in the `findScoped` tenant-isolation + NULL-fallback contract.
  `@Testcontainers(disabledWithoutDocker=true)` so Docker-less builds aren't broken. **Follow-up:**
  expand (Sell/Item, addSell atomicity, invoice) + other services + CI wiring. _slices/29._
- [~] 🟠 **Real schema migrations** — business-service DONE+VERIFIED 2026-06-14 (Flyway V2 applied,
  success=1); welfare V2 (donation/donator), agriculture V2 (income/expense/land), and education V2
  (org_id across 14 tables) all authored (idempotent org_id adds, awaiting each service restart).
  Remaining: flip all to `validate` once confirmed applied per env.
  slice 30: each service already had `V1__baseline`
  + baseline-on-migrate, but no forward scripts for the slice 21–28 changes (prod-on-`validate` would
  miss them). Authored idempotent business-service `V2__slice21_28_org_money_invoice.sql` (org_id on 10
  tables + invoice cols + money→DECIMAL; guarded so it's a no-op where ddl-auto already applied them).
  Policy: structural changes via Flyway henceforth; target ddl-auto→validate. **Follow-up:** V2 for
  education/welfare/agriculture, then flip to validate. _slices/30._
- [x] 🟠 **API gateway resilience** — DONE + VERIFIED 2026-06-14 (Cypress green through the gateway):
  per-user rate limiting added (RateLimitGlobalFilter — in-memory, bearer/IP-keyed, 429 over
  gateway.ratelimit.requests-per-second default 100) but shipped **disabled by default**
  (`gateway.ratelimit.enabled:false`): all gateway traffic is the single monolith client, so a
  bearer/IP bucket collapses to one shared counter that a normal dashboard fan-out exceeds — enabling
  it 429-broke the suite. Proper per-tenant limiting needs X-User-Id (post-JWT) keying + tuned limit
  in a multi-client deploy; kept opt-in/tunable. Also fixed the gateway `/actuator/health` 503
  (`management.health.redis.enabled:false` — local start-all has no Redis). per-route Resilience4j **circuit
  breaker** (own name each → per-service isolation, `forward:/fallback` → 503 JSON) + **httpclient
  timeouts** (connect 5s, response 20s; timelimiter raised 1s→20s). **Follow-up:** per-user **rate
  limiting** (deferred — all gateway traffic is one monolith IP, so it must key on `X-User-Id` after the
  JWT filter; Redis dep present but not guaranteed in local dev). _slices/27-gateway-resilience.md._
- [~] 🟠 **Dependency vulnerabilities** — **234** alerts (42 critical / 103 high / 77 moderate / 12 low).
  Added `.github/dependabot.yml` (maven root + microservices, npm, github-actions; grouped weekly bump
  PRs) so updates are auto-proposed and gated by the new Microservice Tests + PR-validation CI. Monolith
  is already on Spring Boot 3.5.0, so remaining CVEs are transitive/npm → best remediated via the
  reviewable Dependabot PRs rather than blind bumps. **Manual step:** enable "Dependabot security
  updates" in repo Settings → Code security (targets the open alerts directly). **Follow-up:** OWASP
  Dependency-Check + SpotBugs in CI to fail on new criticals (NVD API key needed; complements Trivy).
- [x] 🟡 **Bean Validation** — DONE + VERIFIED 2026-06-14 (Cypress green): `@NotBlank` on the required name of
  Customer/ItemType/ItemUnit/Vender/Company DTOs; `GlobalExceptionHandler` now maps form-bind/validation
  failures to the flat `GenericResponse("ERROR", …)` (200) so the monolith shows them (was falling to a
  500). `ItemDTO.iname` left unconstrained (shared by addStock) — groups follow-up. _slices/26-bean-validation.md._
- [ ] 🟡 **Observability** — no distributed tracing (add Micrometer Tracing + OTel/Zipkin).
  _(Correction: actuator surface is already hardened in prod — `application-prod.yml` sets
  `show-details: never` + `include: health,info` + probes; the `always` is dev-only shared config. So
  only the tracing gap remains.)_
- [x] 🟡 **Finish org-scoping** — DONE + VERIFIED 2026-06-14 (build ✓ + service-level smoke ✓, no 500s):
  slice 28: welfare (Donation/Donator) + agriculture
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
