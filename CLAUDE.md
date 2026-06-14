# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
