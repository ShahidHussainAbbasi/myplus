# RESET-1 — say WHY a set-password link does not work

Status: **DESIGN + TEST CASES 2026-10-16 — awaiting consent to build.** Found in manual testing 2026-10-15: the
owner of TAHSEEN HEALTHCARE's pharmacy (`tahseenhealthcare2@`) got **"Invalid token."** four times (19:34–19:36 UTC)
while that account's own link (`526f13c2…`) sat unused and valid. The page being submitted held the clinic's link,
already used at 19:26. Nothing on the screen could say so.

## Review — the path today (traced 2026-10-16)
| # | Fact | Evidence |
|---|---|---|
| R1 | **One** caller of `POST /api/auth/reset-password`: the monolith's `AuthServerClient.resetPassword` ← `PasswordResetFacadeImpl.completeReset` ← `POST /user/savePassword` ← `updatePassword.html`. | grep, all modules |
| R2 | auth refuses a reset for **2** reasons, as English text with no code: *Invalid reset token* (no such token — used, replaced by a newer e-mail, or never existed) and *Reset token expired*. | `AuthService.resetPassword` 919–925 |
| R3 | A token is deleted in **3** places: on use (930), on a newer e-mail (908, TEAM-1), on an expired attempt (923). So the SECOND try on an expired link already says "invalid", not "expired". | same |
| R4 | The monolith turns **every** `HttpStatusCodeException` — both refusals AND a 5xx — into `auth.message.invalidToken` = "Invalid token." | `PasswordResetFacadeImpl` 41–47 |
| R5 | A weak password does **not** reach auth: `@ValidPassword` (passay: 8–30, upper, digit, special, no 3-sequences) refuses it in the monolith and the page lists the rules. Not part of this slice. (Corrects what I said on 10-15.) | `PasswordDto`, `RestResponseEntityExceptionHandler.handleMethodArgumentNotValid` |
| R6 | The link page (`GET /user/changePassword`) checks nothing: a dead link shows the form, the person types a password twice, and only then is refused. | `RegistrationController` 78–82 |
| R7 | The pattern to copy exists: `SignInRefusal` (auth) → `ApiResponse.reason` → `SignInFailure` (monolith) → message key (10-14). | `exception/SignInRefusal.java`, `com/security/SignInFailure.java` |
| R8 | Gateway already lets `/api/auth/reset-password/**` through (`startsWith`); auth's `SecurityConfig` permits the exact path only. | `JwtAuthenticationFilter` 59–96, `SecurityConfig` 71 |
| R9 | **3** existing specs open a dead link, TYPE a password, then expect `#globalError`: `auth/team-invite-first-login` TI-05, `auth/team-members-manage` TM-02, `docs/team-signin-guide-screens` T2. They change with R6's fix (no form to type into). | grep `changePassword?token` |

Market check: Shopify, Google and Microsoft account recovery check the link **when it is opened** and say which
problem it is ("This link has expired" / "already used"), with a one-click "send a new link" — the form is never
shown for a dead link.

## Design
**D1 · Codes, not sentences (auth).** `ResetRefusal` enum — `RESET_LINK_UNKNOWN` (used, replaced, or never issued:
indistinguishable once deleted, and saying which would tell a stranger an e-mail was sent) and `RESET_LINK_EXPIRED`
— sent as `ApiResponse.reason`, status 400 as today. `ResetRefusedException` + one handler, like `SignInRefusedException`.
An expired token is **no longer deleted on an attempt** (R3), so it keeps saying "expired"; the next e-mail removes it.

**D2 · Check on open (auth + monolith).** `GET /api/auth/reset-password/check?token=` — read-only, returns ok or the
refusal code, never the e-mail or the account. `GET /user/changePassword` calls it: a dead link shows the reason and a
**Send me a new link** button (→ Forgot password) **instead of the form**.

**D3 · The monolith maps codes to keys.** `ResetFailure` enum {`RESET_LINK_UNKNOWN`, `RESET_LINK_EXPIRED`,
`AUTH_UNAVAILABLE`} → message keys, used by both the page (D2) and `/user/savePassword` (a link that dies while the
form is open — another tab used it, or a newer e-mail came). A 5xx / unreachable auth says "try again shortly", never
"Invalid token". Contract comment on both enums, as for sign-in.

**D4 · Text.** New keys in all 6 bundles: `auth.message.resetLinkUnknown` "This link has already been used, or a newer
e-mail replaced it. Ask for a new link and use the newest e-mail.", `auth.message.resetLinkExpired` "This link has
expired — links work for 1 hour. Ask for a new link.", `ui.reset.newLink` "Send me a new link". `auth.message.invalidToken`
has no other reader (grep) and is left in the bundle unused.

**Not in scope:** the e-mail text; reCAPTCHA on Forgot password (runbook §6 open item); the passay messages.

## Files (planned)
auth: `exception/ResetRefusal.java`, `exception/ResetRefusedException.java`, `GlobalExceptionHandler`, `AuthService`
(resetPassword + `checkResetToken`), `AuthController` (`GET /reset-password/check`), `SecurityConfig` (permit),
`AuthServiceResetTest` (new). Monolith: `com/web/error/ResetFailure.java`, `PasswordResetException` (carries the
failure), `PasswordResetFacade(Impl)`, `AuthServerClient.checkResetToken`, `RegistrationController`,
`updatePassword.html`, 6 × `messages*.properties`. Specs: the 3 in R9 + `docs/reset-1-link-guide-screens.cy.js`;
`cypress.config.js` task `expireResetToken` (local DB only — stands in for the hour passing).
Deploy: `auth-service,monolith`. No migration.

## Test cases (Test Book §35 — `cypress/e2e/docs/reset-1-link-guide-screens.cy.js`)
| Case | What a person sees |
|---|---|
| P1 | A link used once, opened again: the reason + **Send me a new link**; no password boxes |
| P2 | Forgot password twice: the FIRST e-mail's link says it was replaced; the newest works |
| P3 | A link older than an hour: "expired — links work for 1 hour" + Send me a new link |
| P4 | Form open, then a newer e-mail is sent: pressing Update Password says replaced — not "Invalid token." |
| P5 | Send me a new link → Forgot password → the newest e-mail sets the password; sign-in works |
