# TIER-1 — the platform operator signs in with two factors · standards §1e (tier 1)

Status: **DEFERRED by the user (2026-10-15: "skip 2FA at the moment").** Design kept; nothing built. Dev-enrolment choice
made before the deferral: seed the dev operator from `.env.local` (`OPERATOR_TOTP_SECRET`); production enrols by QR.

## Why first
`admin@myplus.com` is the ONLY platform operator (measured 2026-10-15) and it can switch features, plans and support
sessions for EVERY tenant. Its 2FA is OFF. A leaked or guessed password is a whole-platform breach. Every serious SaaS
console requires a second factor for its operators (AWS root/IAM admins, GitHub org owners, Google Workspace super
admins, Stripe); this is the cheapest, largest risk reduction in the access model.

## What exists (measured)
- TOTP 2FA per user already works: `users.two_factor_enabled/secret`, `TwoFactorService.verifyCode`, login answers
  `twoFactorRequired`, the login page has a code field, `/user/2fa/setup|verify|disable` enrol a user.
- Operator power is checked in ONE helper, `CurrentUser.isPlatformOperator()` (common-security, `ROLE_ADMIN`), plus
  21 `ROLE_ADMIN` guards in auth and 6 elsewhere.
- 67 Cypress specs sign in as the operator through ONE command, `cy.loginAsOperator`.

## The rule (best practice: "required, enrol at next sign-in, nothing else until then")
1. **Policy is data/config, on everywhere:** `auth.operator.mfa-required = true` (dev and prod).
2. **The token says how the person signed in.** Login adds the claim `mfa: true` only when a 2FA code was verified in
   that sign-in (OIDC's `amr` idea). Refresh keeps it; nothing else can set it.
3. **Operator power requires it.** `isPlatformOperator()` = `ROLE_ADMIN` **and** `mfa: true` (when the policy is on);
   the 21 + 6 guards use the same helper/expression. An operator token without `mfa` can do ONE thing: enrol.
4. **No lock-out of the only operator.** An operator who has not enrolled still signs in with the password, and the
   monolith sends them straight to the 2FA set-up screen (QR + confirm code) — every other operator screen and API
   answers 403 "Set up two-factor authentication first". After enrolling they sign in again with a code.
5. **Recovery (break-glass)** — written runbook, not a hidden backdoor: a DB-side reset of the operator's 2FA by a
   person with database access, which is itself audited; documented in `docs/deploy/` before this ships.
6. **Owners and members are unaffected** (their own 2FA stays optional — a later, separate decision).

## Tests
- Cypress computes TOTP itself: `cy.task('totp', secret)` in `cypress.config.js` using Node's built-in `crypto`
  (RFC 6238, ~15 lines, no new dependency). The dev operator's secret lives in the git-ignored `.env.local` /
  `cypress.env.json`, never in the repo. `cy.loginAsOperator` sends the code → all 67 specs keep working.
- Unit: `isPlatformOperator` false without `mfa`; login sets `mfa` only after a verified code; refresh keeps it.
- Gate `auth/operator-mfa.cy.js` (red first):
  O1 operator without a code → lands on 2FA set-up; console page and a console API refused (403);
  O2 with a valid code → console works; O3 wrong code → refused, nothing granted;
  O4 an owner (no 2FA) signs in exactly as before.

## Rollout (no manual step in production beyond the operator's own enrolment)
Deploy auth + common-security consumers + monolith → the operator signs in → is sent to enrol → scans the QR → signs in
again with a code. Dev: the seeded operator gets the known dev secret from `.env.local` so tests and local use work.
