# Slice 56 — E-commerce: public order tracking

A guest places an order but has no way to check it again. This adds **order tracking**: checkout returns a visible
**order reference**, and a public page lets the customer look up the order's fulfilment status by **ref + contact**
(light verification, no account needed). Reuses the existing `Order.fulfilmentStatus` lifecycle and the public
storefront proxy pattern.

## Changes
- **marketplace-service**
  - `OrderTrackDTO {ref, customerName, status, placedAt, total}` — a minimal public projection (no address /
    paymentRef / reservationId leak).
  - `OrderService.trackPublic(ref, contact)` — find by id, return only if `customerContact` matches (case-insensitive,
    non-blank); else `ResourceNotFoundException` (don't reveal existence).
  - `PublicOrderController GET /public/order/track?ref=&contact=` (anonymous; gateway already allow-lists
    `/api/marketplace/public/`).
- **monolith** `StorefrontController GET /storefront/track?ref=&contact=` → proxies the public track endpoint.
- **store.html** — checkout success shows "Order #<ref>"; a **Track your order** panel (ref + contact → status).

## Tests
- Cypress `storefront-track.cy.js` (headed): place an order → checkout returns a ref → track by ref+contact shows
  status `NEW`; a wrong contact returns not-found (no leak).

## Status
- [x] Design (this doc)
- [x] marketplace `OrderTrackDTO` + `OrderService.trackPublic` + `PublicOrderController GET /public/order/track`;
      monolith `StorefrontController GET /storefront/track`; store.html order-ref on success + Track panel
- [x] Cypress `storefront-track.cy.js` authored
- [x] **Cypress green (headed, 2026-06-26): storefront-track 2/2 + storefront 4/4 + storefront-saga 3/3.**
- Note: no contract/inventory/business change. Gateway already allow-lists `/api/marketplace/public/`.
  Also fixed a pre-existing testIsolation login gap in storefront.cy.js's out-of-stock test (added beforeEach login).

## Deferred
- Customer accounts (E4) + order history list — tracking here is per-order by ref+contact.
- Email/SMS status notifications on fulfilment transitions.
