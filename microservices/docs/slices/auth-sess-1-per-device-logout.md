# AUTH-SESS-1 — logout ends THIS device, not every device

**Status: BUILT, unit-green, awaiting the user's rebuild of auth-service + monolith. Not committed.**
Scope consented by the user 2026-09-16: **A + B together**. Spec-account cleanup is a separate change, by their choice.
Unit: **auth-service 48/48** (`RefreshTokenServiceTest` 12 = myplus-11's 8 + AUTH-SESS-1's 4; new `UserServiceTest` 3
for defect B) — the WHOLE module, per the CACHE-1 lesson. Gate written: `cypress/e2e/auth/auth-per-device-logout.cy.js`
(5 cases, gateway auth API only, accounts `cashier.b@` + `user.business@` — never the user's `owner.business@`).
⚠ The tree also carries myplus-11's SESS-1 (sessions chip, revoke-others, eviction logging, session-expired path);
both slices need the SAME rebuild pair.
Raised 2026-09-16 from a PRODUCTION monolith log the user pasted. Reviewed end to end on the local tree at `8d46ce44`.

> ✅ **CAUSE CONFIRMED by the user (2026-09-16): somebody DID sign out of that account in the ~15 minutes before the
> sale failed.** That is defect A end to end: the sign-out deleted every refresh token for the account, the till kept
> working on its unexpired access token, and the first call after it aged out — `addSell` — refreshed, found no row,
> and was refused. The ~15-minute gap is `jwt.access-token-expiration-ms` (900000) exactly, and no other deleter
> produces that sequence. Residual assumption, stated: that production runs this code. Nothing else is inferred.

---

## 1. The symptom, in the user's own log

```
WARN  c.w.u.GatewayClient - Token refresh FAILED (BadRequest): 400 Bad Request on POST
      ".../api/auth/refresh": {"success":false,"message":"Invalid refresh token"}. The caller will see a 401.
ERROR c.w.c.b.SellController - addSell proxy error
      401 Unauthorized on POST ".../api/business/addSell"
```

A cashier pressing **Save** on a sale was refused. The sale wrote **nothing** (verified locally on the same code:
`customer_history` is the invoice header and carries `saga_status`; a 401 at the proxy means business-service never
ran — there is no half-written invoice to repair).

⚠ **Production is NOT verified.** No production log beyond this paste, no production DB read, no confirmation that
production runs this code. Everything below is verified against the local tree only. **Do not touch production.**

## 2. What the message proves

`AuthService.refreshToken:656-657` throws `ValidationException("Invalid refresh token")` **only** when
`refreshTokenService.findByToken(...)` is EMPTY:

```java
RefreshToken token = refreshTokenService.findByToken(request.getRefreshToken())
        .orElseThrow(() -> new ValidationException("Invalid refresh token"));
refreshTokenService.verifyExpiration(token);        // expiry is a DIFFERENT error
```

So the row was **deleted**, not expired. And the HTTP session was **alive** — it reached `SellController`, held a
refresh token, and used it. That rules out the monolith's `maximumSessions(1)`, whose victims get a 302 to `/login`
or "This session has been expired".

**Only three things delete a row** (`RefreshTokenRepository`, by type — 3 write sites):

| # | Deleter | Trigger | Deliberate? |
|---|---|---|---|
| 1 | `RefreshTokenService.createRefreshToken` — oldest-first eviction above the cap | a 6th login for that user | yes, bounded by design |
| 2 | `RefreshTokenService.deleteByUserId` → `deleteByUser` | **any logout** | ⚠ NO — this is defect A |
| 3 | rotation inside `refreshToken` | the session's own refresh | yes |

## 3. Defect A — logout is logout-ALL (the fix this slice is for)

The chain, every hop verified:

```
browser "Sign out"
  → SecSecurityConfig:198   .addLogoutHandler(revokeTokenLogoutHandler)
  → RevokeTokenLogoutHandler:36   authServerClient.logout(tokenStore.getAccessToken())
  → AuthServerClient:105    POST /api/auth/logout   (Bearer ACCESS token only)
  → AuthController:63-68    authService.logout(user.getId())
  → AuthService:739-741     refreshTokenService.deleteByUserId(userId)
  → RefreshTokenRepository  deleteByUser(user)   // "Revoke every session for a user (logout-all …)"
```

**One device signing out deletes every other device's refresh token for that account.** The other devices keep
working until their access token ages out (≤15 min), then die on the next refresh with exactly the message above.

It needs no unusual setup — a back-office sign-out is enough to kill the till mid-sale. That is the money-losing
shape: the cashier's failure arrives minutes later, with a customer at the counter, and the log names a token, not a
logout.

**Why the endpoint cannot do better today:** `AuthController.logout` takes `@AuthenticationPrincipal UserDetails`,
and the JWT carries only `subject` (email), `issuedAt`, `expiration` and the extra claims — **no `jti`, no session or
device id** (`JwtService.buildToken`). The access token can identify the USER but not WHICH session, so "log out this
device" is not expressible with what the endpoint receives. The refresh token the client already holds is the only
device-identifying key that exists (`refresh_tokens` = `id, expiry_date, token, user_id` — no device column).

## 4. Defect B — nothing revokes sessions when a password changes

`AuthService.resetPassword:771-782`, `UserService.changePassword:54-61` and `UserService.lockUser` all save the user
and stop. **No session is revoked**, so a password reset leaves every existing session alive for up to the refresh
window (`jwt.refresh-token-expiration-ms`, default **7 days**) — including the session the reset was meant to shut
out. The capability to do this exists and is spelled `deleteByUserId`; it is simply wired to the wrong event.

A and B are the same mistake in mirror image: **the blunt tool fires on the routine action, and never on the one that
needs it.**

## 5. Defect C (bounded, not this slice) — the 5-session cap evicts silently

`RefreshTokenService` `@Value("${jwt.max-sessions-per-user:5}")`, eviction oldest-first BEFORE the insert, no
override deployed. A shop on a 6th device loses its oldest session with no warning. That is defensible as designed —
the cap exists to bound the table — but the eviction is silent and the victim learns about it as a failed sale. Worth
a decision, not a fix in this slice.

⚠ **Test hygiene, separate from the product:** every `cy.loginAs` consumes a slot, because the monolith FORM login
stores auth's token pair (`AuthServerAuthenticationProvider:108-109`). 18 spec files plus `commands.js` log in as
`owner.business@` — the human's account. A dedicated spec account fixes that; it is not a product defect.

## 6. Design

### 6.1 Logout ends the session that asked

```mermaid
sequenceDiagram
    participant B as Browser (till)
    participant M as monolith
    participant A as auth-service
    participant DB as refresh_tokens
    B->>M: POST /logout
    M->>M: RevokeTokenLogoutHandler reads TokenStore (access + REFRESH)
    M->>A: POST /api/auth/logout  Bearer access  { refreshToken }
    A->>DB: delete WHERE token = :refreshToken   (one row, this device)
    Note over A,DB: other devices' rows untouched
    A-->>M: 200
    M->>M: tokenStore.clear(); session invalidated
```

- `POST /api/auth/logout` gains an **optional** body `{ "refreshToken": "..." }`.
  - present → delete that ONE row (and only if it belongs to the authenticated user — anti-IDOR, same rule as every
    scoped read in this platform).
  - absent → today's behaviour (revoke all), so no existing client breaks. The monolith is the ONLY caller in the
    repo (`AuthServerClient:105`, verified across java/js/yml/html in `src`, `microservices`, `cypress`), so the
    fallback is a safety net, not a used path.
- `RevokeTokenLogoutHandler` sends `tokenStore.getRefreshToken()` alongside the access token. It already holds both,
  session-scoped (`TokenStore`, `SCOPE_SESSION`).
- A refresh token that is absent, foreign or already rotated → **200, nothing deleted**. Logout must never fail; the
  local session is cleared regardless (the handler is already best-effort).

### 6.2 Revoke-all, kept and pointed at the right events

`AuthService.logoutAll(userId)` keeps `deleteByUserId` and is called by the paths that mean it:
`resetPassword`, `changePassword`, and account lock/disable. (Defect B.)

### 6.3 What this does NOT do — stated, not glossed

An **access token stays valid until it expires** (`jwt.access-token-expiration-ms`, default 15 min). The gateway
checks signature and expiry only — `JwtAuthenticationFilter`'s sole Redis use is the demo write-counter (:241-243),
there is no denylist. So "log out everywhere" is not instant anywhere in this platform today, and this slice does not
change that. Making it instant means a revocation denylist at the gateway: a bigger change, and a separate decision.

## 7. Gate

**Unit (`mvn test`) — auth-service has THREE test files today and none touch logout or refresh; this is the first
coverage.**
- `logout(user, refreshToken)` deletes exactly that row and leaves the user's other sessions intact.
- a foreign refresh token (another user's) deletes NOTHING and still answers 200 — anti-IDOR.
- an unknown/already-rotated token deletes nothing, answers 200.
- no refresh token supplied → revoke-all (the compatibility path).
- `resetPassword` / `changePassword` revoke every session (defect B).
- the cap still evicts oldest-first at 6 logins (defect C unchanged).

**Cypress (headed, solo) — `auth-per-device-logout.cy.js`, entirely through the gateway API, so it needs no second
browser:**
1. ⭐⭐ Log in twice as ONE account (two refresh tokens = two devices). Log out with device B's pair. **Device A's
   `/api/auth/refresh` must still answer 200** — today it answers 400 "Invalid refresh token". This case IS the slice.
2. ⭐ Device B's own refresh token is gone after its logout (400).
3. ⭐ A password change revokes BOTH devices (defect B).
4. ⭐ Control: an untouched second account is unaffected throughout.
5. The existing `session.cy.js` "after logout, businessDashboard redirects to login" must stay green — local logout
   behaviour is unchanged.

⚠ The gate must use a **dedicated account**, not `owner.business@` — this spec deliberately logs in repeatedly and
logs out, which is precisely what must never be aimed at the account a human is using.

**Regressions:** `session.cy.js`, `login.cy.js`, plus any spec that logs out (`capability-enforcement`,
`territory-assignment`, `territory-screen`).

## 8. Rollout

auth-service and the monolith must ship **together**: the monolith starts sending a field the old auth-service
ignores (harmless — it would revoke-all, i.e. today's behaviour), and a new auth-service with an old monolith simply
never sees the field (also today's behaviour). Either order is safe; both deployed is the fix.

## 9. Decisions for the user

1. **Scope**: A + B in one slice (recommended — same method, opposite wiring), or A alone first?
2. **Defect C**: leave the cap at 5, raise it, or make the eviction visible ("you were signed out on another device")?
3. **The spec accounts**: switch the 18 spec files off `owner.business@` in this slice, or as its own change?
4. **Production**: this design is written from the local tree. Confirming the paste came from production, and whether
   anyone signed out of that account in the ~15 minutes before the failed sale, would settle A as the live cause — one
   answer, no production access required.
