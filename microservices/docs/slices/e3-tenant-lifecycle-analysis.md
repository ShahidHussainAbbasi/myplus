# E3 — analysis: the programme said "freshness", the code says something else

**Status:** ANALYSIS, shared for review. No design, no code — per `SAAS-BUILD-STANDARDS.md`, *"The standards
analysis is shared for review **before** documenting or designing, not alongside it."*
**Programme:** [`saas-control-plane-review.md`](../saas-control-plane-review.md) — E3 of E0..E6.
**Predecessors:** [`e1-entitlement-ceiling.md`](e1-entitlement-ceiling.md) ·
[`e2-operator-portal-design.md`](e2-operator-portal-design.md) — both ✅ green.

Live state read from the **Docker** MySQL (`myplus-mysql`) and from the running containers on 2026-09-01.

---

## 1. Verdict up front

**E3 as scoped is not worth building, and a bigger gap sits next to it.**

The programme defined E3 as *"freshness — implement whichever D-1 option is chosen; a ceiling a stale token
bypasses is decoration."* Checked against the code, **that ceiling is not bypassable and the freshness work is
already done**. Meanwhile:

> **A platform operator has no way to suspend a customer.** `Organization.status` is written once at
> creation, displayed on the console I just built, and **enforced nowhere**. A tenant who never pays keeps
> trading indefinitely.

That is the slice. I would re-scope E3 from *freshness* to **tenant lifecycle**, and fold the one piece of
freshness that genuinely matters into it — because suspension is precisely the case where 15 minutes is too
long, and entitlement revocation is not.

---

## 2. Why the freshness work is already done

| Question | Evidence | State |
|---|---|---|
| How long can a revoked entitlement survive? | `jwt.access-token-expiration-ms = 900000` | **≤ 15 minutes** |
| Does a refresh recompute capabilities, or replay the old ones? | `AuthService.refreshToken` → `buildClaims(user)` → `capabilityService.encodeFor(orgId)` | **recomputes** |
| Can a client keep an old claim by not refreshing? | `GatewayClient.execute` refreshes on 401 and retries; the token is rejected after 15 min | **no** |
| Can a service be told a capability it was not given? | gateway `JwtAuthenticationFilter` **strips any client-sent `X-Org-Caps`** before stamping its own | **no** |
| Is the DB read itself stale? | `JpaEntitlementSource` — Caffeine per org, **invalidated on write** by `EntitlementService.set` | **no** |

So the D-1 decision — *cached read, no hot-path call* — is implemented, and the residual staleness is a
**bounded 15 minutes on an active session**, which is the documented, deliberate cost of carrying capabilities
in the token (and what keeps the sale path free of remote calls, which V44 settled as a hard requirement).

**Is 15 minutes acceptable for a licensing boundary?** Yes, and the benchmark agrees: Stripe and Shopify do not
cut a merchant off mid-second either. Billing is not fraud prevention. Shrinking it would mean a per-request
version check across every service — reintroducing exactly the hot-path dependency E1 refused.

**One thing worth doing anyway, and it is cheap:** the *screen* can be stale for as long as a tab stays open,
because `capabilities.js` fetches once at page load. The server still refuses correctly, so this is cosmetic —
a control that has gone quietly inert in the UI while the API answers properly. Worth a note, not a slice.

---

## 3. The gap that is actually open

### F8 🔴 A tenant cannot be suspended — `Organization.status` is decorative

`Organization.status` is set to `"ACTIVE"` in `createTenant` and `getOrCreatePrimaryOrg`, returned by my own
E2 tenant list, and **read by nothing**. A repo-wide grep for a status check on the *organization* in the login
or token path returns nothing.

Consequences, in order:

* **There is no lever for non-payment.** The only tool an operator has today is revoking capabilities one at a
  time — thirteen actions, each needing a reason, and the tenant can still log in, keep selling, and keep
  writing to their books.
* **There is no way to close an account.** A customer who leaves stays fully operational forever.
* **The console displays `status` as though it means something.** That is worse than not showing it: E2's
  tenant list prints a field an operator will reasonably read as enforcement.

### F9 🟠 `Membership.status` is enforced in one place and ignored in others

`getOrCreatePrimaryOrg` filters memberships to `ACTIVE`. Nothing else does, so a suspended membership still
resolves through other paths. Removing one person from a tenant is not reliably possible either.

### F10 🟢 `User.enabled` works, and shows what "done properly" looks like

`CustomUserDetailsService` passes `user.isEnabled()` into the `UserDetails`, so Spring Security refuses a
disabled user at authentication. There is exactly one caller that sets it false — `disablePortalUser`, for
revoking a guardian's portal access — and no operator surface. **The mechanism exists and is correct; what is
missing is a reason to call it and a screen to call it from.**

This matters for the design: suspension at the **user** level is already enforced, so the honest question is
whether tenant suspension should reuse that path or be a genuinely org-level check.

---

## 4. Where suspension must be enforced — and why 15 minutes is too long *here*

This is the one place the freshness question is real, and it inverts the entitlement answer:

| | Revoking a capability | Suspending a tenant |
|---|---|---|
| What it stops | one feature | **all trading** |
| Why | commercial packaging | **non-payment, abuse, closure** |
| 15-minute delay acceptable? | **yes** — a shop keeps a feature it is not paying for, briefly | **no** — a suspended tenant keeps *selling*, and every sale writes to stock and the ledger |

So suspension cannot ride the JWT claim the way capabilities do. It has to be checked where a session is
**established or renewed**, which is cheap and already on a cold path:

* `AuthService.login` — refuse at the door;
* `AuthService.refreshToken` — refuse the renewal, so an existing session dies **within 15 minutes** without
  any hot-path check anywhere;
* `switchOrganization` — refuse switching *into* a suspended tenant.

That gives a bounded 15-minute cutoff using only the three cold paths, with **no per-request cost and no new
remote call**. If a hard immediate cutoff is ever needed, deleting the tenant's refresh tokens on suspend is a
one-line addition — which is the E5-adjacent question of whether we forcibly log people out.

---

## 5. What I would build

| | Work | Why |
|---|---|---|
| **E3a** | Enforce `Organization.status` at **login**, **refresh** and **switch-organization**. `SUSPENDED`/`CLOSED` refuse with a clear message | F8 — the whole point |
| **E3b** | `POST /api/auth/admin/organizations/{id}/status` — `ROLE_ADMIN`, validated against an enum, **reason required** (same rule as E2's plan and entitlement writes) | gives the operator the lever |
| **E3c** | Operator console: a status control on the tenant detail, and a **Suspended** badge on the row beside the lapsed-trial badge | E2 already prints `status`; make it mean something |
| **E3d** | Refuse to suspend the **operator's own** tenant, and never let a suspension apply to `ROLE_ADMIN` | a console that can lock its own operator out of the console is a foot-gun with no undo |
| **E3e** | `OrganizationStatus` enum in `common-settings` beside `Plan` | `status` is free text today, exactly as `plan` was before E2 closed it (F2) |
| **E3f** | Gate + manual cases | standard |

**Deliberately out:** shrinking the 15-minute window (§2 — already correct), deleting tenant data on closure
(a different, irreversible conversation), and billing.

---

## 6. Three questions before I design

**Q1 — what states?** I propose `ACTIVE` · `SUSPENDED` (reversible, non-payment) · `CLOSED` (the customer
left). *Recommendation: those three.* `CLOSED` differs from `SUSPENDED` only in intent today, but the two
answer different questions on a report, and merging them means never being able to separate churn from
dunning.

**Q2 — what does a suspended tenant's user see?** *Recommendation: refused at login with "This account is
suspended — please contact MaxTheService", and nothing else.* Not a read-only mode: read-only sounds kind and
means building and testing a second behaviour for every screen in the product.

**Q3 — does suspension survive a plan change?** i.e. if an operator sets a suspended tenant to `PRO`, does it
reactivate? *Recommendation: no — status and plan are separate axes*, and an implicit reactivation is the kind
of side effect nobody predicts. The operator sets status explicitly.

---

## 7. Risks

| | Risk | Mitigation |
|---|---|---|
| 1 | Suspension is the most destructive operator action in the product | Reason required; `ROLE_ADMIN` only; E3d protects the operator's own tenant; the gate asserts reactivation restores access |
| 2 | A wrong suspension stops a real business trading | Reversible by design, and the gate proves the round trip — not just the refusal |
| 3 | Refusing at `refreshToken` could log out more than intended | Scoped to the *organization*, and `ROLE_ADMIN` is exempt |
| 4 | `status` is free text like `plan` was | E3e — an enum, validated at the one write |
| 5 | E4 must audit this too | Same `reason`-carrying command shape as E2, so E4 stays a listener |
