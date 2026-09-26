# UI-DASH-1 + PERF review — dashboard tiles, compression, caching (2026-09-26)

Requests: "review and fix alignment, layout of data-widget="companies", data-widget="vendors", kpi-card etc on
dashboard" · "review again and fix e2e 100% compression and caching as well". NOT committed; awaiting a monolith
rebuild for the green runs.

## 1. Compression + caching — review (measured, not read)
| Layer | Finding | Evidence |
|---|---|---|
| Compression (browser ↔ monolith) | ✅ complete. Every text response >1 KB arrived gzipped, JSON included | real dashboard load: 0 resources with encoded == decoded |
| Static assets | ✅ content-hash URLs + `max-age=31536000, public, immutable`; the second navigation transferred 0 bytes of static | perf-cache-versioning 13/13 |
| Unhashed URLs | ✅ templates: only vendored jsPDF example pages (no route serves them); runtime: lazy-export resolves hashes via `window.__ASSETS` | grep of templates + static/js |
| HTML / API JSON | ✅ `no-cache, no-store` (Spring Security) — correct for authenticated data | /login headers |
| Service hops | no compression, no proxy in repo — correct for internal hops | |
| Server-side caches | ✅ catalog cache-aside 7/7, refs cache 13/13 (run with `env -u SHELL`, as the spec says), settings cache 3/3 | |
| Existing gates | ✅ 54/54 (perf-compression, perf-cache-versioning, perf-jquery, perf-cdn-jquery, perf-lazy-export, catalog-cache-aside, settings-cache) | |

**The one real defect — what a dashboard open DOWNLOADS (not how):**
- `/getUserProduct` — the WHOLE catalogue, **1.4 MB of JSON** (81 KB gz), parsed into thousands of `<option>`s on
  EVERY dashboard open, and `/customerOptions` fetched **twice**. Both for the Sale Detail Report's filter rail,
  which `$(document).ready` mounted AND filled at page load (business.js:27) although the report is a separate
  screen. Root cause located with a request probe (stacks were all jQuery frames; narrowed by URL shape to
  report-filters.js).
- **Fix (Lazy Load + shared cache):** `report-filters.js` mounts the rail but fills Customer/Product only on
  `loadLists()` — called when the report screen opens (`#sellType` = SRDiv; dashboard drill-downs use the same
  select), when it runs (`loadSR`), or when the operator reaches into the rail (`focusin`/`mousedown`, capture phase
  on the RAIL: the selects are bootstrap-selects, so a listener on the `<select>` never hears the click).
  Lists come from `ProductPicker` / `CustomerPicker` (usually warm already — the till preloads them) instead of
  their own reads. Same product set: `/getUserProduct` defaults to `includeInactive=false`; the picker is ACTIVE-only.
- `dashboard-no-freeze.cy.js` case 5 asserted the rail was filled at page load — updated: now nothing is filled
  while hidden, and it fills when the report opens.
- Gate `ui/perf-dashboard-payload.cy.js` (4): no `/getUserProduct` on a dashboard open; `/customerOptions` ≤ 1;
  opening the report fills both filters without `/getUserProduct`; the product filter still sends its id (a form
  POST — the first draft of that case read the URL, wrongly). **RED 3/4 on the old build** (case 4 green = the guard).

Noted, not changed: `/catalogProductPicker` (2 × 2,000 rows, 309 KB decoded) re-downloads on every navigation — it is
server-cached (CACHE-1) and preloaded for the till by design; HTTP revalidation (ETag) would be its own slice.

## 2. Dashboard KPI tiles — review (measured at 1366 / 1024 / 390, demo + owner tenants)
- 8 tiles in `col-sm-2` = a ragged **6 + 2** at every width above phone.
- **98 px** tiles on a 1024 px tablet: numbers cut to the first digit, labels to two letters.
- Heights **93-160 px within one row** (the tile grows with its label; "STOCK VALUE (AT LAST PURCHASE RATE)" in spaced
  capitals ran to 4 lines).
- Figures clipped: "3704" shown as "370"; ungrouped raw values ("165710") even for money.
- **"On Terms" stuck on "-"**: ONB-2 made `installmentsDue` absent whenever there are no open plans; the client
  (whose comment still described the older rule) only wrote a present key, so the loading placeholder stayed.

**Fix:** `.kpi-grid` — 4 equal columns (2 on a phone), equal-height rows (`height:100%`), a value that cannot clip
(`nowrap` + ellipsis + `min-width:0`), sentence-case labels clamped to 2 lines; figures grouped via the Sale Report's
`srNum`/`srMoney` (reused), money whole on the tile with the exact amount in the tooltip; "On Terms" absent = 0.
The tiles keep `data-widget`/`data-drill`; dashboard-widgets.js reorders within the parent, so the capability tile
still leads. Gate `business/dashboard-kpi-layout.cy.js` (6) — **RED 5/6 on the old build** for the reasons above.

## 3. Also carried in this build (from UI-FORM-1)
- Product form: the "Already registered" panel unfolds only for what the operator TYPED (an Edit fills the name
  programmatically; unfolding then showed an empty 230 px box that scrolled the title away — form-layout R1).

## 4. Runs after the rebuild (2026-09-26)
Green: perf-dashboard-payload 4/4, form-layout 12/12, report-filters 6/6, report-grouping 6/6, busy-controls 21/21.
Read the reds:
- dashboard-kpi-layout tablet: "165,710" needed 83px, the tile left 72px beside a 52px icon — **real, mine**; icon and
  padding shrink below 1200px.
- dashboard-no-freeze case 5: `<select>` held 85 customers, the WIDGET one row — **real, mine**: the lazy fill reads
  through the shared caches (global:false, or no read at all when warm), so the ajaxComplete redraw never fired —
  the exact trap recorded for TIER-1b. Fixed: fillRows calls `refreshSearchableSelect()`.
- sale-report-order 2: fixture guard ("spanning more than one month") on a direct /loadSR request — tenant data, not UI.
- product-existing-panel "broken list": the stale spec (waits on /getUserProduct; PS-1 moved the panel), as before.
Awaiting the next rebuild.
**After the next rebuild: GREEN** — dashboard-kpi-layout 6/6, dashboard-no-freeze 5/5, perf-dashboard-payload 4/4,
report-filters 6/6, report-grouping 6/6. NOT committed.
