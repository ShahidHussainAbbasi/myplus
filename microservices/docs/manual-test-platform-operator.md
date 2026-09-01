# Manual test & demo use cases — the platform control plane (E1 + E2)

**Purpose.** The same two jobs as `manual-test-use-cases.md`, for a different audience: a **manual regression
script** for the operator console, and the **shot list** for showing a prospective customer — or an investor —
how MaxTheService is actually run. Ordered as a story, not by screen.

**Persona note.** Everything here is done by **MaxTheService staff**, not by a customer. That distinction is the
whole point of the slice, and §D is the part that proves it.

**How to read a case.** Every case names its **persona**, its **preconditions**, numbered **steps**, the
**expected result**, and the **slice that proves it** — so a failure has somewhere to go. 🎬 marks cases worth
recording.

**Accounts** (dev seeds only — `app.seed-admin` / `app.seed-demo` / `app.seed-test-fixtures`):

| Account | Password | Who they are |
|---|---|---|
| `admin@myplus.com` | `Admin@2025!` | **the platform operator** — `ROLE_ADMIN`, not a customer |
| `owner.business@myplus.com` | `Demo@2025!` | a tenant OWNER — every privilege inside their own org |
| `admin.business@myplus.com` | `Demo@2025!` | a tenant ADMIN — same org, fewer rights |
| `user.business@myplus.com` | `Demo@2025!` | a tenant USER — same org again |

> ⚠️ **Two caveats to read before testing, or you will file a bug that is not one.**
>
> 1. **A change reaches the tenant within 15 minutes, not instantly.** Capabilities travel in the JWT
>    (`jwt.access-token-expiration-ms = 900000`), so a revoke lands when the tenant's session next refreshes.
>    To see it immediately, **log the tenant out and back in**. The screen says so where the operator acts.
> 2. **A refusal is an ANSWER, not a failure.** Refused writes come back as HTTP 200 with `success:false` and
>    the server's own sentence. A 500 would be a real bug; a polite refusal is the control working.

---

## A. The operator's own path

### A1 🎬 — MaxTheService staff land on a console, not on a shop's till
**Persona:** platform operator · **Proves:** E2 (`operator-portal.cy.js` case 1)

1. Log in as `admin@myplus.com`.

**Expected:** the **Platform** console at `/platformDashboard` — a dark header, a search box, a list of
tenants. **Not** the blue POS dashboard.

> **Why this is case one:** before E2, `ADMIN` was not in the routing map, so the operator fell through to the
> commerce default and landed on `/businessDashboard` — a shopkeeper's till, scoped to the operator's own
> accidental organization. Nothing was leaked; it was simply the wrong product.

### A2 — Find one customer among many
**Persona:** platform operator · **Preconditions:** several tenants exist (40 in the dev seed)

1. Type `mobile` into the search box.

**Expected:** the list narrows to matching tenants and the counter reads *"N of M tenants"*. Clearing the box
restores the full list and the count.

> Search and paging happen **on the server**. To confirm, watch the network tab: typing issues a request. A
> client-side filter over every tenant would work today and stop working at scale.

### A3 ⭐ — Spot the customers whose trial has run out
**Persona:** platform operator · **Proves:** E2 (case 4)

1. Scan the tenant list for the amber **⚠ Trial lapsed** badge.

**Expected:** every tenant on a `TRIAL` whose end date has passed carries the badge. In the dev seed there are
**14 of 20**. A tenant on `PRO` with an old trial date does **not** carry it.

> **The commercial point, and worth saying aloud in a demo:** these customers were invisible before this
> screen. A lapsed trial is a conversation somebody should be having.

---

## B. Managing one customer

### B1 🎬 — See what a customer is entitled to, and what they have switched on
**Persona:** platform operator · **Proves:** E1 + E2 (case 2)

1. From the tenant list, click a tenant.

**Expected:** two cards. **Plan** (tier, trial date, a control) and **Capabilities** (13 rows), each badged:

| Badge | Meaning |
|---|---|
| **Entitled** | sold to them; they may switch it on |
| **Not in plan** | their plan does not include it |
| **Revoked** | the platform withdrew it — an operator's decision, with the reason shown beneath |

> **Read the distinction carefully**: *Revoked by us* and *switched off by the tenant* look identical to a
> customer and are opposite problems. Without it an operator "fixes" an entitlement that was never what was
> wrong.

### B2 ⭐ — Withdraw a capability, with a reason
**Persona:** platform operator · **Proves:** E1 + E2 (case 8)

1. Open `owner.business@`'s tenant. Find **Sell on installments**.
2. Click **Revoke**. Type a reason — e.g. *"non-payment, invoice 4471"* — and confirm.
3. Log in separately as `owner.business@myplus.com` (a fresh login, per the caveat).
4. Go to **Settings → Configuration** and find *Sell on installments*.

**Expected:** the capability is off for the tenant, and its Configuration row is **disabled** with a
🔒 **Not in plan** badge. The reason is visible to the **operator**, never to the customer.

**Restore it afterwards:** Grant, reason *"payment received"*.

### B3 — A reason is not optional
**Persona:** platform operator · **Proves:** E2 (case 9)

1. Revoke a capability and leave the reason blank; confirm.

**Expected:** refused, with *"A reason is required for an entitlement change."* Nothing changes.

> Enforced by the **API**, not by the form — the endpoint is reachable without this screen. An audit trail of
> unexplained revocations answers *who* and *when* and not the only question anybody ever asks, which is *why*.

### B4 — Honour a contract without inventing a price tier
**Persona:** platform operator

1. Find a tenant on `FREE` with a capability badged **Not in plan**.
2. **Grant** it, reason *"contract 2026-114"*.

**Expected:** the badge becomes **Entitled**; the owner can now switch it on. Their plan is unchanged.

> An explicit grant beats the plan in both directions. Without it, every negotiated exception would need its
> own pricing tier.

### B5 — Move a customer onto a paid plan
**Persona:** platform operator · **Proves:** E2 (case 10)

1. On a lapsed-trial tenant, set **Plan** to `PRO`, reason *"converted"*, confirm.
2. Return to the tenant list.

**Expected:** the plan badge reads `PRO` and the **Trial lapsed** badge is **gone** — moving off a trial clears
its end date. An unrecognised plan is refused.

> If the date were left behind, a paying customer would go on reading as a lapsed trial on the very screen used
> to decide who to chase.

### B6 🎬 — Onboard a new customer without a developer
**Persona:** platform operator

1. **+ Provision tenant**. Fill in business name, owner email, first/last name, plan.
2. Submit.

**Expected:** the tenant is created, the owner receives a **set-your-password email**, and the tenant appears in
the list. **No password is displayed or issued** — no operator-known credential for a customer's account ever
exists.

> Before E2 this endpoint existed with no screen: onboarding a customer was a curl command.

---

## C. What the customer sees

### C1 ⭐ — An owner cannot give themselves a paid feature
**Persona:** tenant owner · **Proves:** E1 (`entitlement-ceiling.cy.js`)

1. As operator, revoke a capability from `owner.business@`'s tenant (B2 steps 1–2).
2. Log in as `owner.business@myplus.com`, go to **Settings → Configuration**.
3. Try to switch that capability on.

**Expected:** the control is **disabled** with a 🔒 **Not in plan** badge and a tooltip carrying the server's
own sentence. If it is switched on by other means, the server **refuses** the write.

> **This is the hole E1 closed.** The Configuration screen is owner-gated, and an owner holds every privilege
> inside their own org — so before the ceiling, any owner could grant themselves a paid capability for nothing.

### C2 — A withdrawn capability can still be switched OFF
**Persona:** tenant owner · **Proves:** E1

1. With the capability revoked but still switched **on**, turn it **off**.

**Expected:** allowed.

> If withdrawal also froze the switch, a tenant would hold a policy they can neither use nor clear, and the
> only way back would be a database edit.

### C3 — The help text stays readable on a locked row
**Persona:** tenant owner · **UI/UX**

1. Look at a locked Configuration row.

**Expected:** the label dims; the **explanation stays at full strength**.

> An owner reading a locked row is deciding whether to ask for it. A shop that cannot see what it is missing
> never upgrades.

---

## D. ⭐ The security cases — the ones that matter most

> The tenant list is the platform's **first deliberate cross-tenant read**. Everything else in the product
> scopes by organization; this screen's purpose is not to. **Run this section on every release.**

### D1 ⭐ — A customer cannot reach the operator portal
**Persona:** tenant owner · **Proves:** E2 (cases 5, 6)

1. Log in as `owner.business@myplus.com`.
2. Navigate directly to `/platformDashboard`.

**Expected:** **refused — HTTP 403.** No tenant list, no page, at any point.

> An owner holds `ADMIN_PRIVILEGE` inside their own organization, which is exactly why every gate here is the
> platform `ROLE_ADMIN` and never a privilege. Gating this on a privilege would hand every customer the list of
> every other customer.

### D2 ⭐ — Nor can the rest of the customer's team
**Persona:** tenant admin, tenant user · **Proves:** E2 (case 7)

1. Repeat D1 as `admin.business@` and again as `user.business@`.

**Expected:** refused for both, at the page and at the API.

> Same organization as the owner, so a refusal here proves **role**, not tenancy.

### D3 — A refusal is a 403, never a 500
**Persona:** tenant owner · **Proves:** E2

1. As `owner.business@`, call `/platform/organizations` directly.

**Expected:** **403**, with a readable message.

> Found by E2's own gate: every `@PreAuthorize` refusal in the monolith used to answer **500 InternalError**.
> A security event was being reported as a server error — every unauthorised attempt looked like a bug, and any
> real bug hid among them.

### D4 — The operator cannot reach a customer's trading data
**Persona:** platform operator · **By design**

1. Look for anything on the console showing a tenant's products, sales, invoices or stock.

**Expected:** **there is nothing**, and no endpoint behind the screen can return it. The console manages
**accounts**: plan, trial, entitlements, members.

> Deliberate, and the same line Shopify Partners draws. Reaching real tenant data is the **audited support
> session** (E5, not yet built). A "just peek at their products" shortcut is how a support backdoor gets built
> by accident.

---

## E. Known limits — do not file these as bugs

| | Behaviour | Why |
|---|---|---|
| **E1** | A change takes up to **15 minutes** to reach an active tenant session | Capabilities ride the JWT so no hot path makes a remote call (E1 ruling D-1). The screen states it where the operator acts. Log the tenant out and in to see it now |
| **E2** | The operator account **owns an empty organization** | An artefact of `getOrCreatePrimaryOrg`'s legacy first-login path. Recorded rather than fixed — changing it touches every first-login path (analysis §2b, Q4) |
| **E3** | No **usage** figures — how much of a capability a tenant actually uses | We do not collect per-capability telemetry. Salesforce shows licences *used*; inventing a number would be worse than omitting one |
| **E4** | Operator actions are **not yet in the audit log** | E4. Every mutation already carries `reason`, so that slice is a listener rather than a retrofit |
| **E5** | Search matches the **business name** only, not the owner email | Deliberate for now; widening it is one line when somebody asks |
