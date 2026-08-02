# Education — feature inventory & gap analysis

**Status: SUPERSEDED — kept as the raw inventory it was.** Produced 2026-07-30 against `education-service`
(15 entities, 19 controllers, Flyway V1–V7) and `educationDashboard.html` (22 screens).

> **⚠️ Do not plan from this document.** The live plan is
> [`education-complete-programme.md`](education-complete-programme.md), which supersedes it.
>
> **Much of what this lists as a gap has since shipped** (Phase 0 + all of Phase 1, 2026-07-30 → 2026-08-01):
> academic year & term, examinations, marks entry, grading scales, report cards, promotion, and the fee →
> GL/AR/credit money path. The service is now at **Flyway V16**, not V7, and carries 24 entities, not 15.
>
> The inventory below is left **exactly as written** because it is a point-in-time record of what the service
> looked like before the programme started — which is what makes the programme's decisions readable. Read it
> as history, not as a backlog.

Scope question this answers: *what is missing before this can run a department of education, not just a school?*

---

## 1. What exists today

| Domain | Entities | Screens | State |
|---|---|---|---|
| **Organisation** | `School`, `Owner` | School, Owner, Main Branch | multi-school, org-scoped, branch-scoped ✅ |
| **Academic structure** | `Grade` (class+section, room, timing, fee), `Subject` (per grade, publisher, edition) | Grade, Subject | structure only — no delivery |
| **People** | `Student` (30 fields), `Guardian`, `Staff` (designation, qualification, timings) | Student, Guardian, Staff, Team | strong master data |
| **Attendance** | `Attendance` (student, in/out, status, remarks) | Attendance, PA | **students only** |
| **Fees** | `FeeCollection`, `FeeSetting`, `Discount` | Fee Collection, Fee Setting, Discount, Fee Report, Fee Voucher, Arrears | collection is solid |
| **Transport** | `Vehicle` | Vehicle | vehicle register only |
| **Comms** | `Alerts`, `AlertChannel` | Alerts | outbound alerts |
| **Config** | `OrgSetting` | Configuration | owner-configurable policies ✅ |
| **Insight** | — | Dashboard, Analytics | KPI + charts |

**The honest summary:** what exists is an excellent **administrative** system — enrolment, fee collection,
attendance and communication. What is largely absent is the **academic** system — everything to do with
teaching, assessing and reporting on learning.

A school currently cannot answer *"what did this child learn, and how are they doing?"* from this product.

---

## 2. Gaps, by priority

### 🔴 P1 — the academic core (a school cannot operate without these)

| # | Gap | Why it blocks | Depends on |
|---|---|---|---|
| **A1** | **Examinations** — exam/assessment definition (term, type, max marks, weighting) | there is no concept of an exam anywhere in the codebase | Grade, Subject |
| **A2** | **Marks & results** — per student, per subject, per exam | the single most-requested school function | A1 |
| **A3** | **Grading scales** — A/B/C bands, GPA, pass marks, per-org configurable | a department mandates its own scale; hard-coding one blocks adoption | A2 |
| **A4** | **Report cards / transcripts** — printable, per term and cumulative | the actual deliverable parents receive | A2, A3 |
| **A5** | **Promotion / progression** — roll a class forward at year end, with retained students | without it, year 2 requires re-enrolling every student by hand | Grade, A2 |
| **A6** | **Academic year / term** as a first-class entity | `Student.ys/ye` are loose year fields; every academic record needs a term to hang on | — |

> **A6 is the keystone.** Exams, marks, promotion, fee cycles and attendance reporting all need "which term?".
> Building A1–A5 without it means retrofitting a term column into five tables later.

### 🟠 P2 — daily teaching operations

| # | Gap | Why it matters |
|---|---|---|
| **B1** | **Timetable** — period-level schedule (class × period × subject × teacher × room) | `Grade` has one `timeFrom/timeTo`; `Staff` has `timeIn/timeOut`. There is no period concept, so no clash detection, no "who is free now", no substitution |
| **B2** | **Staff attendance & leave** | only students have attendance. A department needs teacher presence for payroll and accountability |
| **B3** | **Homework / assignments** | the most-used parent-facing feature in modern school apps |
| **B4** | **Discipline / behaviour log** | pastoral record; often a statutory requirement |

### 🟡 P3 — the intake and exit pipeline

| # | Gap | Why it matters |
|---|---|---|
| **C1** | **Admissions** — enquiry → application → test/interview → offer → enrol | students can only be *created*, as if already admitted. No funnel, no waitlist, no seat capacity |
| **C2** | **Transfer certificate / leaving** | statutory document; `Student.status` exists but there is no exit process |
| **C3** | **Alumni** | follow-on from C2 |
| **C4** | **Student documents** — birth certificate, photo, prior records | compliance and identity verification |

### 🟢 P4 — facilities (adopt per customer need)

| # | Gap | Note |
|---|---|---|
| **D1** | **Transport routes & stops** | `Vehicle` is a register only — no route, stop, or student-to-stop assignment, so transport fees can't be derived |
| **D2** | **Library** | issue/return/fine |
| **D3** | **Hostel** | room allocation, warden, mess |
| **D4** | **Health records** | only `bloodGroup` today |
| **D5** | **Assets / inventory** | `inventory-service` already exists platform-wide — compose, don't rebuild |

### 🔵 P5 — what "department of education" specifically adds

This is the part a single-school product usually lacks entirely, and it's implied by your framing.

| # | Gap | Why it matters |
|---|---|---|
| **E1** | **Cross-school aggregate reporting** | a department needs district-level enrolment, attendance and results — not one school at a time. `School` + branch scoping is the foundation; the aggregation layer is missing |
| **E2** | **Statutory returns / census export** | governments mandate periodic enrolment and staffing returns in a fixed format |
| **E3** | **Student national/unique ID** | a department tracks a child across schools; `enrollNo` is per-school |
| **E4** | **Teacher certification & posting history** | `Staff.qualification` is one free-text field — no certification expiry, no transfer history |
| **E5** | **Parent & student portals** | currently everything is staff-facing. Self-service is what makes results and homework worth capturing |

---

## 3. Also worth fixing while in here

- **`Attendance` and `FeeCollection` use cryptic column names** (`en`, `sn`, `grid`, `gn`, `dt`, `d`, `dd`, `da`,
  `f`, `fp`, `pd`, `od`, `odd`, `p`, `rb`, `ri`, `cn`, `vf`, `db`). They work, but they are unreadable and make
  every new report a guessing game. Rename behind a Flyway migration before building reporting on top.
- **Denormalised names** (`Attendance.sn`, `gn`) will drift when a student or class is renamed.
- **Fee structure is a single amount** (`Grade.fee` / `Student.fee`). Real schools bill heads — tuition,
  transport, lab, exam, admission. Transport fees in particular can't be derived without D1.

---

## 4. Recommended build order

Each is a vertical slice under the standard cadence (Document → Design → Implement → Test → Cypress gate).

1. **A6 — Academic year & term.** Small, unblocks everything, and cheap now vs. a five-table retrofit later.
2. **A1–A2 — Exams & marks.** The biggest single capability gap.
3. **A3–A4 — Grading scales & report cards.** Turns marks into the thing parents actually receive.
4. **A5 — Promotion.** Makes year 2 possible without manual re-enrolment.
5. **B1 — Timetable.** Largest P2 item; enables substitution and teacher workload.
6. **B2 — Staff attendance & leave.**
7. **C1 — Admissions pipeline.**
8. **E1–E2 — Department aggregation & statutory returns.** Do after the academic core exists, because the
   returns report on it.

Facilities (P4) are best driven by a real customer asking, not built speculatively.

---

## 5. Sequencing note

Items 1–4 form one coherent programme — **the academic record** — and are worth treating as a single design
effort with four implementation slices, because they share the same term/exam/marks spine. Designing them
together avoids three migrations across the same tables.

Everything else can be scheduled independently.
