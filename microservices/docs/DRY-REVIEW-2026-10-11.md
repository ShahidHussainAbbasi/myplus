# DRY review — whole code base (2026-10-11)

Asked by the client: "review DRY pattern end to end — no duplication of code and usage in the whole code base".
Measured, not eyeballed: **jscpd 4.0.5** (copy-paste detector; verbatim clones ≥ 60–70 tokens) over the three code
bases, plus a semantic pass for helpers re-implemented under the same or different names (jscpd cannot see those).
Libraries (jQuery, jsPDF, bootstrap, …), generated sources, `target/` and tests-of-Java were excluded.

## Numbers

| Code base | Files | Clones | Duplicated lines | Share |
|---|---|---|---|---|
| Browser JS (app's own) | 83 | 12 | 111 | **0.53 %** |
| Java — monolith + all services | 1 654 | 544 | 9 600 | **6.47 %** |
| Cypress specs + support | 475 | 436 | 3 708 | **4.04 %** |

## 1. Java — classes copied from service to service (the largest finding)

| Copied class | Duplicated lines | Where it should live |
|---|---|---|
| `SecurityConfig` | 394 | one auto-configured chain in `common-security`, each service adds only its own public paths |
| `GenericResponse` / `ApiResponse` / `PageResponse` | 346 / 123 / 142 | one response envelope in a shared contracts library (two envelopes exist side by side today) |
| `AppUtil` (+ `DateUtils`, `EmptyValidator`, `MobileNumberValidator`, `EmailValidator`) | 291 (+119, 61, 32, 31) | the date pieces are now `BusinessDay` / `RenderZone`; the rest a shared util/validation module |
| `GlobalExceptionHandler` | 281 | `common-security` (or common-web) auto-configured `@RestControllerAdvice` |
| `JpaSettingsStore` + `OrgSetting` + `SettingsController` | 279 + 211 + 55 | the tenant config store as a library; each service keeps its own `org_setting` TABLE (schema per service), not its own copy of the code |
| `OrgDocumentSeq` + `Id` + `Repo` | 80 + 139 + 73 | per-org document numbering as a library (same pattern: shared code, per-service table) |
| `PartyBridgeService` | 161 (education, welfare, pharma) | one bridge in the party client library |
| `XssRequestWrapper` | 53 | `common-security` has it — the monolith copy goes when the monolith is retired |
| client configs (`AuditClientConfig`, `NotificationClientConfig`, `PartyClientConfig`) | 55 / 36 / 35 | auto-configuration in each client library |
| outbox services (`ExpenseOutboxService` ≈ `MarketplaceGlOutboxService`, business/education `GlOutboxService`) | 52 + | `common-audit`'s `AbstractAuditOutbox` pattern extended to a generic outbox relay |

**Not a DRY defect (by decision):** business-service ⇄ monolith pairs (`AppUtil` 259, `GenericResponse` 164,
`DateUtils` 119, `ItemTypeDTO`/`ItemUnitDTO` 113/111) — the monolith's business code is being retired ("build fresh,
not port"; myplusdb removal). They disappear with the monolith, not by refactoring.

**Inside one service** (CRUD controllers that repeat each other): education `Grade/Vehicle/Owner/Guardian/Discount/
Student` controllers (47–76 lines each pair), agriculture `Expense ⇄ Income` (52), business `ItemType ⇄ ItemUnit`
(67 + DTOs 99) — a shared base controller / generic CRUD service per module.

## 2. Browser JS — the same job, written again per file

Verbatim copying is low (0.53 %); re-implementation is not:

| Job | Definitions | Files | Shared helper that exists | Finding |
|---|---|---|---|---|
| translate (`t`, `tr`, `tOr`, `L`, `msg`) | 44 | 39 | `common/i18n.js` `t()` / `tOr()` | per-file wrappers adding the same English fallback |
| HTML escape (`esc`, `escHtml`, `escSR`, …) | 25 | 24 | `common/dom-safe.js` `escHtml` | 21 delegate (harmless aliases); **grid-loading.js** has its own (no `'` escaping); **guardian.js / student-portal.js `esc()` escapes NOTHING** — a null guard named `esc`. Every current use ends in `.text()` (verified: safe today), but the name invites a future `.html()` XSS |
| money formatting (`money`, `num`) | 25 | 24 | `main.js money2` | 23 own implementations with 3 behaviours for blank/NaN (`''`, `'0.00'`, `0`) — the same amount can read differently screen to screen |
| date/time formatting | 21 | 16 | `common/datetime.js` (H6) | B/B2 moved the PARSE to the shared formatter; the display wrappers remain per file |
| URL/context (`ctx`, `url`) | 11 | 10 | global `serverContext` | one-liners |
| module settings screen (`showConfig`, `loadConfig`, `saveConfigToggle`, `resetConfigToggle`) | 4 × 3 | agriculture, education, welfare | `common/settings-form.js` (self-rendering, used by business) | three copies of what settings-form already does |
| `loadDataTable`, `intVal` | 4 / 3 | business, education, welfare, agriculture | — | one DataTables bootstrap in common |
| portal readers `loadHomework` / `loadNotices` | 3 / 3 | education, guardian, student-portal | — | one portal module |

## 3. Cypress — spec-local helpers instead of custom commands

`post` defined in **53** specs, `relogin` 17, `setOf` (switch a member's permission set) 9, time helpers `expected` 8 /
`rowsOf` 6 / `emulateZone` 3 — **the time helpers include ones written in the H6 specs today**. Largest clone pairs:
the HMS specs share their setup blocks (patient → token → visit → prescription, 30–38 lines each), pricing specs pair up
(38–40). Cypress' own guidance: shared steps are custom commands in `support/commands.js`.

## Plan (proposed — each slice behaviour-neutral, gated by the existing specs)

| # | Slice | Size | Risk |
|---|---|---|---|
| D1 | **Security-adjacent first:** the two no-op `esc()` and grid-loading's escaper → `escHtml` from dom-safe.js | small | XSS hardening |
| D2 | One `money()` in `common/` with ONE rule for blank / NaN (client to choose), 23 copies → it | medium | money display |
| D3 | Cypress: `cy.post`, `cy.relogin`, `cy.setPermissionSet`, `cy.emulateZone`, `cy.rowsOf`, `cy.expectLocalTime` → `support/commands.js`; specs migrated | medium | test-only |
| D4 | Java shared library slice: `GlobalExceptionHandler` + `SecurityConfig` base + one response envelope into common-security / a common-web module | large | every service; one at a time |
| D5 | Tenant config store + per-org document numbering as libraries (tables stay per service) | large | Flyway per service unchanged |
| D6 | Translate wrappers → `tOr`; settings screens of agriculture/education/welfare → settings-form.js; DataTables bootstrap → common | medium | UI |
| D7 | Per-module CRUD controller base (education, agriculture, business ItemType/Unit) | medium | per module |
| — | business ⇄ monolith copies: removed by the monolith retirement, not here | — | — |

Standards that apply: "No duplicate functions — common → main.js, module → <module>.js"; "library by default"; "confirm
big rewrites" (D4/D5 are big). Reports: `scratchpad/jscpd/{js,java,cy}/jscpd-report.json`.

## D4/D5 drift check (before design)

How far the copies have drifted decides the shape of the shared code (measured: bodies compared without package and
import lines):

| Class | Copies | Distinct bodies | What actually differs (diffs read) |
|---|---|---|---|
| `SecurityConfig` | 19 | 18 | almost only the service's own public path (`/api/<service>/public/**`); a few add more paths or filters |
| `JpaSettingsStore` | 10 | 10 | **comment wording only** — it is already the SPI adapter onto the shared common-settings engine |
| `OrgSetting` | 10 | 10 | per-service entity (table per service, by design) — field/comment variations to read one by one |
| `GlobalExceptionHandler` | 8 | 5 | 39–158 lines: a common core + service-specific exception mappings |
| `GenericResponse` / `ApiResponse` | 4 / 8 | 4 / 3 | two envelopes side by side |

**Shape:** not a rewrite — each becomes ONE shared class with the service-specific part as a parameter:
`SecurityConfig` → an auto-configured base chain + `app.security.public-paths` property; `JpaSettingsStore` → a generic
`JpaSettingsStoreSupport<E>` in common-settings, each service passing its repository (its table stays its own);
`GlobalExceptionHandler` → a shared core advice + per-service `@ExceptionHandler`s for what only it throws; the response
envelope → one (choice to the client: `ApiResponse` or `GenericResponse`). One service at a time, each with its gates.
