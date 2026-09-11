# Turning "it should be fast" into a promise you can hold someone to

**Status:** ANALYSIS, for review. No code changed by this document.
**Asked:** *"make sure it should be a measurable promise: fast user journeys at the 95th/99th percentile,
isolated tenants, predictable overload behavior, and automatic recovery/scaling."*

Audited on 2026-09-09 against the running stack. Every claim below has a command or a file behind it.

---

## 1. Verdict up front

**Of the four things you want to promise, one is nearly true, one is half-built, and two cannot currently be
stated at all.**

| Pillar | Today | Blocker |
|---|---|---|
| Fast at p95/p99 | ❌ **unmeasurable** | no latency metrics exist anywhere |
| Isolated tenants | ❌ **contradicted** | 8 DB connections per service, shared by every tenant |
| Predictable overload | ⚠ **half** | breakers ✅ and timeouts ✅ (today), rate limiting OFF, no bulkheads |
| Automatic recovery / scaling | ⚠ **recovery yes, scaling no** | in-memory sessions make a 2nd monolith instance impossible |

⚠ **A promise you cannot measure is not a promise.** The first item is therefore not one of four tasks — it
is the precondition for the other three, because "isolated", "predictable" and "recovers" are all statements
about numbers nobody is currently collecting.

---

## 2. Pillar 1 — fast user journeys at p95/p99

### The evidence

```
/actuator/prometheus  → exposed in config, 404 at runtime
micrometer-registry-prometheus → on 0 of 22 services' classpaths
otel config comment   → "Metrics are OFF for now (logs+traces scope)"
collector / Grafana   → NONE running
```

The shared config lists `prometheus` in `management.endpoints.web.exposure.include`, so it **reads as
instrumented**. The registry is not on the classpath, so the endpoint does not exist. That is the same
"present, reviewed, and inert" shape `SettingsService` already documents for `@Cacheable` and
`@EnableWebMvc` — the third instance of it in this codebase.

**There is no p50 anywhere, let alone a p99.**

### What has to change

1. `micrometer-registry-prometheus` on every service (it is a runtime dependency, no code).
2. **Percentile histograms**, which are off by default:
   ```yaml
   management.metrics.distribution:
     percentiles-histogram.http.server.requests: true
     slo.http.server.requests: 200ms,500ms,1s,2s,5s
   ```
   ⚠ `percentiles:` (client-side) is **not** the same as `percentiles-histogram:` — only the histogram can
   be aggregated across instances. Getting this wrong yields a p99 that is wrong the moment there are two
   replicas, silently.
3. Something scraping it. The OTel collector is already configured for traces; Prometheus is the smaller
   addition.

### The promise, once it can be measured

SLOs belong on **journeys**, not endpoints — a shopkeeper does not experience `/getUserSell`, they
experience "ring up a sale". Proposed, grounded in what was measured today:

| Journey | p95 | p99 | Why this number |
|---|---|---|---|
| Ring up a sale (submit → receipt) | **1.5 s** | 3 s | the counter queue is real; anything slower is felt |
| Add / correct stock | **400 ms** | 800 ms | measured at 85 ms today (PERF-9); ample headroom |
| Open a screen (dashboard, product, customer) | **2 s** | 4 s | once per session, not per action |
| Search / picker | **300 ms** | 600 ms | typing latency; above this it feels broken |
| Report (sale detail, finance) | **5 s** | 10 s | deliberately generous — a report is not a keystroke |

**Availability:** 99.5% monthly for the sale path (≈3.6 h/month), measured as non-5xx.

⚠ Do not adopt these numbers as-is. Set them once the histograms are live and you can see the CURRENT
distribution — an SLO chosen above today's p99 promises nothing, and one below it is breached on day one.

---

## 3. Pillar 2 — isolated tenants

### The arithmetic, which is the whole finding

```
Tomcat threads per service (default) : 200
Hikari maximum-pool-size            :   8      ← DB_POOL_MAX
services sharing one MySQL          :  22      (22 × 8 = 176)
MySQL max_connections               : 200
```

**Eight.** That is the number of requests per service that can touch the database at once. The other 192
threads queue on the pool.

Before today's N+1 fix, `/getUserSell` held a connection for ~1.2 s. **Eight concurrent report loads from
ONE tenant would have blocked every other tenant on that service** for as long as they ran — no error, no
log line, just everyone waiting. That is the precise opposite of isolation, and it is a configuration
value, not an architectural limit.

### What else is missing

* **No per-tenant quota anywhere.** The gateway rate limiter is `enabled:false` by default, and its own
  comment says why it could not work if switched on: *"all gateway traffic originates from the single
  monolith client, so a bearer/IP-keyed bucket collapses to one shared counter."* It also keys on bearer/IP
  and stores counters **in-memory per gateway instance**.
* **One database, one schema set, one buffer pool.** `innodb_buffer_pool_size` is the 128 MB default against
  31.8 MB of data today — comfortable now, and a cliff the moment a customer's data passes ~100 MB, at
  which point every tenant starts reading from disk because of one tenant's volume.

### What to change

| | Change | Effect |
|---|---|---|
| 1 | **Raise `DB_POOL_MAX`** to ~20–30 per service and re-check against MySQL's 200 | 8 → 25 concurrent DB requests |
| 2 | **Cap the work, not just the pool** — server-side paging (items 2+6) so no single request holds a connection for a second | removes the thing that makes queueing visible |
| 3 | **Re-key the rate limiter to `X-Org-Id`** (post-JWT) and make it Redis-backed | a per-TENANT ceiling, which is what isolation means |
| 4 | **Raise `innodb_buffer_pool_size`** before data passes ~100 MB | keeps every tenant's working set in RAM |

**The measurable promise:** *no tenant can consume more than N% of a service's DB concurrency or M requests
per second.* Neither N nor M can be stated today because neither is enforced.

---

## 4. Pillar 3 — predictable overload

### What is genuinely good

* **Per-route circuit breakers**, one named instance per service, so a failing service cannot trip another's.
  Tuned: 50% failure rate over a 20-call window, 10 s open, 5 trial calls, 18 s slow-call threshold.
* **A time limiter at 20 s** with a real fallback response.
* **Timeouts on the monolith's outbound calls — as of today** (PERF-11). Before that a hung downstream held
  a monolith request thread forever; the class's own log line recorded the symptom without the cure.

### What is missing

* **No bulkheads.** All 200 Tomcat threads are one pool, so a single slow endpoint can consume every thread
  and take the whole service down with it — including the fast endpoints that were never the problem.
* **No load shedding.** There is no bounded queue and no "return 503 fast" path. Under overload the system
  degrades by getting slower for everyone rather than by refusing some work quickly.
* **Rate limiting off** (§3).

### The measurable promise, once bulkheads exist

> Beyond X concurrent requests, the service sheds load with 429/503 in under 50 ms rather than queueing —
> and p99 for everyone else stays inside its SLO.

That sentence is testable with a load generator. Today the honest version is *"beyond some unknown number of
concurrent requests, everything gets slower until something times out"* — which is not a promise.

---

## 5. Pillar 4 — automatic recovery and scaling

### Recovery: mostly there

* `restart: unless-stopped` on **23** services.
* **24** healthchecks, and they probe `/actuator/health/READINESS` rather than `/actuator/health` —
  deliberately, with the reasoning recorded: the aggregate endpoint includes the `db` indicator, and a DB
  blip made healthy services report DOWN, which with `depends_on: service_healthy` would stall the whole
  stack on a transient. **That is a well-made decision** and it is the difference between a self-healing
  stack and a restart loop.
* `mem_limit` on 22 services, so one leak cannot take the host.

### ⚠ Scaling: blocked, and not by capacity

```
replicas: in docker-compose        →  0 occurrences
spring-session                     →  not a dependency
TokenStore                         →  @Scope(SCOPE_SESSION)
SecSecurityConfig                  →  maximumSessions(1)
gateway rate limiter               →  in-memory ConcurrentHashMap
```

**The monolith cannot run a second instance.** Sessions live in its heap, and the session holds the JWT
every downstream call needs (`TokenStore` is session-scoped). A second instance behind a load balancer would
log users out on every other request without sticky sessions — and even with them, one restart drops every
user's token.

This is the single blocker between "one bigger box" and "more boxes", and it is worth naming plainly:
**the platform is currently vertically scalable only.**

### What to change, in order

1. **`spring-session-data-redis`** — Redis is already running for the gateway. Sessions leave the heap;
   the monolith becomes replicable; a restart stops logging everyone out. This is the unlock.
2. Then `replicas:` / an autoscaler, with the gateway rate limiter moved to its Redis-backed form in the
   same change (it is per-instance today, so N instances currently means N × the limit).
3. Scale on the metrics from Pillar 1 — not on CPU. CPU is a poor proxy for a service whose real limit is
   8 DB connections.

---

## 6. What I would do, in order

| | Work | Unlocks |
|---|---|---|
| **1** | Prometheus registry + percentile histograms + a scrape target | **every other promise on this page** |
| **2** | Baseline for a week; set SLOs from the observed distribution | numbers that mean something |
| **3** | `DB_POOL_MAX` up; server-side paging (items 2+6) | tenant isolation |
| **4** | Rate limiter re-keyed to org + Redis-backed | a per-tenant ceiling |
| **5** | `spring-session-data-redis` | horizontal scaling at all |
| **6** | Bulkheads + load shedding | predictable overload |
| **7** | Replicas + autoscaling on the Pillar-1 metrics | elasticity |

**Step 1 is not optional and nothing else should go first.** Steps 3–7 are all changes whose success or
failure can only be observed through it — doing them blind means finding out from a customer.

---

## 7. What I did NOT check

* **Whether any customer has actually breached these numbers.** The SLOs above are proposals from measured
  local latencies, not from production traffic — which does not exist in a form anyone can query yet.
* **The AWS/ECS side.** Memory records Terraform for ECS/Fargate and GitHub Actions; there is no `infra/` or
  `terraform/` directory in this tree, so autoscaling may already exist somewhere I cannot see. Worth
  confirming before building it twice.
* **Front-end latency.** Everything here is server-side. A p95 the user feels includes render time, and
  a 1.76 MB grid is as much a browser problem as a network one.
* **The other 21 services.** Business was audited end to end; the pool, thread and metric findings are
  platform-wide by construction (shared config), but per-service hot paths were not traced.
