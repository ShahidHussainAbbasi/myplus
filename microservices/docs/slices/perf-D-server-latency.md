# PERF-D — server latency: the dashboard and the sale screen

**Status:** D1–D5 DONE + GREEN 2026‑08‑26. D6 closed (security). D6b open — see §10.
**Scope:** business-service read paths + the LoadBalancer cache. No schema beyond one index.
**Prompted by:** a production slowness report. Everything below was measured against the running stack
before it was changed, and again after.

---

## 1. The headline, which was not what anyone expected

The slowest thing on the platform was **not a missing cache**. It was three read paths doing work that
should never have been done at all, and caching them would have hidden behaviour that degrades linearly
with tenant size.

| Path | Before | After |
|---|---|---|
| `/getBusinessDashboardStats` | 640 ms for a **183-byte** payload | **~65 ms** |
| `/getDashboardChartData` | 1.9–2.6 s, every call | **~0.2 s** |
| Dashboard page total | **~3 s** | **~0.27 s** |
| Sale screen customer load | 196,610 B / 350 ms | **65,919 B / 71 ms** |
| LoadBalancer production cache | 6 of 17 services (by accident) | **17 of 17** |

None of these improved on repeat before the change: nothing was cached because nothing needed to be.

---

## 2. D1 — dashboard counts (commit `be979496`)

```java
long companyCount  = companyService.findScoped(orgId, userId).size();
long venderCount   = venderRepo.findScoped(orgId, userId).size();
long customerCount = customerService.findScoped(orgId, userId).size();
```

Three tenant tables hydrated into JPA entities and discarded to keep an integer — the customer one is the
same read that returns ~196 KB elsewhere — plus a month of `Sell` entities counted and summed in a Java
stream. Replaced with `COUNT(*)` and one aggregate row.

**The risk was never that the new query would fail.** It was that it would succeed with a slightly
different predicate: a plausible integer on a dashboard nobody would think to check. *There is no screen on
which "441" looks wrong.* So each `countScoped` carries a character-for-character copy of its `findScoped`
predicate, NULL-org fallback included, and `ScopedCountParityTest` (5/5, real MySQL) asserts the same fact
twice — list and count — and requires agreement. It also pins the literal, because parity alone would pass
if both sides were wrong in the same direction.

Verified against the live database: companies 45, venders 23, customers 441, monthlySales 367, revenue
16,386,507 — every figure identical.

---

## 3. D2 — the index, and the one that was nearly shipped instead (`V49`)

The plan said index `sell(dated)`. **`findSellByDates` filters on `updated`.** An index on `dated` would
have been written, measured, shipped, and been completely useless to the query it was meant to serve —
`dated` is simply the column a reader expects a sales report to use.

The cause was also deeper than a missing index:

```
without the OR-null branch : type=ref    key=idx_sell_org_user     rows=394
as the application runs it : type=ALL    key=NULL                  rows=1181  filtered=3.72%
with idx_sell_org_updated  : type=range  key=idx_sell_org_updated  rows=363   filtered=100%
```

The NULL-org fallback is survivable alone — `customer` and `vender` still reach their indexes as
`ref_or_null`. What breaks `sell` is that fallback **combined with a range predicate no index covers**:
MySQL has nothing to seek on and abandons the index entirely.

`filtered=100%` is the result that matters — every row read is now a row kept, where before it discarded
96 in every 100. V49 was proven before it was written: the index was created on a live copy, `EXPLAIN`ed,
and dropped again.

---

## 4. D3 — dashboard charts (commit `daee7fd7`)

Four aggregations, all the same shape:

| Was | Now |
|---|---|
| six months of `Sell` hydrated → `Map.merge` loop | `GROUP BY year, month` |
| a month of `Sell` hydrated → day-bucket array | `GROUP BY day` |
| the same month re-walked → `HashMap` + sort + limit 5 | `GROUP BY productId ORDER BY … LIMIT 5` |
| **every customer loaded (441)** → filter, sort, keep 10 | `WHERE due > 0 ORDER BY due DESC LIMIT 10` |

**Two traps avoided, both of which would have changed a number silently:**

* `SellRepo.topProductsScoped` already existed and looked like exactly the query needed. It is not — it
  filters on `dated` with an open-ended `since`, while this endpoint buckets by `updated` within a closed
  month. Reusing it would have shown a different set of products with nothing to say why.
* The stats endpoint sums `netAmount`; this chart has always summed `totalAmount`. Harmonising them here
  would have moved a number on a chart somebody reads, under cover of a performance change.

Every series verified against SQL: monthly count 367, revenue 16,386,517, top-product quantities 6/5/3/3/3,
day-16 revenue 14,455.00, top due customer 150,000.00.

---

## 5. D4 — the customer picker's lean projection (commit `9dd1da93`)

`/getUserCustomer` returns 22 fields for 441 customers, unpaginated, on every open of the sale screen. The
dropdown binds six. `/customerOptions` selects exactly those.

**Alongside, not instead.** Forty Cypress specs read `/getUserCustomer`, and screens legitimately need the
full record — `partyId`, addresses, licence details. Slimming a general-purpose read because two of its
callers are pickers would break the rest to speed those two up. This is the PERF-8 shape.

The payload is the visible half: as a **constructor projection**, Hibernate never builds 441 managed
entities, never walks `customerHistory`, and ModelMapper never runs.

**The gate asserts scoping, not size.** Both reads are role-aware — whole-org viewers see the org, everyone
else only what they created. Two projections mirror `findScoped` and `findOwnScoped` exactly. A picker that
scoped differently would show an operator a customer they cannot otherwise open, or hide one they can, and
*nothing on the screen would reveal either.*

### The defect this shipped with, and why the build did not catch it

The DTO first declared `String customerType`; the entity's is a `CustomerType` **enum**. A JPQL constructor
projection matches on TYPE, so business-service crash-looped 13 times:

```
SemanticException: Missing constructor for type 'CustomerOptionDTO'
```

> **`mvn install` PASSED.** Constructor projections are validated at bean creation, not compile time, so
> nothing before deployment could have caught it. The only defence is checking every field type against the
> entity — I had verified `dueAmount` was `BigDecimal` and assumed the other five.

---

## 6. The LoadBalancer cache (commit `34dcaeab`)

Spring warns on startup that LoadBalancer is using its development cache. **Dismissed the first time, wrongly:**
the monolith has no `@LoadBalanced` bean and calls services by fixed URL, and that conclusion was generalised
to the whole stack. The microservices resolve peers through Eureka, and the gateway's routes are `lb://` URIs.

A perfect inverse correlation settled it — the six quiet services were exactly the `common-settings`
consumers, so PERF-C1 had already fixed those **by accident**, which is what made the wrong sample look
convincing.

Declared in three places rather than eleven: `service-parent` (15 services), `api-gateway` and `auth-service`
(neither extends it). `eureka-server`, `config-server` and the monolith are deliberately excluded — none
resolves anything through LoadBalancer. Verified 17/17 quiet **and** `caffeine-*.jar` present in all 17 jars:
an absent log line could mean rotated logs; the jar is the property.

---

## 7. Open

| # | Item | Note |
|---|---|---|
| D5 | Converge the hand-rolled caches | ✅ **DONE** — see §9 |
| D6 | ETag on authenticated tenant reads | ⛔ **WILL NOT IMPLEMENT** — see §10 |
| D6b | `Cache-Control` on PUBLIC static views | 🔓 **open, and security-neutral** — see §10 |
| — | ~~Cache the dashboard~~ | **dropped**: at 0.27 s it buys little and adds a staleness question that does not currently exist. Caching it *first* would have masked the query — which is the whole lesson of this slice |

### Not a performance question, and awaiting a decision

**"Sales this month" is filtered by `updated`, not `dated`**, so an edited old invoice moves between months.
That is a correctness question about the report. Recorded in `V49` because if it is ever changed, the index
must move with it.

---

## 8. On cache types, since it came up

TTL, LRU and LFU are usually discussed as alternatives. They are not: **TTL bounds staleness (correctness);
LRU/LFU bound memory (capacity).** Most real caches need one of each.

The multi-tenant argument is the one that matters here: a shared **LRU** cache is polluted by scale
asymmetry — one large distributor running a report walks thousands of rows, those become the
most-recently-used entries, and the working set of every small shop is evicted. **LFU** resists it (a
one-off scan builds no frequency); **W-TinyLFU** resists it better, because a frequency sketch decides what
is even *admitted*. That is what Caffeine gives by default, and the right baseline for every per-tenant
cache here.

**Rule of thumb for this codebase:** TTL for freshness, size bound + W-TinyLFU for memory, and
eviction-on-write wherever a single writer exists — an exact eviction beats any TTL guess. `SettingsService`
(PERF-C1) already does all three.

**Redis is deployed and barely used, and that is currently correct.** Every cache discussed is per-tenant
configuration read constantly and written rarely; in-process Caffeine answers in nanoseconds where Redis
costs a network hop. Redis earns its place when a cache must be shared across replicas or survive restart —
today each service runs a single container.

**Not cached, deliberately:** stock, credit standing, balances. The books have already drifted once from a
stale read, and milliseconds are not worth a wrong number.


---

## 9. D5 — one bounded per-tenant cache, and the maps that must not use it

`PeriodLockGuard` and `CheckoutService.taxPolicyCache` were the same thing written twice: a
`ConcurrentHashMap` keyed by organisation, holding a value beside an expiry timestamp. **Neither had a size
bound** — keyed by organisation, they grew with every tenant the process had ever served. Both now use
`common-web` `TenantCache` (TTL + `maximumSize` + Caffeine's W-TinyLFU admission).

**The plan said "converge the four". That was wrong.** `RateLimitGlobalFilter` and `CaptchaAttemptService`
hold superficially similar maps and are **counters, not caches**. For a cache a miss is merely slow;
evicting a rate-limit window hands the caller a fresh quota, and evicting a failed-login counter forgives
the attempts. Moving them would have been a security regression dressed as a tidy-up.

They are bounded a different way — **pruned by the clock, never by pressure**. The size threshold decides
only *when* to sweep; what is swept is decided purely by expiry, so a live entry can never be dropped:

* `RateLimitGlobalFilter` already did this (sweep expired windows past 10,000 keys).
* `CaptchaAttemptService` did **not**, and that was a real leak: entries were removed only when a key was
  read again, so a username that fails once and is never retried stayed for the life of the process — the
  credential-stuffing shape exactly. It now copies the gateway's pattern. Gate: **a live block survives a
  sweep of 10,000+ expired entries**, which is the property an LRU would break while looking like a fix.

### Two defects caught inside this slice

* The cache was first built at field initialisation, where `@Value` has not yet been injected — which would
  have made `app.period-lock.cache-ttl-ms` **inert**. That is the same trap PERF-C1 recorded, repeated
  within the hour. Both caches are now built in `@PostConstruct`.
* **Caffeine does not record a null mapping.** `PeriodLockGuard` caches nulls deliberately — null means "no
  lock", the *common* answer, and it is also the fail-open fallback. A naive migration would have stopped
  caching the common case and turned every write into a remote call: **slower after the change than before**,
  while looking tidier. It now caches `Optional<LocalDate>`.

### Final state — every cache and counter carries both bounds

| | Staleness | Memory | Evicts by |
|---|---|---|---|
| `SettingsService` | TTL 60 s, configurable | 1,000 | W-TinyLFU |
| `TenantCache` (period lock, tax policy) | TTL configurable | 1,000 | W-TinyLFU |
| `RateLimitGlobalFilter` | 1 s window | sweep at >10k | **clock only** |
| `CaptchaAttemptService` | 4 h window | sweep at >10k | **clock only** |

The split is the point: **caches evict under pressure; counters prune strictly by time.**

---

## 10. D6 — ETags: closed for tenant reads, open for public pages

### Closed, and why it is a security decision rather than a performance one

An ETag only helps if the browser **stores** the response — that is what it revalidates against. Every
authenticated response here carries Spring Security's default:

```
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
```

`no-store` means *never write this to cache*. **Enabling ETags therefore requires removing `no-store`** —
there is no variant that keeps it. On a POS/ERP product that matters more than usual, because **tills are
shared between cashiers and shifts**, and `no-store` is what stops tenant data persisting in a browser's
disk cache after logout.

The candidates settle it:

| Endpoint | Size | |
|---|---|---|
| `/customerOptions` | **69 KB** | customer names, contacts, **due amounts** — personal *and* financial |
| `/settings` | 24.8 KB | tenant configuration |

**The only endpoint with a meaningful payoff is the one that most needs `no-store`.** Trading disk-cached
customer balances on a shared till for 69 KB is a bad trade. Closed — recorded here rather than left as an
open item somebody revisits later without the security context.

### But the blanket close was too broad — D6b is open

Checking the public surface rather than assuming, two unauthenticated pages are sent `no-store` purely
because Spring Security applies its default globally:

| Endpoint | Size | Tenant data? |
|---|---|---|
| `/services` | **72 KB** | **none** — `MvcConfig:92` is `addViewController("/services")`, a static view with no model |
| `/store` | 32 KB | public storefront — needs checking before any change |

`/api/live-users` already carries `Cache-Control: max-age=10`, so a per-endpoint caching decision has been
made here before; the pattern exists.

**`/services` is a genuine, security-neutral opportunity**: 72 KB of static HTML re-sent on every visit,
with nothing to leak. It is open rather than done because it needs the same care as the rest of this slice —
scoped to that path, not applied globally, and `/store` verified for tenant scoping before being included.
