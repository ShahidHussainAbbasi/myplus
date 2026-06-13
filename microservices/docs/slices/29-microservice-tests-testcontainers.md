# Slice 29 — Microservice tests (JUnit + Testcontainers)

Status: **PATTERN ESTABLISHED — awaiting build** 🔨. Tech-debt #12 (🔴). First service-level test infra.

## Document — what & why
The microservices had **zero** JUnit/integration tests (coverage was monolith Cypress only). Stand up a
real test foundation: JUnit 5 + **Testcontainers-MySQL** so repository/query tests run against the
**actual MySQL dialect** (not H2, which silently diverges on `@Query` JPQL, DECIMAL, etc.). business-service
is the template; the pattern copies to the other services.

## Design
- Deps (test scope, versions via Spring Boot 3.5 BOM): `spring-boot-starter-test`,
  `org.testcontainers:mysql`, `org.testcontainers:junit-jupiter`.
- `src/test/resources/bootstrap.yml` disables the config-server fetch during tests.
- `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `@Testcontainers` against a
  `MySQLContainer`; datasource/ddl/flyway/eureka supplied via `@DynamicPropertySource`.
- **Safety:** `@Testcontainers(disabledWithoutDocker = true)` → the test **skips** (doesn't fail) when
  Docker is absent, so a Docker-less `mvn test`/`install` is never broken.

### First test — `CustomerRepoScopingTest`
Proves the multi-tenant `findScoped` contract end-to-end on real MySQL: persist rows for org1 (two
users), org2, and pre-migration org-NULL rows; assert `findScoped(org1, user1)` returns own-org rows +
the caller's org-NULL row, and **excludes** org2 and other users' org-NULL rows. This is the highest-value
first test — it locks in the tenant-isolation invariant the org-scoping slices (21/28) depend on.

## Implement (checklist)
- [x] business-service test deps (Testcontainers MySQL + starter-test)
- [x] test `bootstrap.yml` (config-server off in tests)
- [x] `CustomerRepoScopingTest` (findScoped isolation + NULL-fallback)
- [ ] `mvn test` with Docker up → green (auto-skips without Docker) — **awaiting build**
- [ ] (follow-up) replicate the pattern: Sell/Item findScoped, addSell atomicity, invoice numbering;
  then education/welfare/agriculture; eventually wire into CI

## Test
- With Docker: `mvn -pl microservices/business-service test` → `CustomerRepoScopingTest` green.
- Without Docker: the test reports as skipped; build still passes.
