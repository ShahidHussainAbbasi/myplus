# Period close / lock

**Status:** ✅ DONE - period close/lock shipped (finance V4); `PeriodLockGuard` gates 10 operations and the owner has a Period-Close tab.

Closes the "period close / lock" item from `pos-retail-standards-audit.md` §2/§5 — freeze a date range so posted
transactions can't be changed or back-dated after the books are closed. Cadence: Document → Design → Implement →
headed Cypress → next.

> **Status: IMPLEMENTED** (finance single-source, D1). finance-service: `period_lock` (V4) + `PeriodLockService` +
> `GlService.postJournal` guard + `GET/POST /gl/period-lock`. commerce-contracts: `FinanceClient.getPeriodLock` +
> `PeriodLockView`. business-service: `PeriodLockGuard` (best-effort read, `app.period-lock.cache-ttl-ms` default 15s)
> wired into all 10 mutating ops (new→today, edit/void→original doc date), reason surfaced to the user. Monolith:
> `/gl/periodLock` proxy (`ADMIN_PRIVILEGE` on set) + owner-gated Finance → *Period Close* tab. Test:
> `cypress/e2e/business/period-close.cy.js`.

## 1. Problem
Nothing stops a user from editing/voiding an old invoice or back-dating a sale into a period whose books are already
closed/filed — which silently changes reported figures (tax already filed, financials already shared). A real system
lets an owner **close a period** (lock everything on/before a date) so it becomes read-only.

## 2. Model — one lock date per org
A per-org **`locked_through` date**: everything dated **on/before** it is frozen. Simple, matches how accounting
closes ("closed through 30-Jun"). Reopening = moving the date back (or clearing it). Default = null (nothing locked).

## 3. Where it lives + how it's enforced — finance-service single source (D1 chosen)
The lock is an **accounting** concept, so **finance-service** (the GL owner) is the single source of truth. It stores
the lock, exposes it, and enforces it on the GL; **business-service reads it** to gate its own user-facing ops.

- **finance-service** Flyway **V4**: `period_lock` (`organization_id` UNIQUE, `locked_through` DATE, `updated_by`,
  `updated_at`). `PeriodLockService`: `lockedThrough()`, `setLock(date)`, `assertOpen(date)` → throws when
  `date <= locked_through`. Enforced in **`GlService.postJournal`** (an entry dated in a closed period is rejected —
  covers business events via the outbox, the payment hook, AND direct `/gl/journal`).
- **API** (`GlController`): `GET /api/finance/gl/period-lock` (the org's date, or null) + `POST /api/finance/gl/period-lock`.
- **commerce-contracts**: `FinanceClient.getPeriodLock()` + `setPeriodLock(...)` + a `PeriodLockView` DTO.
- **business-service**: a `PeriodLockGuard.assertOpen(date)` that reads the org's lock via `FinanceClient` (best-effort
  — if finance is unreachable it does NOT block, availability over strictness) and throws `PeriodClosedException`
  ("This period is closed (locked through <date>). Reopen it to make changes.") when `date <= locked_through`. Called
  by the 10 mutating ops. (Direct read per op — a short-TTL cache is a trivial future optimization if the hop matters.)
- **Enforcement rule** (the effective date of the change):
  - **New** transactions (`addSell`, `addPurchase`, `receivePayment`, `payVendor`, `saleReturn`, `purchaseReturn`) →
    check **today** (a return/payment is a *new current-period* entry, allowed even if the original doc is in a closed
    period; it just can't be back-dated into a closed one).
  - **In-place, retroactive** changes (`updateSell`, `voidSell`, `updatePurchase`, `voidBill`) → check the **original
    document's date** — you can't alter a closed-period document.

## 4. Admin UI + audit
- Owner-only **Close Period** control (a date picker + Close / Reopen) on the dashboard, proxied to
  `/getPeriodLock` + `/setPeriodLock` → finance. Gated `sec:authorize="hasAuthority('ROLE_OWNER')"` like Tax Settings.
- **Audited lightly**: `period_lock.updated_by`/`updated_at` record who/when. (Full audit-service emission is a
  follow-up — finance-service isn't an audit producer yet.)

## 5. Decisions (defaults chosen; confirm the fork)
| # | Decision | Default (recommended) | Alternative |
|---|---|---|---|
| D1 | Ownership | **business-service owns + enforces** (local, performant); finance GL guard = Phase B | finance-service single source + business reads it (cross-service hot-path or a cache) |
| D2 | Date rule | **New ops → today; edit/void → original doc date** (standard) | Always check today (simpler, but lets you void a closed-period invoice) |
| D3 | Who can close/reopen | **Owner/super only** | Any admin |
| D4 | Reopen | **Allowed** (move the date back / clear), audited | One-way close (no reopen) |

## 6. Test plan (headed Cypress — `period-close.cy.js`)
1. Sale + close the period through today → editing/voiding that invoice is **rejected** ("period is closed"); a new
   sale dated today is also rejected (today ≤ lockedThrough).
2. Close through *yesterday* → today's new sale **succeeds**; editing/voiding an invoice from before the lock is rejected.
3. **Reopen** (clear the lock) → the previously-blocked edit/void now succeeds.
4. The lock set + reopen appear in the Audit Log (`PERIOD_CLOSE` / `PERIOD_REOPEN`).

## 7. Build surface
business-service (V24 + `PeriodLock`/repo/`PeriodLockService` + `assertOpen` in the 10 ops + `/getPeriodLock`,
`/setPeriodLock`) · monolith (proxies + owner-only Close Period control + dashboard JS). Contracts + finance unchanged
(Phase A). Cypress `period-close.cy.js`.
