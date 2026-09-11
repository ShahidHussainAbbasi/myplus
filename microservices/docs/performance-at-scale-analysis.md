# Why it slows down on big tenants — measured, and what to do

**Status:** ANALYSIS + **PERF-9 implemented** (Addendum A). Items 1-7 awaiting your go.
**Reported:** *"clients having huge data facing slowness … when I update the stock in `addstk_5422` and press
`addstkbtn_5422` it taking time to update … if everything is according to standards and best practice then
why still slow?"*

Every number below was measured on the running system on 2026-09-09 against **org 13**: 1,000 products,
807 sale lines, 4,510 stock rows. A customer with ten times the history multiplies them.

---

## 1. Verdict up front

**The architecture is not the problem. Four specific, fixable defects are — and three of them are the same
defect wearing different clothes: work that is done PER ROW, on a list that has no ceiling.**

The button you named is not itself slow. Measured, five runs each:

| | |
|---|---|
| `POST /addProductStock` | **31 ms** |
| `GET /productStock` (the refresh after it) | **50 ms** |

That is the whole button press: ~85 ms. What makes it *feel* slow is the screen it sits on, and what that
screen loaded before you touched it. On this tenant:

| Call | Time | Bytes |
|---|---|---|
| **`/getUserSell?q=-1`** | **2.93 s** | **1,758,072** |
| `businessDashboard` (HTML) | 0.39 s | 329,646 |
| `/getUserProduct?includeInactive=true` | 0.08 s | 384,540 |
| `/getUserCustomers` | 0.06 s | 64,018 |

**`/getUserSell` is 3.6 ms and 2.2 KB per row, on an indexed table.** That is the number to explain: no
index fixes it, because the time is not in the query.

---

## 2. The four causes, in order of what they cost

### ⭐ C-1 The proxy drops `page` and `size`, so pagination has never run

`business-service` has had paged reads since slice 24:

```java
List<Sell> objs = (page != null && size != null && pagedWholeOrg)
        ? sellService.findScoped(orgId(), userId(), PageRequest.of(page, size))
        : visibleSells();                       // ← the whole history
```

The monolith proxy forwards **one** parameter:

```java
String q = request.getParameter("q");
return client.get("/getUserSell", q != null ? "q=" + q : "");   // page/size dropped
```

**Proven, not inferred:** requesting `size=25`, `50`, `100`, `200`, `400` returns **exactly 1,758,072 bytes
every time.** The paged branch is unreachable, so every call loads the tenant's entire sale history.

⚠ **This is the fifth instance of one pattern in this codebase** — the GL outbox dropping event fields, the
product row projection, the monolith `SellDTO`, `addProductStock` dropping cost, and now this. The shape to
distrust is *a hand-built parameter list over a call that already has the parameters.* **18 proxies** build
their query string by hand; **12** pass it through.

### ⭐ C-2 N+1: 1,615 queries to draw one screen

```java
// Sell.java
@OneToOne(fetch = FetchType.EAGER, cascade = CascadeType.MERGE)
private CustomerHistory customerHistory;

// CustomerHistory.java
@ManyToOne(fetch = jakarta.persistence.FetchType.EAGER, cascade = CascadeType.MERGE)
private Customer customer;
```

`findScoped` is a plain JPQL select with **no `join fetch` and no `@EntityGraph`**. EAGER does not mean
"joined" — it means "fetched before the entity is returned", and Hibernate issues a separate query per row:

```
   1  select the 807 Sells
 807  select each Sell's CustomerHistory
 807  select each CustomerHistory's Customer
─────
1,615 round trips to MySQL for ONE screen
```

At 10,000 sale lines that is **20,001 queries**.

### C-3 No batch fetching configured

`hibernate.default_batch_fetch_size` is not set anywhere. One line in the shared config would collapse
C-2's 1,614 follow-up queries to ~17, without touching a single entity. It is the cheapest mitigation
available and it applies to every service at once.

### C-4 The mapper is reconfigured once per row

```java
objs.forEach(o -> {
    modelMapper.addConverter(appUtil.localDateTimeToString);   // ← inside the loop
    modelMapper.addConverter(appUtil.localDateToString);
    SellDTO dto = modelMapper.map(o, SellDTO.class);
```

`addConverter` mutates shared mapper state and invalidates its internal type cache. Called **1,614 times**
for one response, plus three reflection-based `map()` calls per row.

---

## 3. Two more that cost every call, everywhere

### P-1 No HTTP connection pooling between the monolith and the services

```java
private final RestTemplate restTemplate = new RestTemplate();   // GatewayClient
```

A bare `RestTemplate` uses `SimpleClientHttpRequestFactory` → `HttpURLConnection`: **a new TCP connection
for every proxied call, and no timeouts.** The class's own comment already records the second half —
*"the RestTemplate below has NO timeouts, so this is the shape a hung downstream takes"*.

Every screen makes 5–15 proxied calls, each paying a fresh handshake, and one hung service can hold a
request thread forever.

### P-2 Stock rows grow forever and are never compacted

`StockImportService` appends a new `stock_entries` row on **every** add and never merges. Measured: 4,510
rows for 3,624 products; the worst product carries **80 rows**, and 206 are fully exhausted but still read.
`/productStock` returns every batch of a product on each refresh — including the one that runs after the
button you reported.

At this size it costs ~50 ms. On a shop adding stock daily for two years it is unbounded.

---

## 4. What to do, in the order I would do it

| # | Change | Effort | Effect |
|---|---|---|---|
| **1** | `default_batch_fetch_size: 100` in the shared config | **1 line** | 1,615 queries → ~19, platform-wide |
| **2** | Forward the whole query string in the `/getUserSell` proxy | **1 line** | pagination starts working |
| **3** | Move the two `addConverter` calls out of the loop | **2 lines** | removes 1,614 cache invalidations |
| **4** | `@EntityGraph` on the scoped Sell reads | small | makes C-2 structural, not incidental |
| **5** | Pool + time-bound the `RestTemplate` | small | removes a handshake per proxied call |
| **6** | DataTables `serverSide: true` on the big grids | medium | the browser stops holding 1.76 MB |
| **7** | Compact exhausted `stock_entries` | medium | stops unbounded growth |

**Items 1–3 are four lines and should be measured together before anything else is considered.** My
estimate is that they take `/getUserSell` from 2.9 s to well under 300 ms, but that is a prediction — it
must be measured, not assumed.

### On tools and patterns, since you asked

* **`@EntityGraph` / `join fetch`** — the standard JPA answer to C-2. Already used 6 times in this codebase;
  the highest-volume read is not one of them.
* **DTO projections** (`select new com.…SellDTO(…)`) — skips entity hydration *and* the mapper entirely.
  The Sale Detail Report already proves the pattern here.
* **MapStruct instead of ModelMapper** — compile-time generated mappers, no reflection, no shared mutable
  state, and a mapping that fails to compile rather than silently dropping a field. Worth adopting for new
  code; a wholesale migration is not urgent.
* **`serverSide` DataTables** — the grids already page in the browser, which requires downloading
  everything first. This is the only real fix for a tenant with 50,000 sales.
* **Keyset pagination** (`WHERE sell_id < :last ORDER BY sell_id DESC LIMIT n`) rather than OFFSET, once
  histories get deep — OFFSET degrades linearly, and the existing `idx_sell_org_dated` already supports it.
* **A pooled, timed `RestTemplate`** (Apache HttpClient 5) or a migration to `RestClient`/`WebClient`.

**What I would NOT reach for:** caching, read replicas, or a rewrite. Every measured problem here is work
the system should not be doing at all; caching it would hide the cause and add invalidation bugs.

---

## 5. Answering the question directly

> *"If everything is according to standards and best practice then why still slow?"*

Because the standards held at the **boundaries** and not in the **middle**. Tenant scoping, indexing,
capability gating and the outbox are all correct — I checked. The gaps are all one shape:

**a list with no ceiling, and per-row work inside it.** Pagination that exists but never receives its
parameters; EAGER associations without a fetch plan; a mapper reconfigured per row; batch rows that
accumulate forever. Each is invisible at 100 rows and quadratic-feeling at 10,000.

That is why it appears only for "clients with huge data", and why it will keep appearing on new screens
until the *shape* is what gets reviewed — which is what `SAAS-BUILD-STANDARDS.md` §0 already asks for on
correctness, and does not yet ask for on cost.

---

## 6. What I did not check

* **The browser side.** These are server timings. Rendering 807 rows into a DataTable has its own cost, and
  a real client's slowness may be partly DOM, not network. Needs a profile on a real tenant.
* **Which endpoint a customer actually complained about.** The button reported measures fast here; the
  screen around it is where the seconds are. Worth confirming against a big tenant's own timings.
* **The other 17 paged endpoints.** `/getUserSell` was traced end to end. The same proxy shape appears
  18 times and each needs its own check — I have not verified that the other 17 lose their parameters too.
* **Education, welfare, agriculture.** Business was traced because that is where the report came from.

---

# Addendum A — PERF-9: the write answers with what it wrote (IMPLEMENTED)

**Asked:** *"can we just update that specific record on both side server and client side instead of fetching
all again?"* — yes, and it was already half-built.

## What it was

```
POST /addProductStock   → {"success":true,"created":"1"}
GET  /productStock?…    → read the new on-hand back
```

**Two browser round trips to change one number on screen.** The second existed only because the first threw
its answer away: `StockImportService` computes the new on-hand (it sets it on the StockLevel it saves) and
returned a row count. `StockService.adjustStock` was worse — `applyStockDelta` **returns** the new on-hand and
the method discarded it in favour of the audit row.

⚠ The pattern already existed one line away in the same interface: *`reconcilePurchase` … Returns the
product's new on-hand.* The import and the adjustment were the outliers.

## What it is now

| Layer | Change |
|---|---|
| `StockImportResult` (new contract DTO) | `created` + `onHand` per product |
| `StockImportService.importStock` | returns it instead of an `int` |
| `StockService.adjustStock` | keeps the on-hand `applyStockDelta` already returned |
| `StockAdjustment.resultingOnHand` | `@Transient` — a carrier, never a second source of truth |
| `CatalogController` ×2 | both writes answer with `stock` |
| `catalog-products.js` | `applyStock()` reads it; falls back to `refreshStock` when absent |

**Browser round trips per stock change: 2 → 1.** On a shop's own LAN that is ~50 ms; over a real connection
it is a whole round trip, on the button a stock-taker presses all day.

**Safe by construction, not by inspection:**

* All **six** bulk callers of `importStock` ignore the return value — verified one by one — so widening the
  contract changes nothing for a sale return, a void, a repossession or a purchase.
* `stock` is **absent** rather than `0` when it cannot be determined. A missing figure and a real zero must
  not look alike, and the client re-reads instead of rendering a wrong number.
* ⚠ The key-matching block **cannot throw**. The stock is already written by the time it runs, so an escaping
  exception would report a FAILURE for an add that succeeded — and the operator would answer that by adding
  the stock again.

---

# Addendum B — caching, reviewed

Asked for after the round-trip work. **Findings first: the caching that exists is well-chosen, and most of
what is missing should stay missing until items 1–3 are measured.**

## What exists

| Layer | State | Verdict |
|---|---|---|
| **Static assets** | content-hash URLs, 1-year `immutable` (PERF-2) | ✅ correct, and the hash makes it safe |
| **Client-side pickers** | `product-picker.js` (PERF-8), `customer-picker.js` — in-memory + one `ajaxComplete` invalidation hook | ✅ the right grain, and invalidation cannot be forgotten |
| **Settings** | hand-rolled `Cache<Long, Map<String,String>>` per ORG in `SettingsService` | ✅ 17 queries → 1; keyed by org, which is the load-bearing part |
| **Spring `@Cacheable`** | **not used at all** | ✅ deliberate — see below |
| **Hibernate 2nd-level cache** | **not enabled** | ✅ leave it that way |
| **Redis** | api-gateway only (rate limiting) | — available if ever needed |
| **HTTP `ETag` on API reads** | **none** | ⚠ the one real gap |

`SettingsService` records why `@Cacheable` was rejected, and the reasoning generalises: *"Spring's cache
annotations are proxy-based and a call arriving from inside the same bean bypasses the proxy … the annotation
would be present, reviewed, and inert"* — the same class of trap as `@EnableWebMvc` silently disabling
`spring.web.resources.cache.period`. **Any caching added here must be verified to actually fire**, not
assumed from the presence of an annotation.

## What I would NOT add

**Hibernate second-level cache.** It is tenant-blind by default, and this is a multi-tenant database where
`SettingsService`'s own comment already names the failure: *"a cache keyed without the org would serve one
tenant's configuration to another: silent, absent from the logs, and invisible to every existing test."*
The blast radius is a data leak, not a slow page.

**Caching `/getUserSell` or `/getUserProduct`.** These are slow because they do work they should not do —
1,615 queries and an uncapped result set. Caching that hides the cause, adds invalidation bugs, and still
serves the first user of every cache period a 3-second page.

## What is worth doing — but only AFTER items 1–3 are measured

| | Candidate | Why | Effort |
|---|---|---|---|
| **A** | **`ETag` / conditional GET on the big list reads** | A shopkeeper reloading the Product screen re-downloads 384 KB that has not changed. `ShallowEtagHeaderFilter` turns that into a 304 with no application change. The one gap with no downside. | small |
| **B** | **`ProductRef` lookups, short TTL, per org** | `productRefs(ids)` is a cross-service call on every sale, report and grid load. Already batched, so this is not an N+1 — but it is a network hop for data that changes rarely. Must be **org-keyed** and invalidated on product write. | medium |
| **C** | Tax codes / categories / units | Small, read on every screen, changed monthly. Same shape as the settings cache that already works. | small |

**None of these is the bottleneck today.** Item 1 (batch fetch size) and item 2 (forward the query string)
address a 2.9-second page; A/B/C address tens of milliseconds each. Doing them first would be optimising the
wrong number and would make the real cause harder to see.

**Recommendation: implement items 1–3, re-measure the same endpoints, and only then decide whether A–C are
still worth their invalidation cost.** A is likely to survive that test; B and C may not.

---

# Addendum C — PERF-10: items 1 and 3 implemented, and why the "bounded window" idea was dropped

## ⚠ A measurement that changed the plan

The obvious fix for a 1.76 MB grid reload is to load fewer rows. **Measured, it does almost nothing:**

```
  q=5     1.187 s     10,984 bytes
  q=25    1.563 s     55,351 bytes
  q=100   1.714 s    220,879 bytes
  q=-1    1.336 s  1,758,072 bytes
```

**`q=5` returns 11 KB and still costs 1.19 s.** The server loads every row from the database and then
truncates the list *in Java*, so capping the payload leaves the N+1 exactly where it was. Nearly all of the
server time is the 1,615 queries, not the bytes.

That rules out the bounded-window compromise as a primary fix — and it is worth recording, because it is the
change most people would reach for first. It would have cost the grid's client-side search (DataTables would
only see the loaded window) and bought back almost nothing on the server.

*(The 1.76 MB still matters — over a customer's actual connection it is seconds of transfer. It is a
TRANSPORT problem, not the server problem, and the fixes are different.)*

## What was implemented

**Item 1 — `default_batch_fetch_size: 100`**, in the shared config, one line, every service.

Hibernate fetches the missing associations in `IN (…)` batches instead of one query per row:
**1,615 → ~19 queries.** No mapping change, no code change, no behaviour change — the same reads, in far
fewer round trips. `@EntityGraph` on the specific hot reads is still the structural fix (item 4); this is
the floor under every read that has not had one.

**Item 3 — the mapper is configured once, not once per row.** Found in **three** places, not one:

| | Rows in the grid | Was |
|---|---|---|
| `SellController.getUserSell` | 807 | 1,614 `addConverter` calls |
| `CustomerController` | 543 KB grid | same shape |
| `PurchaseController` | 273 KB grid | same shape |

`addConverter` mutates the shared mapper and drops its internal type cache, so every row paid to rebuild
what the row before it had just built. Registering the same converter twice was always a no-op in effect —
only in cost.

**Neither change alters behaviour**, which is why they went first and why they are safe to ship without a
UX decision.

## Still open, and needing your call

**Item 2 — forward `page`/`size` in the proxy.** One line, but it only pays off once a grid actually asks
for a page, and that is the change that costs client-side search. Worth doing together with item 6
(`serverSide: true`), which moves search and paging to the server and loses nothing.

**The 1.76 MB transfer.** Batch fetching fixes the server; it does not shrink the response. For a remote
customer that payload is the larger half of what they feel. Items 2+6 together, or an `ETag` (Addendum B, A)
so an unchanged grid re-reads as a 304.

## Measure this, do not trust it

The prediction is `/getUserSell` ~1.3 s → well under 300 ms of server time. **It has not been measured** —
the config change needs config-server and business-service restarted to take effect. The same four commands
that produced the table above will confirm or refute it:

```bash
for q in 5 25 100 -1; do curl -s -b cookies -o /dev/null \
  -w "q=$q  %{time_total}s  %{size_download} bytes\n" \
  "http://localhost:8080/getUserSell?q=$q"; done
```

---

# Addendum D — items 4 and 5 implemented

## Item 4 — `@EntityGraph` on the scoped Sell reads

`default_batch_fetch_size` (item 1) is the floor under every read nobody has looked at. This makes the
highest-volume read structurally correct: **one joined query** instead of 19 batched ones.

Applied to `findScoped` (both overloads) and `findScopedByStores` — the reads `visibleSells()` actually
uses. Safe with `Pageable` because both associations are to-**one**: the join cannot multiply rows, so
Hibernate still pages in SQL. A collection here would force in-memory pagination, which is why none is
listed.

### ⚠ A trap removed on the way past

The repository carried this, commented out:

```java
// @EntityGraph(attributePaths = {"stock", "customerHistory", "customerHistory.customer"})
```

**`stock` is no longer an attribute of `Sell`** — the local Stock entity was retired when inventory-service
took ownership. Spring Data validates attribute paths at **startup**, so uncommenting that line would not
have been slow, it would have stopped business-service from booting. It read as a ready-made optimisation
one keystroke away. Replaced with a note saying so.

## Item 5 — a pooled, time-bounded HTTP client

```java
private final RestTemplate restTemplate = new RestTemplate();   // was
```

A bare `RestTemplate` uses `SimpleClientHttpRequestFactory` → `HttpURLConnection`: **a new TCP connection per
proxied call**, and **no timeouts at all**. Every screen makes 5–15 proxied calls, so every screen paid 5–15
handshakes a keep-alive pool makes free.

Now a shared `java.net.http.HttpClient` behind `JdkClientHttpRequestFactory` (Spring 6.1+; verified present
in the 6.2.7 on this build) — **connection pooling, HTTP/2 where offered, and no new dependency.**

### The timeouts are the part that matters for overload

| | | |
|---|---|---|
| `gateway.client.connect-timeout-ms` | **5 000** | a refusal should be seen fast |
| `gateway.client.read-timeout-ms` | **30 000** | deliberately ABOVE the gateway's own 20 s limiter |

⚠ **The read timeout is above the gateway's on purpose.** The gateway runs a resilience4j time limiter at
20 s and trips its breaker on calls slower than 18 s, answering with a proper fallback. A shorter timeout
here would abandon the call FIRST and throw that considered answer away — turning *"degraded, here is the
fallback"* into *"unreachable"*. This only fires when the gateway itself is hung, which nothing else covered.

This class's own log line had already recorded the consequence of having none: *"the RestTemplate below has
NO timeouts, so this is the shape a hung downstream takes."* A hung service held a monolith request thread
forever; enough of them and the monolith stops answering **every** tenant. That is the difference between a
slow dependency and an outage — and it is the first prerequisite for the overload guarantee.

## Where the plan now stands

| # | Item | State |
|---|---|---|
| 1 | `default_batch_fetch_size` | ✅ done |
| 2 | forward `page`/`size` | ⏸ deferred — only pays off with item 6, which needs a UX decision |
| 3 | mapper configured once | ✅ done (3 places, not 1) |
| 4 | `@EntityGraph` on the hot reads | ✅ done |
| 5 | pooled + timed HTTP client | ✅ done |
| 6 | `serverSide` DataTables | ⏸ needs the search trade-off decision |
| 7 | compact `stock_entries` | ⏸ not started |
| — | PERF-9 write-returns-state | ✅ done |

**Nothing above has been measured yet.** All of it needs config-server, business-service, inventory-service
and the monolith rebuilt before the numbers mean anything.
