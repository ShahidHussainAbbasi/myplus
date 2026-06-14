# Slice 14 — Fee voucher / ledger / report (education-service + UI)

Status: **DONE** ✅. Built **fresh** for any org (not a monolith port), with faithful multi-month aging
+ runtime fee config + registration-time dues. Follows `../ARCHITECTURE-MULTITENANCY.md`; org-scoped;
feeds the income side of the expense/income + analytics goal ([[project_org_analytics_goal]]).

## Test — all green
- getFeeSetting defaults; saveFeeSetting persisted (GUARDIAN_VOUCHER, dueDay 5)
- Registration auto-dues: new student ENR-002 → opening due registered
- Aging voucher ENR-002: monthlyDue 500 × 1 + prevBalance 0 = 500
- Guardian-consolidated voucher (guardianId=1): ENR-001 + ENR-002 = 1000
- Ledger ENR-001: history + totals (paid 5000); Report totals fee 5500 / paid 5000 / balance 500 / count 2
- Cypress headed Chrome: fee.cy.js 4/4; full education suite 50/50

## Document — what & why

Three related fee features on top of the org-scoped `FeeCollection` (slice 11):
1. **Fee Report** — the org's collection report: filter by scope + date range, see rows + **totals**
   (fee, discount, other dues, paid, balance). This is the income dataset the analytics dashboards roll up.
2. **Fee Ledger** — one student's payment history (chronological), printable.
3. **Fee Voucher** — a payable slip for a student: what's due now.

The legacy screens computed dues inside tangled jsPDF code with a nested `{object, collection}` shape.
We replace that with clean flat endpoints + a clear UI, and export via the existing DataTable buttons
(CSV/Excel/PDF/Print) instead of bespoke PDF drawing.

## Design

### Fee Report — `POST /loadFR` (or GET with params), org-scoped
- Filters: `by` (ALL | STUDENT | GUARDIAN | CLASS | CAMPUS), `id` (the selected entity, when not ALL),
  optional `fromStr`/`toStr` (dd-MM-yyyy) on payment date.
- Returns `GenericResponse` with `collection` = flat rows
  `{enrollNo, studentName, gradeName, schoolName, paymentDateStr, payee, receivedBy, fee, discount,
  otherDues, otherDuesDesc, dueAmount, feePaid, balance}` and `object` = totals
  `{fee, discount, otherDues, dueAmount, feePaid, balance, count}`.
- Source: `FeeCollection.findScoped(org,uid)` filtered by date + (resolve enroll-nos for the chosen
  student/guardian/class/campus from org-scoped Students). Names resolved per row.

### Fee Ledger — `GET /loadFL?enrollNo=`, org-scoped
- Returns the student's `FeeCollection` rows (chronological) + a header `{enrollNo, studentName,
  gradeName, schoolName, guardianName}` + totals. `collection` of payment rows; `object` = header+totals.

### Fee Voucher — `GET /loadFV?enrollNo=` (or `?guardianId=`), org-scoped — MULTI-MONTH AGING
- `monthlyDue = gradeFee + vehicleFare − discount` (discount `%` or fixed, from the student's Discount).
- `dueMonths = months between (last payment date, else enroll date) and now` (min 1 when unpaid; 0 when
  the current period is already paid). **Faithful aging:** `totalDue = monthlyDue × dueMonths +
  previousBalance + otherDues` (previousBalance = latest `FeeCollection.dueBalance` for the enroll no).
- **Payment mode (from FeeSetting):** when `GUARDIAN_VOUCHER` (or `BOTH` + `guardianId` given), the
  voucher **consolidates all the guardian's students** (sum of each child's aged due) into one slip;
  when `INDEPENDENT`, the voucher is per student. Returns the breakdown + per-student lines.

### Fee configuration — runtime, per org (NEW)
`FeeSetting` (org-scoped, one row per org, editable at runtime):
- `feeCycle` (MONTHLY), `dueDay` (1–28), `agingEnabled` (multi-month aging on/off),
- `autoRegisterDues` (on student registration, create the initial due record so the student shows as
  owing and reports/aging work),
- `paymentMode` = `GUARDIAN_VOUCHER` | `INDEPENDENT` | `BOTH` (how guardians pay).
- Endpoints `GET /getFeeSetting` (returns existing or defaults) + `POST /saveFeeSetting` (upsert one
  per org, stamps org + user). UI: a **Fee Settings** panel the admin can change anytime.

### Registration hook (NEW)
`StudentController.addStudent`: after save, if the org's `FeeSetting.autoRegisterDues` is on, create the
student's initial due `FeeCollection` record (`fee = monthlyDue`, `feePaid = 0`, `dueBalance = monthlyDue`,
`en = enrollNo`, org + user stamped). Voucher aging then accumulates from there. No-op if the student
has no enroll no or dues already exist.

### education-service
- `FeeReportController` (or extend `FeeCollectionController`): `loadFR`, `loadFL`, `loadFV` — all
  org-scoped; reuse `StudentRepository`/`GradeRepository`/`SchoolRepository`/`DiscountRepository`.
- Add `FeeCollectionRepository.findByOrganizationIdAndEn(org, en)` (ledger / previous balance).

### monolith
- `FeeCollectionController` proxy: add `loadFR` (POST form), `loadFL` (GET), `loadFV` (GET) passthroughs.

### UI (educationDashboard `FRDiv` + `FVDiv`)
- **FRDiv (Report):** scope dropdown + id + from/to dates → Load → DataTable with rows + a **totals**
  footer; keep CSV/Excel/PDF/Print buttons. (income view)
- **FVDiv (Voucher/Ledger):** enter/select student → two actions: **Show Ledger** (history table) and
  **Generate Voucher** (a clean printable card of dues). escHtml everywhere.

## Implement (checklist)
- [ ] `FeeCollectionRepository.findByOrganizationIdAndEn`
- [ ] education-service: `loadFR`, `loadFL`, `loadFV` (org-scoped, totals, dues computation)
- [ ] monolith: proxies for loadFR/loadFL/loadFV
- [ ] UI: rebuild FRDiv (report+totals) and FVDiv (ledger + voucher); DataTable export kept
- [ ] (user) rebuild education-service + monolith

## Test
- [ ] `/loadFR` returns rows + correct totals; org-scoped; date filter works
- [ ] `/loadFL?enrollNo=` returns that student's history (org-scoped)
- [ ] `/loadFV?enrollNo=` returns computed dues (gradeFee+vehicle−discount+prevBalance)
- [ ] cross-tenant isolation
- [ ] Cypress (headed): report loads with totals; voucher shows dues; ledger lists payments
