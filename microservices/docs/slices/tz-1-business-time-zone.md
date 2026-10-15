# TZ-1 — store UTC, render in each tenant's zone

Status: **DESIGN (2026-09-25). USER RULING: Option B — "Store UTC + per-tenant zone"** (Option A, a fixed
Asia/Karachi JVM zone, was offered and declined). Each phase needs its own go-ahead.

## TZ-2 — "today" is the CLIENT's day (2026-10-04) — USER RULING + consent: "go ahead with best practices & standards"
**Trigger.** At 02:38 PKT an expense dated today was refused: *"An expense cannot be dated in the future"*. Every
container runs on UTC, so between 00:00 and 05:00 PKT the server's `LocalDate.now()` is still yesterday. User: *"we
decided it will be displayed as per client side (browser) UI/UX"*, then *"review e2e again 100% and do some R&D and go
ahead with best practices & standards"*.

### R&D — how mature products decide "today"
| Product | Rule | Mechanism |
|---|---|---|
| Odoo | Store UTC; a date field's "today" is the **user's** day | `fields.Date.context_today(record)` reads `context['tz']`, which the web client sends from the browser |
| ERPNext / Frappe | Store in the system zone; the user's zone is used for display | `frappe.utils.nowdate()` + per-user `time_zone` |
| Shopify / Square | Orders and reports are in the shop's zone | The store's zone setting |
| Java guidance (JSR-310, *Effective Java*-era practice) | Never use the JVM default zone for a business decision; inject `java.time.Clock` | `LocalDate.now(clock)`; enforced at build time with **forbiddenapis** (Apache Lucene, Elasticsearch) |

**Chosen:** Odoo's model, which matches your ruling. The browser tells the server its zone on every request, the server works
out "today" in that zone, and storage stays as it is. The server never trusts the client's clock, only its zone, so the
most anyone can shift "today" is the real ±14 h world range.

### Design
```mermaid
sequenceDiagram
  participant B as Browser (main.js)
  participant M as Monolith (BFF)
  participant G as Gateway
  participant S as Service A
  participant T as Service B
  B->>B: cookie myplus_tz = Intl…resolvedOptions().timeZone
  B->>M: any request (AJAX, form, page) + cookie
  M->>G: GatewayClient adds X-Client-Tz
  G->>S: passes X-Client-Tz (not an identity header)
  S->>S: TenantClock.today() = LocalDate.now(clock in X-Client-Tz)
  S->>T: GatewayIdentityForwarding copies X-Client-Tz
  Note over S,T: No request (relay, scheduler) → app.tz.default-zone (Asia/Karachi)
```
| Standard | Applied as |
|---|---|
| Single source of truth for "today" | `TenantClock.today()` / `thisMonth()` (common-security). The monolith uses `ClientZone.today()`, read from the same cookie |
| Testable time (inject a Clock) | `TenantClock.useClock(Clock)` for tests; production uses `Clock.systemUTC()` |
| Validate input, never guess | An unparseable zone is ignored, so the default zone applies |
| Prevent regression at BUILD time | **forbiddenapis** in service-parent: `LocalDate#now()` and `YearMonth#now()` fail `mvn package`. The monolith has a JUnit guard against `toISOString()` dates in browser scripts |
| One browser helper (DRY) | `localIsoDate(d)` in main.js replaces 9 UTC `toISOString()` dates and 2 private copies |
| Activity times follow the viewer's zone (TZ-1 ruling) | platform.js "x ago" and stamp use local components |

### Blast radius (Rule 0, counted)
- **Services (measured after the sweep):** 149 `LocalDate.now()` lines, as follows.
  - 8 are comments (left as they are).
  - **140** code sites in 11 services became `TenantClock.today()`; **5** `YearMonth.now()` became `thisMonth()`. That is 72 files: business 60, education 42, finance 15, expense 6, inventory 5, marketplace 5, agriculture 4, pharma 3, analytics 2, catalog 2, appointment 1.
  - The gateway demo-quota key is now explicit `LocalDate.now(ZoneOffset.UTC)`.
  - `PricingContext.of()` had 0 callers and was removed.
  - forbiddenapis needed an explicit `<phase>process-classes</phase>`. Without it the check silently never ran on compile or package. A probe class proved it red, then green.
- **Monolith:** 12 `LocalDate.now()` (on 11 lines) (AppUtil defaults, FeeVoucherDTO) → `ClientZone.today()`. Outbound: GatewayClient (26 callers) plus 3 public header builders (storefront ×2, appointment public) add `X-Client-Tz`.
- **Browser:** 9 UTC dates (business.js 4, stock-count 1, education.js 4) plus 2 UTC activity stamps (platform.js). driver-settlement.js and installment.js already use local dates; their private copies move to main.js.
- **Out of scope:** 425 `LocalDateTime.now()` instants. They are correct UTC instants inside the JVM, and how they are stored and displayed is TZ-1 P1/P3. One known edge: a void or return checks the period lock with the sale's UTC date (`dated.toLocalDate()`), until P3.

### Gate — `cypress/e2e/platform/tz-2-client-today.cy.js`
The gate is deterministic at any hour. It picks a zone whose date differs from UTC's date right now: Pacific/Kiritimati (UTC+14) when the UTC hour is 10 or later, else Pacific/Pago_Pago (UTC−11).
1. Header path (gateway): an expense dated the client's today is accepted, and the client's tomorrow is refused. On Kiritimati this is the reported defect; on Pago_Pago it is the reverse (old code accepted the client's tomorrow).
2. An undated expense is dated the client's today.
3. No zone, or an invalid zone, falls back to Asia/Karachi.
4. Cookie path (monolith → gateway → expense): the same as case 1, through the screen's own endpoint.
5. Browser: with `cy.clock` at 02:30 PKT, Receive payment and Pay supplier pre-fill the local day, not yesterday.
6. The trial balance still balances. Every voucher is voided and Expense management is restored in `after()`.

## P1 progress (paused 2026-09-26)
Written, NOT enabled: `common-security` `RenderZone`, `RenderZoneFilter`, `RenderZoneWebConfig` (web-layer-only
converter), `UtcDefaultTimeZone`; `RenderZone.toDisplay/fromDisplay` in business `AppUtil` (7 sites) and the
education/welfare formatters. Tests: `RenderZoneTest` 7/7, `UtcDefaultTimeZoneTest` 2/2, common-security 33/33.
**⚠ All of it is behind switches that default OFF** — `app.tz.pin-utc` (JVM pin, read after config data loads) and
`app.tz.render-zone.enabled` (filter + converter). A peer session caught that the pin was first written UNGUARDED in
spring.factories: it would have changed the JVM zone of every service (and every `mvn test` on the PKT host) at
the next rebuild while this work was described as "paused and inert". Remaining for P1: the 17 `.toString()`
sites, the auth `tz` claim, the monolith `X-Render-Tz` header, the viewer-zone helper, the receipt-time gate —
then switch both properties on together, deliberately.

## 0. The fact that shapes the plan
**Inside the JVM the app already holds TRUE UTC.** A value stored as 16:53 at +05:00 is read by Hibernate as 11:53
in the UTC JVM — which IS the UTC time of that sale. So the visible defect is purely at the RENDERING edge, and the
storage change is a separate, internal step. That lets the fix ship in the safe order:

| Phase | What | Data change | Risk |
|---|---|---|---|
| **P1 Tenant zone + display** | `org.timezone` per tenant (IANA id, default `Asia/Karachi`), carried in the JWT → `AuthenticatedUser.zoneId`, published to the page as `window.TENANT_TZ`; ONE shared browser formatter (`main.js`: `fmtDateTime` / `fmtDate`, `Intl.DateTimeFormat` with `timeZone`) treating zone-less server strings as UTC; the **23 hand-slicing sites in 11 files** switched to it; server-built text (notifications/SMS with a time) formatted in the tenant zone | none | low — **fixes the receipt** |
| **P2 Business dates** | an injectable `TenantClock` (the java.time `Clock` pattern) — `today()` / `now()` in the request's tenant zone; the **133 `LocalDate.now()`** classified one by one (business date → tenant today; technical stamp → UTC); report `from/to` dates → a UTC range `[from 00:00, to+1 00:00)` in the tenant zone; GL posting date and period lock from tenant today | none | medium — fixes the **00:00-05:00 GL date** and date filters |
| **P3 Store UTC** | JDBC `connectionTimeZone=UTC` in every service URL (config-server, application.yml, ECS); per-service Flyway migration shifting every **DATETIME** column −5 h (**293 columns, 16 databases**; 0 TIMESTAMP columns; the **83 DATE columns are NOT shifted** — a due date is a calendar day, not an instant), idempotent via a per-schema marker; native SQL using `NOW()` / `CURDATE()` / `DATE(col)` reviewed (the session zone becomes UTC) | **yes, all timestamps** | **high** — needs a maintenance window: services stopped, migrate, switch URLs, start; a half-switched fleet would write both zones |
| P4 Onboarding other zones | zone picker in Configuration, validation, "9 am tenant time" for reminders | none | low |

### User rule (2026-09-25): WHOSE zone
Business documents and business days follow the **shop's (location's) zone**, whoever views them — receipts,
invoices, credit notes, challans, reports, day/shift close, ledger, due dates, "today". Activity-style times (audit
log, last login, sessions, notifications) follow the **viewer's zone** (browser, overridable per user). Where the two
differ, show both: `16:53 PKT · 7:53 AM your time`. Zone resolution: **location (store) → organisation → Asia/Karachi**.
(Market check: Shopify/Square/Xero = store zone for business data; Odoo = user zone for display, plain dates for
accounting; Gmail/Slack = viewer zone for personal activity.)

### P1 approach — REVISED after measuring (needs the user's OK)
The browser reads timestamp fields in **442 places across 47 files** (the first count of 23 caught only code that
slices strings; most grids print the field raw, e.g. `obj.updated`). Editing 442 call sites — and every future
screen — is how a fix silently misses places. The browser talks ONLY to the monolith, which already proxies every
call, so the conversion belongs there — the **edge-translation (BFF) pattern**:
- **Out:** in the monolith's proxy response path, every value matching a strict ISO LocalDateTime
  (`yyyy-MM-ddTHH:mm[:ss[.f]]`, no offset) is converted **UTC → business zone**. `LocalDate` values (10 chars — due
  dates, birthdays) are never touched. Screens keep their string handling; they simply receive local time.
- **In:** request bodies/params carrying a LocalDateTime (appointment start, a typed datetime) are converted
  **business zone → UTC** before forwarding, so a round trip is exact.
- **Zone at the edge:** a `tz` claim in the JWT (organisation zone from a new `org.timeZone` setting, default
  `Asia/Karachi`, owned by auth like `org.shape`) → every service and the monolith know it with **no extra call**.
  The location (store) override (P1b) is resolved in business-service for documents of a given store (receipt
  payload carries `timeZone`).
- **Pinned server clock:** every JVM set to UTC programmatically at startup (one shared initializer), so services
  run the same on the Windows host (today: PKT) and in Docker (UTC). Without this the edge cannot know what zone
  an unmarked value is in.
- **Viewer zone:** a small client helper converts business → viewer for the few activity screens and shows both.

P1 and P2 are correct whatever the storage zone is, because the driver converts consistently; P3 changes only what
the database holds. A tenant in another zone is correct after P1 + P2 for everything it SEES and DATES.

---

## (Superseded) original review and Option A
Reported: the receipt prints **11:53** for a sale rung at **16:53** Pakistan time (INV-000054, org 13).

## 1. What is actually happening (verified, not inferred)

| Layer | Setting | Effect |
|---|---|---|
| MySQL (Docker `myplus-mysql`) | `@@time_zone = SYSTEM` = UTC | server clock UTC |
| JDBC, every service | `connectionTimeZone=%2B05:00&forceConnectionTimeZoneToSession=true` (the DB timezone standard) | the session is +05:00; **stored values are Pakistan wall-clock** — INV-000054 `dated` = 16:53:10 ✅ |
| JVM, every container | **no zone set** — `eclipse-temurin:21-jre-alpine`, no `TZ`, no `-Duser.timezone` | **JVM default = UTC** |
| Hibernate 6 | binds `LocalDateTime` through `java.sql.Timestamp` in the JVM zone | read: 16:53 +05:00 → instant → **11:53 UTC** local time |

So every timestamp any service RETURNS is 5 h early (receipts, grids, reports, audit views), and every
`LocalDate.now()` / `LocalDateTime.now()` in the code is UTC. Locally-run services (Windows host, PKT) never showed
it — **only the containers** (dev Docker AND the production VPS, which runs the same compose) are wrong.

## 2. Blast radius (Rule 0 — counted)
- **481 clock reads** in `*/src/main`: 133 `LocalDate.now()`, 348 `LocalDateTime.now()` — all UTC in Docker today.
- **12 GL postings** date the journal with `LocalDate.now()`: a sale between 00:00 and 05:00 PKT is posted to the
  ledger on the **previous day**; period-lock checks use the same wrong date.
- **Scheduled jobs: 0 with a cron time** (all fixed-delay) → no job changes its run time.
- **Stored data: unaffected.** Every value written through JDBC was converted to +05:00 on the way in, so the
  database already holds Pakistan time. Fixing the JVM zone changes what the APP sees, not what is stored.
- **AWS (Terraform `ecs.tf`)**: `SPRING_DATASOURCE_URL` carries NO timezone parameter, so the driver would use the
  JVM zone (UTC) and store UTC — the opposite of the VPS. Setting the JVM zone makes both consistent.

## 3. Design — Option A (recommended): one business zone for every JVM

```mermaid
flowchart LR
  ENV["APP_TIMEZONE (default Asia/Karachi)"] --> JVM["-Duser.timezone in JAVA_TOOL_OPTIONS, all 20 services"]
  JVM --> NOW["LocalDate.now / LocalDateTime.now = Pakistan time"]
  JVM --> READ["read 16:53 +05:00 → 16:53 ✔"]
  DB["JDBC connectionTimeZone +05:00 (unchanged)"] --> READ
```
1. `microservices/docker-compose.yml`: one anchor `x-jvm-opts` = the existing
   `-XX:MaxRAMPercentage=60.0 -Xss512k` **+ `-Duser.timezone=${APP_TIMEZONE:-Asia/Karachi}`**, referenced by all 20
   `JAVA_TOOL_OPTIONS` lines (they are identical today — DRY). `.env.example` documents `APP_TIMEZONE`.
2. `infrastructure/terraform/ecs.tf`: the same `JAVA_TOOL_OPTIONS` in the shared task-definition environment.
3. **Invariant, written into the DB timezone standard:** the JVM zone and `connectionTimeZone` must name the
   SAME offset. Pakistan has no DST, so `Asia/Karachi` ≡ `+05:00` all year.
4. **No data migration.** Stored rows are already Pakistan time.
5. **Not repaired (reported):** GL entries already dated with the UTC date for sales between 00:00-05:00 PKT —
   the posting date is a day early. A read-only count is in §5; restating journals is a separate owner decision
   (the COGS precedent was not to restate).

**Option B (the SaaS end-state, not now):** store UTC, stamp a zone per tenant, render in the tenant's zone. Needed
the day a tenant outside Pakistan signs up; a multi-service rewrite plus a data migration. A is the correct fix for
the platform as it is, and does not make B harder.

## 4. Deploy
Every Java container must be recreated to pick up the new `JAVA_TOOL_OPTIONS` (`docker compose up -d` recreates
only services whose config changed — here, all 20). The user controls restarts.

## 5. Tests (the gate)
- `mvn test`: none of the logic changes; existing suites must stay green (they run on the PKT host already).
- Cypress `receipt-time.cy.js` (new): make a sale, then `/getReceipt` `dated` must be within 2 min of the browser's
  own clock (the host runs PKT) — **RED today** by exactly 5 h; plus INV-000054 (org 15) reads **22:27**, its stored
  value, not 17:27.
- Container check after deploy: `docker exec <svc> java -XshowSettings:properties -version | grep user.timezone`
  = `Asia/Karachi` on all 20.
- Read-only: count GL entries whose `entry_date` is one day before their sale's PKT date (the §3.5 residue).

### Clinic (HMS) instants — add to P1/P3 (2026-10-10)

Seen in the HMS gates: every clinic time is shown in the server's UTC (a note written at 12:02 PKT reads 07:02; "Sent to the pharmacy" at 13:07 reads 08:07; the pharmacy list Date likewise — the last predates HMS). Sites: clinical-service `LocalDateTime.now()` in ConsultService (startedAt, completedAt, updatedAt, rxSubmittedAt), ClinicalNote.createdAt, QueueService/TokenWriter (called/parked/completed/cancelled), PatientService, RxTemplateService; pharma `Prescription.prePersist` createdAt. Deliberately NOT patched inside HMS: a clinic-only conversion would be a second way to show time; it follows the platform rule chosen for P1/P3.
