# Education Management System — complete programme

**Status: IN DELIVERY — Phase 0 and Phase 1 COMPLETE and Cypress-green; the education review is CLOSED.**
**PHASE 2 IS COMPLETE** (2.1–2.5 all Cypress-green, 2026-08-03). Now in **Phase 3 — guardian & student portals**: **3.1 is DONE & Cypress-green (11/11, 2026-08-04)**, and 2.5's V23 rename re-run is green with it. **3.2 is BLOCKED on D-4 (payment provider) — a user decision; 3.3 and 3.5 are unblocked.** Produced 2026-07-30; last
updated 2026-08-04. Supersedes `education-feature-gap-analysis.md` (kept as the raw inventory).

| Shipped so far | |
|---|---|
| **Phase 0** | fees → GL, fees → AR, fee credit, branch-scope settings, cryptic-column rename (part), D-3 privilege map |
| **Phase 1** | 1.1 academic year & term · 1.2 examinations · 1.3 marks entry · 1.4 grading scales · 1.5 report cards · 1.6 promotion — **all six green** |
| **Review** | findings A (cross-tenant save takeover), B (fee validation), C (privilege gating), D (analytics perf) fixed; E partly |
| **Phase 2** ✅ | 2.1 timetable · 2.2 substitution · 2.3 staff attendance & leave · 2.4 homework · 2.5 behaviour log — **all five green** |
| **Now 2026-08-06** | **N1 notification outbox ✅ DONE & green (V24)** — the third use of the shared `OutboxRelay`; 2.2's cover notice is a real send at last. **Next: 3.3 student portal or 3.5 notices** — 3.2 still waits on D-4, and **guardian sign-in must land before 3.2**. Two platform changes this plan now accounts for: see **§9a**. |
| **Now** | Phase 3.1 guardian portal — ✅ **DONE & green 11/11 (2026-08-04)**, V22 applied. **Naming settled with it: the domain word is GUARDIAN, never "parent"** — see §0a. 2.5 was amended to match (V23) and its re-run is green. **Next: 3.3 or 3.5 — 3.2 waits on D-4.** _(Notification remains the strongest non-phase candidate: 2.2 + 2.4 + 2.5 all want a real send.)_ |

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

### 0a. Ubiquitous language — the domain word is GUARDIAN (settled 2026-08-04)

A domain model is only as good as its vocabulary, and this one had two words for one person. Settled
platform-wide with 3.1:

| Use | Never use |
|---|---|
| `Guardian`, `guardianId`, `guardian_informed`, `guardianDashboard.html`, `guardian.js`, `ui.guardian*` | `Parent`, `parentId`, `parent_informed`, `parentPortal.html`, `ui.parent*` |

**Why guardian and not parent.** It is not a synonym choice, it is a correctness one. The adult a school
deals with is frequently *not* a parent — a grandparent, an older sibling, a foster carer, a local
authority. Naming the field `parent` asserts a family relationship the school has not verified and often
knows to be false, and it reads badly to exactly the families most likely to notice. `Guardian` is also
what the existing entity has always been called, so this removes a synonym rather than introducing one.

**Where "parent" legitimately survives, and must not be swept.** The *containment* sense is a different
word that happens to be spelled the same: `re-parent` (anti-IDOR comments), `parent exam`
(`ExamPaper`→`Exam`), `parent year` (term→year), `service-parent` (the Maven pom), `categories.parent_id`,
and DOM `parentNode` / `.parents()` in vendored libraries. A blanket find-and-replace across the repo
would corrupt all of these — the 2026-08-04 sweep protected them explicitly.

**Applies to new slices.** 3.2–3.5 and anything touching families use *guardian* in entity names, column
names, DTO keys, element ids, i18n keys and prose. In `hi` and `ar` the bundles were already correct
(अभिभावक / ولي الأمر); `en`, `fr`, `es` and `ur` were reworded.

---

## 1. Who the system serves

A department of education is not one user. Each stakeholder is a first-class surface. _Status column updated
2026-08-04 — Phases 1 and 2 between them moved four rows; 3.1 moved the guardian row. The remaining ❌ portal
row (student) is 3.3._

| Stakeholder | Needs | Today |
|---|---|---|
| **School admin / clerk** | enrolment, fees, attendance, records | ✅ served |
| **Teacher** | timetable, mark entry, attendance, homework, my classes | 🟡 **marks, timetable view, homework and the behaviour log** shipped (1.3, 2.1, 2.4, 2.5); a single teacher home screen is still absent |
| **Head / principal** | school-wide results, staff performance, finance | 🟡 dashboard + report cards + promotion; staff performance still absent |
| **Guardian** | results, attendance, fee dues, homework, pay online, meet teacher | ✅ **3.1 portal DONE & green** (results, attendance, dues, homework — own children only); paying online is 3.2, meeting a teacher is 3.4. **No guardian can sign in yet** — the auth-service account is deliberately not built (3.1 §6) |
| **Student** | timetable, results, homework, materials | ❌ no portal at all |
| **Accountant** | receivables, arrears, expenses, payroll, books | 🟡 **fees now reach the ledger** (AR + GL + aging + statements, Phase 0); expenses and payroll still absent |
| **Transport in-charge** | routes, stops, who rides which bus | 🟡 vehicle register only |
| **Librarian / hostel warden / nurse** | domain registers | ❌ |
| **HR** | staff records, attendance, leave, payroll, certification | 🟡 **staff master + daily register + leave with derived balances** (2.3); payroll and certification still absent |
| **Department officer** (district/province) | cross-school aggregates, statutory returns, child tracking across schools | ❌ |

---

## 2. Current state

**Measured 2026-08-06 (post-N1): 35 domain entities · 33 controllers · Flyway V1–V24 · 20 unit test
classes · 36 Cypress specs.** _(The metric itself was corrected on 2026-08-04 — see the box below. N1 added
a table but **zero** domain entities, so this count is unchanged since 3.1; §9b.)_
_(As first written, 2026-07-30: 15 entities · 19 controllers · 22 screens · Flyway V1–V7 — kept so the
distance travelled is visible.)_

> **The "entity" count was wrong every time it was written, and it feeds a live architectural trigger.**
> `entity/` holds **49 files**: 38 carry `@Entity`, and **11 are enums** (`ExamStatus`, `PortalStatus`,
> `SubmissionState`, …) that a file count silently counts as entities. Of the 38, three are
> **infrastructure, not domain** — `GlOutbox`, `AuditOutbox`, `OrgSetting` — so the domain figure is **35**.
> The previously-recorded "47" would have tripped §3's ~40 split trigger; **35 does not.** Definition
> pinned in §3 so the next reader measures the same thing.

**Strong:** enrolment, guardians, staff master, student attendance, fee collection, discounts, alerts,
multi-school branch scoping, owner-configurable policy, org-scoping (hardened in the education review) —
**plus the entire academic record shipped since**: academic year & term, examinations, marks entry, grading
scales, report cards, promotion, and the timetable.

**Absent:** admissions, HR/payroll, the student portal, and every facility register beyond a vehicle list.

### 2.1 The composition gap — the biggest structural finding

```
business-service  composes → Audit, Catalog, Finance, Inventory, Party        (5)
education-service composes → Audit, Finance, Notification, Party              (4)   ← was 1
```

**✅ LARGELY CLOSED (Phase 0, 2026-07-30/31).** As first written this read *"education composes 1"*, and the
concrete symptom was that **fee collection never reached the general ledger**. That is fixed: education now
enqueues to its own `gl_outbox` → `finance-service` (0.1 fees→GL, 0.2a fees→AR, 0.2b fee credit), audits
marks and report-card publication through `audit-service`, and reaches guardians via `notification-service`.

Still uncomposed and still correct to reuse rather than build: `catalog` + the sell saga (books/uniforms),
`inventory` (library stock), `appointment` (guardian–teacher meetings), `campaign`, `analytics`.

---

## 3. Composition map — reuse before building

Per §1.2/§1.3, each need is first checked against an existing service.

| Education need | Decision | Service | Note |
|---|---|---|---|
| Fee receivable, arrears, statements, aging | **REUSE** | `finance-service` AR | invoice + aging + statement already exist |
| Fee revenue → books (journal, P&L, period close) | **REUSE** | `finance-service` GL via `GlOutbox` | copy the business pattern exactly |
| School expenses / vendor bills | **REUSE** | `finance-service` AP | |
| Student · guardian · staff identity | **REUSE** | `party-service` | already bridged (P3a) — extend to staff/guardian |
| Email / SMS to guardians | **REUSE** | `notification-service` | |
| Bulk fee reminders, campaigns | **REUSE** | `campaign-service` | |
| Cross-school analytics | **REUSE** | `analytics-service` | |
| **Marks-change audit trail** | **REUSE** | `audit-service` | immutable log — a marks edit MUST be auditable |
| Guardian–teacher meeting booking | **REUSE** | `appointment-service` | a genuine fit; no new scheduler |
| Books / uniforms sold to guardians | **REUSE** | `catalog` + business sell saga | it is a retail sale |
| Library stock, school assets | **REUSE** | `inventory-service` | issue/return needs a thin education layer |
| Owner-configurable policy | **REUSE** | `common-settings` | |
| Guardian / student login | **REUSE** | `auth-service` | new user types + roles, not a new identity system |
| **Documents** (certificates, photos, homework attachments) — *storage of binaries* | **NEW** | `document-service` | genuinely cross-cutting — pharmacy needs Rx scans, business needs receipt images. Per microservice-standards this earns its own service. **Gated on D-5** |
| **Document layout & printing** — *rendering, not storage* | **REUSE** (added 2026-08-06) | `DocumentRenderer` + document designer | Built for B2B invoices: server-validated field whitelist, owner-editable profile, **one render function for preview and print**. Education's report card, fee receipt and transfer certificate are candidates. **Currently module-scoped in `js/business/` — promote to common before the second consumer, not after.** Shape assessment needed: the whitelist is invoice-shaped (header/line/totals). See §9a |
| **Payroll** (salary, deductions, payslips) | **NEW** | `payroll-service` | distinct bounded context; posts to `finance-service` GL. Needed by education AND business staff |
| Exams · marks · grading · report cards · timetable · homework | **BUILD IN** `education-service` | — | education's core domain, not cross-cutting. Stays put |

> **Split trigger:** if `education-service` passes ~40 **domain** entities, split
> `education-academic-service` (exams/marks/timetable/LMS) from `education-core` (people/admin/fees). Not
> before — a premature split buys distributed-transaction problems for no gain.
>
> **Metric pinned 2026-08-04 (it was being measured wrongly).** A *domain entity* = a file in `entity/`
> carrying `@Entity`, **excluding enums** (11 of them live there) and **excluding infrastructure tables**
> (`GlOutbox`, `AuditOutbox`, `OrgSetting` — outbox and settings plumbing are not the domain getting large).
> Command: `grep -l "^@Entity" entity/*.java | wc -l`, then subtract the three infra rows.
>
> **Standing at 35 / ~40 — approaching, not crossed.** Phase 3's remaining slices add roughly 3–5
> (payment record, student portal access, notice), which puts the trigger in reach **during Phase 4**, not
> now. **Do not split on the raw file count** — it reads 49 and would fire a split ~14 entities early, for
> which the price is a distributed transaction across the marks/fees boundary that today is one commit.

---

## 4. The programme

Six phases. Each numbered item is one **slice** = Document → Design → Implement → Test → headed Cypress green.

### Phase 0 — Composition & correctness (do first; smallest effort, largest structural payoff)

| Slice | What | Why first |
|---|---|---|
| **0.1** ✅ **DONE** | **Fee collection → GL** via `GlOutbox` + `PostingEventRequest`, mirroring `SellController` | school revenue enters the books; unlocks P&L, trial balance, period close for education with no new UI |
| **0.2** ✅ **DONE** (0.2a AR + 0.2b fee credit) | **Fee dues → `finance-service` AR** — a student's outstanding fee becomes a receivable with aging | replaces the bespoke arrears screen with the platform's statements/aging |
| **0.3** ✅ **DONE** (2026-08-01) | **Performance remediation** — education review finding D: `AnalyticsController` loaded 5 whole tables + 24 loops per render; 12 (not 17) dup-checks used `findScoped().stream().anyMatch()`. Shipped as SQL aggregates + indexed `EXISTS` with **V16**, gate `analytics-perf.cy.js` — `slices/edu-D-analytics-perf.md`. _(This row read "⬜ OPEN" until 2026-08-04 while the progress log recorded the same work as done and the review as CLOSED. Corrected — **one table said open, another said closed, and a plan that contradicts itself is worse than one that is merely out of date.**)_ | performance-priority; done before the academic tables made it worse |
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

### Phase 3 — Guardians & students (the missing surfaces)

| Slice | What |
|---|---|
| **3.1** ✅ **DONE & green** | **Guardian portal** — results, attendance, dues, homework for *my* children. **CORRECTED: the `GUARDIAN` user type does NOT exist** — `Membership.role` is free text whose javadoc merely lists it. The real work is a CHILD-scoped access shape, not a new role — `slices/edu-3.1-guardian-portal.md` |
| **3.1b** 📐 **DESIGN — BLOCKING** | **Portal sign-in** — the account 3.1 deliberately did not build, **plus the deny rule that must ship with it.** ⚠️ **Education's READ endpoints are ungated** (`getUserStudent`, `getUserGuardian`, `getMarksSheet`, `getUserFc` — verified 2026-08-06, zero `@PreAuthorize`). That is safe only while every authenticated user is staff; **the day a guardian can sign in, it is a data breach**. Answer = a deny-by-default `PortalScopeFilter` in `common-security`, not 74 annotations — `slices/edu-3.1b-portal-sign-in.md`. **3.2 and 3.3 both depend on this** |
| **3.2** | **Online fee payment** — guardian pays; settles the AR from 0.2. **Needs 3.1b** (you cannot pay if you cannot sign in) |
| **3.3** | **Student portal** — timetable, results, homework. **Needs 3.1b** — building it first would be a SECOND portal nobody can log into. _(materials await D-5)_ |
| **3.4** | **Guardian–teacher meetings** — booking via `appointment-service` |
| **3.5** | **Notices / circulars** — school→guardian broadcast via `campaign-service` |

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
| **UI/UX** | what does *that role* actually need — teacher, guardian, accountant — not a generic admin CRUD form? | today every screen is staff-facing; Phase 3 exists because of this |
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
| Guardian portal — what guardians may see (results before publish? other children?) | 3.1 | |
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
- **Phase 1 before 2 and 3.** Portals and timetables are surfaces onto academic data; without marks, a guardian
  portal shows attendance and dues only.
- **Phase 5 last of the functional phases.** Departmental reporting aggregates academic data that must exist first.
- **Phase 6 on demand.** Speculative facility modules are how products bloat.

---

## 7. Open decisions (needed before the phase they gate)

| # | Decision | Gates |
|---|---|---|
| **D-1** | **Jurisdiction** — statutory return format + TC format are country/state specific. **NOT a blocker for 1.4**: grading bands/GPA/pass mark are per-org configurable, which is this row's own answer — the platform never needs to know the board. D-1 shapes only which DEFAULT preset ships and the statutory formats in 5.3. | ~~1.4~~ **5.3 only** |
| **D-2** | **Customer shape** — single school, group, or government department? ~~Changes whether Phase 5 outranks Phase 2~~ **RE-FRAMED 2026-08-03: Phase 2 is complete, so the live question is whether Phase 5 (department layer) outranks Phase 3 (portals).** A department customer wants cross-school aggregates before guardian logins; a single school wants the opposite | **phase order — now live** |
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

## 9. Standards conformance review — 2026-08-04

Measured against §0's rules, at the Phase-3 boundary. **Evidence, not assertion** — every row below was
verified against the code on the date shown, because the previous review round recorded an entity count that
was wrong and an 0.3 status that contradicted the progress log two tables away.

| Standard | Verdict | Evidence / gap |
|---|---|---|
| **Multi-tenancy** — org scoping, stamped writes, anti-IDOR | ✅ **strong** | finding A fixed 9 repos + 7 saves; `save-takeover-idor.cy.js` is a standing gate. The hardest case yet — a guardian, i.e. an *external* principal — went through one class (`ChildResolver`) rather than 31 controllers |
| **Compose, don't duplicate** (§1.2) | ✅ **largely closed** | 1 → **4** composed services (Audit, Finance, Notification, Party). Fees reach the GL, AR, aging and statements. Still uncomposed *and correct to be*: catalog/sell saga, inventory, appointment, campaign, analytics |
| **Reuse-first** (§1.3) | ✅ **exemplary** | **five libraries extracted rather than copied**: `common-outbox`, `common-subledger`, `common-credit`, plus `StudentVisibilityService` and `StaffAbsenceService` inside the service. Each was extracted at the second caller, not speculatively |
| **Money `BigDecimal(19,2)`** | ⚠️ **deliberate deviation** | education fees are `Integer` — **user-confirmed, not a defect**. Recorded so an audit does not "fix" it |
| **DTOs at the boundary** | ❌ **open violation** | `FeeCollectionController.getUserFc` returns raw `FeeCollection` entities (verified 2026-08-04, line 119). Known since 0.4 and still unfixed; it is why a field rename changed the JSON contract |
| **Saga + outbox for cross-service atomicity** | ✅ | `gl_outbox` + `audit_outbox` over the shared `OutboxRelay`. `@EnableScheduling` was missing and is fixed — without it the retry relay never ran |
| **DB standards D1–D8** | 🟡 | Flyway owns the schema V1–V23. **Gap: student `attendance` has no UNIQUE key** on (org, student, date) and upserts via `findFirstBy…` — a live check-then-act race. 2.3 shipped its equivalent key from day one; the older table never got one |
| **Config standards C1–C4** | ⚠️ **one violation** | `edu.exam.minAttendancePercent` is registered and readable but **no screen consumes it** — C1 says a flag must be read on the path it governs. A setting nothing reads is decorative. Either wire it or drop it |
| **Cadence + Cypress gate** | ✅ | 20 slices, each Document→Design→Implement→Test→green. **35 education Cypress specs** |
| **Tests on build** | 🟡 **19 unit test classes** (from 2) | pure-Mockito, no Docker, run on every `mvn test`. **Finding E's last gap stands: no empty-tenant Testcontainers test** — V16 moved `sum()` into SQL, which returns NULL where Java returned 0, and the demo org always has data |
| **Performance** | ✅ then 🟡 | finding D replaced 5 whole-table loads + 24 loops with SQL aggregates + 15 indexes (V16), and 1.5's N+1 (3 queries *per mark*) was batched. **Residual: the 12 dup-checks are cheap but still racy** |
| **Cross-cutting → own service** | 🟡 | `document-service` and `payroll-service` are still unbuilt and correctly identified. `HomeworkSubmission.documentRef` is a nullable column nothing writes, held for D-5 |
| **Ubiquitous language** | ✅ **settled** | GUARDIAN, never "parent" (§0a) — with the containment-sense homonyms explicitly protected |
| **Owner-configurable via common-settings** | 🟡 | proven pattern, widely used. **Known inconsistency unresolved:** fee-collection branch scoping still lives on `FeeSetting.feeCollectionBranchScoped`, its own screen — two config surfaces for one class of policy (§5d) |

### The three findings this review adds

1. **The split trigger was being measured with the wrong number** (§2/§3). 47/49 counted enums and outbox
   plumbing as domain entities; the real figure is **35 of ~40**. Acting on the raw count would have split
   the service ~14 entities early and bought a distributed transaction across the marks/fees boundary.
2. **The plan contradicted itself about 0.3** for three days — "⬜ OPEN" in the Phase 0 table, "done, review
   CLOSED" in the progress log. Both were written by the same process that claims the progress log is the
   source of truth. **A plan that disagrees with itself is worse than one that is merely stale**, because a
   reader cannot tell which half to trust.
3. **"Notification is a stub" was imprecise and made the gap look bigger than it is.** Education composes
   `notification-service` and sends alert email through it today. What is missing is that 2.2/2.4/2.5's
   three hooks log instead of calling the client that already exists — hours of work, not a slice.

### 9a. Addendum — re-review 2026-08-06 (education did not move; the platform did)

**No education code changed between 2026-08-04 and 2026-08-06.** Verified: no education commits, Flyway
still ends at V23, no `notify_outbox`. **The programme is blocked on one thing — N1's design is awaiting
approval**, and nothing else in Phase 3 is startable ahead of it without abandoning the cadence.

Two changes landed in *other* modules that this plan must now account for, both found by re-reading the
diff rather than by assuming education is self-contained:

**1. `party-service` V3 added an account hierarchy — and its own migration header names education.**

> *"The hierarchy lives HERE, not in business-service, because it is identity STRUCTURE — the same shape
> **Education corporate sponsors** and Welfare corporate donors already need."*

`party` gained `parent_party_id` + `account_level`, with `account_level` defaulting to `INDIVIDUAL` and every
`ADD` guarded on `information_schema`.

- **Impact on education today: none, and that is verified, not assumed.** Education's bridge
  (`PartyBridgeService`, `Student.partyId`) reads identity, not structure, and every existing party is
  correctly described as `INDIVIDUAL`.
- **Impact on the plan: a capability education will want now exists and must not be rebuilt.** A corporate
  sponsor paying fees for a group of students is company→branch→contact — the exact shape just built. When
  that requirement appears, it composes `party-service`; it does not become an education table.

**2. A document-profile renderer + owner designer was built for B2B invoices — and education has at least
three printable documents that will otherwise each grow their own print code.**

`DocumentRenderer` (`js/business/receipt.js`) is a data-driven engine: a server-validated field whitelist
(`/documentFields`), an owner-editable profile, presets, and **one render function used by both the preview
and the printer** — its own comment notes that a preview drawn by a second implementation eventually lies
about what comes out of the printer.

| Education document | Today | Bearing |
|---|---|---|
| Report card (1.5) | `print.css`, D7 — deliberately not `document-service` | the closest structural match: rows + an aggregate, like lines + totals |
| Fee receipt / voucher (0.2) | bespoke | a receipt is what the engine was built for |
| Transfer certificate (4.2) | not built | **check reuse BEFORE building it** — this is §1.3 in its literal form |

- **Honest caveat:** the whitelist is **invoice-shaped** (`header` / `line` / `totals`) and the presets are
  trade documents. A report card is subject rows plus a term aggregate — structurally similar, not identical.
  This is a **reuse candidate needing a shape assessment**, not a drop-in.
- **It also sits in the wrong place for reuse.** `js/business/` is module scope; the DRY standard puts shared
  code in a common module. Reusing it means **promoting `DocumentRenderer` out of `business/` first** — and
  that is much cheaper now, with one consumer, than after education writes the second copy.
- **It does not replace `document-service` (D-5).** That is *storage of binaries*; this is *layout and
  rendering*. Two different halves — worth stating so the D-5 decision is not thought to be resolved.

### 9b. Addendum — re-review 2026-08-06 (post-N1)

**Measured, not restated: 35 domain entities · 33 controllers · Flyway V1–V24 · 20 unit test classes ·
36 education Cypress specs.**

**The headline is that the domain did not grow.** `entity/` is now **50 files** — 39 `@Entity`, 11 enums —
but **four** of those are infrastructure (`GlOutbox`, `AuditOutbox`, `OrgSetting`, and now `NotifyOutbox`),
so by the metric §3 pins the domain count is **still 35**, exactly where 3.1 left it.

> **This is the metric earning its keep, one slice after it was defined.** A raw file count would now read
> **50 against a ~40 threshold** and demand an immediate service split. The truth is that N1 added a *table*
> and **zero domain entities** — outbox plumbing is not the domain getting large. Had the earlier, wrong
> count survived, the next reader would have split `education-academic-service` off on the strength of a
> notification queue.

#### N1 against the standards

| Standard | N1 |
|---|---|
| **Reuse-first (§1.3)** | ✅ third use of `OutboxRelay`; **no new pattern and no new state machine** |
| **Cross-service atomicity** | ✅ transactional outbox — the notice commits with the decision |
| **Performance** | ✅ HTTP moved **off** the write path; 3 point lookups instead of a 4-table helper |
| **Config C1/C2** | ✅ `edu.notify.coverAssigned` is **read inside `queue()`**, the path it governs, and the gate asserts catalog *and* both consumer directions |
| **DB D3** | ✅ `idx_notify_outbox_org` + the relay's `(status, id)` queue index |
| **DRY** | ✅ `send()` delegates to the new `sendTo()` — one sender, one retry loop |
| **Tests on build** | ✅ `CoverNoticeBuilderTest`, 9 pure cases, no Docker |

**N1 introduced no new violations.**

#### The standing violations, re-checked (not assumed)

| Gap | Status |
|---|---|
| `getUserFc` returns raw `FeeCollection` entities | ❌ **unchanged** — verified at line 119 |
| `edu.exam.minAttendancePercent` registered, **zero consumers** | ❌ **unchanged — and now carried across FOUR slices** (1.5 → 1.6 → 3.1 → N1) |
| Student `attendance` has no UNIQUE key | ❌ unchanged |
| 12 dup-checks cheap but still racy | ❌ unchanged |
| No empty-tenant Testcontainers test | ❌ unchanged |

**`edu.exam.minAttendancePercent` should now be escalated or dropped.** It is a live **C1 violation** — a
flag nothing reads — sitting in the catalog of a shipped product, and it has survived four consecutive
slices by being someone else's problem each time. N1 just demonstrated the cost of the opposite discipline:
its setting was wired to its consumer in the same slice that introduced it, and the gate proves both
directions. **Either wire the eligibility flag onto the marksheet and report card, or delete it** — the
decision is small, and leaving it is the one thing the standards explicitly call worse than not having it.

### Recommended order from here

| # | Work | Why now |
|---|---|---|
| ~~1~~ ✅ **DONE (N1, 2026-08-06)** | **Notification** — and **NOT the "hours of wiring" this row first claimed.** Only 2.2's hook is a genuine wiring gap; 2.4 has no hook at all and 2.5 must not send (see the carried requirement). And an already-written design, **`slices/105-notification-multichannel-broadcast.md`**, records that `notification-service` **has no database**: delivery is synchronous on the request thread, unrecorded, and never retried (its G3). Wiring 2.2 straight to `EmailService.send()` would add a fourth synchronous un-retried send on a write path — knowingly building on the defect 105 exists to fix | **decision needed: slice 105 first, or an education-side notify outbox, or defer** |
| 2 | **3.3 student portal** | reuses 3.1's `ChildResolver` shape while it is fresh; 3.2 is blocked on D-4 anyway |
| 3 | **3.5 notices/circulars** | composes `campaign-service` — closes another §1.2 row |
| 4 | **Dup-check audit → UNIQUE constraints** + student `attendance` UNIQUE key | two live check-then-act races, both known, both deferred on D5 grounds that an audit would settle |
| — | **3.2 online payment** | **BLOCKED — D-4 is yours to decide** |
| — | **Guardian sign-in** (auth-service `Membership role=GUARDIAN` + portal claim) | 3.1's surface has no users until this exists. Must precede 3.2, or a payment path opens onto an account nobody can reach |

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
| 2.2 substitution | ✅ done | `slices/edu-2.2-substitution.md` | `education/substitution.cy.js` |
| 2.3 staff attendance & leave | ✅ done | `slices/edu-2.3-staff-attendance-leave.md` | `education/staff-leave.cy.js` |
| 2.4 homework | ✅ done | `slices/edu-2.4-homework.md` | `education/homework.cy.js` |
| 2.5 discipline log | ✅ done — **Phase 2 complete**; amended 2026-08-04 (`guardianInformed`, V23) and **the re-run is GREEN** | `slices/edu-2.5-discipline-log.md` | `education/behaviour.cy.js` |
| **3.1 guardian portal** | ✅ **DONE & Cypress-green 11/11 (2026-08-04)** — but **has no users until 3.1b** | `slices/edu-3.1-guardian-portal.md` | `education/guardian-portal.cy.js` |
| **3.1b portal sign-in** | 🔨 **IMPLEMENTED 2026-08-06, awaiting build + gate.** Carries the ungated-reads finding; adds `PortalScopeFilter` (deny-by-default) to **`common-security`**, so every service is affected | `slices/edu-3.1b-portal-sign-in.md` | `education/portal-sign-in.cy.js` |
| **N1 notification outbox** (non-phase) | ✅ **DONE & Cypress-green 11/11 (V24, 2026-08-06)** + `substitution.cy.js` green as the 2.2 regression. Scope was corrected to **2.2 only**: 2.4 has no hook and 2.5 must not send | `slices/edu-N1-notification-outbox.md` | `education/notification-outbox.cy.js` |

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
| 3.1 D4 → **behaviour in the portal** | 2.5's notes were written with no expectation a guardian would read them; exposing them retroactively changes that contract. Needs a per-note 'shared with guardian' decision | own slice |
| 2.2 → **notification** | **CORRECTED 2026-08-04 — this row used to say "2.2 + 2.4 + 2.5 all want a real send". Only 2.2 does.** Verified against the code and the slice docs: <br>• **2.2 cover assigned — YES.** `SubstitutionController.notifyCoverBestEffort` calls `appUtil.li(...)`, not the client. A genuine wiring gap. <br>• **2.4 homework set — there is NO hook.** `HomeworkController` contains no notify call of any kind; 2.4 §6 lists guardian notification as *deferred scope*. It is a **new feature with class-sized fan-out**, not a wiring fix. <br>• **2.5 guardian informed — NOT A SEND, and wiring it would be a defect.** 2.5 §98 defines `guardianInformed` as *"the school ticks it when they have spoken to the guardian"* — a record that a human conversation already happened. D5 says the slice deliberately does not reach notification-service. Emailing on that tick would send a second, machine-written message about a conversation that already took place. <br>**PRECISION: education DOES compose `notification-service`** — `AlertController` → `EmailService` → `NotificationClient` → `lb://notification-service` works today. | ✅ **DONE & green (N1, 2026-08-06)** — 2.2 now queues through `notify_outbox`. **2.4 and 2.5 remain deliberately unwired**, for the reasons above |
| 2.5 D6 → **safeguarding** | confidential disclosures need read-auditing and a narrower access tier — explicitly NOT what `behaviour_note` is for, recorded so no school misuses it | own initiative |
| platform → **4.2 / 4.3 / 1.5 reprint** | **`DocumentRenderer` exists (2026-08-06) and education must check it before building any new printable document.** Report card, fee receipt, transfer certificate. Needs promoting out of `js/business/` to common scope first — cheapest with one consumer. Does NOT resolve D-5, which is binary *storage* | §9a |
| platform → **corporate fee sponsors** | `party-service` V3 (2026-08-06) added company→branch→contact, and its own header names education corporate sponsors as a target shape. When sponsored fees are requested, **compose party-service — do not add an education table** | when requested |
| 2.4 D4 → **continuous assessment** | homework deliberately does NOT feed the report card: 1.5's aggregate is a published number and adding a source would change its meaning silently. Needs its own weighting slice | own slice |
| 2.3 §6 → **platform** | student `attendance` has **no UNIQUE key** on (org, student, date) — the same check-then-act race as finding D, still open. Found while designing 2.3 | open |
| 2.3 D4 → **holiday calendar** | leave-day arithmetic cannot skip weekends/public holidays: the platform has no such concept, and the weekend is not Sat–Sun everywhere this ships | own slice |
| 2.1 §6 → **platform** | `GatewayClient` has no HTTP connect/read timeouts (standard D3e) — one slow downstream pins a monolith thread. Not education's to fix alone | open |
| 1.6 → **next** | `edu.exam.minAttendancePercent` is REGISTERED but no screen consumes it — a setting nothing reads is decorative (slice B's `@PositiveOrZero` lesson). Wire the eligibility flag onto the marksheet + report card, or drop the setting | open |

### Open findings (outside the slice sequence)

- `Student.fee` and `Student.vf` are persisted columns with **no DTO field** — money unreachable through the API.
- ~~0.3 performance remediation (finding D)~~ — **DONE 2026-08-01 (V16).** This bullet contradicted the
  progress log for three days; corrected 2026-08-04.
- 0.4 remainder — `Attendance` (`en`/`sn`/`grid`/`gn`) and `Student` (`vf`/`nd`/`di`/`mn`/`wa`/`pob`/`ys`/`ye`).
- **The dup-check race is still open and is finding D's real fix.** V16 made the 12 check-then-act
  duplicate scans *cheap*, not *correct*: two concurrent saves still both pass because there is no UNIQUE
  constraint behind them. `findDuplicate*Scoped()` shipped so the data can be audited first (D5 — a tenant
  already holding duplicates would fail the migration and break the deploy). **Auditing then constraining is
  a slice nobody has scheduled.**
- **Finding E (test depth) remains "partly".** 2 → 12+ unit test classes, but there is still **no
  empty-tenant Testcontainers test**: V16 moved `sum()` into SQL, which returns NULL where the replaced Java
  returned 0, and the demo org always has data so Cypress cannot reach that path.
