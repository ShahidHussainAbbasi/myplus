# Slice 18 — Appointment module dashboard (own dashboard + role-based landing)

**Status: IMPLEMENTED — pending build + runtime verify.** Decisions: userType `APPOINTMENT`; list
enriched in appointment-service; verify P3/P4 first (separate). Built: appointment-service DTO/service
name-enrichment; monolith `/loadHospitals` + dashboard controller; `appointmentDashboard.html` rebuilt
(snav + Hospital/Doctor/Appointments cards, self-contained JS); `appointment.html` restyled + booking
envelope fix (`AppointmentController` now sets `status=SUCCESS/FAILURE` properly).
**Follow-ups:** (a) auth-service must issue `userType=APPOINTMENT` for clinic users so the success
handler auto-lands them on `/appointmentDashboard`; (b) anonymous public booking can't list org-scoped
hospitals (needs a per-clinic public link / hospital-id param) — a future "public clinic page" slice.
Branch: `feature/monolith-myplusdb-removal`. Builds on slice 17 (appointment-service) + P3 proxy cutover.
Standing directive: [[feedback-improve-ui-with-move]] — modernise the monolith UI as the move touches it.

## 1. Document — what & why
After P3 the appointment screens are legacy Thymeleaf pages that the move left **broken**:
- `hospital.html` — state/city are empty `required` AJAX selects (geo removed) → can't submit.
- `appointment.html` — JS checks `data.status=="SUCCESS"`, but the proxy puts text in `status` → success never shows.
- `appointmentDashboard.html` — table reads `clientlist.patient.name`/`doctor.name` (nested entity) but data is now a flat `Map` → blank rows.

Goal: give the appointment module **its own dashboard** like education/business, with the app's standard
look (theme.css snav + dashboard.css cards), and route appointment users to it on login.

## 2. Role-based landing (already generic)
`MySimpleUrlAuthenticationSuccessHandler.determineTargetUrl` already returns `/<userType.toLowerCase()>Dashboard`.
So a user whose JWT `userType=APPOINTMENT` lands on `/appointmentDashboard` automatically — **no handler change**.
Need: auth-service issues `userType=APPOINTMENT` for appointment/clinic users; `/appointmentDashboard` stays
behind `LOGIN_PRIVILEGE` (already authenticated). (Decision U: confirm the userType string `APPOINTMENT`.)

## 3. Design
### Dashboard `/appointmentDashboard` (AppointmentDashboardController → view `appointmentDashboard`)
- Shell: `header-css` / `header` / `header-js` fragments; off-screen `#apptNav` select + `snav` dropdowns
  (Register → Hospital / Doctor; Appointments) using the shared `snavToggle`/`snavGo` (inline) + a small
  section-toggle (hide all `.formDiv`, show `#<value>`), self-contained (no education.js coupling).
- Sections (cards):
  - **HospitalDiv** — name, phone, email, country (static `${countries}`), **state/city free-text**,
    hours; posts `/registerHospital`.
  - **DoctorDiv** — hospital (AJAX `/loadHospitals`), name, speciality, email, mobile, address,
    availability, day/time range, appointmentOfferType/Value; posts `/registerDoctor`.
  - **AppointmentsDiv** — table (Patient, Token, Phone, Doctor, Hospital, Date) from `/loadAppointments`
    rendered client-side (DataTables).
- Model: `countries` (AppUtil static map). Lists load via AJAX.

### appointment-service — enrich the list (names, not just ids)
`AppointmentDTO` carries only `doctorId/patientId`. Add `patientName`, `patientPhone`, `doctorName`,
`hospitalName` to the list/booking responses (service resolves them) so the dashboard + booking show
names. (Small: enrich `AppointmentService.list()` mapping; org-scoped lookups already exist.)

### Monolith proxy additions
- `HospitalController` `/loadHospitals` (JSON `[{id,name}]`) for dropdowns.
- `AppointmentDashboardController.loadAppointments` already returns the JSON list (flat map) — keep;
  table reads the enriched flat keys.

### Public booking `appointment.html`
Restyle to the theme (header fragments + card), fix the `status`/`message` success bug, keep
hospital→doctor→details→book flow against the public proxy.

## 4. Implement — checklist
- [ ] appointment-service: enrich `AppointmentDTO` + `AppointmentService.list()/bookPublic()` with
      patient/doctor/hospital names (org-scoped).
- [ ] monolith: `/loadHospitals` JSON; `AppointmentDashboardController` serves the dashboard (countries).
- [ ] `appointmentDashboard.html` rebuilt: snav + 3 card sections + inline JS (register + load + table).
- [ ] `appointment.html` restyled + success-bug fix.
- [ ] both compile (user builds).

## 5. Test
- Cypress `appointment.cy.js` (headed): login (appointment user) → register hospital → register doctor →
  appointments table renders; public booking books anonymously and shows the token.
- P4 `console`/change-password spec (separate): change-password delegates; 2FA setup→verify→disable.
- Regression: education/business dashboards unaffected.

## Decisions at the gate
- **U — userType string** for appointment users (`APPOINTMENT` → `/appointmentDashboard`). Recommended.
- **List enrichment** in appointment-service (names) — recommended (vs. extra monolith lookups).
- **Sequencing** — build slice-18 now, then verify on the new dashboard (vs. verify P3/P4 on the current
  screens first).
