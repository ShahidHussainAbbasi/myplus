# Slice 13 — Attendance marking (education-service + UI)

Status: **DONE** ✅. Deferred feature, built **fresh** for any org (not a monolith port) — see the
"build fresh, not port" rule. Follows `../ARCHITECTURE-MULTITENANCY.md`; org-scoped like every domain.

## Document — what & why

Slice 10 made attendance *records* org-scoped and readable, but there was no way to **mark** attendance.
This adds marking with a **class-roster UX**: the user picks a class + date, sees that class's students
in one list, marks each Present / Absent / Late (with a one-click "Mark all present"), and saves the
whole roster in a single request. This replaces the legacy per-keystroke ajax with an efficient,
clear screen any school can use.

## Design

### UX (educationDashboard → Attendance / `ADiv`)
1. Select **Class** (grade dropdown, org-scoped) and **Date** (defaults to today).
2. Click **Load Roster** → table of that class's students: enroll no, name, and a status control
   (Present / Absent / Late) + optional time-in/out + remark. Pre-fills existing marks for that date.
3. **Mark all present** button; per-row override.
4. **Save Attendance** → one POST with the roster; success toast; table reflects saved state.

### education-service — flat endpoints (GenericResponse), org-scoped
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/getClassRoster?gradeId=&dateStr=` | GET | Students of the class (org-scoped) joined with any existing attendance for that date → `collection` of `{enrollNo, studentName, gradeId, gradeName, status, timeInStr, timeOutStr, remark}` (status defaults to "Present" when none) |
| `/markAttendanceBulk` | POST (JSON) | `{gradeId, dateStr, rows:[{enrollNo, status, timeInStr, timeOutStr, remark}]}` → upsert one `Attendance` row per student **per date** (no duplicates); stamps `userId` (audit) + `organizationId` (tenant) |
| `/getUserStudentMap` | GET | org-scoped map `enrollNo -> {name, gradeId, gradeName}` (kept for client lookups) |

- **Upsert key**: (organization_id, enroll_no, date) — re-saving a date updates, never duplicates.
  Add `AttendanceRepository.findScopedByEnAndDate(...)`; needs a `date` (LocalDate) on lookups — the
  existing `dt` (LocalDateTime) is the mark time; add comparison on its date part, or store a `LocalDate`
  marking day. **Decision:** add `att_date` (LocalDate) to `Attendance` for a clean upsert key.
- Roster read scopes students via `StudentRepository.findScoped` filtered by `gradeId`; attendance via
  `AttendanceRepository.findScoped` for the date.

### monolith
- `AttendaceController` proxy: add passthroughs for `/getClassRoster`, `/markAttendanceBulk`,
  `/getUserStudentMap` (GET/POST via `EducationRestClient`, same as existing education proxies).
- JSON POST: `markAttendanceBulk` takes a JSON body — proxy forwards it (extend `EducationRestClient`
  to POST JSON, or accept form + build JSON). **Decision:** send as form fields the JS already builds,
  OR add a JSON passthrough. Simpler: JS posts JSON to the monolith; monolith proxy forwards JSON.

### Data
`Attendance` gains `att_date` (LocalDate) — the marking day (clean upsert key), in addition to `dt`
(timestamp). `organization_id` already added in slice 10.

## Implement (checklist)
- [x] `Attendance.attDate` (LocalDate) column
- [x] `AttendanceRepository`: `findFirstByOrganizationIdAndEnAndAttDate` (upsert key) + `findByOrganizationIdAndAttDate`
- [x] education-service `AttendanceController`: `/getClassRoster`, `/markAttendanceBulk`, `/getUserStudentMap` (org-scoped, upsert, stamps org + user_id)
- [x] monolith `AttendaceController` proxies + `EducationRestClient.postJson` passthrough
- [x] UI: `ADiv` rebuilt as roster screen + JS (load roster, mark all, save); escHtml
- [x] (user) rebuild education-service + monolith

## Test — all green
- [x] `/getClassRoster` → SUCCESS, Ali Khan with default status "Present"
- [x] `/markAttendanceBulk` saved; re-mark same day → **upsert** (single row updated Present→Late, no duplicate)
- [x] DB: `attendance(enroll_no=ENR-001, status=Late, att_date=2026-06-06, user_id=51, organization_id=1)` — org + who-marked captured for analytics
- [x] Cypress (headed Chrome, slowed): roster loads → mark all present → save → "saved"; full education suite 46/46
