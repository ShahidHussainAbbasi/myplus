# Education Management System — complete programme

**Status: PROGRAMME PLAN — no code written.** Supersedes `education-feature-gap-analysis.md` (kept as the raw
inventory). Produced 2026-07-30.

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

A department of education is not one user. Each stakeholder is a first-class surface, and today only the first
row exists.

| Stakeholder | Needs | Today |
|---|---|---|
| **School admin / clerk** | enrolment, fees, attendance, records | ✅ served |
| **Teacher** | timetable, mark entry, attendance, homework, my classes | ❌ nothing teacher-specific |
| **Head / principal** | school-wide results, staff performance, finance | 🟡 dashboard only |
| **Parent / guardian** | results, attendance, fee dues, homework, pay online, meet teacher | ❌ no portal at all |
| **Student** | timetable, results, homework, materials | ❌ no portal at all |
| **Accountant** | receivables, arrears, expenses, payroll, books | 🟡 fee collection only, **not in the ledger** |
| **Transport in-charge** | routes, stops, who rides which bus | 🟡 vehicle register only |
| **Librarian / hostel warden / nurse** | domain registers | ❌ |
| **HR** | staff records, attendance, leave, payroll, certification | 🟡 staff master only |
| **Department officer** (district/province) | cross-school aggregates, statutory returns, child tracking across schools | ❌ |

---

## 2. Current state

15 entities · 19 controllers · 22 screens · Flyway V1–V7.

**Strong:** enrolment, guardians, staff master, student attendance, fee collection, discounts, alerts,
multi-school branch scoping, owner-configurable policy, org-scoping (hardened in the education review).

**Absent:** the entire academic record (exams, marks, grading, report cards, promotion), timetable, staff
attendance, admissions, portals, and every facility register beyond a vehicle list.

### 2.1 The composition gap — the biggest structural finding

```
business-service composes → Audit, Catalog, Finance, Inventory, Party   (5)
education-service composes → Party                                       (1)
```

Sixteen sibling services exist. Education uses one. Concretely: **fee collection never reaches the general
ledger.** `business-service` enqueues every sale to `GlOutbox` → `finance-service` (journal, trial balance,
P&L, period close). Education enqueues nothing, so a school's revenue is invisible to the books the platform
already maintains.

This is a §1.2 violation, and fixing it is cheaper than any new feature.

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

### Phase 2 — Daily teaching operations

| Slice | What |
|---|---|
| **2.1** | **Timetable** — class × period × subject × teacher × room, with clash detection |
| **2.2** | **Substitution** — cover an absent teacher from the timetable |
| **2.3** | **Staff attendance & leave** — presence, leave types, balances |
| **2.4** | **Homework / assignments** — set, submit, mark (attachments via `document-service`) |
| **2.5** | **Discipline / behaviour log** |

### Phase 3 — Parents & students (the missing surfaces)

| Slice | What |
|---|---|
| **3.1** | **Parent portal** — results, attendance, dues, homework for *my* children (auth-service user type `GUARDIAN`) |
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
| **D-2** | **Customer shape** — single school, group, or government department? Changes whether Phase 5 outranks Phase 2 | phase order |
| ~~**D-3**~~ | ~~Privilege map~~ — **RESOLVED 2026-07-31.** Three tiers: `WRITE_PRIVILEGE` for day-to-day records, `ADMIN_PRIVILEGE` for money/structure/policy, `DELETE_PRIVILEGE` for deletes. Every write endpoint gated; gate `education/privilege-map.cy.js`. **Marks entry lands in the ADMIN tier.** | ~~1.3~~ unblocked |
| **D-4** | **Online payment provider** | 3.2 |
| **D-5** | **Document storage backend** — DB blob, filesystem, or S3-compatible | 4.3 |

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
| **finding D — analytics perf** | 🔵 **next** | `education-review-audit.md` | — |

### Carried requirements (must not be lost between slices)

| From | Requirement | Lands in |
|---|---|---|
| 1.2 §7 → 1.3 | the exam lock is inert until marks set it | ✅ done in 1.3 (D4) |
| 1.2 D5 → 1.3 | audit exam lock/unlock | ✅ done in 1.3 (D5) |
| 1.4 D4 → **1.5** | grading is derived, so a published report card must be **snapshotted**, not re-derived | ✅ done in 1.5 (D1) |
| 1.2 D4 → **1.5** | a term whose exam weights do not total 100 must be **refused**, not computed | ✅ done in 1.5 (D2) |
| 1.2 §6 → 1.3 → 1.4 → **1.5** | exam eligibility by attendance % — deferred three times, now explicitly 1.5 | ⚠️ **partly.** 1.5 built the attendance aggregate it was waiting on and established that eligibility can only be a **computed flag, not a gate** (there is no exam-registration step to block). The `edu.exam.minAttendancePercent` setting is **not built** — it needs the first `INT` entry in the shared `common-settings`, which is a scope decision |
| 1.5 D1 → **1.6** | promotion must read the SNAPSHOT, not re-derive a term's result | ✅ done in 1.6 (D2) |
| 1.6 → **next** | `edu.exam.minAttendancePercent` is REGISTERED but no screen consumes it — a setting nothing reads is decorative (slice B's `@PositiveOrZero` lesson). Wire the eligibility flag onto the marksheet + report card, or drop the setting | open |

### Open findings (outside the slice sequence)

- `Student.fee` and `Student.vf` are persisted columns with **no DTO field** — money unreachable through the API.
- 0.3 performance remediation (finding D) — still open, and the academic tables now make it more urgent.
- 0.4 remainder — `Attendance` (`en`/`sn`/`grid`/`gn`) and `Student` (`vf`/`nd`/`di`/`mn`/`wa`/`pob`/`ys`/`ye`).
