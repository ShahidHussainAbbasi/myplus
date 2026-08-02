# Education service — end-to-end review: findings & backlog

Branch: `feature/education-review` (based on `feature/pharmacy-review` HEAD — `master` is 163 commits
behind and lacks the i18n/RTL/responsive work already applied to `educationDashboard.html`).

Read-only audit. **No code has been changed.** Same cadence as the pharmacy review:
Document → Design → Implement → Test, one confirmation-gated step at a time.

Surface: 94 Java classes · **19 controllers** · Flyway V1–V7 · **2 unit tests** · 15 Cypress specs.

---

## Findings at a glance

**Status: CLOSED (2026-08-01)** — findings A, B, C and D are fixed and Cypress-green; E is partly done, with
its remainder tracked below. The audit text is the ORIGINAL, kept as written; only this table and the backlog
at the end move, so the findings stay readable as they were first stated.

| # | Finding | Severity | Blast radius | Status |
|---|---|---|---|---|
| **A** | Cross-tenant record **takeover** on save — 7 controllers | 🔴 **Critical** | Any authenticated user, any tenant | ✅ **fixed** — 9 repos + 7 saves + 3 child refs (+2 more found by the sweep); gate `save-takeover-idor.cy.js` |
| **B** | Fee collection (money in) has **no validation at all** | 🟠 High | Financial integrity | ✅ **fixed** — `slices/edu-B-fee-validation.md` (+§8); gate `fee-validation.cy.js`. **Partly stale when written** — overpayment was already fixed by 0.2a/0.2b |
| **C** | Writes are **ungated** — only deletes carry a privilege check | 🟠 High | Any USER can alter fees, staff, grades | ✅ **fixed** — 3-tier map (WRITE / ADMIN / DELETE), 21 → 44 gates, zero ungated writes; gate `privilege-map.cy.js` |
| **D** | Analytics loads **5 whole tables** per dashboard render | 🟠 High | Degrades with school size | ✅ **fixed** — `slices/edu-D-analytics-perf.md`; gate `dashboard.cy.js` (unchanged) + `analytics-perf.cy.js`. Five table loads → SQL aggregates; 12 duplicate checks → indexed `EXISTS` (V16). **Also found:** those checks are a **check-then-act race**, not just a slow scan — made cheap here, closed by the UNIQUE follow-up |
| **E** | 2 unit tests for 94 classes; no service-level test at all | 🟡 Medium | Everything above went unnoticed | 🟡 **partly** — 2 → 10 unit test classes + 28 Cypress specs; still no repository/scoping test for most controllers, and no Testcontainers test for the empty-tenant aggregate path (finding D §6) |

> ## ✅ The review is closed (2026-08-01)
> A, B, C and D are fixed and Cypress-green; E is materially improved and its remainder is tracked below.
> **Two follow-ups outlive this review** and are deliberately not folded into it:
> 1. **UNIQUE constraints on the 12 duplicate-checked columns** — the actual fix for the race. Needs a
>    per-tenant duplicate audit first, which `findDuplicate*Scoped()` (shipped in finding D) makes possible.
>    Adding them blind fails the migration on any tenant already holding duplicates (DB standard D5).
> 2. **A Testcontainers test for an empty tenant** — SQL `sum()` returns NULL where the replaced Java
>    returned 0. Guarded twice in code, but unproven by a test the demo org cannot exercise.

---

## A — Cross-tenant record takeover on save 🔴

**The bug.** Every `add*` endpoint resolves an edit by a **client-supplied id with no scope check**, then
overwrites `organizationId` with the caller's own:

```java
// GuardianController.java:159-163   (identical shape in 6 more)
Guardian obj = (dto.getId() != null)
        ? guardianRepository.findById(dto.getId()).orElseGet(Guardian::new)   // ← unscoped fetch
        : new Guardian();
obj.setUserId(userId);
obj.setOrganizationId(orgId);       // ← stamps the victim's row into the attacker's tenant
```

**Why this is worse than an IDOR read.** The attacker does not merely edit another school's record — the
`setOrganizationId` **moves it into their own tenant**. The victim's row disappears from every one of their
scoped queries. It is a silent cross-tenant *takeover*, and with sequential ids the victim rows are
trivially enumerable.

**Affected (unguarded):**

| Controller | Line | Entity |
|---|---|---|
| `GuardianController` | 159 | Guardian |
| `StaffController` | 146 | Staff |
| `SubjectController` | 140 | Subject |
| `DiscountController` | 152 | Discount |
| `OwnerController` | 128 | Owner |
| `SchoolController` | 194 | School (the branch itself) |
| `FeeCollectionController` | 135 | **Fee payment records** |

**Already correct** — `StudentController:233`, `GradeController:163`, `VehicleController:160` do exactly
the right thing and are the template for the fix:

```java
if (dto.getId() != null && obj.getId() != null && !requestUtil.canAccessSchool(obj.getSchoolId())) {
    return new GenericResponse("NOT_FOUND", "Student not found");
}
```

**Root cause.** The P4 anti-IDOR pass hardened **deletes** (all nine go through `ScopedDeleter.deleteScoped`
— verified correct) and the three **branch-scoped** saves. The **org-level saves were missed.** This isn't
carelessness so much as an invisible boundary: nothing in the code distinguishes "already hardened" from
"not yet", so the pass had no way to show what it had skipped.

**Secondary.** Child references are attached by unchecked id too — `SchoolController:213`
(`ownerRepository.findById(id)`) and `StaffController:174` (`gradeRepository.findById(gid)`) let a caller
attach another tenant's owner/grade to their own row.

---

## B — Fee collection has no validation 🟠

`FeeCollectionController.addFc` (line 129) is the money-in path. In full, it takes **every field straight
from the client and saves**:

- ❌ no check that `feePaid` ≥ 0 — a negative payment *increases* what the school is owed
- ❌ no check that payment ≤ amount due
- ❌ no check that `en` (enroll no) is a real student — it's a free-form string with **no FK**, so a payment
  can be filed against a student who doesn't exist, or one in another school
- ❌ no privilege check — any authenticated user can record a payment
- ❌ unscoped `findById` (this is also finding A)

Compare `business-service`'s `SagaSellService`, which derives line totals server-side *specifically because*
trusting client-submitted totals produced rows that contradicted themselves. The same discipline has never
been applied here.

> **Not a bug:** fees are whole-number `Integer` by deliberate design (user-confirmed). Not proposing to change that.

---

## C — Writes are ungated 🟠

21 `@PreAuthorize` annotations across 19 controllers, and they are almost all the same one:

| Gate | Count |
|---|---|
| `DELETE_PRIVILEGE` | 14 |
| `ADMIN_PRIVILEGE` (fee settings, school config) | 2 |
| `ROLE_OWNER`/`ADMIN` (settings) | 1 |
| `SUPER_PRIVILEGE` (party backfill) | 1 |

So **every create and update is open to any authenticated user.** A teacher can change fee amounts, create
staff, alter grades, rewrite discounts. `AnalyticsController` and `DashboardController` have no gate at all
(org-scoped, so not a leak — but no privilege check either).

Worth a deliberate decision rather than a blanket fix: a teacher *should* probably record attendance, but
should not be able to rewrite the fee schedule. This needs a privilege map before code.

---

## D — Analytics loads five whole tables 🟠

`AnalyticsController:70-74`, on every dashboard render:

```java
List<Student>        students   = studentRepository.findScoped(orgId, userId);
List<FeeCollection>  fees       = feeCollectionRepository.findScoped(orgId, userId);
List<Attendance>     attendance = attendanceRepository.findScoped(orgId, userId);
List<Staff>          staff      = staffRepository.findScoped(orgId, userId);
List<Grade>          grades     = gradeRepository.findScoped(orgId, userId);
```

…then 24 loops over them in Java. **Attendance is the killer**: one row per student per day. A 2,000-student
school generates ~400,000 rows a year — all loaded into heap to compute a handful of KPIs that SQL
aggregates would return in milliseconds.

**Related, same root cause:** 17 duplicate-name checks use `findScoped(org, user).stream().anyMatch(...)` —
a full table load on *every save*, across 11 controllers. And every `findScoped` returns an **unbounded
`List`**; only pharma-service learned to page these.

---

## E — 2 unit tests for 94 classes 🟡

`SchoolGradeRepoScopingTest` (which does cover Flyway) and `EmailServiceTest`. **No service-level test
exists** — no fee lifecycle, no attendance, no enrolment, no scoping test for the seven controllers in
finding A.

15 Cypress specs give real end-to-end coverage, but they exercise the happy path through the UI. None of
them tries to edit another tenant's record by id, which is why finding A survived.

---

## Proposed backlog

Ordered by risk, each step gated on your confirmation:

| Step | Work | Why this order | Status |
|---|---|---|---|
| **1** | **Fix A** — scope guard on all 7 saves + the 2 child-reference lookups. Mirror `StudentController:233`. Cypress gate that *attempts* the takeover cross-tenant. | Data belonging to one school silently moving to another is the only finding that is actively dangerous today | ✅ done |
| **2** | **Fix B** — validate `addFc`: non-negative amounts, payment ≤ due, enroll-no must resolve to a student in the caller's org | Money, and the fix is small and self-contained | ✅ done |
| **3** | **Decide C** — agree a privilege map (who may edit fees vs record attendance vs manage staff), then apply. Needs your input; I won't guess | Requires a policy decision, not a code decision | ✅ done — 3-tier map chosen by the user |
| **4** | **Fix D** — replace the analytics table-loads with aggregate queries; convert the 17 duplicate checks to `exists…Scoped`; page the unbounded finders | Real, but it degrades gradually rather than corrupting anything | ✅ done (paging deliberately scoped out — see below) |
| **5** | **Fix E** — service tests for the fee lifecycle + scoping, so 1–4 stay fixed | Locks the rest in | 🟡 partly |

~~**My recommendation: start at step 1.**~~ **All five steps are addressed; 1–4 are done and green.**

**Two corrections to step 4 as originally written**, recorded so the difference does not read as an omission:

- **"17 duplicate checks" was 12**, across 9 controllers (three of the 17 grep hits were the CSV-import
  lookups in `StudentController`, which are batch maps rather than per-save scans and are correct as they are).
- **"Page the unbounded finders" was deliberately NOT done wholesale.** After the analytics fix, `attendance`
  — the only table where it mattered — is never loaded whole again. Paging `grade`/`staff`/`subject`
  (tens of rows) would be ceremony, and paging `student` changes a UI contract, so it is its own slice.
  Rationale in `slices/edu-D-analytics-perf.md` D5.

Also fixed along the way: `getStudentMarks` was N+1 (three scoped lookups **per mark** — a class of 40 cost
~3,600 queries), found while designing 1.5 and batched there.

---

## Deliberately not raised

- **Whole-number fees** — `Integer` throughout is a confirmed design decision, not an oversight.
- **`FeeVoucherDTO.inclExclSelected`** — declared but never read. Dead, harmless; noted for step 4 cleanup.
- **The 19-controller structure** — no argument for restructuring; it mirrors the other verticals.
