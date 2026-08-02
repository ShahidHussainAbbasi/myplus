# Slice 2.1 — Timetable

**Status: ✅ DONE — Cypress gate GREEN on run 2 (2026-08-02), after a clean monolith rebuild.**
Gate `education/timetable.cy.js` (9 cases, none skipped) + `ClashDetectorTest` (13 pure cases). Flyway **V17**.
**Open question resolved:** copy-into-a-non-empty-term **refuses outright** (user decision, 2026-08-01).

> ### ⚠️ Run 1's failure was never explained — read this before trusting the green
>
> Run 2 passed after `mvn clean package` + a monolith restart. **Neither change made between the runs can
> account for it:** the `GatewayClient` edit adds logging only (it rethrows unchanged), and the test-3
> fixture change runs *after* the test that failed.
>
> The likeliest explanation is that the monolith was serving a **stale build** — but that is inference, not
> evidence, and it sits awkwardly with test 1 having passed on the same endpoint in run 1.
>
> **So this is a green with an unexplained red behind it.** If it recurs, the logging added in run 1's
> investigation will name the cause immediately (`Downstream FAILED … -> <status>; body=…`) instead of the
> generic `"Error Occurred"`. That, not the pass, is the durable outcome of the failure.

### Gate run 1 — 2026-08-02: 1 failed, 1 silently skipped

| # | Case | Result |
|---|---|---|
| 1 | lesson saves into a free slot | ✅ passed — insert confirmed in the service log |
| 2 | same teacher, same slot → REFUSED | 🔴 **FAILED** — got a monolith 500 (`Error Occurred`), not `FAILED` |
| 3 | same class, same slot → REFUSED | ⚠️ **SKIPPED, and reported green** — see the hollow-green note |
| 4 | shared room → saved with a warning | ✅ passed — two inserts confirmed |
| 5–9 | non-teaching period, period-in-use, copy refusal, ADMIN tier, cross-tenant | ✅ passed |

**Evidence gathered (no code changed):**

1. `Error Occurred` is `message.error` (`messages.properties:98`), emitted **only** by the monolith's
   catch-all `RestResponseEntityExceptionHandler.handleInternal`. So this is a **500 from the monolith**,
   not a refusal from the service.
2. **The request never reached education-service.** Test 1's insert is at log line 598 (12:27:15) and test
   4's at line 834 (12:28:06); between them the service logged *nothing but outbox polling* — zero queries.
3. **~51 seconds elapsed** between tests 1 and 4 while test 3 skipped instantly, so test 2 spent ~50s before
   failing. That is a timeout signature, not an immediate rejection.
4. The gateway is not in this path (`saveTimetableEntry` appears 0× in `api-gateway.log`) — the monolith
   calls education-service directly via `EducationRestClient`'s `directBaseUrl`.

**ROOT CAUSE OF THE BLINDNESS FOUND (2026-08-02) — two real defects in shared monolith infrastructure,
`com.web.util.GatewayClient`. Both affect EVERY module's proxy, not just education.**

**1. Every downstream error was discarded.** `GatewayClient.execute` caught exactly two cases — 401
(refresh-and-retry) and 403 (DEMO_LIMIT) — and let everything else propagate to the monolith's catch-all
handler, which replaced it with a generic `"Error Occurred"` 500. The class had **no logger at all**, so the
service's real status, message and body were written nowhere. That is why this failure was a black box: the
answer was thrown away at the moment it arrived.
*Fixed:* the class now logs `Downstream FAILED/UNREACHABLE <method> <url> -> <status>; body=…` and rethrows
unchanged. **Behaviour is identical; only visibility changes.**

**2. The `RestTemplate` has no timeouts** — `new RestTemplate()` with the default factory, so a downstream
that never answers pins a monolith request thread indefinitely. This is the shape of the ~50 s hang, and it
is a platform-wide availability risk rather than a timetable bug.
*NOT fixed here* — adding timeouts changes how every module behaves when a service is slow, which deserves
its own decision rather than riding along in a timetable slice. Raised in §6.

**Still unknown:** the trigger itself — why a request near-identical to test 1's did not reach the service.
The logging above is what will name it: re-run the gate and the monolith console will state the downstream
status and body on the failing call, instead of `"Error Occurred"`.

**⚠️ Hollow green in test 3 — FIXED.** It ran only when the org had two subjects in the *same* class; the
demo org has one per class (the inserts show subjects 10 and 11 in grades 14 and 15), so it logged
`SKIPPED-BY-DESIGN` and reported green while proving nothing. The same hollow-green shape flagged on
`marks.cy.js` (1.3) and guarded against in slice B trap 4.
*Fixed:* `before()` now **seeds** a second subject in `subjectA`'s class when the org lacks one, and the test
asserts the fixture exists rather than skipping. Cleanup removes only the subject the spec created. **A spec
must seed what it needs, never opt out of its own most important assertion.**
Programme: `education-complete-programme.md` Phase 2.1 — *"Timetable — class × period × subject × teacher ×
room, with clash detection"*. **The keystone of Phase 2**: 2.2 substitution reads it, and 2.3 staff attendance
is what makes a substitution necessary.

---

## 1. Document — what and why

Phase 1 answered *what happened* — marks, grades, results. Phase 2 answers *what happens on Tuesday*. The
timetable is the spine: it is the first structure in this system that says **who is where, when**, and almost
everything in Phase 2 reads it.

### What already exists — including one thing an earlier slice said did not

| Existing | Consequence for 2.1 |
|---|---|
| `Subject` has `@ManyToOne Grade` | a subject **already belongs to one class** — "Maths for Class 5" and "Maths for Class 6" are separate `Subject` rows. A slot referencing a subject therefore already implies its class |
| **`staff_grades` join table + `Staff.grades` (`@ManyToMany`, EAGER)** | teacher ↔ class association **exists today** — see the correction below |
| `Grade.timeFrom` / `timeTo` | the class's daily window is already recorded, so periods have an outer bound to validate against |
| `Grade.room` — a bare `Long`, no `Room` entity | there is no room master. D6 |
| `Term` (1.1), nullable everywhere | a timetable belongs to a term, and a school with no terms must still work |

**A distinction worth stating, because it is easy to misread 1.3.** Slice 1.3 D6 declined to build a class
teacher model because it *"needs staff↔class **ownership** that does not exist yet"*. That is exactly right and
is **not** contradicted here: `staff_grades` gives an **association** (Mrs Khan is attached to 5A), not
**ownership** (Mrs Khan is 5A's form teacher, accountable for it). 2.1 needs only the association, which is why
2.1 is not blocked on the HR concern 1.3 was avoiding. The two facts are easy to conflate — I conflated them
while planning this slice — so the difference is recorded rather than left to be re-derived.

### What the association does NOT give us

`staff_grades` says *Mrs Khan is associated with 5A*. It does not say *Mrs Khan teaches **Maths** to 5A*.
A timetable needs the finer fact, and that is what a timetable entry is: the assignment **is** the schedule.
So 2.1 does not add a separate teacher-subject-class mapping — the timetable itself is that mapping, which is
why this slice is not blocked on an HR model.

---

## 2. Design

### D1 — Periods are an ENTITY the school defines; slots reference them, not clock times

A `Period` per org: name ("Period 1", "Break"), `sequence`, `startTime`, `endTime`.

The alternative — free start/end times on each timetable entry — makes clash detection an interval-overlap
problem and buys nothing, because real schools ring a bell. With fixed periods, "two things in the same slot"
is an equality test, which is also what makes it enforceable in the database (D4).

Same shape as 1.1's terms and 1.4's grade bands: **the entity IS the configuration** for list-shaped things.
No `edu.timetable.periodCount` setting — the school creates the periods it runs.

A **non-teaching period** (break, assembly) is just a `Period` with no entries against it. It appears on the
grid as a labelled row and needs no special case.

### D2 — A slot is (day, period, subject, teacher, room). The class comes from the subject… and is ALSO stored

The entry references `subjectId`, and `Subject` already knows its `Grade` — so per **1.2 D2** ("never store the
class twice") the class should be derived and not stored.

**This slice deliberately deviates, and stores `gradeId` on the entry as well.** Two reasons the earlier
precedent does not carry:

1. **The class is the primary query axis.** "Show 5A's timetable" is the single most common read in Phase 2,
   and 2.2 substitution runs it repeatedly. Deriving it means joining through `subject` on every read of the
   busiest screen in the phase.
2. **The clash constraint needs a column.** A class cannot be in two places at once, and per 1.3 D1 / 1.6 D6
   the only thing that makes that true under a double-clicked save is a UNIQUE key — which cannot be built on
   a derived value.

**The cost is named rather than hidden:** two sources of truth for the class. It is contained by validating at
write that `entry.gradeId == subject.grade.id`, refusing the save otherwise. A copy that is checked on every
write is a cache; a copy that is never checked is a bug waiting. If that check is ever removed, this becomes
exactly the drift 1.2 D2 warned about.

### D3 — Three clashes, and they are not equally serious

| Clash | Rule | Why |
|---|---|---|
| **Teacher** in two places in one slot | **refuse** | physically impossible; the timetable is wrong |
| **Class** in two places in one slot | **refuse** | physically impossible |
| **Room** double-booked | **warn** | `Grade.room` is a bare number with no room master (D6). Refusing on data this weak would block legitimate saves — two classes may genuinely share a hall |

The teacher and class checks are hard errors *and* DB constraints (D4). The room check is a message, and says
which other class holds the room.

**Two further checks that cost nothing:** a period outside the class's `timeFrom`/`timeTo` window, and a
teacher assigned outside their `timeIn`/`timeOut`. Both **warn** — the existing time fields are loosely
maintained, and a hard refusal on data nobody curates teaches people to work around the system.

### D4 — What the database enforces

```sql
UNIQUE (organization_id, term_id, day_of_week, period_id, staff_id)   -- a teacher is in one place
UNIQUE (organization_id, term_id, day_of_week, period_id, grade_id)   -- a class is in one place
```

Both are enforceable precisely because D2 stores `gradeId` and D1 makes a slot an equality. Room is **not**
constrained, matching its warn-only status.

**A nullable `term_id` inside a UNIQUE key behaves differently from what people expect** — in MySQL, NULLs do
not collide, so two entries with `term_id IS NULL` in the same slot would both be accepted. That is a real hole
in the constraint for schools with no terms defined. It is closed the way 1.1 chose: the validator refuses the
clash regardless, and the constraint is the second line. Stated here because a constraint that silently does
not apply to some tenants is worse than none if nobody wrote it down.

### D5 — A timetable belongs to a TERM, nullable per 1.1

Timetables change between terms. `term_id` is nullable, so a school with no terms keeps one working timetable —
1.1's rule that "a null term is permanently valid" applies here as it did to attendance and fees.

**Not versioned.** Editing a slot edits it; there is no history. Unlike a report card (1.5) or a promotion
(1.6), a timetable is a *plan*, not a record of what happened to a child — and the thing that records what
actually happened is attendance, which already exists. Versioning is named in §6 rather than assumed away.

### D6 — Room is a free-text label, not a foreign key

`Grade.room` is a bare `Long` and there is no `Room` entity, so there is nothing to reference. The entry
carries a `room` string, defaulting to the class's own room when blank.

Inventing a room master here would be a second slice smuggled into this one, and it is genuinely wanted later
(capacity, facilities, 6.x). Deferred honestly — and it is why room clash only warns (D3).

### D7 — Scope

| In | Out |
|---|---|
| `Period` + `TimetableEntry` (V17), org-scoped | a `Room` entity / room booking (D6) |
| grid read per class and per teacher | substitution (2.2 — reads this) |
| clash detection: refuse teacher + class, warn room (D3) | staff attendance (2.3) |
| two UNIQUE constraints (D4) | timetable auto-generation / optimisation |
| copy-timetable-to-next-term | versioning or history (D5, §6) |
| Timetable screen + print + i18n × 6 | student-level electives (one student in a different set) |

### Layer answers (§5b)

| Layer | Answer |
|---|---|
| **UI/UX** | one Timetable screen: pick term + class → a day × period grid → click a cell to set subject/teacher/room. A teacher view of the same data answers "where am I today". Clashes render on the cell that caused them, not in a summary at the top |
| **Service/API** | `/getPeriods`, `/savePeriod`, `/deletePeriod`, `/getTimetable`, `/saveTimetableEntry`, `/deleteTimetableEntry`, `/copyTimetable`. Periods and entries are **`ADMIN_PRIVILEGE`** — a timetable decides where every teacher stands; reads open, because everyone needs to read it |
| **Database** | `period`, `timetable_entry` (V17); the two UNIQUE keys above; index `(organization_id, term_id, grade_id)` for the class grid and `(organization_id, term_id, staff_id)` for the teacher view — **per standard D3b, indexing the query and not just the scope** |
| **Patterns** | entity-as-configuration for periods (1.1/1.4 precedent); **pure validator** `ClashDetector` (the `BandValidator`/`MarksValidator`/`PromotionPolicy` line); DB-enforced uniqueness (1.3 D1); explicit, argued deviation from derive-don't-store (D2) |
| **Microservice design** | education-local. No new service, no shared-library change |
| **Configurability** | the periods themselves, per org. **No setting decides whether clashes are refused** — "may a teacher be in two rooms at once" is not a policy question |
| **DRY** | `StudentVisibilityService` is not reused (this is staff/class-shaped, not student-shaped); `Validations` reused for the numeric/time checks; the day-of-week vocabulary is one enum, not strings |

---

## 3. Architecture & UML

```mermaid
flowchart LR
  UI["Timetable screen<br/>term × class → day/period grid"]
  C["TimetableController"]
  V["ClashDetector<br/>(pure)"]
  P[("period")]
  T[("timetable_entry<br/>2 UNIQUE keys")]
  S[("subject → grade")]
  ST[("staff")]

  UI -->|"1. read grid"| C
  C --> P
  C --> T
  UI -->|"2. save a slot"| C
  C -->|"validate FIRST"| V
  V -->|"teacher / class clash"| T
  V -->|"grade must match the subject's (D2)"| S
  V -->|"room clash → WARN only"| T
  C -->|"3. only then"| T
  V -.->|"refuse: nothing written"| UI
  T -.->|"2.2 substitution reads this"| ST
```

```mermaid
classDiagram
  class Period {
    +Long id
    +String name
    +Integer sequence
    +LocalTime startTime
    +LocalTime endTime
    +boolean teaching
    +Long organizationId
  }
  class TimetableEntry {
    +Long id
    +Long termId
    +DayOfWeek dayOfWeek
    +Long periodId
    +Long subjectId
    +Long gradeId
    +Long staffId
    +String room
    +Long organizationId
  }
  class ClashDetector {
    <<pure>>
    +List~Problem~ check(candidate, existing, subjectGradeId)
    +boolean teacherClash(...)
    +boolean classClash(...)
    +boolean roomClash(...)
  }
  class Problem {
    +String field
    +String message
    +Severity severity
  }
  class Severity {
    <<enumeration>>
    REFUSE
    WARN
  }
  TimetableEntry --> Period
  ClashDetector ..> TimetableEntry : validates
  ClashDetector ..> Problem
  Problem --> Severity
```

```mermaid
sequenceDiagram
  actor Admin
  participant C as TimetableController
  participant V as ClashDetector
  participant DB as timetable_entry

  Admin->>C: 5A, Monday, Period 3 → Maths, Mrs Khan, Room 12
  C->>DB: the term's existing entries (one query)
  DB-->>C: current grid
  C->>V: check(candidate, existing, subject.gradeId)
  alt teacher already booked in that slot
    V-->>C: REFUSE — "Mrs Khan teaches 6B in Period 3 on Monday"
    C-->>Admin: FAILED, naming the other class
    Note over C,DB: nothing written
  else room shared, no hard clash
    V-->>C: WARN — "Room 12 is also used by 6B"
    C->>DB: INSERT
    Note over DB: UNIQUE (org, term, day, period, staff)<br/>and (org, term, day, period, grade) —<br/>the guarantee under a double-clicked save
    C-->>Admin: SUCCESS + the warning
  end
```

---

## 4. Implement — checklist

- [x] `Period` + `TimetableEntry` + `Severity`, Flyway **V17**
- [x] two UNIQUE keys (D4) + the two query indexes (standard D3b)
- [x] `ClashDetector` — **pure**, takes the candidate + the existing grid + a resolved `Context`
- [x] teacher and class clashes REFUSE; room clash, out-of-window period and out-of-hours teacher WARN (D3)
- [x] `entry.gradeId` must equal the subject's grade — **strengthened, see the corrections below**
- [x] validator refuses clashes even when `term_id` is NULL, where the UNIQUE key cannot (D4)
- [x] grid read by class **and** by teacher, each one query
- [x] `copyTimetable` term → term, refusing outright into a non-empty term
- [x] `ADMIN_PRIVILEGE` on every write; reads open
- [x] Timetable screen + i18n × **6 bundles**, 45 lines each, all 43 new keys verified present in all six
- [x] `ClashDetectorTest` (13 pure cases) + `cypress/e2e/education/timetable.cy.js` (9 cases)
- [x] DOM built with `.text()`/jQuery construction, so subject and teacher names cannot inject markup
- [ ] **`.table-scroll` on the grid — NOT added.** `responsive-tables.js` wraps every table automatically
      (its line-30 guard prevents double-wrapping), so adding it by hand would be the duplication that
      guard exists to catch. Verify on a phone during the gate; if the grid overflows, the fix belongs in
      the shared helper, not here.

### Corrections made during implementation

**`gradeId` is now DERIVED server-side, not merely validated.** The design said the write would check
`entry.gradeId == subject.grade.id` and refuse a mismatch. The controller goes further: it never reads
`gradeId` from the request at all, taking it from the resolved subject instead. **A client cannot desync a
value it does not supply** — the check in `ClashDetector` remains as the second line, and is what
`ClashDetectorTest` pins for any future caller that does set the field.

**Deleting a period is refused while it holds lessons.** Not in the design. An orphaned entry is worse
than a blocked delete because it renders on no grid — it is invisible rather than obviously wrong. The
check spans **every** term plus the term-less bucket, since a period is org-wide and checking only the
term on screen would miss the rest.

**A non-teaching period refuses lessons outright.** The design said a break is "just a `Period` with no
entries against it", which describes the intent but not what stops one being created. The save now
refuses, and the UI omits non-teaching periods from the subject picker.

**`copyTimetable` skips re-validation, deliberately.** The target term is empty (or the copy is refused)
and the source was itself clash-free, so the copy cannot introduce a clash. Written as one `saveAll`
batch rather than a validated save per lesson.

## 5. Test

| # | Case | Expected |
|---|---|---|
| 1 | A clean slot | saved |
| 2 | Same teacher, same slot, different class | **refused**, message names the other class |
| 3 | Same class, same slot, different subject | **refused** |
| 4 | Same room, same slot | **saved with a warning** naming the other class |
| 5 | `gradeId` not matching the subject's grade | refused — D2's copy is checked, not trusted |
| 6 | Clash with `term_id` NULL on both rows | **refused by the validator** — the UNIQUE key cannot see it (D4) |
| 7 | Double-clicked save of the same slot | one row; the second hits the UNIQUE key |
| 8 | Period outside the class's `timeFrom`/`timeTo` | saved with a warning |
| 9 | Teacher assigned outside their `timeIn`/`timeOut` | saved with a warning |
| 10 | Copy a term's timetable to an empty term | every entry copied, no clashes |
| 11 | Copy into a term that already has entries | **REFUSED outright** — decided 2026-08-01. Merging into whichever slots happen to be free produces a timetable that is half one term's plan and half another's, with nothing on screen saying which row came from where. A refusal the admin clears deliberately is easier to reason about, and this operation writes a whole term in one go |
| 12 | Teacher view | that teacher's week, one query |
| 13 | A teacher edits a period or slot | 403 — ADMIN tier |
| 14 | Another tenant's entry by id | refused |

Gate: `cypress/e2e/education/timetable.cy.js`.
**Regression:** `sections.cy.js`, `privilege-map.cy.js` (new ADMIN endpoints), `owner-config.cy.js`
(the catalog is unchanged — assert that it did **not** grow, since periods are an entity not a setting).
Pure unit: `ClashDetectorTest`.

## 6. Open / deferred

**`GatewayClient` has no HTTP timeouts — platform-wide, raised by this slice's gate failure.**
`new RestTemplate()` uses the default request factory, so a downstream service that accepts a connection and
never answers holds a monolith request thread until the OS gives up. One slow service can therefore exhaust
the monolith's thread pool and take the whole UI down with it, for every module.

The fix is small — a `SimpleClientHttpRequestFactory` (or `HttpComponentsClientHttpRequestFactory`) with
connect and read timeouts, ideally from `application.properties` so environments can differ. It is
**deliberately not done here**: it changes how every module behaves when a service is slow, and that is a
platform decision, not a timetable one. Worth doing next, and it pairs naturally with the gateway's existing
per-route Resilience4j timeouts (§1.5 of the standards), which today protect the gateway but not the monolith.

**Substitution (2.2)** reads this and is the next slice. Nothing here should be shaped for it beyond keeping
`staffId` on the entry, which it needs.

**A `Room` entity** (D6). Wanted for capacity and facilities; it is what would let room clash graduate from
warning to refusal.

**Versioning / history (D5).** A timetable is a plan, not a record. If a school later needs "what was the
timetable in March", the honest shape is the snapshot pattern 1.5 established, not an audit log.

**Student-level electives.** One student in a different set for one subject breaks the class-is-the-unit
assumption throughout. Real for senior years, and its own slice.

## 7. Risks

- **D2 is a deliberate deviation from an established precedent.** If the `gradeId == subject.grade` check is
  ever dropped, the copy drifts and 1.2 D2's warning comes true. The check is in the checklist and test 5.
- **The nullable `term_id` weakens both UNIQUE keys** for tenants with no terms. Mitigated in the validator,
  and worth re-reading if terms ever become mandatory.
- **`Staff.grades` is `@ManyToMany(EAGER)`.** Loading staff for a timetable screen pulls their grade
  collections whether or not they are needed. Not this slice's to fix, but it is on the read path 2.1 makes hot
  — measure before assuming it is fine, and treat it as a candidate for the finding-D treatment.
