# Making it *feel* instant — search, saving, and staying ahead of the user

**Status:** R&D + gap analysis, for review. No code changed by this document.
**Asked:** three specific questions about search caching, save latency, and proactive data loading.

Measured on the running stack, 10 September 2026. Where something already exists, it is described as built —
several of these are further along than expected.

---

## 0. The distinction that organises all three answers

The work done so far attacked **throughput** — `/getUserSell` went from 1.34 s to 0.19 s. Your three
questions are about something different: **perceived latency**. They are not the same problem and they do not
have the same fixes.

> A 200 ms action that blocks the screen feels slower than a 600 ms action that does not.

Every fast product you can name is built on that sentence. None of them is fast because their servers are
fast; they are fast because **the user is never made to wait for something the interface could have shown
already.**

---

## 1. Search — what exists, and the gap

### What is built ✅

**Client-side search on the grids.** Every grid loads its rows and DataTables filters them in the browser.
Once loaded, typing is instant — genuinely zero latency, better than any server round trip.

**A proper server-side search endpoint already exists and is proxied:**

```
GET /products/search?q=&category=&uncategorised=&includeInactive=&minPrice=&maxPrice=&page=&size=
   → catalog-service ProductController.search(...)  — paged, indexed, tenant-scoped
```

**Two in-memory client caches**, `product-picker.js` (PERF-8) and `customer-picker.js`, each with a single
`ajaxComplete` invalidation hook so no write path can forget to clear them.

### ⚠ The gap

**The grid does not use the search endpoint.** It loads up to 1,000 rows through `getUserProduct` and
filters locally, which means:

| | |
|---|---|
| Search only sees what was loaded | a tenant past 1,000 products **cannot find the rest** |
| The full list must arrive first | 384 KB before the first keystroke does anything |
| `sort=id,desc` was added as a mitigation | the newest 1,000 — the oldest silently unreachable |

That last point is already documented in the code: *"once a tenant passes 1000 products their most recent
ones stop appearing … A shopkeeper would add a product and not find it."* The cap was moved to the least
harmful end; it was not removed.

### What the fast products do

**Server-side type-ahead with a debounce, and a cache keyed on the query.** Concretely:

1. **Debounce 150–250 ms.** Do not issue a request per keystroke; issue one per pause.
2. **Cancel in flight.** A newer keystroke aborts the older request — otherwise results arrive out of order
   and the list flickers backwards. `AbortController` is the standard tool.
3. **Cache by query string, per tenant.** `q=pan` is asked for repeatedly in a session; the answer has not
   changed. An LRU of ~50 queries in memory costs nothing.
4. **Stale-while-revalidate.** Show the cached answer instantly, refresh behind it, update if it differs.
   This is what makes Linear and Notion feel like local software — the list is never empty while thinking.
5. **Server-side pagination + virtualised rendering** so 50,000 products cost the same as 50.

### Recommendation

Use the endpoint that already exists. The work is on the client, not the server:

- `q` → debounced call to `/products/search`, `AbortController` on the previous one
- an LRU keyed `org + q + filters`, stale-while-revalidate
- keep client-side filtering as the *instant* layer for what is already loaded, and fall through to the
  server when the query has no local match — the user gets instant results for the common case and correct
  results always

⚠ **What NOT to do:** cache search results on the *server*. Results are tenant-scoped and change on every
write; the invalidation cost exceeds the benefit, and a leak across tenants is a data breach rather than a
slow page. Cache on the client, where the tenant boundary is the session.

---

## 2. Saving — measured, and yes, optimistic UI is the answer

### Measured today

| | |
|---|---|
| `addCustomer` (warm) | **0.08 – 0.17 s** |
| grid reload it triggers | `getUserCustomer` — **543 KB** |
| blocking overlay appears after | **220 ms** of any jQuery ajax |

The save is fast. **What the user waits for is the reload, and the overlay covering the screen while it
happens.** 22 calls opt out of the overlay with `global: false`; writes do not.

### ⚠ An outlier worth naming honestly

One `addCustomer` measured **7.02 seconds**. I could not reproduce it — eight subsequent attempts, a
60-second idle, and two fresh logins all returned 0.08–0.17 s.

**That single unreproducible outlier is the strongest argument in this document for instrumentation.** A user
hit something; I cannot reproduce it; nothing recorded it. That is precisely what a p99 histogram exists to
catch, and it is why the SLO work is step one. Every "it was slow yesterday" report is currently
unanswerable.

### The pattern you described *is* the industry pattern

What you proposed — update the client immediately, send the request in the background — is **optimistic UI**,
and it is what Linear, Superhuman, Figma and Gmail all do. Your instinct is right. Three things make it safe:

**1. Apply locally, reconcile on the response.**
```
  user edits → paint the change immediately → POST in background
             → on success: replace the optimistic row with the SERVER's row
             → on failure: roll the row back, show what happened, keep their input
```
The reconcile step is not optional. The server derives fields the client does not have — on a customer save
that is `id`, `dated`, `updated`, `userId`, the defaulted `customerType`, and the **preserved `dueAmount`**.
Painting the form's values and stopping would show a customer owing nothing when they owe money.

**2. Idempotency keys**, so a retry cannot double-write. Already present in five services
(`business`, `inventory`, `marketplace`, `notification`, `commerce-contracts`) — the mechanism exists and
needs extending to the CRUD writes.

**3. Never optimistic about money.** A stock adjustment, a payment, a GL posting — show these as pending and
wait. A customer's phone number can be optimistic; a receivable cannot.

### The three changes, in order of value

| | Change | Effect |
|---|---|---|
| **1** | **Stop reloading the whole grid after a save.** Return the saved row; patch that one row. | removes 543 KB and a full re-render per save |
| **2** | **Take writes off the blocking overlay.** Show a small inline "saving…" instead. | the user can keep working immediately |
| **3** | **Optimistic apply + reconcile** for non-financial edits. | the change appears instantly |

⚠ Change 1 is a prerequisite for 3. Patching a row requires the write to return the row, and today
`addCustomer` returns `GenericResponse("SUCCESS", "Customer saved successfully.")` — the saved object is
discarded. This is the same shape as **PERF-9**, already fixed for stock: *the write answers with what it
wrote.*

Also blocking: each grid's row builder is a ~200-line `if/else` chain **inside** the ajax success handler.
Patching one row needs it extracted into `buildRow(entity, obj)` first — mechanical, but it touches every
entity.

---

## 3. Staying ahead of the user — more built than expected

### What exists ✅

**`picker-prefetch.js` already does this**, and does it well. It fills the till's pickers during idle time
after the dashboard paints, so New Sale opens from memory instead of two cold round trips.

Its own limits are the part worth keeping:

- **one page only** — a bounded speculative budget, using `PAGE_SIZE` rather than an invented number
- **skipped on `saveData`** (and a hidden tab) — prefetch spends someone else's data allowance. _It was also skipped on
  2g/slow-2g until 2026-09-15; the user removed that rule after Chrome's estimate flipped slow-2g ↔ 4g within seconds
  on a busy till PC and `picker-prefetch.cy.js` failed on it — a slow link is where the preload helps most._
- **never blocks, never retries, never reports an error** — if it fails, the screen fetches as it always did

That is a correct, disciplined implementation of exactly the technique you are asking about.

**Also built:** the two picker caches; `SettingsService`'s per-org settings cache (17 queries → 1); and the
grid's own load already runs non-blocking (`global: false`) so it does not hold the overlay.

### ⚠ The gaps

| Gap | What it costs |
|---|---|
| Prefetch covers **pickers only** | opening Customers, Products or a report is still cold |
| No **intent-based** prefetch | hovering a menu entry, or focusing the search box, predicts the next screen and is not used |
| No **stale-while-revalidate** anywhere | a cached list is either fresh or refetched; never "show now, refresh behind" |
| Caches are **per page load** | a navigation throws away everything; nothing survives in `sessionStorage` |

### What the fast products do

1. **Prefetch on intent, not on load.** `mouseenter` on a nav item, or focus on a search box, is a ~300 ms
   head start on a click that has not happened yet. Cheap, and it is the single biggest perceived win
   available here.
2. **Stale-while-revalidate everywhere.** Never show a spinner for data you have; show it, then refresh.
   React Query / SWR made this the default expectation.
3. **Session-scoped cache**, so moving between screens does not re-fetch reference data that has not changed.
4. **Skeletons, not spinners.** A spinner says "wait"; a skeleton says "this is arriving" and measures as
   faster for the same real latency.

### Recommendation

Extend `picker-prefetch.js` rather than build something new — the discipline in it (bounded, idle, respects
Save-Data, never blocks) is the hard part and it is already done. Add:

- **intent prefetch** on section nav hover/focus
- **stale-while-revalidate** on the picker caches
- **`sessionStorage`** for reference data (tax codes, units, categories) so it survives navigation

---

## 4. Answering the underlying question

> *If everything is according to standards and best practice, why are we still slow?*

Because these three questions are about a **different discipline** from the one the codebase has been
measured against. The standards followed here — tenant scoping, indexing, transaction boundaries, the outbox
— are all about **correctness under load**. They are genuinely well done; I checked each.

Perceived latency is about **what the user is made to wait for**, and it has its own rules:

1. **Never fetch what you already have.** (The grid reload after every save.)
2. **Never make the user wait for work that could have started earlier.** (Prefetch — partly built.)
3. **Never block the screen for a write that will almost certainly succeed.** (Optimistic UI — not built.)
4. **Never show an empty state for data you have a stale copy of.** (SWR — not built.)

None of those is a scaling problem, and none is fixed by a faster server. That is why the throughput work
made `/getUserSell` seven times faster and a user editing a customer would still feel a pause: the pause was
never the query.

---

## 5. Order of work

| | Change | Where | Effect |
|---|---|---|---|
| **1** | Writes return the saved row | server, per entity | unblocks everything below |
| **2** | Patch the row instead of reloading the grid | client | −543 KB and a re-render per save |
| **3** | Writes off the blocking overlay → inline "saving…" | client, one file | the user keeps working |
| **4** | Intent prefetch on nav hover/focus | extend `picker-prefetch.js` | biggest perceived win per line |
| **5** | Debounced server search + `AbortController` + LRU | client | search stops being capped at 1,000 |
| **6** | Stale-while-revalidate on the picker caches | client | no spinner for data we hold |
| **7** | Optimistic apply + reconcile, non-financial edits only | client | edits appear instantly |

**1–3 are one slice** and worth doing together — they are the same defect seen from three angles.

⚠ And **instrumentation still comes first.** The 7-second outlier in §2 is the argument: without a p99
histogram, none of the above can be shown to have worked, and the next unreproducible report will be just as
unanswerable as this one.

---

## 6. What was NOT checked

* **Real browser timings.** Everything here is server-side plus code reading. The overlay's felt cost and
  DataTables' render time for 807 rows need a browser profile.
* **Whether the 7-second outlier recurs.** One observation, unreproducible in ten attempts. It is reported as
  an anomaly, not as a finding.
* **Education, welfare, agriculture.** The grid, save and prefetch patterns are shared, so the findings
  should carry — but only business was traced.
* **Whether any tenant has actually passed 1,000 products** and lost search. The cap is real; who is over it
  was not measured.
