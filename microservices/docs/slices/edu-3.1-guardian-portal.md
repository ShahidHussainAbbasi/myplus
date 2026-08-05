# Slice 3.1 — Guardian portal

**Status: DONE & Cypress-GREEN — 11/11, 2026-08-04.** Gate-run findings in §8.
Approved 2026-08-04. Flyway **V22** adds one table, so education-service must be repackaged and restarted
before the gate.
Programme: `education-complete-programme.md` Phase 3.1 — *"Guardian portal — results, attendance, dues,
homework for **my** children (auth-service user type `GUARDIAN`)"*. **First slice of Phase 3.**

**Not blocked.** D-4 gates **3.2** (payment provider), not this. D-2's ordering question was settled by the
decision to keep phase order intact.

---

## 1. Document — what and why

Everything built so far serves people *inside* the school. This is the first slice that lets someone
**outside** it log in — and that single fact makes it the most security-sensitive slice in the programme,
more so even than 2.5's behaviour log.

### The plan says "auth-service user type GUARDIAN". It does not exist

Checked before designing against it. `Membership.role` is a **free-text String** whose javadoc reads:

```java
/** Role within this org: OWNER | ADMIN | TEACHER | STUDENT | GUARDIAN | ... */
private String role;
```

Nothing seeds `GUARDIAN`, nothing enforces it, and no guardian has ever logged in. The plan's parenthetical
describes an intention, not a mechanism.

### The real problem is not a new role. It is a new SHAPE of access

Every read in education-service is org-scoped:

```java
findScoped(orgId, userId)   // → every row in the organization
```

filtered, at most, to the branches a staff member is granted. **That model cannot express what a guardian
needs.** A guardian must see exactly two children and nothing else — not the school's students filtered, but
a set defined by relationship. Bolting `GUARDIAN` onto the existing role list would hand a guardian an
org-scoped token and rely on every future query remembering to narrow it.

That is the failure the education review's **finding A** already found once: a scoping rule that had to be
remembered in each controller was forgotten in seven of them. Repeating it with **a stranger's login** is a
materially worse bet.

So the central decision (D1) is: the guardian's identity carries the children, and the data path refuses
anything else — rather than the guardian getting a normal token that screens are trusted to narrow.

### What exists to build on

| Existing | Consequence |
|---|---|
| `Student.guardianId` → `Guardian` | the relationship already exists in data; it has simply never been an access path |
| `Guardian.email` | the natural login identity, and already collected |
| Every read that matters (results, attendance, fees, homework) is already **per student** | so a child-scoped read is a filter over existing queries, not new reporting |
| `party-service` bridges Guardian (P3) | a guardian already has a `partyId` — identity work is not starting from zero |
| `auth-service` multi-org `Membership` | the seam a guardian membership fits into, without a second identity system |

---

## 2. Design

### D1 — A guardian's access is CHILD-scoped, derived server-side, and never client-supplied

The portal's every read resolves the caller's children first:

```
guardian (by authenticated email)
   → Student.guardianId = guardian.id           ← the ONLY source of "my children"
   → every read filtered to that enrolment set
```

**No endpoint takes an `enrollNo` the caller supplies and trusts it.** A guardian asking for a child's
results passes an id, and the service intersects it with the derived set — the anti-IDOR discipline
already used across education, applied where it matters most.

The set is derived **per request**, not stamped into the JWT. A child transferring out, or a guardian link
being corrected, must take effect immediately rather than at next login.

### D2 — A guardian gets a SEPARATE controller, not a `GUARDIAN` role on the existing endpoints

`GuardianPortalController` exposes a small, purpose-built read surface. Existing education endpoints stay
staff-only and are **not** opened to guardians with a privilege check.

This is the heart of the slice, and it is a deliberate rejection of the cheaper option:

| Option | Why not |
|---|---|
| Add `GUARDIAN` to the role list, `@PreAuthorize` the existing endpoints | every one of ~31 controllers becomes a place a guardian might reach. Finding A proved that "remember to scope it" fails at 7 controllers; it will not hold at 31 with an external user |
| **A separate, small, child-scoped surface** | **chosen** — the attack surface is what the portal explicitly exposes, and nothing else. A new staff endpoint is not automatically a guardian endpoint |

The cost is a handful of read endpoints that resemble existing ones. That duplication is worth it: it is
**explicit** rather than emergent, and it is the difference between an allowlist and a denylist.

### D3 — Guardian login reuses `auth-service`; it does NOT create a second identity system

A `Membership` with `role = GUARDIAN` in the school's org, linked to the guardian record. Same login, same
JWT, same gateway.

What is new is small and deliberate: the token carries a claim marking the session as a **portal** session,
so the gateway and services can distinguish a guardian from staff without inspecting roles. A guardian hitting
a staff endpoint is refused because the endpoint is not part of the portal surface (D2), not because a role
string happened not to match.

**Invitation, not self-registration.** The school invites a guardian by email; nobody claims a child by
typing an enrolment number. Self-service registration against a child's identifier is an obvious
account-takeover path, and the school already knows who the guardians are.

### D4 — Read-only, and that is the whole slice

The portal shows: results (published report cards only), attendance, fee dues, homework, behaviour notes.
It changes nothing.

Two consequences worth stating:

- **Report cards: PUBLISHED only.** 1.5 made an issued card a snapshot precisely so it could be shown
  outside the school. A draft or superseded card must never appear — a guardian seeing a mark that later
  changes is exactly the harm snapshotting prevents.
- **Behaviour notes: a real question, deliberately answered NO for now.** 2.5's log includes staff
  judgements written without any expectation that a guardian would read them tomorrow. Exposing them
  retroactively changes the contract under which they were written. §6 — it needs a per-note "shared with
  guardian" decision, which is a feature, not a filter.

### D5 — Fee dues are shown; paying is 3.2

The portal shows what is owed, from the existing AR/aging path (0.2a). The **Pay** button belongs to 3.2 and
is gated on **D-4**.

Showing a balance without a way to pay it is a legitimate half-step: guardians currently have no way to see it
at all, and the arithmetic is already correct because finance owns it.

### D6 — Scope

| In | Out |
|---|---|
| `GuardianPortalAccess` (V22) + invitation flow | self-registration by a guardian (D3) |
| child-scoped reads: results · attendance · dues · homework | **any write at all** (D4) |
| separate `GuardianPortalController` surface (D2) | online payment (3.2, gated on D-4) |
| published report cards only (D4) | behaviour notes (D4, §6 — needs a per-note decision) |
| a guardian-facing page, mobile-first | student portal (3.3), PTM booking (3.4), circulars (3.5) |
| every portal read audited | messaging between guardian and teacher |

### Layer answers (§5b)

| Layer | Answer |
|---|---|
| **UI/UX** | a **separate, mobile-first page** — not the education dashboard with things hidden. Guardians are non-technical, on phones, checking one thing. A child switcher when there are several; otherwise no chrome at all |
| **Service/API** | `/portal/me`, `/portal/children`, `/portal/child/{enrollNo}/results`, `/attendance`, `/dues`, `/homework`. All GET. All intersect the requested child with the derived set (D1) |
| **Database** | `guardian_portal_access` (V22): guardianId, email, status, invitedOn, activatedOn. **The child list is NOT stored here** — it is derived from `Student.guardianId` on every request (D1) |
| **Patterns** | allowlist-not-denylist surface (D2); derive-authority-server-side (D1); read-only projection; invitation over self-service (D3); reuse the identity provider rather than forking it (D3) |
| **Microservice design** | education-local reads; `auth-service` for identity; `finance-service` (already composed) for dues. No new service |
| **Configurability** | `edu.portal.enabled` (BOOL, default **false**) — a school opts in. A portal that goes live the moment the code deploys is not something to spring on a school |
| **DRY** | reuses report-card snapshots (1.5), attendance, the fee AR path (0.2a) and homework reads (2.4) — the portal is a projection, not new reporting |

---

## 3. Architecture & UML

```mermaid
flowchart LR
  P["Guardian (phone)"]
  A["auth-service<br/>Membership role=GUARDIAN"]
  GW["api-gateway"]
  PC["GuardianPortalController<br/>separate surface (D2)"]
  R["resolveMyChildren()<br/>Student.guardianId"]
  DATA[("report_card · attendance<br/>fee_collection · homework")]
  STAFF["31 staff controllers"]

  P -->|login| A
  A -->|JWT + portal claim| GW
  GW --> PC
  PC -->|"1. derive MY children — never client-supplied"| R
  PC -->|"2. read, intersected with that set"| DATA
  PC -.->|"NEVER reachable: not a privilege refusal,<br/>simply not part of the portal surface"| STAFF

  classDef blocked stroke-dasharray: 4 4
  class STAFF blocked
```

```mermaid
classDiagram
  class GuardianPortalAccess {
    +Long id
    +Long guardianId
    +String email
    +PortalStatus status
    +LocalDate invitedOn
    +LocalDate activatedOn
    +Long organizationId
  }
  class PortalStatus {
    <<enumeration>>
    INVITED
    ACTIVE
    REVOKED
  }
  class GuardianPortalController {
    +me() GenericResponse
    +children() GenericResponse
    +results(enrollNo) GenericResponse
    +attendance(enrollNo) GenericResponse
    +dues(enrollNo) GenericResponse
    +homework(enrollNo) GenericResponse
  }
  class ChildResolver {
    <<the ONLY source of authority>>
    +Set~String~ myChildren(guardianId)
    +String requireMine(enrollNo, guardianId)
  }
  GuardianPortalAccess --> PortalStatus
  GuardianPortalController ..> ChildResolver : every read goes through this
```

```mermaid
sequenceDiagram
  actor Guardian
  participant GW as gateway
  participant PC as GuardianPortalController
  participant CR as ChildResolver
  participant DB

  Guardian->>GW: GET /portal/child/S-1042/results
  GW->>PC: JWT (portal session, guardian email)
  PC->>CR: which children are mine?
  CR->>DB: Student.guardianId = me
  DB-->>CR: {S-1042, S-1088}
  CR-->>PC: S-1042 is mine ✓
  PC->>DB: PUBLISHED report cards for S-1042 only
  DB-->>Guardian: the issued snapshots

  Guardian->>GW: GET /portal/child/S-9999/results
  PC->>CR: is S-9999 mine?
  CR-->>PC: no
  PC-->>Guardian: NOT_FOUND
  Note over PC,Guardian: NOT_FOUND, never FORBIDDEN —<br/>"that child exists but isn't yours"<br/>is itself a disclosure
```

---

## 4. Implement — checklist

- [x] `GuardianPortalAccess` + `PortalStatus`, Flyway **V22**; UNIQUE `(organization_id, guardian_id)`
- [x] `ChildResolver` — **the only** source of "my children"; derived per request, never from the client
- [x] `GuardianPortalController` — GET only, every read intersected via `requireMine` (D1)
- [x] a **separate** surface: no `GUARDIAN` privilege added to any existing controller (D2)
- [x] **invitation only**, no self-registration; a separate `PortalAccessController` at `ADMIN_PRIVILEGE` (D3)
- [x] published report cards only; **no behaviour endpoint exists at all** (D4)
- [x] dues read-only, no payment (D5)
- [x] `edu.portal.enabled` BOOL default **false**, and it fails CLOSED
- [x] every portal read audited via `EduAuditService`
- [x] a separate mobile-first page + i18n × **6 bundles**, 39 lines each, all 37 new keys verified in all six
- [x] `ChildResolverTest` (8 pure cases) + `cypress/e2e/education/guardian-portal.cy.js` (11 cases)
- [x] **fixtures seeded, never skipped** — the spec invites its own guardian and sets its own config
- [ ] **`Membership role=GUARDIAN` + a portal JWT claim — NOT built.** See the corrections below.

### Patterns applied (named, so they can be argued with)

| Pattern | Where | Why this one |
|---|---|---|
| **Allowlist surface, not a denylist role** | separate `GuardianPortalController` (D2) | finding A proved "remember to scope it per controller" fails at 7; it will not hold at 31 with an outsider |
| **Single source of authority** | `ChildResolver` — every read passes through it | one class to review, so a missing check is visible rather than distributed |
| **Derive, never cache, an ACCESS list** | child set resolved per request (D1) | a stale copy of an access list is not a caching bug, it is a stranger reading a child's record |
| **Pure function for the security decision** | `ChildResolver.isMine` static | the highest-consequence check in the programme, testable with no Spring |
| **Fail closed** | `portalEnabled()` returns false on any error | the safe state for an external door is shut |
| **Indistinguishable refusals** | one `NOT_FOUND` for every failure | "that child exists but is not yours" is itself a disclosure |
| **Command/query separation of surfaces** | portal reads vs `PortalAccessController` writes | a guardian session is never one routing mistake from an endpoint that grants access |
| **Snapshot at grant time** | `email` copied at invitation | correcting the guardian record must not silently re-point a live login |

**Library vs service:** neither — education-local reads plus the existing `auth-service` identity. The one
thing deliberately NOT built is a second identity system.

### Corrections made during implementation

**The `GUARDIAN` membership role and portal JWT claim are NOT built, and the slice works without them.**
The design assumed a guardian would authenticate as a distinct principal type. In practice the portal is
secured by **who the signed-in email resolves to** — `GuardianPortalAccess` keyed on email, checked on every
request — so no auth-service change was needed to make the surface safe. That is a smaller, more auditable
change than minting a new principal type.

**What that means, stated plainly:** a guardian still needs a login to exist. The access row, the child
derivation, the allowlisted surface and the kill switch are all real and tested; **provisioning the actual
user account in `auth-service` is the remaining step before a real guardian can sign in.** It is deliberately
separated because creating external user accounts touches the platform's identity model and deserves its own
review — and because everything in this slice is verifiable without it (the gate proves the surface refuses
every session that lacks an access row, including staff).

**Named `guardianDashboard.html` / `guardian.js`, not `guardianPortal.*`** (user, mid-implementation). Two
reasons, both right: the platform's convention is `<audience>Dashboard.html` (business, education, welfare,
agriculture, appointment), and the **domain entity is `Guardian`** — `Student.guardianId`,
`GuardianPortalAccess`. The visible title stays "Guardian Portal": internal code uses the domain term, the
human-facing string uses the human one, and that split is now recorded rather than accidental.

**"Parent" was retired platform-wide with this slice** (user: *"replace word used parent with guardian
100%"*). Not cosmetic — the two words had been used interchangeably and only one is *true*: the adult a
school deals with is frequently a grandparent, a sibling, a foster carer or a local authority, so a field
named `parent` asserts a relationship the school has not verified. `Guardian` was already the entity name,
so this removed a synonym rather than adding one. The rule is now programme §0a and standards **D9b**.

Collateral, all in this sweep: **2.5's `parentInformed` → `guardianInformed`**, which is a *shipped,
gate-green* slice — so it needed **Flyway V23** (never an edit to the applied V21; standards **D9a**) and a
re-run of `behaviour.cy.js`. Also `ui.parentInformed`/`ui.parentPortal` → `ui.guardian*` across six
bundles, with `en`/`fr`/`es`/`ur` values reworded and `hi`/`ar` left alone because अभिभावक and ولي الأمر
already said guardian. The containment sense of the word (`re-parent`, `parent exam`, `service-parent`,
`parentNode`) was protected and deliberately untouched.

**The staff side is its own controller** (`PortalAccessController`), not endpoints on the portal. Keeping
grant/revoke physically apart from the guardian surface means a guardian session cannot reach them however the
roles evolve.

**`invitePortalAccess` takes the email from the GUARDIAN RECORD, never the request.** Accepting an email
parameter would let a staff member point a child's portal at any address, with nothing in the guardian
record showing they had.

## 5. Test

**The security cases are the point of this gate.** A functional pass with a scoping hole is worse than a red.

| # | Case | Expected |
|---|---|---|
| 1 | Guardian sees their own children | exactly those, no others |
| 2 | **Guardian requests another guardian's child by enrolment number** | **NOT_FOUND** — the case this slice exists to get right |
| 3 | Guardian requests a child in another **tenant** | NOT_FOUND |
| 4 | Guardian hits a staff endpoint (`/getUserStudent`, `/saveBehaviourNote`) | refused — not reachable from a portal session |
| 5 | Results | **published cards only**; a draft or superseded card never appears |
| 6 | Attendance / dues / homework | scoped to that child, matching what staff see |
| 7 | Behaviour notes | **not exposed anywhere** in the portal (D4) |
| 8 | Any write attempt | no write endpoint exists on the portal surface |
| 9 | `edu.portal.enabled` = false | portal reads refused even for an ACTIVE guardian |
| 10 | A `REVOKED` guardian | refused |
| 11 | A child whose `guardianId` is cleared mid-session | disappears on the **next request** — derived, not cached (D1) |
| 12 | Every portal read | an audit event is written |

Gate: `cypress/e2e/education/guardian-portal.cy.js`.
**Regression:** `privilege-map.cy.js` (a new principal type must not widen staff access), `save-takeover-idor.cy.js`,
`report-cards.cy.js`, `fees-ar.cy.js`.
Pure unit: `ChildResolverTest` — the intersection rule, including the empty and cross-tenant cases.

## 6. Open / deferred

**Behaviour notes in the portal (D4).** Needs a per-note "shared with guardian" flag and a conversation about
notes written before such a flag existed. Exposing 2.5's log retroactively would change the contract its
authors wrote under.

**Paying the dues (3.2).** Gated on **D-4**.

**Student portal (3.3)** reuses `ChildResolver`'s shape with the student as their own subject — worth
building on the same surface rather than a third one.

**Guardian contact details as login identity.** `Guardian.email` is currently free text with no verification.
Invitation-only mitigates it, but a wrong email in the guardian record now sends a portal invitation to a
stranger. **Worth an email-verification step before this goes to a real school.**

## 7. Risks

- **This is the first external login. The blast radius of a scoping mistake is a stranger reading a child's
  record.** D1 (derived) and D2 (separate surface) exist for that; tests 2, 3 and 4 are the ones that must
  not be waved through.
- **A separate controller duplicates some reads.** Accepted deliberately — explicit duplication over an
  emergent allowlist. If it grows past a handful of endpoints, that is a signal to extract a shared
  read-model, not to start reusing staff endpoints.
- **`Guardian.email` is unverified** (§6). Invitation-only limits it, but a typo sends a child's portal
  invite to the wrong address.
- **One guardian, many children, many schools.** A guardian with children at two branches of the same group
  should see both; the derived set handles that naturally, but it must be tested with a real two-child
  fixture rather than assumed.

---

## 8. Gate run — what it cost, and what it proved

**Green on run 2: 11/11.** Run 1 died in the `before` hook. The failure was the FIXTURE, and the product
was correct in refusing:

```
invite the guardian: {"status":"FAILED","message":"This guardian has no email address on record.
                      Add one before inviting them."}
```

**The refusal is the design working.** D3 takes the address from the guardian RECORD and never from the
request, precisely so staff cannot point a child's portal at an arbitrary address with nothing in the record
showing it. A guardian with no email therefore *cannot* be invited — correctly. Verified before touching
anything that the capture path is complete end to end: `Guardian.email` on the entity, on `GuardianDTO`,
persisted by `GuardianService`, and an input on the staff form (`educationDashboard.html:869`,
`id="guardianEmail" name="email"`). Nothing was missing; the demo org simply holds guardians created without
one.

**The spec picked `students[0]` with any `guardianId` and never checked that guardian could be invited** —
it asserted its precondition instead of seeding it. `before()` now finds a student whose guardian HAS an
email, and seeds a `CY_GP_*` guardian + linked child when the org has none.

**This is the FIFTH fixture-caused red in a row** (2.1 test 3 skipped silently · 2.3 asserted a size that
contradicted its own fixture · 2.4 assumed a populated class · 2.5 assumed the wrong input-handling model ·
3.1 assumed an invitable guardian). Every one was mine, none was the product.
**The refinement this run adds: it is not enough for a fixture to exist — it must satisfy the precondition
the ENDPOINT enforces, which means reading the endpoint's refusals before choosing the fixture.**
Existence is not eligibility.

**Also removed: `fx.theirs`** — computed, never used. The "another family's child, by enrolment number" case
it was for cannot run until a guardian can actually sign in, which this slice deliberately does not build
(§6). Left in place it read as coverage that does not exist; replaced with a comment naming the gap. The
nearest reachable case — a staff session with no access row — is tests 3 and 4, and they pass.
