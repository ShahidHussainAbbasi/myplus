# Education Management System — complete programme

**Status: IN DELIVERY — Phase 0 and Phase 1 COMPLETE and Cypress-green; the education review is CLOSED.**
**PHASE 2 IS COMPLETE** (2.1–2.5 all Cypress-green, 2026-08-03). Now in **Phase 3 — parent & student portals**: 3.1 📐 designed, awaiting approval. **3.1 is NOT blocked** — D-4 gates 3.2, and D-2's ordering question was settled by keeping phase order. Produced 2026-07-30; last
updated 2026-08-03. Supersedes `education-feature-gap-analysis.md` (kept as the raw inventory).

| Shipped so far | |
|---|---|
| **Phase 0** | fees → GL, fees → AR, fee credit, branch-scope settings, cryptic-column rename (part), D-3 privilege map |
| **Phase 1** | 1.1 academic year & term · 1.2 examinations · 1.3 marks entry · 1.4 grading scales · 1.5 report cards · 1.6 promotion — **all six green** |
| **Review** | findings A (cross-tenant save takeover), B (fee validation), C (privilege gating), D (analytics perf) fixed; E partly |
| **Phase 2** | 2.1 timetable ✅ · 2.2 substitution ✅ · 2.3 staff attendance & leave ✅ · 2.4 homework ✅ — all green (**2.4 ships without attachments**: they gate on D-5) |
| **Phase 2** ✅ | 2.1 timetable · 2.2 substitution · 2.3 staff attendance & leave · 2.4 homework · 2.5 behaviour log — **all five green** |
| **Now** | Phase 3.1 parent portal — 📐 design, awaiting approval. _(Notification remains the strongest non-phase candidate: 2.2 + 2.4 + 2.5 all want a real send.)_ |

**Goal:** an education management system complete enough for a department of education — every stakeholder,
every department, on one multi-tenant platform.

---

## 0. The rules this programme follows

Non-negotiable. Every slice below is measured against these; they are not restated per slice.

| Rule | Source |
|---|---|
| Multi-tenancy: `organization_id` + `findScoped` NULL-fallback, scoped reads, stamped writes, anti-IDOR by-id | `ARCHITECTURE-MULTITENANCY.md` |
| Compose, don't duplicate — a vertical composes core services, never re-stores their data | `SAAS-BUILD-STANDARDS.md` §1.2 |
| Reuse-first — new code only for genuinely net-new capability | §1.3 |
| One vertical-aware dashboard, white-labelled by user type | §1.4 |
| `BigDecimal(19,2)` money · DTOs at the boundary · saga + outbox for cross-service atomicity · `ApiResponse` | §1.5 |
| **DB standards D1–D8** — Flyway owns every schema; migrations run in `mvn test` on an empty DB; index every scoped column; entity-vs-table diff is never evidence; never drop on inference | §1b |
| **Config standards C1–C4** — a toggle that changes nothing is worse than none; gate-test both halves; safety flags default ON and fail ON; verify the OFF path | §1c |
| Cadence: **Document → Design (3 Mermaid diagrams) → Implement (UI→API→DB) → Test → headed Cypress GREEN → next** | `DESIGN-STANDARD.md`, slice-cadence |
| A slice is not done until its **headed Cypress passes** — the user runs it | Cypress-gate |
| Vertical slices — finish one domain end to end before starting the next | vertical-slices |
| Tests ship with every slice and run on `mvn test` (pure-logic always-run + Testcontainers where a DB is needed) | tests-on-build |
| Every schema/data change is a Flyway script — a fresh deploy needs no manual step | flyway-deploy-reproducible |
| **Performance is a standing priority** — batch over per-row, index scoped columns, keep inter-service calls off hot paths | performance-priority |
| Cross-cutting capability → its **own** standalone service (contract + client, DIP); atomicity via transactional outbox, never a shared DB or distributed transaction | microservice-standards |
| DRY — never the same function in two files; common → `main.js`, module → `<module>.js` | no-duplicate-functions |
| Build fresh for any org; the monolith is reference only, never a port | build-fresh-not-port |
| Modernise the UI of any area you touch — don't just wire a proxy | improve-ui-with-the-move |
| Owner-configurable policy uses the **common-settings** catalog + `org_setting` override pattern | tenant-config-store |

---

## 1. Who the system serves

A department of education is not one user. Each stakeholder is a first-class surface. _Status column updated
2026-08-03 — Phases 1 and 2 between them moved four rows. The two ❌ portal rows are exactly what Phase 3 is._

| Stakeholder | Needs | Today |
|---|---|---|
| **School admin / clerk** | enrolment, fees, attendance, records | ✅ served |
| **Teacher** | timetable, mark entry, attendance, homework, my classes | 🟡 **marks, timetable view, homework and the behaviour log** shipped (1.3, 2.1, 2.4, 2.5); a single teacher home screen is still absent |
| **Head / principal** | school-wide results, staff performance, finance | 🟡 dashboard + report cards + promotion; staff performance still absent |
| **Parent / guardian** | results, attendance, fee dues, homework, pay online, meet teacher | ❌ no portal at all |
| **Student** | timetable, results, homework, materials | ❌ no portal at all |
| **Accountant** | receivables, arrears, expenses, payroll, books | 🟡 **fees now reach the ledger** (AR + GL + aging + statements, Phase 0); expenses and payroll still absent |
| **Transport in-charge** | routes, stops, who rides which bus | 🟡 vehicle register only |
| **Librarian / hostel warden / nurse** | domain registers | ❌ |
| **HR** | staff records, attendance, leave, payroll, certification | 🟡 **staff master + daily register + leave with derived balances** (2.3); payroll and certification still absent |
| **Department officer** (district/province) | cross-school aggregates, statutory returns, child tracking across schools | ❌ |

---

## 2. Current state

**Measured 2026-08-03: 46 entities · 31 controllers · Flyway V1–V21.**
_(As first written, 2026-07-30: 15 entities · 19 controllers · 22 screens · Flyway V1–V7 — kept so the
distance travelled is visible.)_

**Strong:** enrolment, guardians, staff master, student attendance, fee collection, discounts, alerts,
multi-school branch scoping, owner-configurable policy, org-scoping (hardened in the education review) —
**plus the entire academic record shipped since**: academic year & term, examinations, marks entry, grading
scales, report cards, promotion, and the timetable.

**Absent:** admissions, HR/payroll, the parent and student portals, and every
facility register beyond a vehicle list.

### 2.1 The composition gap — the biggest structural finding

```
business-service  composes → Audit, Catalog, Finance, Inventory, Party        (5)
education-service composes → Audit, Finance, Notification, Party              (4)   ← was 1
```

**✅ LARGELY CLOSED (Phase 0, 2026-07-30/31).** As first written this read *"education composes 1"*, and the
concrete symptom was that **fee collection never reached the general ledger**. That is fixed: education now
enqueues to its own `gl_outbox` → `finance-service` (0.1 fees→GL, 0.2a fees→AR, 0.2b fee credit), audits
marks and report-card publication through `audit-service`, and reaches parents via `notification-service`.

Still uncomposed and still correct to reuse rather than build: `catalog` + the sell saga (books/uniforms),
`inventory` (library stock), `appointment` (parent–teacher meetings), `campaign`, `analytics`.

---

## 3. Composition map — reuse before building

Per §1.2/§1.3, each need is first checked against an existing service.

| Education need | Decision | Service | Note |
|---|---|---|---|
| Fee receivable, arrears, statements, aging | **REUSE** | `finance-service` AR | invoice + aging + statement already exist |
| Fee revenue → books (journal, P&L, period close) | **REUSE** | `finance-service` GL via `GlOutbox` | copy the business pattern exactly |
| School expenses / vendor bills | **REUSE** | `finance-service` AP | |
| Student · guardian · staff identity | **REUSE** | `party-service` | already bridged (P3a) — extend to staff/guardian |
| Email / SMS to parents | **REUSE** | `notification-service` | |
| Bulk fee reminders, campaigns | **REUSE** | `campaign-service` | |
| Cross-school analytics | **REUSE** | `analytics-service` | |
| **Marks-change audit trail** | **REUSE** | `audit-service` | immutable log — a marks edit MUST be auditable |
| Parent–teacher meeting booking | **REUSE** | `appointment-service` | a genuine fit; no new scheduler |
| Books / uniforms sold to parents | **REUSE** | `catalog` + business sell saga | it is a retail sale |
| Library stock, school assets | **REUSE** | `inventory-service` | issue/return needs a thin education layer |
| Owner-configurable policy | **REUSE** | `common-settings` | |
| Parent / student login | **REUSE** | `auth-service` | new user types + roles, not a new identity system |
| **Documents** (certificates, photos, homework attachments) | **NEW** | `document-service` | genuinely cross-cutting — pharmacy needs Rx scans, business needs receipt images. Per microservice-standards this earns its own service |
| **Payroll** (salary, deductions, payslips) | **NEW** | `payroll-service` | distinct bounded context; posts to `finance-service` GL. Needed by education AND business staff |
| Exams · marks · grading · report cards · timetable · homework | **BUILD IN** `education-service` | — | education's core domain, not cross-cutting. Stays put |

> **Split trigger:** if `education-service` passes ~40 entities, split `education-academic-service`
> (exams/marks/timetable/LMS) from `education-core` (people/admin/fees). Not before — a premature split buys
> distributed-transaction problems for no gain.

---

## 4. The programme

Six phases. Each numbered item is one **slice** = Document → Design → Implement → Test → headed Cypress green.

### Phase 0 — Composition & correctness (do first; smallest effort, largest structural payoff)

| Slice | What | Why first |
|---|---|---|
| **0.1** ✅ **DONE** | **Fee collection → GL** via `GlOutbox` + `PostingEventRequest`, mirroring `SellController` | school revenue enters the books; unlocks P&L, trial balance, period close for education with no new UI |
| **0.2** ✅ **DONE** (0.2a AR + 0.2b fee credit) | **Fee dues → `finance-service` AR** — a student's outstanding fee becomes a receivable with aging | replaces the bespoke arrears screen with the platform's statements/aging |
| **0.3** ⬜ OPEN | **Performance remediation** — education review finding D: `AnalyticsController` loads 5 whole tables + 24 loops per render; 17 dup-checks use `findScoped().stream().anyMatch()` | performance-priority; do before adding academic tables that make it worse |
| **0.4** 🟨 PARTLY — `FeeCollection` DONE; `Attendance`/`Student` still cryptic | **Column-name remediation** — `Attendance` (`en`,`sn`,`grid`,`gn`,`dt`) and `FeeCollection` (`d`,`dd`,`da`,`f`,`fp`,`pd`,`od`,`odd`,`p`,`rb`,`ri`,`cn`,`vf`,`db`) renamed behind Flyway | every academic report will read these; fix before building on them |

### Phase 1 — The academic record (the core capability gap)

| Slice | What | Depends on |
|---|---|---|
| **1.1** ✅ **DONE** | **Academic year & term** as a first-class entity; wire existing attendance + fees to it | — **keystone** |
| **1.2** ✅ **DONE** | **Examinations** — exam definition (term, type, max marks, weighting) per grade/subject | 1.1 |
| **1.3** ✅ **DONE** | **Marks entry** — per student × subject × exam; teacher-facing grid; **every edit audited** via `audit-service` | 1.2 |
| **1.4** ✅ **DONE** | **Grading scales** — owner-configurable bands/GPA/pass mark via common-settings | 1.3 |
| **1.5** ✅ **DONE** | **Report cards** — printable per term + cumulative transcript | 1.4 |
| **1.6** ✅ **DONE** | **Promotion** — roll a class forward at year end, with retained students | 1.1, 1.3, **1.5** |

> **Phase 1 is COMPLETE (2026-08-01).** All six slices shipped and Cypress-green: the academic record runs
> end to end from academic year → term → exam → marks → grading scale → report card → promotion.

> 1.1–1.6 share one term/exam/marks spine. **Design them together, implement as six slices** — designing
> separately means three migrations over the same tables.

### Phase 2 — Daily teaching operations ✅ COMPLETE (2026-08-03)

> **Phase 2 is the current phase** (Phase 1 complete 2026-08-01, education review closed the same day).
> 2.1 is the keystone: 2.2 substitution reads the timetable. **CORRECTED 2026-08-02:** this note used to add
> *"and 2.3 staff attendance is what makes a substitution necessary — so the order is a dependency chain"*,
> which read literally puts 2.3 BEFORE 2.2. There is no staff-attendance data today (`Attendance` is
> student-only). Resolved in 2.2's design: **2.2 owns a minimal `StaffAbsence` record and 2.3 later absorbs
> it** — see `slices/edu-2.2-substitution.md` §1.

| Slice | What |
|---|---|
| **2.1** ✅ **DONE** | **Timetable** — class × period × subject × teacher × room, with clash detection — `slices/edu-2.1-timetable.md` |
| **2.2** ✅ **DONE** | **Substitution** — cover an absent teacher from the timetable — `slices/edu-2.2-substitution.md` |
| **2.3** ✅ **DONE** | **Staff attendance & leave** — presence, leave types, balances. **Must WRITE `staff_absence` (2.2), not build a parallel absence concept** — `slices/edu-2.3-staff-attendance-leave.md` |
| **2.4** ✅ **DONE** | **Homework / assignments** — set, submit, mark. **Attachments deferred to D-5**; the lifecycle ships without them — `slices/edu-2.4-homework.md` |
| **2.5** ✅ **DONE** | **Discipline / behaviour log** — append-only, positive AND concern, no workflow — `slices/edu-2.5-discipline-log.md` |

### Phase 3 — Parents & students (the missing surfaces)

| Slice | What |
|---|---|
| **3.1** 📐 **design** | **Parent portal** — results, attendance, dues, homework for *my* children. **CORRECTED: the `GUARDIAN` user type does NOT exist** — `Membership.role` is free text whose javadoc merely lists it. The real work is a CHILD-scoped access shape, not a new role — `slices/edu-3.1-parent-portal.md` |
| **3.2** | **Online fee payment** — parent pays; settles the AR from 0.2 |
| **3.3** | **Student portal** — timetable, results, homework, materials |
| **3.4** | **Parent–teacher meetings** — booking via `appointment-service` |
| **3.5** | **Notices / circulars** — school→parent broadcast via `campaign-service` |

### Phase 4 — Admissions, HR & finance depth

| Slice | What |
|---|---|
| **4.1** | **Admissions pipeline** — enquiry → application → test → offer → enrol, with seat capacity |
| **4.2** | **Transfer certificate & leaving**; **alumni** |
| **4.3** | **Student documents** — via new `document-service` |
| **4.4** | **`payroll-service`** — salary structure, deductions, payslips; posts to finance GL |
| **4.5** | **School expense management** — via `finance-service` AP |
| **4.6** | **Teacher certification & posting history** |

### Phase 5 — Department of education layer

| Slice | What |
|---|---|
| **5.1** | **National / unique student ID** — track a child across schools (`enrollNo` is per-school) |
| **5.2** | **Cross-school aggregate reporting** — district enrolment, attendance, results |
| **5.3** | **Statutory returns / census export** — jurisdiction-specific format |
| **5.4** | **School performance comparison** — league/benchmark within the department |

### Phase 6 — Facilities (build on real demand, not speculatively)

Transport routes & stops (unlocks transport fee derivation) · Library (on `inventory-service`) · Hostel ·
Health records · Assets.

---

## 5. Definition of done — every slice

0. **All seven layers of §5b answered explicitly** in the design doc — including the datastore choice and its
   justification (§5c), and which behaviour is owner-configurable (§5d).
1. Design doc in `microservices/docs/slices/` with the **three Mermaid diagrams** (architecture, class, sequence).
2. `organization_id` + `findScoped` NULL-fallback on every read; writes stamped; by-id reads anti-IDOR.
3. Flyway migration; **indexed** on every scoped column (D3); migrations execute in `mvn test` on an empty DB (D2).
4. Unit tests that run on `mvn test` (pure Mockito where possible — no Docker dependency).
5. Owner-configurable behaviour goes through **common-settings**, and the flag is **read** on the path it
   governs (C1) with the gate asserting **both** catalog and consumer (C2).
6. UI modernised, not just wired; i18n keys for all new strings; responsive; shared `uiConfirm`/date-picker.
7. **Headed Cypress spec, green**, run by the user.
8. Memory + `SAAS-BUILD-STANDARDS.md` updated if the slice establishes a new rule.

---

## 5b. The per-layer lens — what every slice must answer

A slice is not "an API plus a screen". For each use case, all seven layers get a deliberate answer:

| Layer | Question | Standing gap to watch |
|---|---|---|
| **UI/UX** | what does *that role* actually need — teacher, parent, accountant — not a generic admin CRUD form? | today every screen is staff-facing; Phase 3 exists because of this |
| **Service / API** | contract, envelope, validation, error relay to the user | proxy layers that collapse a real reason into `{success:false}` |
| **Database** | what is the access pattern, and is MySQL right for it? | see §5c — **currently unexamined** |
| **Patterns / principles** | SOLID, DIP (contract + client), saga + outbox for cross-service atomicity, DTOs at the boundary | |
| **Microservice design** | bounded context; compose don't duplicate; cross-cutting → its own service | §2.1 composition gap |
| **Per-org configurability** | what would a school reasonably want set up differently? → common-settings, never a constant | see §5d |
| **DRY** | is this renderer/flow already written somewhere? | |

## 5c. Database selection — an open architectural gap

**Every service currently uses MySQL, by default rather than by decision.** Several planned capabilities have
access patterns MySQL serves poorly. This must be decided per slice, not inherited.

| Capability | Access pattern | Candidate | Note |
|---|---|---|---|
| Marks, fees, enrolment | relational, transactional | **MySQL** ✅ | correct as-is |
| Attendance history | append-heavy, time-ranged, 1 row/student/day | MySQL + partitioning, or time-series | at district scale this is the largest table by far |
| **Documents** (certificates, photos, homework) | large binaries | **object storage (S3-compatible)** | a blob column in MySQL will not scale — gates decision **D-5** |
| Cross-school analytics (Phase 5) | aggregate scans over years | columnar / read replica / materialised rollups | finding D already shows the naive version loading 5 whole tables |
| Student / staff search | fuzzy, multi-field | MySQL FULLTEXT first; Elasticsearch only if proven | resist premature adoption |
| Timetable clash detection | in-memory constraint check | MySQL + cache | |
| Sessions, hot config | ephemeral key-value | Redis | also helps the 50-entry demo counters |

**Rule going forward:** state the datastore choice and its justification in each slice's design doc. "MySQL,
because the data is relational and transactional" is a fine answer — an *unstated* answer is not.

## 5d. Per-org configurability — what a school must be able to set up

Anything below is a policy a real school will want to differ on. Each goes in the **common-settings** catalog
(`org_setting` override, self-rendering Configuration screen), never a hard-coded constant — and each must be
**read on the path it governs** (C1) with the gate asserting catalog *and* consumer (C2).

| Setting | Phase | Why it can't be a constant |
|---|---|---|
| Grading scale — bands, GPA, pass mark | 1.4 | jurisdiction- and school-specific |
| Attendance rule — half-day, late threshold, minimum % for exam eligibility | 0.4 / 1.2 | varies per board |
| Term structure — 2 vs 3 terms, term names | 1.1 | |
| Promotion rule — auto-promote, or pass-marks required | 1.6 | |
| Report card — which columns, whether rank is shown | 1.5 | many schools forbid publishing rank |
| Fee — late fee %, grace days, whether partial payment is allowed | 0.2 | |
| Fee heads — tuition/transport/lab/exam composition | 0.2 | |
| Marks — who may edit after publish, and for how long | 1.3 | ties to **D-3** |
| Parent portal — what parents may see (results before publish? other children?) | 3.1 | |
| Transport fee derivation — flat vs distance/stop-based | 6 | |
| Staff leave — types and annual balances | 2.3 | |
| Branch scoping — staff, subject, guardian, discount | ✅ done | already on this pattern |

Existing settings already follow this (`edu.staff.branchScoped`, `edu.subject.branchScoped`,
`edu.guardian.branchScoped`, `edu.discount.branchScoped`) — the pattern is proven, it just needs extending.

> **Known inconsistency to resolve:** fee-collection branch scoping lives on `FeeSetting.feeCollectionBranchScoped`
> (its own screen), not in common-settings. Two config surfaces for one class of policy. Unify — with a data
> migration — rather than adding a second switch.

## 6. Sequencing rationale

- **Phase 0 before everything.** It is small, fixes a standards violation, and every later phase reports on data
  that Phase 0 makes correct. Building Phase 1 first means re-doing its finance and performance work.
- **Phase 1 before 2 and 3.** Portals and timetables are surfaces onto academic data; without marks, a parent
  portal shows attendance and dues only.
- **Phase 5 last of the functional phases.** Departmental reporting aggregates academic data that must exist first.
- **Phase 6 on demand.** Speculative facility modules are how products bloat.

---

## 7. Open decisions (needed before the phase they gate)

| # | Decision | Gates |
|---|---|---|
| **D-1** | **Jurisdiction** — statutory return format + TC format are country/state specific. **NOT a blocker for 1.4**: grading bands/GPA/pass mark are per-org configurable, which is this row's own answer — the platform never needs to know the board. D-1 shapes only which DEFAULT preset ships and the statutory formats in 5.3. | ~~1.4~~ **5.3 only** |
| **D-2** | **Customer shape** — single school, group, or government department? ~~Changes whether Phase 5 outranks Phase 2~~ **RE-FRAMED 2026-08-03: Phase 2 is complete, so the live question is whether Phase 5 (department layer) outranks Phase 3 (portals).** A department customer wants cross-school aggregates before parent logins; a single school wants the opposite | **phase order — now live** |
| ~~**D-3**~~ | ~~Privilege map~~ — **RESOLVED 2026-07-31.** Three tiers: `WRITE_PRIVILEGE` for day-to-day records, `ADMIN_PRIVILEGE` for money/structure/policy, `DELETE_PRIVILEGE` for deletes. Every write endpoint gated; gate `education/privilege-map.cy.js`. **Marks entry lands in the ADMIN tier.** | ~~1.3~~ unblocked |
| **D-4** | **Online payment provider** | 3.2 — **next phase, so this is now near** |
| **D-5** | **Document storage backend** — DB blob, filesystem, or S3-compatible. **CORRECTED 2026-08-03: this row said "4.3", but 2.4 homework reaches it FIRST** — homework attachments need the same `document-service`. It is not a hard blocker for 2.4: the homework lifecycle (set → submit → mark) is valuable without file upload, so 2.4 ships attachment-less and adds them when D-5 lands. Recorded so the next reader does not treat 2.4 as blocked, nor forget that attachments are missing. | **2.4** (attachments only) · 4.3 (fully) |

> **D-3 is urgent independently of this programme.** Marks entry without a privilege map means any authenticated
> user could alter results.

---

## 8. Risks

- **Scope.** This is a multi-month programme. The cadence exists to keep it shippable: each slice stands alone.
- **`education-service` size.** Watch the split trigger in §3.
- **Cryptic columns (0.4)** touch live data — needs the D5 treatment: count rows per environment, never drop on
  inference.
- **Marks are contested data.** Audit from day one (1.3), not retrofitted.

---

## Progress log

Kept current as slices land — this table, not memory or a chat message, is the source of truth for "what next".

| Slice | Status | Design doc | Gate |
|---|---|---|---|
| 0.1 fees → GL | ✅ done | `slices/edu-0.1-fees-to-gl.md` | `education/fees-to-gl.cy.js` |
| 0.2a fees → AR | ✅ done | `slices/edu-0.2-fees-to-ar.md` | `education/fees-ar.cy.js` |
| 0.2b fee credit | ✅ done | `slices/edu-0.2b-fee-credit.md` | `education/fee-credit.cy.js` |
| Branch-scope settings | ✅ done | `slices/edu-branch-scope-settings.md` | `education/branch-scope-settings.cy.js` |
| D-3 privilege map | ✅ done | — | `education/privilege-map.cy.js` |
| 1.1 academic year & term | ✅ done | `slices/edu-1.1-academic-year-term.md` | `education/academic-year.cy.js` |
| 1.2 examinations | ✅ done | `slices/edu-1.2-examinations.md` | `education/exams.cy.js` |
| 1.3 marks entry | ✅ done | `slices/edu-1.3-marks-entry.md` | `education/marks.cy.js` |
| Finding B — fee validation | ✅ done | `slices/edu-B-fee-validation.md` | `education/fee-validation.cy.js` |
| Finding B §8 — grade/discount validation | ✅ done | same doc, §8 | same gate |
| 1.4 grading scales | ✅ done | `slices/edu-1.4-grading-scales.md` | `education/grading.cy.js` |
| 1.5 report cards | ✅ done | `slices/edu-1.5-report-cards.md` | `education/report-cards.cy.js` |
| 1.6 promotion | ✅ done — **Phase 1 complete** | `slices/edu-1.6-promotion.md` | `education/promotion.cy.js` |
| finding D — analytics perf | ✅ done — **the education review is CLOSED** | `slices/edu-D-analytics-perf.md` | `education/dashboard.cy.js` (unchanged) + `analytics-perf.cy.js` |
| 2.1 timetable | ✅ done | `slices/edu-2.1-timetable.md` | `education/timetable.cy.js` |
| 2.2 substitution | ✅ done |
| 2.3 staff attendance & leave | ✅ done |
| 2.4 homework | ✅ done |
| 2.5 discipline log | ✅ done — **Phase 2 complete** |
| **3.1 parent portal** | 📐 **design, awaiting approval** | `slices/edu-3.1-parent-portal.md` | `education/parent-portal.cy.js` | `slices/edu-2.5-discipline-log.md` | `education/behaviour.cy.js` | `slices/edu-2.4-homework.md` | `education/homework.cy.js` | `slices/edu-2.3-staff-attendance-leave.md` | `education/staff-leave.cy.js` | `slices/edu-2.2-substitution.md` | `education/substitution.cy.js` |

### Carried requirements (must not be lost between slices)

| From | Requirement | Lands in |
|---|---|---|
| 1.2 §7 → 1.3 | the exam lock is inert until marks set it | ✅ done in 1.3 (D4) |
| 1.2 D5 → 1.3 | audit exam lock/unlock | ✅ done in 1.3 (D5) |
| 1.4 D4 → **1.5** | grading is derived, so a published report card must be **snapshotted**, not re-derived | ✅ done in 1.5 (D1) |
| 1.2 D4 → **1.5** | a term whose exam weights do not total 100 must be **refused**, not computed | ✅ done in 1.5 (D2) |
| 1.2 §6 → 1.3 → 1.4 → **1.5** | exam eligibility by attendance % — deferred three times, now explicitly 1.5 | ⚠️ **partly.** 1.5 built the attendance aggregate it was waiting on and established that eligibility can only be a **computed flag, not a gate** (there is no exam-registration step to block). The `edu.exam.minAttendancePercent` setting is **not built** — it needs the first `INT` entry in the shared `common-settings`, which is a scope decision |
| 1.5 D1 → **1.6** | promotion must read the SNAPSHOT, not re-derive a term's result | ✅ done in 1.6 (D2) |
| 2.2 → **2.3** | 2.2 owns a minimal `StaffAbsence`. **2.3 must WRITE these rows, not create a parallel absence concept** | ✅ **done & green.** Both paths write it via the extracted `StaffAbsenceService`; 2.2 was refactored onto the same owner, and `substitution.cy.js` proves its behaviour is unchanged |
| 2.4 D6 → **D-5 / 4.3** | `HomeworkSubmission.documentRef` is a nullable column **nothing writes**, held for `document-service`. Justified (the alternative is migrating a table with real data) but it is the same shape as the `Student.fee` unreachable-field finding — keep it documented or an audit will read it as a defect | when D-5 lands |
| 3.1 §6 → **before any real school** | `Guardian.email` is unverified free text, and it becomes a portal login identity. Invitation-only limits it, but a typo invites a stranger to a child's record. **Needs email verification** | open |
| 3.1 D4 → **behaviour in the portal** | 2.5's notes were written with no expectation a parent would read them; exposing them retroactively changes that contract. Needs a per-note 'shared with parent' decision | own slice |
| 2.2 + 2.4 + 2.5 → **notification** | THREE shipped/designed slices now want a real send (cover assigned · homework set · parent informed) and the path is still a logging stub. **Strongest candidate for the next non-phase slice** | open |
| 2.5 D6 → **safeguarding** | confidential disclosures need read-auditing and a narrower access tier — explicitly NOT what `behaviour_note` is for, recorded so no school misuses it | own initiative |
| 2.4 D4 → **continuous assessment** | homework deliberately does NOT feed the report card: 1.5's aggregate is a published number and adding a source would change its meaning silently. Needs its own weighting slice | own slice |
| 2.3 §6 → **platform** | student `attendance` has **no UNIQUE key** on (org, student, date) — the same check-then-act race as finding D, still open. Found while designing 2.3 | open |
| 2.3 D4 → **holiday calendar** | leave-day arithmetic cannot skip weekends/public holidays: the platform has no such concept, and the weekend is not Sat–Sun everywhere this ships | own slice |
| 2.1 §6 → **platform** | `GatewayClient` has no HTTP connect/read timeouts (standard D3e) — one slow downstream pins a monolith thread. Not education's to fix alone | open |
| 1.6 → **next** | `edu.exam.minAttendancePercent` is REGISTERED but no screen consumes it — a setting nothing reads is decorative (slice B's `@PositiveOrZero` lesson). Wire the eligibility flag onto the marksheet + report card, or drop the setting | open |

### Open findings (outside the slice sequence)

- `Student.fee` and `Student.vf` are persisted columns with **no DTO field** — money unreachable through the API.
- 0.3 performance remediation (finding D) — still open, and the academic tables now make it more urgent.
- 0.4 remainder — `Attendance` (`en`/`sn`/`grid`/`gn`) and `Student` (`vf`/`nd`/`di`/`mn`/`wa`/`pob`/`ys`/`ye`).
