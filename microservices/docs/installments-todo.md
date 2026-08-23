# Installments (Shahzad Mobile Shop) — what is left

**Status:** open backlog. Plan of record: `installment-dues-reminders-design.md` §8.
**Last verified:** 2026-08-23 — business-service unit **179 / 0 fail, 0 skipped**; every installment gate green.

**Independently re-run 2026-08-23 (second pass, different session).** Unit **179/179**; Cypress **47/47**
across all eight installment specs — plan 6, screen 5, statement 4, aging 3, worklist 7, serial 3, reminders 9,
repossession 10. Deployment checked before trusting any of it: `ReminderScanner`, `RepossessionService`,
`SequenceRetry` and `OrgDocumentSeq` all present in the running jar, and **V43–V46 applied with `success=1`**
in the live schema with counters seeded from real maxima (INVOICE 324, PLAN 191, CREDIT_NOTE 66, DEBIT_NOTE 13,
QUOTE 42). A passing spec cannot show that the migration ran or that a counter starts above the numbers already
issued — only reading the database can.

One defect found and fixed on the re-run: `installment-screen.cy.js` typed into the plan fields while the
`.ao-box` AJAX overlay from *Add to cart* still covered them. Cypress reports that as "cy.clear() failed
because this element is being covered", which reads as a broken field rather than a timing problem. Fixed by
waiting for the overlay at all **three** add-to-cart sites — not with `{force:true}`, which would type into a
control the operator genuinely cannot reach yet and pass on a screen a human could not use. Two of the three
sites had never failed; they were latent.

> Workflow rule (also in memory): no design or implementation starts without confirmation. The user runs all
> compile/build/run/restart. A slice is not done until its Cypress gate passes.

---

## 1. Delivered — nothing outstanding

| Slice | What it gives the shop | Gate |
|---|---|---|
| INST-1 | Sell a handset on terms; schedule agreed at the counter before commit | `installment-plan` 6/6 · `installment-screen` 5/5 |
| INST-2 | Know the dues: aging by schedule, statement block, Installments screen, collections worklist | `installment-aging` 3/3 · `installment-statement` 4/4 · `installment-worklist` 7/7 |
| INST-3a | The chase list — who to ring today, with the number, and what they said last time | `installment-reminders` 9/9 |
| INST-5a | One live plan per IMEI; repossession with the books right | `installment-serial` 3/3 · `installment-repossession` 10/10 |

Supporting work that came out of gating rather than the plan: serialised document numbers (all six sequences),
the Installments grid (search / paging / exports), and the Testcontainers fix that made 13 silently-skipping
tests actually run.

---

## 2. Open — in the order I would take them

### 2.1 INST-4 — SMS reminders · **BLOCKED ON A CUSTOMER DECISION**

Everything upstream is in place: the chase log carries a `dedupe_key` already sized and shaped for
`notification_broadcast.dedupe_key`, `NotificationClient` takes it, and notification-service enforces it with a
UNIQUE constraint. This is a **transport plug-in, not a redesign**.

**What is needed from the customer, and nothing can start without it:** an SMS provider and a budget.
`Channel.SMS` is a deliberate no-op today precisely because no provider has been chosen.

**Why it matters more than its position suggests:** the sale screen collects a name and a phone number and
no email address, so SMS is the only channel that actually reaches a handset buyer. R4 is currently met as a
call-list, which works but costs the shopkeeper time per customer.

Scope when unblocked: `SmsGateway` port + adapter, channel preference, opt-out, quiet hours, per-tenant quota.
Gate: an SMS broadcast is **dispatched**, not stranded `PENDING`; an opted-out customer gets nothing; quota
exhaustion refuses without losing the row.

### 2.2 INST-6 — markup and late fees to the ledger · **CONDITIONAL**

Only worth doing if the shop intends to **charge markup** on financed sales. Today a plan carrying a markup is
**refused outright**, which is the safe state — finance income is not goods revenue, and posting it to Sales
would misstate the trading account.

Scope: markup → `4400`, late fee → `4500`, unearned finance income if term-recognised.
Gate: **the trial balance** after a marked-up plan and a charged late fee — not the invoice. (The 4200 incident
is the standing warning: three specs stayed green for months over an account that was empty in every tenant.)

### 2.3 INST-5b — full serialisation · **OPTIONAL**

5a deliberately did not build an inventory-wide serial registry, because none of its three behaviours needed
one. Worth doing when the shop wants warranty or theft traceability on **cash** sales too, not only financed
ones.

Scope: `serial_unit` in inventory-service — serials captured at goods-in, picked at sale, shown on the stock
grids. It belongs in inventory, not here: a serialised unit is a stock lifecycle fact, and repossession moves
stock.

### 2.4 INST-7 — extract `common-reminder` · **NEEDS A SECOND CONSUMER**

Premature until education fee instalments exist to prove the abstraction against. Extracting a shared library
with one caller produces a library shaped like that caller.

Gate: education's fee reminder green with **no new arithmetic and no new delivery code**.

### 2.5 INST-8 — mobile-shop tenant profile · **COSMETIC**

Branding, an IMEI field on the shape, an Installments menu entry.

⚠ Carries a real trap the design already flags: a new `Organization.type` needs **two registrations**, and
`ModuleRouter.DASHBOARD_BY_TYPE` is a fixed map with no `MOBILE` entry — an unknown type bounces every login to
`/`. Silent failure, whole-tenant impact.

---

## 3. Carried defects — small, pre-existing, unscheduled

| # | Item | Why it has not been done |
|---|---|---|
| C1 | `CustomerService.saveUpdateCustomer:252` throws a null-pointer on a missing customer instead of refusing with a message | Every real client sends the block; only reachable by a hand-made request |
| C2 | The eight installment specs each duplicate their small fixture helpers (`uniq`, `setConfig`, `monthsOut`, `sellOnPlan`) | Extracting a shared fixture module is its own piece of work, not a side effect of a slice |
| C3 | `mvn -pl <service> test` compiles against the **installed** library jar | A stale jar hid an entire test class. Build with `-am`, or install libraries first. Worth a build-script guard eventually |

---

## 4. Two rules this programme paid for — keep them

**Setup is correctness; cleanup is courtesy.** A spec must establish the state it depends on, never inherit it.
`after()` runs only if the run survives — when an auth token expired mid-run, a hook failed and left a tenant
switch on, and a spec three files away dropped to 1/6 for a reason nothing inside it could explain.

**A total is allocated, never derived by rounding a proportion.** The residual lands on one component so the
parts sum to the whole exactly. Breaking this left a customer two paisa in credit permanently — **while the
trial balance still balanced**, because the posting was self-consistent and simply for the wrong amount.
