# SCHED-2 — a slow scheduling call never reports ERROR for work that happened · MAIL-DEV-2 — no dev path reaches Gmail

Status: **Design → Implement (consented 2026-10-04: "review e2e the remain open and fix 100%")**

## 1 · Open item A — "a meeting slot can be created while the screen says ERROR"

Found 2026-10-04 in the education suite: `meetings.cy.js` case 1 got
`I/O error on POST … /api/scheduling/slots/generate: Read timed out`, and case 2 then found **6 slots already made**.

**Trace (Rule 0).**
- Caller chain: screen → `MeetingController.publishMeetingSlots` → `MeetingService.publishSlots` →
  `SchedulingClient.generate` (education, **read timeout 3 s**, `SchedulingClientConfig`) → appointment-service
  `SchedulingController.generate` → `SchedulingService.generate`. One caller at every hop (grep).
- The same client also carries `slots`, `book` and `cancel` — one caller each, all in `MeetingService`.
- **Why it can exceed 3 s:** `generate` is `@Transactional` and, per slot, opens a `REQUIRES_NEW` transaction
  (`saveSlot`) — a second pooled connection, one commit per slot; an existing slot is found by letting the INSERT
  fail on `uk_slot_provider_time` and catching it. An evening at 10 min is 18+ commits (or 18+ exceptions when
  re-published). Warm, that is well under a second; on the first call after idle it was not.
- **Why the screen lied:** a read timeout is `ResourceAccessException`; `MeetingController` has no case for it, so it
  fell into `catch (Exception)` → `ERROR` + the raw I/O text — while the server finished the work.
- **What makes a retry safe:** every one of these calls is idempotent on the server, by a UNIQUE key —
  `uk_slot_provider_time` (generate), `uk_booking_slot_attendee` (book), cancel-by-id, and `slots` is a read.

**Design.**
- **A1 · appointment-service `generate` — one read, one write.** No outer transaction (it only orchestrates). Read
  the provider's existing start times in the window once (`findByProviderInWindow`, already exists), insert only the
  missing slots in **one** `REQUIRES_NEW` transaction (`saveSlots`). If that insert hits the UNIQUE key — a
  concurrent publish of the same evening — fall back to the existing slot-by-slot path, which already confirms each
  clash is that key and not another. Same response: `{created, alreadyExisted}`.
- **A2 · education `MeetingService` — retry an idempotent call once on an I/O failure.** On
  `ResourceAccessException` try once more; the second answer is the truth (a publish that landed reports
  `alreadyExisted`). If the retry fails too, refuse with what is actually known:
  *"The scheduling service did not answer in time. The slots may already be published — open the evening and check
  before publishing again."* (book / cancel worded likewise). It is an `IllegalStateException`, so the controller
  answers `FAILED` with that sentence and logs the cause — never `ERROR` with a stack-trace fragment.
- Not changed: the 3 s timeout. A portal family must not wait longer; A1 makes the work fit inside it.

**Tests.** `SchedulingServiceTest` (appointment, Mockito): fresh window → one batch, all created; partly published →
only the missing inserted, the rest counted, no exception path; a racing publish → falls back and still counts right.
`MeetingServiceRetryTest` (education): timeout then success → success; timeout twice → the honest refusal; a
non-I/O error is not retried. E2E: `meetings.cy.js` stays 8/8.

## 2 · Open item B — "production sends from the account dev tests locked out"

**Trace.** Every dev way of running the services, and where its mail goes:

| How it runs | Mail before | After |
|---|---|---|
| Docker dev stack | Gmail | Mailpit — EDU-NOTIFY-2 (`COMPOSE_FILE` in dev `.env`) |
| **Local stack, `start-all.ps1`** | **Gmail** — it loads the same `.env`, which sets no `MAIL_HOST` | Mailpit (B2) |
| Production (Docker, its own `.env`) | Gmail | unchanged — and must get its own sender (B3) |

**Design.**
- **B1** `docker-compose.mailpit.yml` also publishes SMTP on **127.0.0.1:1025** (this machine only), so a process
  outside Docker can reach the catcher.
- **B2** `start-all.ps1` — after loading `.env`, if `MAIL_HOST` is **not** set, point every service at
  `localhost:1025` over plain SMTP (`MAIL_*` and `SPRING_MAIL_*`) and say so on the console. Setting `MAIL_HOST` in
  `.env` is the deliberate escape hatch. The script is dev-only; production deploys through Docker.
- **B3** Docs: `DEPLOY-COMMON.md` and `.env.example` state that production uses **its own** sender account (or a
  transactional mail provider) that no dev or test machine ever holds the password for, and that dev mail goes to
  Mailpit. Choosing and creating that account is the owner's decision — it needs credentials, not code.

**Gates.** `start-all.ps1` prints where mail goes; with the local stack up, a sent notice appears in Mailpit.

## 3 · Result (2026-10-04) — GREEN

- Unit: `SchedulingServiceTest` 5/5, `MeetingServiceRetryTest` 4/4.
- Found during the screen walk, fixed: **the Teacher list on Parents' evenings was EMPTY** (`#meStaff` vs the
  loader's `#meStaffDD`) — no school could publish by hand; every API-level case stayed green.
- `meetings.cy.js` 10/10 (new: the SCREEN case, red on the old monolith; three 48-slot evenings). Its fixture
  windows collided across runs — now one 16-hour block per run second; its evening is dated 2999 and closed after.
- `education-modal-keyboard.cy.js` 30/30 — it walked before the form's delayed first-field focus landed; it now waits.
- Test Book §23: 5 step-by-step cases recorded, 5/5 (`sched2-guide-screens.cy.js`); P4: 48 slots answered in 0.5 s.
- Full education suite, all 48 specs: **409 passing, 1 pending** — run as 11 + 19 + 18 after a low-memory stop.
  The one red (`notification-outbox`, `localIsoDate is not defined`) came from another session's in-progress TZ-2
  edit to cypress/support/e2e.js landing mid-run; re-run alone 7/7.
- Probe on the OLD build: 2 of 6 48-slot publishes ran past 3 s and showed ERROR while the slots were created.
- Cleanup: 17 stale open `CyME…` test evenings and 4 left by a failed guide run were closed (reversible).
