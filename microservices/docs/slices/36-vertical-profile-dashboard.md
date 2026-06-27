# Slice 36 — Vertical-profile dashboard (one template, white-labelled per user type)

Foundation for the commerce verticals: **a single dashboard template (`businessDashboard.html`) serves POS,
Pharmacy and E-commerce**, re-skinned at runtime from the logged-in user's **type**. Built before more gap slices
(G5+) so every later component is vertical-aware, not POS-hardcoded. See [[project_vertical_aware_dashboard]] and
`commerce-verticals-blueprint.md`.

## Decision

- **One template only** — `businessDashboard.html`. No per-vertical template forks (would duplicate ~3.7k lines).
- **One route only** — `/businessDashboard` serves every commerce vertical. **No `/pharmaDashboard` or
  `/ecommerceDashboard`.** `CommerceDashboardController` sets `module` from the **logged-in user's type**, and login
  routes all commerce types there (`MySimpleUrlAuthenticationSuccessHandler`).
- The user's **type → `MODULE`** (`BUSINESS`=POS, `PHARMA`=Pharmacy, `ECOMMERCE`=Store). `MODULE` drives a
  **vertical profile**: `{ title, brand, theme, labels, features }`, applied client-side.
- **Labels** relabel a curated set of headings/nav/buttons (exact-match, never a blind scan).
- **Feature flags** show/hide vertical-specific nav items + sections via `data-feature` attributes (default: POS shows
  all of today's trade features; pharma/ecommerce-only features light up as their backends land — blueprint Phase 2/3).
- Default/fallback is **POS (BUSINESS)** so existing behaviour is unchanged.

## What already existed (reused / superseded)

| Piece | State |
|---|---|
| Route by type → `/{userType}Dashboard` | ✅ now: commerce types → single `/businessDashboard` |
| `window.MODULE = ${module} ?: 'BUSINESS'` in the template | ✅ |
| `module-theme.js` relabel-by-MODULE | ✅ extended into the full profile layer (labels+features+theme) |
| `PharmaDashboardController` / `EcommerceDashboardController` | ❌ removed — one route derives `module` from user type |

## Design

```mermaid
flowchart LR
    L[Login] --> T{user.type}
    T -->|BUSINESS| D[/businessDashboard\nmodule=BUSINESS/]
    T -->|PHARMA| P[/pharmaDashboard\nmodule=PHARMA/]
    T -->|ECOMMERCE| E[/ecommerceDashboard\nmodule=ECOMMERCE/]
    D --> V[businessDashboard.html\nwindow.MODULE]
    P --> V
    E --> V
    V --> M[vertical-profile.js\nVERTICALS[MODULE]]
    M --> R[relabel headings/nav/buttons]
    M --> F[show/hide [data-feature]]
    M --> B[title + brand + theme class]
```

### Vertical profile shape (`vertical-profile.js`, evolved from `module-theme.js`)

```js
VERTICALS = {
  BUSINESS:  { title, brand:'MyPlus POS',     themeClass:'v-pos',    labels:{...}, features:{} },        // baseline
  PHARMA:    { title, brand:'MyPlus Pharmacy',themeClass:'v-pharma', labels:{Item:'Medicine',Sale:'Dispense',Customer:'Patient',...}, features:{rx:true,batchExpiry:true} },
  ECOMMERCE: { title, brand:'MyPlus Store',   themeClass:'v-store',  labels:{Sale:'Order',Customer:'Buyer',...}, features:{orders:true,storefront:true} }
}
```

- **Labels:** exact-trim match against `.dash-page-title`, nav `<option>`s, `.snav-btn`, `.snav-item`, `[data-term]`.
- **Features:** elements tagged `data-feature="rx"` are hidden unless the active profile enables that feature.
  Elements tagged `data-vertical-only` list the verticals they belong to. Nothing is hidden for POS today.
- **Theme:** profile adds `themeClass` to `<body>` for per-vertical accenting (CSS only, optional now).

### Routing / types
- `CommerceDashboardController` (`/businessDashboard`) sets `module` from the logged-in user's type
  (`COMMERCE_MODULES = {BUSINESS, PHARMA, ECOMMERCE}`; else POS). Replaces the no-logic MvcConfig view-controller.
- `determineTargetUrl`: commerce types all route to `/businessDashboard` (`COMMERCE_TYPES`). Education/welfare/
  agriculture/appointment unchanged.
- auth-service already accepts any `userType` string (`AuthService` defaults BUSINESS, upper-cases) — `PHARMA` /
  `ECOMMERCE` work today; only signup/provision UI needs to offer them. Roles/privileges reuse the commerce set;
  vertical is a presentation concern, not a new authz model.

## Files

| Module | File | Change |
|---|---|---|
| monolith | `static/js/business/module-theme.js` | expanded into the profile layer: `VERTICALS` map (labels+features+theme), icon-safe text-node relabel, `applyFeatures` |
| monolith | `templates/businessDashboard.html` | `window.MODULE` comment; (future) `data-feature`/`data-vertical-only` tags + `[data-brand]` hook |
| monolith | `controller/BusinessDashboardController` (new) | `/businessDashboard` sets `module` from user type |
| monolith | `MvcConfig` | dropped the no-logic `/businessDashboard` view-controller |
| monolith | `controller/PharmaDashboardController`, `EcommerceDashboardController` | **removed** (one route handles all) |
| monolith | `MySimpleUrlAuthenticationSuccessHandler` | commerce types → `/businessDashboard`; trimmed `KNOWN_DASHBOARDS` |

## Tests
- **Cypress (headed)** — log in as BUSINESS → POS wording; as PHARMA → "Medicine/Dispense/Patient"; as ECOMMERCE →
  "Order/Buyer"; assert a `data-feature` element hides for the wrong vertical.
- Keep `module-theme.js` behaviour green (PHARMA relabels) during the rename.

## Out of scope (later, as backends land)
- Pharma Rx/dispense screens (pharma-service, Phase 2) — `features.rx` will reveal them.
- E-commerce storefront/orders (marketplace-service, Phase 3) — `features.orders`.
- Per-vertical theming CSS beyond the body class hook.

## Status
- [x] Decision (single template **and single route**, MODULE-driven profile from user type)
- [x] Design (this doc)
- [x] `module-theme.js` → profile layer (BUSINESS/PHARMA/ECOMMERCE labels + features + theme; icon-safe relabel)
- [x] `CommerceDashboardController` sets `module` from user type; MvcConfig view-controller dropped; pharma/ecommerce controllers removed
- [x] `determineTargetUrl` routes all commerce types to `/businessDashboard`
- [ ] (future) `data-feature`/`data-vertical-only` tags + `[data-brand]` element as vertical features land
- [ ] Cypress (login as each type → wording)
- [ ] Build + verify (user runs)

> auth-service needs no change — it already accepts any `userType`. To create PHARMA/ECOMMERCE users, the
> signup/provision UI just needs to offer those types (small follow-up).
