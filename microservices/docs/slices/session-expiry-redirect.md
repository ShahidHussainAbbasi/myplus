# Session expiry shows a JSON parse error instead of the login page (task #26)

**Status:** ANALYSIS — awaiting review. Nothing implemented.
Reported by the user:

```
loadSR: SyntaxError: Unexpected token '<', "<!DOCTYPE "... is not valid JSON (200)
```

## 1. What is actually happening

The session expired. Spring Security answered the AJAX call with the **login page** — HTML, under a **200**,
not a 401 — and the browser tried to parse `<!DOCTYPE html>` as JSON.

So every symptom is a consequence of one thing: **an expired session looks like a successful response.**

- `200`, so nothing treats it as an error
- HTML, so `JSON.parse` throws
- The user sees a parser message naming `loadSR`, which implicates the report rather than their session

## 2. Why nothing catches it today

There is **no global handler** for session loss. Verified:

```bash
grep -rn "ajaxError|statusCode:" src/main/resources/static/js/common/*.js
# → nothing that inspects a response for an expired session
```

Every screen parses its own response, so every screen fails its own way. `loadSR` is simply where this user
happened to be — the same thing happens on any AJAX screen after the session drops.

## 3. Why it will keep happening

`SecSecurityConfig` sends unauthenticated requests to `loginPage("/login")`. That is right for a browser
NAVIGATION and wrong for an XHR: a form login page is not an answer an AJAX caller can use, and returning it
under 200 removes the one signal the caller could have acted on.

⚠ Related and already known: **`/rum` had the same shape** — CSRF rejected the beacon with a 302 to login, the
beacon discarded it, and telemetry silently collected nothing. Same root: an auth failure dressed as something
else.

## 4. Options

| Option | What it does | Cost |
|---|---|---|
| **A. Client-side global `ajaxError` + HTML-body sniff** | one `$(document).ajaxComplete` that spots a login page in a JSON-expecting response and redirects | small, no server change; but it detects a symptom by pattern-matching HTML, which is fragile |
| **B. Server answers XHR with 401** | an `AuthenticationEntryPoint` that returns **401** when the request is an XHR (`X-Requested-With`, or `Accept: application/json`), and the login page otherwise | correct at the source; every caller then gets a real signal |
| **C. Both** | B for the signal, plus one small client handler that turns a 401 into a redirect with a message | **recommended** |

**Recommendation: C.** B alone fixes the protocol but leaves each screen to decide what a 401 means — and
there are dozens. A alone leaves the server lying about what happened. Together, the server tells the truth
once and the client acts on it once.

## 5. What the redirect must do

- Send the user to the login page **with a message** — "Your session expired, please sign in again" — not a
  silent bounce that looks like a crash.
- **Preserve where they were**, so signing in returns them to the screen they were on. A cashier mid-sale
  should not be dropped on a dashboard.
- ⚠ **Never redirect on a business refusal.** This stack answers a refusal with HTTP 200 and
  `success:false` / `status:"ERROR"`. Only an actual auth failure may bounce, or a validation error will
  start logging people out.
- Fire **once**, not per in-flight request. A screen with nine parallel calls must not attempt nine redirects.

## 6. Cypress cases (to write BEFORE implementing)

1. An XHR with no session receives **401**, not 200-with-HTML.
2. A browser NAVIGATION with no session still gets the login PAGE — B must not break normal login.
3. A 401 on any AJAX screen redirects to login with a message, once, no matter how many calls were in flight.
4. A business refusal (200 + `success:false`) does **not** redirect — the regression that would log people out
   for a validation error.
5. After signing in, the user lands back on the screen they were on.

## 7. Blast radius

Every AJAX screen in every module. That is the point — one handler replaces the current situation where each
screen fails differently and none of them mentions the session. It is also why the gate needs case 4: a
mistake here is worse than the bug, because it would eject users mid-work.
