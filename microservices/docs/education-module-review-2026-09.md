# Education module — end-to-end review

**2026-09-25.** Measured against the code on `feature/pack-loose-selling`. Every number below came from
counting the source or querying the running database; where something could not be verified at runtime it
says so.

⚠ **The education service was not running while this was written.** `myplus-education`, `myplus-appointment`,
`myplus-welfare`, `myplus-analytics`, `myplus-campaign` and `myplus-marketplace` are all
`Exited (137)` — SIGKILL, i.e. OOM — five of them 43 minutes before this review. So findings marked
*code-verified* have not been reproduced against a live endpoint.

---

## 1 · The authorization surface

| | Count |
|---|---|
| Controllers | **36** |
| Endpoints | **173** |
| — writes (POST/PUT/DELETE/PATCH) | **84** — 75 guarded, **9 ungated** |
| — reads (GET) | **89** — **0 guarded** |
| Controllers that scope by organisation | **31 of 36** |
| Endpoints gated by an `area.action` permission code | **0** |

The guards that exist are coarse and role-shaped, not task-shaped:

| Guard | Uses |
|---|---|
| `hasAuthority('ADMIN_PRIVILEGE')` | 36 |
| `hasAuthority('DELETE_PRIVILEGE')` | 23 |
| `hasAuthority('WRITE_PRIVILEGE')` | 14 |
| `ROLE_OWNER` combinations | 2 |

> **Correction to an earlier pass of this same audit.** A first script reported *52* ungated writes. It
> scanned only upwards from the mapping line for `@PreAuthorize`, and this codebase frequently writes the
> annotation *below* it (`@RequestMapping` → `@ResponseBody` → `@PreAuthorize`). The real figure is **9**.
> Recorded because the wrong number would have justified a far larger and more disruptive change than the
> module needs.

---

## 2 · Gaps, most serious first

### G1 ⚠ `Alerts` and `AlertChannel` are addressable by id with no tenant check — *code-verified*

`AlertsController` and `AlertChannelController` are two of the five controllers that never mention
organisation scoping. Their list endpoints scope by `user.getUserId()`, but the by-id endpoints do not:

```java
@GetMapping("/{id}")    public ApiResponse<?> get(@PathVariable Long id)    { return …alertsService.get(id); }
@PutMapping("/{id}")    public ApiResponse<?> update(@PathVariable Long id, …)
@DeleteMapping("/{id}") public void delete(@PathVariable Long id)
```

and the service resolves with a bare lookup:

```java
public Alerts getEntity(Long id) {
    return alertsRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Alert not found: " + id));
}
```

The `Alerts` entity **carries `organizationId` and `userId`** — the tenancy data is there and is simply not
consulted. This is the same shape as the marketplace quote defect (SCOPE-1): the list is scoped, open-by-id
is not.

**Not reproduced at runtime** — the service is down. The dev database holds 11 alert rows, all org 24, so a
probe as `owner.education@` (org 13) would have settled it in one request. Worth doing before the fix, so the
fix has a red to turn green.

### G2 · 89 reads carry no authority check at all

Mitigated, but real. 31 of 36 controllers scope reads by organisation — `StudentController.getAllStudent`
goes through `StudentVisibilityService.visibleStudents(orgId(), userId())`, and the fee reads
(`/loadFV`, `/loadFL`) take `orgId()` and `userId()`. So this is **not** cross-tenant exposure.

What it is: **every member of staff can read everything in their own school.** A teacher can pull the fee
ledger, every guardian's contact details and every student record. That is exactly what EDU-PERM-1 exists to
fix, and why its slice 3 is the one that matters.

### G3 · The bulk path is weaker than the single path

| Endpoint | Guard |
|---|---|
| `POST /addStudent` (one student) | `WRITE_PRIVILEGE` |
| `POST /impStudents` (a spreadsheet of students) | **none** |

`impStudents` is real and functional — it parses the upload and writes rows (it *is* org-scoped via
`findScoped(org, uid)`). Any signed-in education user can bulk-import students while adding one requires a
privilege. Whatever the right guard is, the bulk path should not be the weaker of the two.

### G4 · Messaging to parents is ungated

`AlertController POST /sendAlerts` and `POST /sendPA` have no guard. These send to parents; the reputational
blast radius of a wrong send is larger than most writes on this list.

### G5 · Nine education privileges that nothing reads — *already confirmed earlier this month*

`role_privileges_education.properties` declares `ADD_STUDENT`, `GET_SUBJECT`, `UPDATE_CLASS` and six more.
**0** are referenced by any `sec:authorize`, and **0** exist among the 35 privileges in `myplusdb_auth`. Dead
configuration that reads as a working permission model.

### G6 · Three more controllers with no tenant scoping to explain

`DashboardController`, `PartyLinkController` and `SettingsController` never mention organisation scoping.
Unlike G1 they were not traced to a concrete leak — they need the same read-every-caller pass G1 got. Listed
so the review is honest about what was checked and what was not.

---

## 3 · What is well built — and should not be "fixed"

Stated because a list of gaps invites uniform changes, and two of these would be made worse by one.

- **The guardian and student portals are correct.** No `@PreAuthorize`, but every endpoint resolves
  `mineOrNull(request)` → `notYours()`, scopes by `orgId()`, and writes an audit line
  (`audit("PORTAL_READ_RESULTS", …)`). For a portal, **ownership is the guard**; an authority check would add
  nothing. Do not "fix" these by adding role guards.
- **`invitePortalAccess` is properly protected** — `ADMIN_PRIVILEGE` *and*
  `guardianRepository.findByIdScoped(guardianId, org, uid)`. Granting an outsider sight of a child's record
  is the highest-stakes write in the module and it is the best-guarded one.
- **`StudentVisibilityService`** was extracted because three controllers held byte-identical copies of the
  visibility rule and a fourth was about to be written. That is the right instinct and the right fix.
- **86% of controllers scope by tenant** (31/36).

---

## 4 · Test coverage

**48 education specs.** Fourteen controller domains have no spec whose *name* matches them:

`academicyear · alertchannel · discount · feecollection · grade · guardianportal · partylink · portalaccess ·
reportcard · school · staffattendance · studentportal · subject · vehicle`

⚠ **Name-matching is a weak proxy** and this list overstates the gap: `portal-sign-in.cy.js` and
`student-portal.cy.js` clearly exercise the portal controllers. Treat it as a list to check, not a list of
holes. The ones I would check first are `portalaccess` and `reportcard` — the two with the most consequential
writes.

---

## 5 · What is already in flight

**EDU-PERM-1** (design: `slices/edu-perm-1-education-permission-sets.md`) closes G2 and G5.

| Slice | State |
|---|---|
| 1 · module axis, 44 education codes + 8 COMMON, 5 built-in sets, module-aware minting | **done, gated** |
| 2 · the permission matrix on the education screen | **done, gated** (education 11/11, business 16/16) |
| 3 · server guards on the endpoints | **not started — this is what G2 needs** |
| 4 · UI guards replace the 34 `ADMIN_PRIVILEGE` checks | not started |
| 5 · "my classes only" (class assignment) | not started |

⚠ **auth-service is awaiting a rebuild** for the placement fix (`defaultSetFor` by module + the `assign()`
module guard + V18). Until then, new education members are still placed on the shop's `Standard` set.

---

## 6 · Suggested order

1. **G1** — two controllers, a scoped finder each. Smallest change, largest severity. Probe it live first so
   the fix has a red to turn green.
2. **G3 and G4** — four endpoints, one annotation each. Same shape as the guards already around them.
3. **Rebuild auth-service** so placement is correct before anything depends on the codes.
4. **EDU-PERM-1 slice 3** — the real answer to G2, and the only one of these that needs the owner / admin /
   teacher / accountant ladder walked before it ships.
5. **G6** — read every caller in the three unexplained controllers.
6. **G5** — retire the nine dead privileges, once nothing claims to read them.

Items 1–2 are small, independent and safe. Item 4 changes who can do what and deserves its own gate run.
