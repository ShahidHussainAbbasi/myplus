# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## RULE 0 — NEVER ASSUME. REVIEW 100% END TO END.

**Before designing, before changing, and before saying anything is done.** This rule comes first because
breaking it is how every other rule here gets broken.

Every defect this project has paid for was an assumption that **read correctly in the source**. Not one was
found by reasoning about the code — each needed the whole path traced, or a person looking at the screen:

| Assumed | Actually |
|---|---|
| "the keyboard chain follows the form order" — its own comment said so | it did not; Enter jumped down the page and back up |
| "moving a field on screen moves it in the keyboard walk" | the sale chain is a literal list; the field became unreachable, and the server required it |
| "the org parameter is honoured for the operator" | honoured for **anyone** — a cross-tenant WRITE |
| "`@Lob`+TEXT is fine" · "ENUM is fine for a String field" | two services crash-looped, 59 and 9 times, under `ddl-auto=validate` |
| "`paidAmount` is what the deposit guard checks" | it checks the TENDERS — 14 gate cases red on an unrelated message |
| "`@Lazy` here prevents a construction cycle" | there is no cycle; `ObjectProvider` resolves on demand. A plausible comment, and false |
| "the tenant has open plans to assert on" | zero. **Existence is not eligibility** |

**The trace, before writing anything:**

1. **Every READER.** List all queries over the table and classify each: does it WANT the new row, or must it
   exclude it? *(OB-1: 8 queries — 5 include, 1 excludes, 2 unaffected. Only listing all 8 found the one.)*
2. **Every CALLER.** A function's contract is what its call sites rely on, not what its name suggests.
3. **Every WRITER — especially anything that RECOMPUTES.** A derived column silently discards what you wrote
   (`recomputeDue`, `recomputePayable`).
4. **The wire.** DTO twins, proxies that collapse repeated parameters, fields lost in re-serialisation.
5. **The column type against the entity.** Under `ddl-auto=validate` a mismatch is not a warning — it is a
   service that does not start, and every screen behind it then fails for an unrelated-looking reason.
6. **State the COUNT.** "8 queries: 5 include, 1 excludes, 2 unaffected" is checkable. "I reviewed it" is not.

**Verify, never infer.** Run the query, read the file, check the timestamp, `docker ps`, print the parsed
value. **A claim that cannot be shown is reported as unverified**, not as fact.

_Full version: `microservices/docs/SAAS-BUILD-STANDARDS.md` §0._

## Build & Run

```bash
# Build (requires MySQL running)
mvn clean install

# Skip tests during build
mvn clean install -DskipTests

# Run
mvn spring-boot:run
# or
java -jar target/myplus.jar

# Unit tests only
mvn test

# Integration tests
mvn test -Pintegration
```

App runs on `http://localhost:8080`. Database `myplusdb` auto-creates on first run via `createDatabaseIfNotExist=true` in the JDBC URL.

**MySQL credentials**: `persistence.properties` reads `${DB_USER}` / `${DB_PASSWORD}` — set them in the git-ignored repo-root `.env.local` (see `.env.example`). DB `myplusdb` on `localhost:3306`.

Email (Gmail SMTP) and reCAPTCHA secrets are read from `.env.local` too (`MAIL_PASSWORD`, `RECAPTCHA_SECRET`).

## Architecture

Standard Spring Boot MVC layered app — no REST API, all responses are server-rendered Thymeleaf HTML.

```
com.spring/           — Config: SecSecurityConfig, MvcConfig, PersistenceJPAConfig, AppConfig, SetupDataLoader
com.web.controller/   — @Controller classes, one sub-package per module
com.service/          — Interfaces + Impl classes, @Transactional lives here
com.persistence/      — JPA entities (model/) and Spring Data repositories (Repo/)
com.web.dto/          — DTOs for controller I/O (never pass raw entities to controllers)
com.security/         — Custom auth: CustomAuthenticationProvider, CustomRememberMeServices, google2fa/
com.registration/     — Email verification event listeners
com.validation/       — Custom Bean Validation annotations
com.web.util/AppUtil  — Shared utility methods used across services
```

### Modules

Four independent business modules share a single auth/registration system:

| Module | User type | Dashboard route | Role prefix |
|--------|-----------|-----------------|-------------|
| Business (inventory/POS) | `BUSINESS` | `/businessDashboard` | `ROLE_BUSINESS_*` |
| Education (schools/fees) | `EDUCATION` | `/educationDashboard` | `ROLE_EDUCATION_*` |
| Welfare/Abbasi (donations) | `WELFARE` | `/welfareDashboard` | `ROLE_WELFARE_*` |
| Agriculture (income/expense) | `AGRICULTURE` | `/agricultureDashboard` | `AGRICULTURE_ROLE_*` |

The landing page (`/`) maps to `maxtheservice_dashboard.html` via `AppController.landing()`.  
Static view routes (no controller logic) are registered in `MvcConfig.addViewControllers()`.

### Security model

Authorization is **privilege-based**, not role-based. Roles hold sets of privileges; code checks privileges.

- HTTP layer: `SecSecurityConfig` — `.anyRequest().hasAuthority("LOGIN_PRIVILEGE")`
- Method layer: `@PreAuthorize("hasAuthority('ADD_ITEM')")` on service methods
- Thymeleaf: `sec:authorize="hasAuthority('DELETE_COMPANY')"` for conditional rendering

Privileges per module live in `src/main/resources/role_privileges_*.properties`. `SetupDataLoader` seeds roles, privileges, and default users on every startup (idempotent).

Optional Google Authenticator 2FA is handled by `CustomAuthenticationProvider` + `aerogear-otp-java`.

### Data flow pattern

`Controller` receives a `*DTO` → calls `Service` → `Service` maps DTO↔Entity using **ModelMapper** → calls `Repository` (Spring Data JPA). Never return JPA entities from controllers.

`Customer`/`CustomerDTO` expose `getId()`/`setId()` as delegation methods to the underlying `customerId` field — callers in `CustomerService` and `AppUtil` rely on this.

### Key config files

| File | Purpose |
|------|---------|
| `persistence.properties` | DB URL, credentials, Hibernate dialect, DDL mode |
| `application.properties` | Port, Thymeleaf, DevTools, email SMTP, reCAPTCHA |
| `role_privileges_*.properties` | Role→privilege mappings per module |
| `messages*.properties` | i18n strings for validation errors |

### Testing

Tests in `src/test/` use H2 in-memory DB (`TestDbConfig`) and a test-specific Spring context (`TestIntegrationConfig`). Files matching `*IntegrationTest` and `*LiveTest` are excluded from the default `mvn test` run.

## Development Environment
- OS: Windows 10.0.19045
- Shell: Git Bash
- Path format: Windows (use forward slashes in Git Bash)
- File system: Case-insensitive
- Line endings: CRLF (configure Git autocrlf)
