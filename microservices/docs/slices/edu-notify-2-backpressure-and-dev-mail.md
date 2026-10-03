# EDU-NOTIFY-2 — a full email queue never fails the request, and dev mail never reaches Gmail

Status: **Design → Implement (consented 2026-10-03)** · Owner: education-service, docker dev stack, one stale spec

## 1 · What was found (2026-10-03, education suite 398/408)

Nine reds in three specs, all traced; none from the EDU-IDOR-2 change.

| Spec | Reds | Cause |
|---|---|---|
| `notices.cy.js` | 1–2 | publish → **InternalError**. `NotifyAsyncConfig` uses `CallerRunsPolicy`; with the 500-slot queue full, delivery runs **on the request thread**, one SMTP attempt per recipient, past the gateway's 20 s limit. The notice *is* saved. |
| `notification-delivery.cy.js` | 1–5 | the delivery row appears minutes late (first attempt 4 min after enqueue), the spec waits 12 s. |
| `picker-refresh-safety.cy.js` | 2 | **stale spec**: asserts a rebuild after a fetch that changed nothing. PSEL-1 (25304ae2) rebuilds only when options change, on purpose. |

Root of the first two: **Gmail refuses the dev stack's logins** — `454-4.7.0 Too many login attempts`, 723 in 45 min.
Every test email (`cyn105…@example.test`, `stmt…@t.com`) is a real SMTP login; each failure is retried, so the
retries sustain the lockout. `notify_outbox` PENDING grew 963 → 2,301 during the day.

⚠ **The same Gmail account (`maxtheservice@gmail.com`) is the production sender**, and `deploy.ps1` states local
and prod run the same compose file and project name. A dev-induced lockout is a lockout of the account.

## 2 · Design

### 2a · Never send on the request thread — `NotifyAsyncConfig`
`CallerRunsPolicy` → **`LeavePendingForRelay`**: a rejected delivery is *not run*; it logs one WARN naming the
queue depth. The row is already committed PENDING (that is what `queue()` does before publishing the event), and
`EduNotifyService.flushPending()` re-drives PENDING rows every 30 s. So a rejection costs latency, never a message.

*Why this is the right trade (named pattern: **load shedding over a durable outbox**).* CallerRunsPolicy is
back-pressure for work that would otherwise be **lost**; here nothing is lost, because the outbox row is the
durable copy. Running SMTP on a servlet thread is the exact failure D3 forbids, and it is reached precisely when
the mail server is already slow — the worst moment to hold a request open.

Readers / callers / writers (Rule 0):
- **Callers of the executor:** 1 — `@Async("notifyExecutor") onEnqueued`. No other bean names it (grep).
- **Other `CallerRunsPolicy` in the repo:** 0 others (grep over `microservices/**`).
- **Writer of the PENDING row:** `queue()` saves before publishing → the row exists before any rejection.
- **Reader that re-drives:** `flushPending()` → `OutboxRelay.flush` → `pending()` = top 100 PENDING by id.

Test: `NotifyAsyncConfigTest` — fill a 1-thread/1-slot pool, submit a third task: it does **not** run on the
calling thread, and the executor does not throw.

### 2b · Dev mail goes to a local catcher — `docker-compose.mailpit.yml` (opt-in per host)
- New override file: a **Mailpit** container (`axllent/mailpit`, SMTP 1025, web UI on `127.0.0.1:8025`) on
  `myplus-net`, and `MAIL_HOST=mailpit`, `MAIL_PORT=1025`, `MAIL_PROTOCOL=smtp` for the five senders:
  notification, auth, education, campaign (via their `${MAIL_*}` placeholders) and the monolith
  (`SPRING_MAIL_*`, since its properties are literal).
- **Opt-in by the dev host's `.env`:** `COMPOSE_FILE=docker-compose.yml;docker-compose.mailpit.yml`. Prod's `.env`
  has no such line, so prod reads only `docker-compose.yml` — unchanged byte for byte.
- Why not edit `docker-compose.yml` defaults: campaign's default port is 587, the others 465 — a shared
  `MAIL_PORT` default changes one of them, and an empty one resolves Spring's placeholder to `""`.
- Mailpit accepts any AUTH (`MP_SMTP_AUTH_ACCEPT_ANY`, `…_ALLOW_INSECURE`): campaign sends with `smtp.auth=true`.

**The stuck backlog drains into Mailpit by itself** once the senders point there — no hand edit of
`notify_outbox` or `notification_delivery` on the shared database. Rows already `FAILED` (20 attempts) stay failed;
that is history, not backlog.

### 2c · The stale spec — `picker-refresh-safety.cy.js`
The two cases append a new `<option>` to the pinned `<select>` before the background fetch — the real situation
the refresh exists for (populate helpers that append without refreshing). Idle → rebuilt; open → untouched until
close, then rebuilt. The "nothing changed → nothing" half is PSEL-1's own, deliberately unasserted here.

## 3 · Gates
1. `NotifyAsyncConfigTest` (`mvn test`).
2. `picker-refresh-safety.cy.js` 4/4 — no rebuild needed (spec only).
3. After the user recreates the stack with the override: Mailpit UI lists mail; `docker logs myplus-notification`
   shows no `Too many login attempts`; `notify_outbox` PENDING falls; then `notices.cy.js` 10/10 and
   `notification-delivery.cy.js` 7/7, and the full education suite.

## 4 · Not done here
- The relay drains oldest-first, 100 per pass: a large backlog still delays new mail. Fine once sends succeed.
- `OutboxRelay` gives up after 20 attempts with no alert (already recorded in the Test Book §13).
