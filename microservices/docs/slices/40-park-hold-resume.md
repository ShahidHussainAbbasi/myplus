# Slice 40 — Park / Hold & Resume a sale (POS R10)

Phase 1 shared core (see `commerce-verticals-blueprint.md`): … day-close✅ → **park/hold (this slice)** → then the
pharmacy vertical. Standard POS need: a cashier can **park** the current cart (serve someone else / wait for the
customer to fetch an item), then **resume** it later — without finalising stock or an invoice.

## Scope
- **Park** the in-progress cart (customer + lines + chosen tender), labelled, to resume later. No stock move, no
  invoice, no shift impact until it's actually completed.
- **Resume** a parked sale → rebuild the cart, then complete as a normal sale (or park again).
- **Discard** a parked sale.
- **Void line / void in-progress cart** already exist (cart `UIT()` removes a line; Delete Cart resets). Voiding a
  *completed* sale = the **Return** path (G2) — not duplicated here.

## Model (business-service, org+cashier scoped)

| Entity | Fields |
|---|---|
| **`ParkedSale`** | `id`, `organizationId`, `userId`, `label`, `itemCount`, `total` (DECIMAL 19,2), `cartJson` (TEXT — the serialized checkout payload), `parkedAt` |

A parked sale is just a stored copy of the same `customerHistory` payload the cart would POST to `addSell`. On
resume it's handed back verbatim; on completion the client deletes it. Additive table (`ddl-auto: update`).

## API (business-service + monolith proxy)
- `POST /parkSale` `{label, itemCount, total, cart:<customerHistory>}` → saves, returns the parked id
- `GET /parkedSales` → list summaries `[{id, label, itemCount, total, parkedAt}]` (org+cashier scoped)
- `GET /resumeParked?id=` → the stored `cart` (anti-IDOR scoped)
- `POST /deleteParked?id=` → discard

## UI (single dashboard, vertical-aware)
- **Park** button on the sell checkout (beside *Complete Sale*) → posts the current cart + a label (customer name or
  prompt), then clears the cart.
- **Parked Sales** panel (Sale menu): list of held carts → **Resume** (rebuild cart) / **Discard**.
- Labels via the vertical profile (POS "Park", pharmacy could read "Hold Rx" later).

## Tests
- **Cypress (headed, slow-mo):** park a cart (API) → it appears in `/parkedSales` → resume returns the same cart →
  discard removes it; anti-IDOR (another tenant's id → NOT_FOUND). UI: Park button + Parked panel render.

## Status
- [x] Design (this doc)
- [x] `ParkedSale` + `ParkedSaleRepo` + `ParkedSaleService` (park/list/resume/discard, org+cashier scoped) + DTOs + `ParkedSaleController` + monolith proxy
- [x] UI: **Park** button at checkout + **Parked Sales** panel (`park.js`) — resume rebuilds the cart + removes the held record
- [x] **Cypress green** (headed Chrome, slow-mo): park-hold.cy.js 3/3 (2026-06-23); day-close 3/3 + commerce-gaps 8/8 + vertical-profile 2/2 no regression. Fixed a real bug — list payload reads `GenericResponse.collection` (not `.object`).
  Note: `park.js` `.collection` fix needs the monolith static rebuilt to take effect in the live UI (the API tests pass already).
