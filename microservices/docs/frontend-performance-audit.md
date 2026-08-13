# Front-end performance audit — slow-connection delivery

**Date:** 2026-08-13
**Scope:** monolith UI delivery (Thymeleaf templates + `src/main/resources/static`), measured against `businessDashboard.html`
**Status:** REVIEW — no code changed. Awaiting consent per the review→consent→design→implement→test rule.

---

## 1. Headline

A single business-dashboard page load transfers **~4.3 MB across ~80 requests, uncompressed, and re-transfers most of it on every navigation.**

| | Measured |
|---|---|
| JS payload | **4,148,826 B (4.05 MB)** across **52 files** |
| HTML payload | **189,640 B (190 KB)** — uncompressed |
| CSS files | 20 `<link>` tags |
| Compression | **none, anywhere in the stack** |
| Static caching | **effectively none** (see F1) |

On a 1 Mbit/s effective link that is roughly **35 seconds of pure transfer** before the first AJAX call fires. Three of the findings below are configuration-level and would cut that by ~85% without touching application logic.

The causes are independent and stack multiplicatively: bytes are never compressed (F2), they are never cached (F1), and there are far more of them than the page needs (F3–F5).

---

## 2. Findings, ranked by impact

### F1 — `@EnableWebMvc` silently voids the static-resource caching config ⚠️ ROOT CAUSE

`src/main/java/com/spring/MvcConfig.java:29` carries `@EnableWebMvc`.

That annotation **disables `WebMvcAutoConfiguration` wholesale.** Two consequences, both invisible in config review:

1. `spring.web.resources.cache.period=3600` in `application-prod.properties:10` **never takes effect.** The property is read by the auto-configuration that `@EnableWebMvc` just turned off. The prod profile looks like it enables caching; it does not.
2. The hand-rolled handler that replaces it —

```java
// MvcConfig.java:116
@Override
public void addResourceHandlers(ResourceHandlerRegistry registry) {
    if (!registry.hasMappingForPattern("/**")) {
        registry.addResourceHandler("/**")
                .addResourceLocations(CLASSPATH_RESOURCE_LOCATIONS);
    }
}
```

— registers `/**` with **no `.setCachePeriod()` and no `.resourceChain()`**. Resources go out with no `Cache-Control`, so the browser falls back to conditional revalidation.

**Effect on a slow link:** the byte cost of a repeat page load may drop, but the browser still issues **~80 conditional GETs, serialised over ~6 connections**. At 300 ms RTT that is several seconds of dead time on *every* navigation, and this app navigates by full page load. This is the finding most likely to match "slow when the internet is slow" — high latency hurts here even more than low bandwidth.

This one finding also explains why the prod-hardening work (P3) did not produce the expected improvement: the setting it added was inert.

### F2 — No HTTP compression anywhere in the stack

`server.compression.enabled` appears in **zero** files across the monolith, all 12 microservices, the api-gateway, and the deployment configs. There is no nginx config in the repo either, so nothing upstream compensates.

Measured gzip ratios on the actual assets:

| Asset | Raw | gzip -9 | Saved |
|---|---|---|---|
| `businessDashboard.html` | 189,640 | 40,760 | **78%** |
| `business.js` | 212,974 | 61,571 | **71%** |
| `jquery-3.3.1.js` | 282,115 | 80,902 | **71%** |
| `chart.umd.min.js` | 205,242 | 69,392 | **66%** |
| `pdfmake.min.js` | 1,093,430 | 452,589 | **58%** |

Text assets compress 58–78%. **~4.2 MB → ~1.3 MB from one config block.** This is the single highest-value change in the audit relative to its risk.

Note: compression is a servlet-container concern (`ServletWebServerFactoryAutoConfiguration`), which `@EnableWebMvc` does *not* disable — so `server.compression.*` will work even before F1 is fixed.

### F3 — 2.4 MB of PDF/export libraries load eagerly on every page

Loaded unconditionally in `templates/fragments/header.html` and `businessDashboard.html:2795`:

| Library | Size | Purpose |
|---|---|---|
| `jQExp/pdfmake.min.js` | 1,093,430 | DataTables PDF export button |
| `jQExp/vfs_fonts.js` | 926,233 | pdfmake's embedded font blob |
| `js/jspdf.min.js` | 234,558 | receipt/invoice printing |
| `js/jspdf.plugin.autotable.js` | 69,794 | ditto |
| `jQExp/jszip.min.js` | 101,953 | Excel export button |
| **Total** | **2,425,968 (2.31 MB)** | |

**This is 58% of the page's JS**, and it exists to service export buttons and the print path — actions the large majority of sessions never invoke. Every cashier ringing up a sale pays 2.3 MB for a PDF button they don't press.

These are the textbook candidates for dynamic `import()` / on-demand injection: load on first click of an export/print button, not at page load.

### F4 — Two jQuery versions load, and the larger one is a non-minified dev build

`templates/fragments/header.html:57-58`:

```html
<script th:src="@{/js/jquery.min.js}"></script>      <!-- jQuery 1.11.2  —  95,931 B -->
<script th:src="@{/js/jquery-3.3.1.js}"></script>    <!-- jQuery 3.3.1  — 282,115 B -->
```

Two distinct problems:

- **jQuery 1.11.2 is loaded and then immediately overwritten** by 3.3.1. It is 96 KB of pure waste — unless something between the two tags depends on 1.x, which nothing does here (they are adjacent lines).
- **The 3.3.1 tag points at the unminified development build.** `js/jquery-3.3.1.min.js` (86,929 B) sits in the same directory, unused. That is 195 KB of comments and whitespace shipped to production.

Fixing both: **378 KB → 87 KB**, one-line change, no behavioural difference.

### F5 — Render-blocking third-party font request

`templates/fragments/header.html:8-9` fetches Inter (7 weights) from `fonts.googleapis.com`. On a slow or congested link this costs a **DNS lookup + TLS handshake + CSS fetch + font fetches to a second origin**, all before text paints. `preconnect` is present, which helps the handshake but not the round-trip count.

For a POS/business app — often on constrained or captive networks, sometimes behind filtering that blocks Google origins entirely — self-hosting the two or three weights actually used is both faster and more reliable. Seven weights are declared; the app almost certainly uses three.

### F6 — 20 CSS `<link>` tags, unbundled

`templates/fragments/header.html` emits 20 stylesheets. Individually small, but each is a request, and under HTTP/1.1 the browser parallelises only ~6 per origin. Combined with the 52 JS files this is where the ~80-request figure comes from. Bundling matters far less than F1–F3 but is the natural companion to fixing them.

### F7 — Full-catalog fetches on the hot path

`business.js:502` and `business.js:1883` both issue:

```js
$.get(serverContext + "catalogProducts?size=2000", ...)
```

The entire product catalogue (up to 2000 rows) is pulled into the browser to populate a `<select>`, on every sell/purchase screen open **and again on every line edit** (line 502 is inside the edit-line path, deliberately re-fetching to dodge a population race). For a tenant with a real catalogue this is a multi-hundred-KB JSON on a hot, repeated path.

The barcode-lookup endpoint (`lookupProduct`, `business.js:322`) already demonstrates the better pattern. A server-side typeahead against `searchable-selects.js` would remove the bulk fetch entirely. This is application work, not config — larger slice, lower urgency than F1–F4.

### F8 — Dead weight in the served directory

`static/` is 25 MB. Two items stand out:

- `static/b.jpg` — **3.4 MB**, referenced by no template or stylesheet (grepped).
- `static/jsPDF-1.3.2/` — **5.7 MB** of vendored library *examples* (`examples/js/editor.js`, bundled jQuery 1.7.1, sample PNGs). The app loads `js/jspdf.min.js`, not this tree.

Also `js/business/` duplicates `jQExp/` wholesale (`pdfmake.min.js`, `vfs_fonts.js`, `jquery-3.3.1.js`, `jquery.dataTables.min.js` are each stored twice, ~2.2 MB duplicated).

None of this is *served* on the dashboard path, so it does not slow users down — but it inflates the jar and the Docker image, which slows every deploy. Cleanup is safe and cheap.

---

## 3. Projected effect

Business dashboard, first load, 1 Mbit/s link:

| Stage | Transfer | Est. time |
|---|---|---|
| Today | ~4.3 MB | ~35 s |
| + F2 compression | ~1.35 MB | ~11 s |
| + F4 jQuery fix | ~1.26 MB | ~10 s |
| + F3 lazy PDF libs | **~0.5 MB** | **~4 s** |
| + F1 caching (repeat loads) | **~0 MB, ~0 requests** | **near-instant** |

**F1 + F2 + F4 are configuration and one-line template edits.** They are ~90% of the benefit and carry close to zero behavioural risk. F3 is a contained JS change. F5–F8 are follow-ups.

---

## 4. Proposed slice plan

Each slice is independently shippable and gated by a headed Cypress run, per the standing cadence.

| Slice | Change | Risk | Files |
|---|---|---|---|
| **PERF-1** | Enable `server.compression` (monolith) — **IMPLEMENTED, awaiting headed gate** | Very low | `application.properties` |
| **PERF-2** | Fix F1: set an explicit cache period + `resourceChain` with `VersionResourceResolver` on the `/**` handler, so assets can be cached **immutably** and still bust on deploy. Re-evaluate whether `@EnableWebMvc` is needed at all | Low–medium | `MvcConfig.java`, `application*.properties` |
| **PERF-3** | Drop jQuery 1.11.2; point at `jquery-3.3.1.min.js` | Low | `fragments/header.html` |
| **PERF-4** | Lazy-load pdfmake / vfs_fonts / jszip / jspdf on first export or print click | Medium | `fragments/header.html`, `business.js`, `receipt.js` |
| **PERF-5** | Self-host the 3 Inter weights actually used; drop the Google Fonts hop | Low | `fragments/header.html`, `static/fonts/` |
| **PERF-6** | Delete `b.jpg`, `jsPDF-1.3.2/`, and the `js/business/` ↔ `jQExp/` duplicates | Low | `static/` |
| **PERF-7** | Replace `catalogProducts?size=2000` with a server-side typeahead | Medium–high | `business.js`, `searchable-selects.js`, catalog-service |

### A caution on PERF-2

`@EnableWebMvc` has been on `MvcConfig` since early in the project's life, and removing it re-enables a large amount of Boot auto-configuration at once — message converters, content negotiation, static-path defaults. It is the *correct* long-term fix, but it is not a one-liner and should not ride along with a caching change. **PERF-2 should first add explicit `.setCachePeriod()` + `.resourceChain()` to the existing hand-rolled handler**, which fixes the caching regardless of the annotation. Removing `@EnableWebMvc` is a separate, later decision with its own gate.

### A caution on PERF-2 versioning

Turning on long-lived caching **without** content-hash versioning would mean a deploy cannot reach browsers that already cached the old asset. `VersionResourceResolver` (content-hash URLs) must land in the *same* slice as the long cache period — never after it.

---

## 4b. PERF-1 — implemented, awaiting gate

**Changed:** `src/main/resources/application.properties` only.

```properties
server.compression.enabled=true
server.compression.min-response-size=1024
server.compression.mime-types=text/html,text/plain,text/xml,text/css,text/javascript,application/javascript,application/json,application/xml,image/svg+xml
```

The same file's `spring.web.resources.cache.*` block also gained a comment recording that those two
properties are inert under `@EnableWebMvc` (F1), so the next reader does not try to fix prod caching there.

**Scope narrowed from the original plan, deliberately.** PERF-1 was written as "monolith + api-gateway".
Checking before implementing showed **the browser never calls the gateway directly** — there is no
reference to `:8765` in any application JS or template; all AJAX targets `serverContext` (the monolith),
which proxies onward server-side via `GatewayClient`. Gateway compression would therefore only compress a
**server-to-server** hop (localhost in dev, same VPC in prod), costing CPU on both ends for no benefit to
a user on a slow link. **Not done, and not needed unless the monolith and gateway are deployed to
separate hosts across a constrained network** — say so and it is a two-line follow-up.

**Why this is safe to enable:**

- No SSE/streaming endpoints exist (`SseEmitter`, `StreamingResponseBody`, `text/event-stream` return
  nothing across `src/main/java`) — those are the responses gzip buffering would break.
- `text/csv` is excluded, so the report exports in `SellController:104` and
  `FinanceReportController:78` stream unchanged.
- Compression is applied by `ServletWebServerFactoryAutoConfiguration` (a servlet-container concern),
  which `@EnableWebMvc` does **not** disable — so unlike F1's caching properties, this one is live.

**Measured live after rebuild+restart** (`curl`, ten largest assets, raw vs `Accept-Encoding: gzip`):

| Asset | Raw | gzip | Saved |
|---|---|---|---|
| `pdfmake.min.js` | 1,093,430 | 452,255 | 58% |
| `vfs_fonts.js` | 926,233 | 451,096 | 51% |
| `jquery-3.3.1.js` | 282,115 | 81,620 | 71% |
| `jspdf.min.js` | 234,558 | 73,445 | 68% |
| `business.js` | 212,974 | 61,902 | 70% |
| `chart.umd.min.js` | 205,242 | 69,659 | 66% |
| `moment.js` | 138,945 | 29,737 | 78% |
| `bootstrap-datetimepicker.js` | 106,518 | 16,067 | 84% |
| `jquery.dataTables.min.js` | 82,577 | 28,195 | 65% |
| `main.js` | 62,845 | 21,055 | 66% |
| **Total** | **3,345,437** | **1,285,031** | **61%** |

Responses carry `Content-Encoding: gzip` with `Content-Length` absent (chunked) — the predicted signature.

One correction to the pre-implementation reasoning: Tomcat labels `business.js` as **`text/javascript`**, not
`application/javascript`, so it is the `text/javascript` entry that actually carries JS here.
`application/javascript` remains listed (other containers and hand-set Content-Types use it), but the
audit's claim that Boot's default list omitting `application/javascript` was the live risk did not hold —
`text/javascript` is in Boot's default list. **The measured win is real either way; the stated reason was
half wrong.** Listing both is still correct.

**Gate:** `cypress/e2e/ui/perf-compression.cy.js` — 6 cases. Asserts the *property* (bytes arrive
gzip-encoded, arrive intact, and binaries are not re-compressed), never the artefact. Every case fails on
the pre-PERF-1 build.

**Ground truth, independent of Cypress** (Cypress decompresses transparently, so the body proves nothing
and only headers discriminate):

```bash
curl -sI -H "Accept-Encoding: gzip" http://localhost:8080/js/business/business.js | grep -i "content-encoding\|content-length"
# expect: content-encoding: gzip
```

---

## 5. Open question for the user

The measurements above are from the **monolith's own static delivery**. If production fronts this with nginx, Cloudflare, or an ALB that already applies gzip/brotli and cache headers, F1 and F2 may be partly mitigated in prod even though they are absent from the repo. No such config exists in the repository.

**Before implementing PERF-1/PERF-2, confirm what sits in front of the app in production** — that determines whether compression belongs in Spring, in the proxy, or both.
