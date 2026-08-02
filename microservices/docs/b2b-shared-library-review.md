# Service review — new shared libraries vs new dependencies

**Status:** PARTLY ACTED ON (2026-08-01). Its credit-limit recommendation is **built** — as
`CreditLimitPolicy` in the **existing `common-credit`** library rather than the new
`commerce-credit-policy` this proposed (see the resolution note in §Credit limit). The remaining
recommendations — pricing, party account hierarchy — are still analysis awaiting approval.
**Context:** **all seven modules are live.** Nothing may break, every default must preserve today's
behaviour, and every migration runs against populated tables.
**Companion to:** [`b2b-b2c-rollout-plan.md`](b2b-b2c-rollout-plan.md).

---

## 1. What already exists — reuse before building

Eleven shared libraries are already in place. **Three of the four capabilities the B2B work needs are
already solved by them**, which is the main finding of this review.

| Library | Classes | Used by | Relevant to B2B? |
|---|---|---|---|
| `common-web` | 7 | **15** | ✅ error envelope, exceptions |
| `commerce-contracts` | 28 | **9** | ✅ ProductRef, clients, DTOs |
| `common-security` | 12 | 5 | ✅ `CurrentUser`, tenant scope |
| `common-settings` | 7 | 5 | ✅ **every new toggle lands here** |
| `common-notify` | 2 | 5 | ✅ **#7 e-mail digest** |
| `common-outbox` | 4 | 3 | ✅ reliable GL/event delivery |
| `common-subledger` | 8 | 3 | ✅ AR/AP movements |
| `common-credit` | 3 | 3 | ⚠️ **see §2 — different concept** |
| `commerce-domain` | 3 | 2 | – |
| `common-captcha` | 7 | 2 | – |
| `common-service` | 2 | 2 | – |

### The pattern to follow

`common-credit` is the reference implementation of how a shared capability should be built here — and it
is worth copying exactly:

```java
public interface CreditStore {          // ← the library defines the PORT
    void append(Long partyId, BigDecimal signedAmount, String reason, String ref);
    BigDecimal balance(Long partyId);
    void cacheBalance(Long partyId, BigDecimal balance);
}
```

> *"Each service supplies its own table and its own cached-balance owner, so **no credit data is shared
> across services** — only the rules in `CreditService` are. Every method is tenant-scoped BY THE
> IMPLEMENTATION — the shared logic never sees an org id, so it cannot get scoping wrong."*

**Rules shared, data owned locally, no runtime coupling.** business-service and education-service both use
it against completely different tables (`store_credit_txn` / `fee_credit_txn`). That is the model for
everything below.

---

## 2. ⚠️ Naming collision to avoid

`common-credit` is **store credit** — a customer wallet, money *the shop owes the customer*.
> **Resolution (2026-08-01, while designing Phase 1).** This review proposed minting
> `commerce-credit-policy`. It should not be minted: **`common-credit` already is that library** — its own
> header says *"no credit data is shared across services — only the rules … are"*, which is precisely the
> shape argued for here, and it already carries the `CreditStore` SPI as the reference for keeping tenant data
> local. Adding a second credit library beside it would split one concept across two modules for no gain, and
> reuse-first is the standing rule. The limit policy therefore lands in `common-credit` as
> `CreditLimitPolicy` — pure arithmetic, **no SPI needed**, because the caller already holds the balance and
> the limit on the row it just loaded.
>
> Note the two are different concepts sharing a library, deliberately: `CreditService` = credit the customer
> **has** (a liability the shop owes), `CreditLimitPolicy` = credit the customer **may take** (a cap on the
> receivable). Same domain word, same architectural shape, opposite direction of money.

Requirement #9 is a credit **limit** — how much *the customer may owe the shop*.

**Opposite directions, same word.** Folding the limit into `common-credit` would produce a class where
`balance()` means two contradictory things. Keep them separate, and name the new one so the difference is
unmissable: **`commerce-credit-policy`**.

---

## 3. The decisions

### D1 — Credit limit: **library, no new runtime dependency** ✅

The OMS plan suggested *"credit-check calls finance AR"*. **I recommend against it**, and the evidence is
that business-service already holds both numbers:

- `Customer.dueAmount` — maintained **locally** (21 `setDueAmount` call sites in business-service)
- `Customer.creditLimit` — the new field, also local

So the check is `dueAmount + thisSale > creditLimit`. Calling finance at sell time would be **a new
network hop on the hot path to fetch a number the service already has** — and it would make every sale
fail when finance is down, for a *warning*.

**Build:** `commerce-credit-policy` — a stateless library holding the decision rules (thresholds,
`off|warn|block`, message shaping), with a `CreditLimitStore` SPI each service implements against its own
table. Same shape as `common-credit`.

**Consumers:** business (customers + vendors) · education (guardian fee dues) · marketplace (B2B buyers) ·
welfare and appointment later. **Five live modules, one rule set.**

> Finance stays the source of truth for the *ledger*. The sell-time check is a fast local guard, exactly
> as the period-lock read already is ("fails open, GL is the backstop").

### D2 — Pricing: **library + tables on catalog, resolved once per sale** ✅

Contract price is per **customer**, so it cannot ride on `ProductRef` the way `rxRequired` does (that
worked because the flag is a property of the *product*).

**Build:** `commerce-pricing` — stateless resolution `base → contract → volume tier → promotion`, with a
`PriceListStore` SPI. Tables live on **catalog-service** (it owns product data).

**Hot-path rule:** resolve the customer's applicable price list **once per sale**, not per line — one
lookup, cached per request. The sell loop already fetches a `ProductRef` per line; pricing must not add a
second per-line call.

**Consumers:** business · marketplace · pharma (institutional rates) — and education later for
sponsor-negotiated fees.

### D3 — Alerts (#7): **reuse `common-notify`, add nothing** ✅

`common-notify` already gives `EmailRequest` + `NotificationClient`, used by 5 services.
inventory-service already computes near-expiry/low-stock alerts. The gap is a **scheduler + thresholds**,
both of which belong *in* inventory-service. No new library, no new service.

### D4 — Reports (#6): **library, not a service** ✅

**Build:** `commerce-reporting` — the query-shaping and export (CSV/PDF) logic, no data of its own. Each
service supplies its own query via a `ReportSource` SPI.

Rejected alternative: a reporting *service* that reads other services' databases. That would breach the
database-per-service rule and couple every schema change to it.

### D5 — Documents (#1, #4, #5, #13): **library** ✅

**Build:** `commerce-documents` — receipt / invoice / credit note / statement rendering, numbering series
(`INV-`/`CRN-`/`DBN-`), and the promo footer (#13, off by default).

Consumers: business · marketplace · pharma · education (fee vouchers already render separately — this
converges them).

### D6 — Targets & bonuses (#11): **defer the library** ⏸

One consumer today (business-service, supplier targets). A library with one consumer is speculative
generality. Build it inside business-service; extract if education or marketplace need it.

### D7 — `logistics-service`: **the only justified new SERVICE** ✅

Shipping, carrier integration, pick/pack — owns its own data and lifecycle, integrates external systems.
That is a real bounded context. Everything else above is a library.

---

## 4. Summary

| Need | Verdict | Where |
|---|---|---|
| Credit limit (#9) | ~~new library `commerce-credit-policy`~~ → **`common-credit` (existing)** | rules shared, data local — **no finance call at sell time**. Resolved 2026-08-01: the library this proposed **already exists**. See the note below. |
| Pricing / discount (#10) | **new library** `commerce-pricing` + tables on catalog | resolved once per sale |
| Reports (#6) | **new library** `commerce-reporting` | SPI per service |
| Documents (#1/4/5/13) | **new library** `commerce-documents` | numbering + render + promo |
| Alerts (#7) | **reuse** `common-notify` | scheduler in inventory-service |
| Config (all) | **reuse** `common-settings` | one-line catalog entry each |
| GL events | **reuse** `common-outbox` + `common-subledger` | already proven |
| Targets (#11) | **no library yet** | one consumer |
| Shipping | **new service** `logistics-service` | only justified service |

**Four new libraries, one new service, zero new runtime dependencies on the sell hot path.**

The sell path keeps exactly the four calls it makes today:
`catalogClient.getProduct` · `inventoryClient.reserve` · `confirm` · `release`.

---

## 5. Because all modules are live

| Rule | Why |
|---|---|
| **Every new setting defaults to today's behaviour** | A live shop must see no change until it opts in |
| **Additive migrations only** — new nullable columns, no renames, no drops | Seven populated databases |
| **`customerType` defaults to B2C for every existing row** | Nobody becomes a B2B customer by accident |
| **Libraries version independently** | A `commerce-pricing` change must not force redeploying education |
| **Adopt one service at a time** | business-service first as the reference; others opt in per their own release |
| **No breaking contract changes** | `commerce-contracts` has 9 consumers — additive fields only |

**Rollout order per library:** build → adopt in business-service → gate with Cypress → then offer to the
other verticals. Never adopt in all seven at once; a library bug would then be seven simultaneous incidents.

---

## 6. What I need approved

1. **The four new libraries** — `commerce-credit-policy`, `commerce-pricing`, `commerce-reporting`,
   `commerce-documents`.
2. **D1 specifically** — credit check stays **local** (no finance call on the sell path). This diverges
   from the OMS plan's *"credit-check calls finance AR"* and I want it explicit rather than assumed.
3. **The naming** — `commerce-credit-policy` vs the existing `common-credit` (store credit). If you prefer
   different names, now is the time; renaming after five services adopt it is expensive.
4. **Deferring #11's library** until a second consumer exists.
