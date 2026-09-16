# SESS-1 — a till stays signed in, sees its own devices, and says so plainly when it does not

**Status 2026-09-16: ✅ GATED GREEN 7/7** (`session-visibility.cy.js`, headed, 38s, exit 0) on the rebuilt
monolith + auth-service, after the SESSION_EXPIRED reader was written (§4.2.3) and case 6 was rewritten (§5).
Regressions green: `auth/session` 16/16, `auth/login` 9/9, `capability-enforcement` 4/4, `territory-screen` 5/5.
⚠ `territory-assignment` 2/7 is a PRE-EXISTING red of its own slice — reproduced identically before AND after a
business-service rebuild, so not a stale image and not this slice. NOT COMMITTED.** Reported from PRODUCTION by the user: a sale was refused with
`Invalid refresh token` → 401 while the shopkeeper was working.
Consent: "go" → then, on the numbers, **"keep `jwt.max-sessions-per-user` 5 not more than it and display on dashboard
how many users are logged in to logged in user"**, plus (asked) the count carries a **Sign out other devices** action
and sits **in the header beside the account menu**. Owner: session myplus-11.

---

## 1. What the shopkeeper saw

A sale at the till was refused. The monolith's own log (production, pasted by the user):

```
WARN  c.w.u.GatewayClient - Token refresh FAILED (BadRequest): 400 … "Invalid refresh token" … The caller will see a 401.
ERROR c.w.c.b.SellController - addSell proxy error
o.s.w.c.HttpClientErrorException$Unauthorized: 401 … "Invalid or expired token"
```

They were shown **"Invalid or expired token"** — a developer's sentence — with no hint that signing in again is the
remedy, and every later attempt failed identically.

## 2. Why it happened (traced, not guessed)

⚠ **CORRECTED 2026-09-16, after the user checked:** somebody **signed out** of that account ~15 minutes before the
failed sale, and `AuthController.logout` revokes **every** session for the user rather than the one device that asked.
That is the CONFIRMED cause, and it is myplus-f9's slice (`auth-sess-1-per-device-logout.md`, defect A). This slice
originally asserted the session cap; that was a mechanism that FITS the signature, not the one that happened. The cap
remains a real and separate hazard — unproven for this incident — which is exactly why §4.2's eviction logging earns
its place: it is what would have told the two apart in seconds.

⚠ **"Invalid refresh token" is thrown ONLY when the row is ABSENT** (`AuthService.refreshToken`, the `findByToken`
empty branch). An expired one says something else — "Refresh token expired. Please login again."
(`RefreshTokenService.verifyExpiration:73-78`). So the token was **deleted**, not aged out. Both a logout-all and a cap
eviction delete rows; the cap is the one this slice makes visible:

```java
// RefreshTokenService.createRefreshToken:52-67
List<RefreshToken> sessions = refreshTokenRepository.findByUserOrderByExpiryDateAsc(user);
int surplus = sessions.size() - (maxSessionsPerUser - 1);   // room for the one about to be made
for (int i = 0; i < surplus && i < sessions.size(); i++) refreshTokenRepository.delete(sessions.get(i));
```

**The interaction nobody costed:** the cap is **5** while a refresh token lives **7 days**. Rows do not age away
between shifts, so every tab, phone and back-office PC that signed in this week still holds a slot. A shop at 5 live
rows loses its **oldest** session on the next login — silently — and that device fails ~15 minutes later, when its
access token next needs refreshing.

Reproduced locally on the same code path: `owner.business@` held exactly 5 rows, all from that morning's automated
logins. ⚠ That was LOCAL. No production data has been read, and the production incident is therefore explained by
mechanism, not by measurement.

## 3. Every clock, and which one was actually wrong (RULE 0)

| Clock | Value | Who it logs out | Decision |
|---|---|---|---|
| Access token `jwt.access-token-expiration-ms` | 15 min | nobody — refreshed silently | **unchanged.** Capabilities are baked in at mint (C3c); a long one delays an owner's capability change reaching the till |
| Refresh token `jwt.refresh-token-expiration-ms` | 7 days | nobody within a shift | **unchanged.** ⚠ Setting this to "12 hours" would have SHORTENED it and forced a daily re-login — the opposite of the intent behind the request |
| Session cap `jwt.max-sessions-per-user` | 5 | **this defect** | **stays 5** (the user's ruling). Made VISIBLE instead — §4.1 |
| Monolith HTTP session | Tomcat default 30 min (unset) | an idle till, separately | **→ 12 h** (`server.servlet.session.timeout`). This is where "12 hours" belongs |
| Monolith concurrent sessions | `maximumSessions(-1)` (`SecSecurityConfig:190`) | nobody | unchanged — already unlimited, deliberately |

**Why keeping the cap is defensible once the count is on screen:** `SecSecurityConfig:167-177` already states the
policy — "a user may be signed in on as many devices as they like, and security comes from VISIBILITY over those
sessions, not from a hard cap". A cap of 5 with the number shown, and a way to clear it, is that policy honoured.

## 4. The fix

### 4.1 Show the shopkeeper their own devices (new)

- **auth-service** — `GET /api/auth/sessions` → `{count, max, oldestSignedInAt}` and
  `POST /api/auth/sessions/revoke-others` → deletes every refresh token for the caller EXCEPT the one this session
  holds. Both identify the caller as `SupportSessionController` does: `jwtService.extractUserId(bearer(auth))`.
  ⚠ **No id is ever read from the body or the query** — that is what makes these immune to IDOR by construction; a
  caller can only ever see and end their OWN sessions.
- **monolith** — proxied with `gateway.forMap(AUTH_PREFIX, authDirectUrl, …)`, the shape
  `BusinessConfigController:291-316` uses.
- **header** — a chip beside the account pill (`fragments/header.html:239-256`), reading
  **"Signed in on 3 of 5 devices"**, and at the cap **"5 of 5 — a new sign-in will end the oldest"**. A
  **Sign out other devices** action sits in it, behind the standard `uiConfirm` (never `window.confirm`).
- **script** — `/js/common/sessions.js`, registered beside the other shared scripts
  (`businessDashboard.html:4542-4553`), markup contract `[data-sessions]` / `[data-sessions-count]`, hidden until the
  count answers — the convention `live-users.js` established.
- ⚠ **NOT the users-online badge.** That figure is everyone online and is deliberately inflated ×5 for marketing
  (`LiveUserCountService`, `app.live-users.multiplier`). This one is the signed-in user's own devices, is never
  multiplied, and must not reuse that service.
- ⚠ **`refresh_tokens` carries no device, browser or last-used column** (`RefreshToken`: id, token, user, expiryDate).
  So the honest display is a COUNT and when the oldest signed in — not a device list. Naming devices needs a new
  column written at login, and is deliberately out of scope.

### 4.2 The three fixes that stand regardless

1. **Log every eviction.** `RefreshTokenService` deletes a session and says nothing, which is why this was invisible
   until a shopkeeper lost a sale. `@Slf4j` (house style in this module), one WARN naming the user id and the cap.
2. **A dead session must not loop.** `GatewayClient.execute:204-211` refreshes once and rethrows; nothing clears the
   session, so every later click repeats the failure. New `SessionExpiredException` → `SessionExpiredAdvice`
   (`@RestControllerAdvice`, the `DemoLimitAdvice` shape) answering `401 {success:false, code:"SESSION_EXPIRED"}` and
   invalidating the HTTP session.
   ⚠ **NOT `tokenStore.clear()` alone**: with no access token `GatewayClient` drops into LEGACY direct-call mode
   (`:170-171`), bypassing the gateway instead of forcing a login. The typed exception is the control.
3. **Say it in the shopkeeper's words.** `ui.js.sessionEnded` in all six `messages*.properties` beside
   `ui.js.expired` ("Your session has ended. Sign in again.").

   ⚠ **CORRECTED 2026-09-16, found by the gate.** This said the reader would be `ajax-overlay.js`'s global
   `ajaxError` hook. It is not, and for a while it was NOWHERE: that hook is `$(document).ajaxError(hide)` and only
   hides the overlay, no client code read `SESSION_EXPIRED`, and `ui.js.sessionEnded` sat in six locale files with
   **zero readers** — dead text. The path a 401 actually takes is `main.js:132 handleAjaxFailure`, which already
   treated every 401 as session-lost and redirected to `login?message=` + jQuery's `errorThrown`. So the till landed
   on the login page reading **"Unauthorized"** — the loop was broken (the useful half) but the developer's word
   survived, which is the thing this item exists to remove. The reader now lives in that function.

   ⚠ It is gated on `code === "SESSION_EXPIRED"`, **not** on the 401. `sessionLost` there is deliberately broad —
   it also matches a login PAGE returned by a 302 and `ConcurrentSessionFilter`'s plain-text notice — and a 401 can
   be an ordinary refusal. Telling one of those users "your session has ended" trades one misleading sentence for
   another.
4. **Session timeout 12 h** in `application.properties`, beside the other `server.*` settings.

**The cart and the idempotency key.** ⚠ **CORRECTED 2026-09-16.** This said they were "not touched" because
`main.js:1295-1298` keeps both on a non-SUCCESS answer. That is true of a **200** carrying `status != SUCCESS`, and
FALSE of the **401** path this slice introduces: `data` and `saleIdempotencyKey` live in `window`, item 2 above sends
the page to `/login`, and a navigation takes both with it. A part-rung basket IS lost when a session dies mid-sale.

`park.js` cannot rescue it — `parkCurrentSale` POSTs `/parkSale`, which would 401 in exactly this state — so
preserving a basket across expiry means browser storage, i.e. a decision about where a shop's unsaved basket lives.
**The user's ruling (2026-09-16): state it as a known limit, decide it separately.** What is guaranteed instead, and
now gated: the sale was refused before anything was written, exactly ONE attempt left the browser, and nothing
survives that a cashier could complete a second time.

## 4b. Implement — what is WRITTEN (2026-09-16, not built, not run)

**auth-service**
- [x] `RefreshTokenRepository`: `countByUser`, `deleteByUserAndTokenNot(user, token)`
- [x] `RefreshTokenService`: `@Slf4j`; a WARN inside the eviction loop; `describeSessions(userId)`;
      `revokeOtherSessions(userId, keepToken)`
- [x] `SessionsController`: `GET /api/auth/sessions`, `POST /api/auth/sessions/revoke-others` — caller from the
      bearer token, no id accepted from anywhere
- [x] `RefreshTokenServiceTest`: 8 cases (myplus-f9 added 4 more for AUTH-SESS-1's `deleteByToken` — 12 in the class)

**monolith**
- [x] `SessionExpiredException` + `SessionExpiredAdvice` (`@Order(HIGHEST_PRECEDENCE)` — without it the catch-all
      `RestResponseEntityExceptionHandler` wins and the 401 becomes a 500, the 2026-08-06 defect)
- [x] `GatewayClient`: throws it when — and only when — auth-service answers "Invalid refresh token"
      (`unknownRefreshToken`); every other refresh failure still returns `false`
- [x] ⚠ `ProxyErrors.rethrowIfUserFacing`: rethrows `SessionExpiredException`. **Without this line the whole fix is
      unreachable** — ~100 proxies end in `catch (Exception) → statusError(e)`, which would flatten it to a 200
- [x] `SessionsProxyController`: `/mySessions`, `/revokeOtherSessions` (keep-token from the session's `TokenStore`)
- [x] header chip in `fragments/header.html` + `/js/common/sessions.js`, registered on all five SIGNED-IN
      dashboards that render the fragment: business, education, agriculture, welfare, appointment.
      ⚠ **NOT on `home.html` / `homepage.html`** — they render the same fragment but are PUBLIC (no
      `isAuthenticated()` anywhere, no common scripts at all). The chip stays hidden there because nothing
      fills it, which is the right answer: there is no account to report. Do not "fix" that by loading the
      script on a public page — it would call `/mySessions` for an anonymous visitor on every landing view.
- [x] `ui.js.sessionEnded` and 7 more keys in all six `messages*.properties`
- [x] ⚠ `main.js:132 handleAjaxFailure` — the READER for those keys, added 2026-09-16 after the gate found it
      missing. Substitutes `t('ui.js.sessionEnded')` for `errorThrown` ONLY when the body carries
      `code:"SESSION_EXPIRED"`, and `encodeURIComponent`s the result (a sentence has spaces; identity for the
      one-word values that used to be passed). **Static asset — a src edit does nothing until the monolith is
      rebuilt**, which is why this is not yet re-gated.
- [x] `server.servlet.session.timeout=12h`
- [x] build + `mvn -pl auth-service -am test` = **48/48** (whole module) — myplus-f9
- [x] **gate 7/7 green** on the rebuilt monolith (2026-09-16 14:31 PKT). ⚠ The asset was verified by CONTENT before
      the run — `curl localhost:8080/js/main.js | grep SESSION_EXPIRED` — not by the container's start time

## 5. Test

**`mvn test` (auth-service):** `RefreshTokenServiceTest` — at the cap the OLDEST row goes and the newest survive;
under the cap nothing is deleted; the eviction is logged. `SessionsControllerTest` — the count is the caller's own,
and revoke-others leaves exactly the calling session. Run the WHOLE module (`mvn -pl auth-service -am test`) and
record THAT count — the CACHE-1 lesson.

**Gate `cypress/e2e/business/session-visibility.cy.js`** (written first):

| # | Case | What the defect would break |
|---|---|---|
| 1 ⭐⭐ | the header shows "Signed in on N of 5 devices", and N matches a second login from a second browser context | a number that is decoration rather than the truth |
| 2 ⭐⭐ | at 5 of 5 the wording warns that a new sign-in ends the oldest | the silence this slice exists to end |
| 3 ⭐⭐ | **Sign out other devices** leaves the current session working and the count at 1 | revoking the caller's own session — the obvious way to get this wrong |
| 4 ⭐ | another account's sessions are never visible or revocable (no id is accepted) | IDOR |
| 5 ⭐⭐ | a `SESSION_EXPIRED` 401 on `addSell` shows the translated sentence and lands on `/login` | the loop, and the developer sentence |
| 6 ⭐ | ⚠ REWRITTEN — the failed sale wrote nothing and cannot come back to be charged twice: ONE attempt on the wire, the till lands on `/login`, and signing back in resurrects NO basket | a double-charge on retry. The original case ("the cart and the key survive") contradicted case 5 and could never pass — an in-memory cart cannot outlive the navigation case 5 requires |
| 7 | an ordinary refusal (insufficient stock) still shows ITS message and does NOT redirect | a blanket redirect swallowing real business answers |

⚠ Case 1 needs a second session for the same account. Use a gateway login (`cy.request`) rather than a second browser
login, so the spec does not evict the session it is testing from.

## 6. Known limits

- The cap stays 5, so a sixth device still evicts the oldest. What changes is that the shopkeeper can SEE it coming
  and can free a slot themselves.
- ⚠ Eviction order is by expiry, which — because `AuthService.refreshToken` re-mints the ACCESS token and hands back
  **the same refresh token** (`buildAuthResponse(user, accessToken, token.getToken(), claims)`), with no rotation —
  is issue order. So the victim is the oldest **login**, however hard that device is being used: a till signed in on
  Monday is evicted before a tab opened this morning and never touched. That is the strongest argument for showing
  the count, and it is also why a token's row is stable enough for "sign out other devices" to key off.
- No device names: a count and a timestamp only (§4.1).
- Production config stays the user's; nothing here changes a running environment.
- ⚠ `commands.js:41` and `:532` still claim the monolith runs `maximumSessions(1)`. It is `-1` since
  `SecSecurityConfig:167-191`. Corrected with this slice — that stale comment cost a wrong diagnosis on 09-16.
