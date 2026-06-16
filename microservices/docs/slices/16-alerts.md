# Slice 16 — Alerts module (education-service + UI, real email)

Status: **DONE** (user consented A+B). Built **fresh**, org-scoped. Real email via SMTP (B).
Verified live: Cypress `alerts.cy.js` 3/3 passing (import→list→send real email; create system alert→list).
Test/admin recipients always included: `uncer_sh@yahoo.com`, `maxtheservice`.

## Document — what & why

Two alert features the org uses to communicate:
- **System Alerts** (`AlertsDiv`): alert definitions to consumers (Students/Guardians/Employees/Drivers/
  Public/All) — type, channels, period, heading, message, signature, validity, status. CRUD + Send.
- **Public Alerts** (`PADiv`): import a contacts CSV, list them, compose, and Send (email).

Every send records who/when (analytics: staff communication activity).

## Design

### Mail (B)
- education-service: add `spring-boot-starter-mail`; `spring.mail.*` in `education-service.yml`
  (reuse the working Gmail app password from `auth-service.yml`).
- `EmailService.send(subject, htmlBody, recipients)` — sends per-recipient (best-effort), returns a
  sent/failed count. **Always appends the two admin recipients** so sends are observable.

### Data (org-scoped)
- `Alerts` += `organization_id`; `AlertChannel` (public contacts: channel=email/mobile, cn=value, ut)
  += `organization_id`. Both get `findScoped(org,uid)`.

### education-service — flat endpoints (GenericResponse), org-scoped
| Endpoint | Purpose |
|----------|---------|
| `GET /getUserAlerts`, `/getAllAlerts` | list system alerts (org-scoped) |
| `POST /addAlerts` | create/update an alert (multi-selects joined to CSV); stamps org+user |
| `POST /deleteAlerts` | delete by checked ids |
| `POST /sendAlerts` | email the alert to resolved consumer emails (students/guardians/staff) + admins; mark Sent |
| `POST /importCSV` | parse contacts CSV → `AlertChannel` rows (org-scoped) |
| `GET /getUserPA` | list public-alert contacts (org-scoped) |
| `POST /sendPA` | email composed message to contact emails + admins |

### monolith
- `AlertsController` proxy already forwards all of the above (no change).

### UI
- `AlertsDiv`: wire `addAlerts` (Submit), `deleteAlerts`, list `getUserAlerts` into `#tableAlerts`,
  `sendAlerts` (Send).
- `PADiv`: wire `importCSV` (csvFile + Import), list `getUserPA` into `#tablePA`, `sendPA` (Send).

## Implement (checklist)
- [x] pom mail starter + `education-service.yml` spring.mail
- [x] `Alerts` / `AlertChannel` organization_id + `findScoped`
- [x] `EmailService`
- [x] flat `AlertController` (8 endpoints, org-scoped, send via EmailService + admin recipients)
- [x] UI: AlertsDiv + PADiv render via the **shared `loadDataTable()` path** (getAll "Alerts"/"PA"),
      not a separate manual renderer — fixed the legacy branches' stale field names to match the new
      `alertMap`/`channelMap` JSON. Slice-16 JS owns create/delete/send/import handlers only.
- [x] (user) rebuild education-service + monolith (monolith JS picked up via DevTools)

## Test
- [x] addAlerts → list shows it (DataTable, org-scoped); deleteAlerts removes it
- [x] importCSV → getUserPA lists contacts (org-scoped)
- [x] sendPA → real email to the two admin recipients; response reports "Sent to X, failed Y"
- [x] Cypress (headed) `alerts.cy.js` 3/3: import→list→send; system-alert create→list. Assertions
      wait on intercepted responses + rendered rows (not alert() timing).

## Deferred cleanup
- Unused REST scaffolding in education-service (`AlertsController`/`AlertChannelController` +
  `AlertsService`/`AlertChannelService` under `/api/education/alert(s|-channels)`) — compiles but
  not used by the monolith proxy. Remove on a later pass (needs consent).
