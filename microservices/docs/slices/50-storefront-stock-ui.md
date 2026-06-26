# Slice 50 — Back-office stock UI for catalog products

Slice 49 made storefront orders **reserve inventory**, but an operator could only stock a product via the
`/addProductStock` API (added for the test). This slice closes the operator loop with UI: from the shared **Product
(Catalog Master)** screen on `businessDashboard`, an operator can see each product's **on-hand** and **add stock** —
which the storefront then sells and the saga draws down. One screen, all verticals (POS/pharmacy/e-commerce), since
the catalog Product master is shared.

## Reuse
- The existing **Product (Catalog Master)** screen (`#ProductDiv`, `catalog-products.js`) — extended, not replaced.
- The slice-49 monolith proxies: `POST /addProductStock {productId,quantity}` and `GET /productStock?productId=`
  (→ inventory `/stock/import` + `/stock/level`). No new backend.

## UI (catalog-products.js + businessDashboard.html)
- Product table gains **On hand** + **Add stock** columns.
- On load, each row lazy-fetches its on-hand (`/productStock`).
- Per-row qty input + **Add** button → `/addProductStock` → re-reads that row's on-hand (optimistic refresh).
- White-labelled by `module-theme.js` like the rest of the screen (no per-vertical markup).

## Tests
- Cypress `catalog-product-stock.cy.js` (headed): create a product → its on-hand shows 0 → add 15 via the UI →
  on-hand shows 15. (Request-level `/addProductStock`+`/productStock` already covered by `storefront-saga`.)

## Status
- [x] Design (this doc)
- [x] catalog-products.js on-hand (lazy `refreshStock`) + `addProductStock` + businessDashboard table columns
- [x] Cypress `catalog-product-stock.cy.js` authored (UI add → on-hand 15 + persisted)
- [x] **Cypress green (headed, 2026-06-26): 2/2 — UI add → on-hand 15 + persisted to inventory.**

## Deferred
- Stock adjustments (decrement/spoilage), batch/expiry entry, per-warehouse stock — this slice is opening-stock add
  + on-hand readout only (the storefront/POS path needs nothing more yet).
