# Slice 15 — Student CSV import (education-service + UI)

Status: **DONE** ✅. Built **fresh** for any org (not a monolith port). Follows
`../ARCHITECTURE-MULTITENANCY.md`; org-scoped.

## Test — all green
- Upload `students-import.csv` → created 2 (org-scoped, grade/guardian resolved by name)
- Re-upload → skipped 2 with row errors (dup enrollNo)
- Auto-dues: imported students got `OPENING_DUE` rows (fee 500) per config
- Cypress headed: student-import.cy.js 2/2; full education suite 52/52

## Document — what & why

Bulk-onboard students from a spreadsheet. A clean import screen: download a template, upload a filled
CSV, and get a **summary** (created / skipped / row errors). Each created student is org-scoped and, if
the org's `FeeSetting.autoRegisterDues` is on, gets its opening due (reusing `FeeService`).

## Design

### CSV format (documented; template downloadable from the UI)
Header row (case-insensitive), columns:
```
enrollNo,name,gradeName,gender,guardianName,mobile,status
```
- `enrollNo`, `name` required. `gradeName` resolved to the org's grade (by name, case-insensitive);
  `guardianName` resolved to the org's guardian (optional). `status` defaults to ACTIVE.
- Simple parser (comma-separated, trims quotes); rows with embedded commas/quotes are out of scope v1.

### education-service — `POST /impStudents` (multipart `file`), org-scoped
- Parse rows; for each:
  - skip + record error if `enrollNo`/`name` missing;
  - skip if a student with that `enrollNo` already exists in the tenant (dup);
  - resolve `gradeId` (org grades by name) / `guardianId` (org guardians by name);
  - create `Student` stamping `organization_id` + `user_id`, `enrollDate = today`;
  - if `FeeSetting.autoRegisterDues`, call `FeeService.registerOpeningDue`.
- Return `GenericResponse` `object = {created, skipped, errors:[ "row N: ..." ]}`.

### monolith
- `/impStudents` proxy already forwards multipart (no change). Add nothing.

### UI (educationDashboard, in `StudentDiv`)
- An **Import Students** panel: **Download template** (CSV built client-side), file input, **Import**
  button → AJAX `FormData` POST to `/impStudents` → show the summary (created/skipped + errors list).
- Replaces the old hidden full-page-submit import form.

## Implement (checklist)
- [ ] education-service `StudentController.impStudents` (multipart, parse, org-scoped create, summary)
- [ ] UI: import panel in StudentDiv + JS (template download, AJAX upload, summary); escHtml
- [ ] (user) rebuild education-service + monolith (monolith proxy already exists)

## Test
- [ ] upload a small CSV → students created (org-scoped, organization_id stamped); summary correct
- [ ] dup enrollNo skipped; missing required → row error
- [ ] auto-dues created when enabled
- [ ] Cypress (headed): upload a fixture CSV → summary shows created count
