# Slice 49 — E-commerce E7: storefront order reserves stock via the trade saga

After E2a/E2b (storefront browse + COD/Card checkout). Until now a guest order only created a marketplace
`Order` row — it never touched inventory, so online sales didn't draw down on-hand. This slice makes a storefront
order **reserve → confirm** stock through the **same inventory reservation saga** that POS uses (slice 33), so an
online sale decrements inventory exactly like a counter sale. **No new saga** — marketplace-service becomes a
second client of the existing `InventoryClient` (reserve/confirm/release).

## Reuse (don't reinvent)
- The **inventory-service reservation saga** (`/api/inventory/reservations` reserve/confirm/release, FEFO, G1
  expired-block, idempotent) is reused verbatim — the same endpoints POS drives via `SagaSellService`.
- The `commerce-contracts` `InventoryClient` + reservation DTOs are reused (added to marketplace-service).
- Identity for the anonymous guest is carried with the existing `GatewayIdentityForwarding.runAs(user, org, …)`
  (built for the saga recovery relay) — no new auth path.

## Flow (`OrderService.placePublic`)
1. Build `StockReservationLine`s from the order `items[]` (productId, quantity).
2. **reserve** (FEFO) under `runAs(STOREFRONT_USER, storeOrg)`. `OUT_OF_STOCK` → `ValidationException`, **no order, no charge**.
3. **Card** → `PaymentGateway.charge`; decline → **release** the hold + throw (no order). Success → paymentStatus `PAID`.
   **COD** → paymentStatus `PENDING`.
4. Create the `Order` (now also stores `reservationId`).
5. **confirm** the reservation (stock decremented). A confirm failure is logged (hold stands for manual reconcile) —
   mirrors `SagaSellService`; marketplace has no recovery relay, so this is best-effort.

So both COD and Card **decrement on order placement** (Card only after a successful charge), consistent with POS.

## Model / API
- `Order.reservationId` (traceability + future cancel→return via the G2 inverse saga).
- `OrderDTO` gains `items[]` (productId/quantity/price — already sent by `store.html`) + `reservationId` (out).
- `POST /api/marketplace/public/order` unchanged on the wire; now drives the reservation.

## Stocking a storefront product (prerequisite)
`/addProduct` creates a catalog `Product` but no inventory stock, so a new storefront product can't be reserved
until stocked. Added a back-office proxy **`POST /addProductStock` {productId, quantity}** → inventory
`/stock/import` (org from the logged-in identity) so a marketplace operator (and the test) can stock a product.
Read-back via **`GET /productStock?productId=`** → inventory `/stock/level/{id}`.

## Wiring
- marketplace-service pom: `commerce-contracts` + `common-security`.
- `MarketplaceClientsConfig` — load-balanced `InventoryClient` to `http://inventory-service/api/inventory` with the
  `GatewayIdentityForwarding` interceptor (+ stamps `X-Internal-Secret` from `service.internal-secret` for prod).
- monolith `InventoryRestClient.postJson` + `CatalogController` stock proxies.

## Tests
- `OrderServiceTest` (Testcontainers + `@MockitoBean InventoryClient`): reserve+confirm on success; COD vs Card paths
  still PENDING/PAID; **OUT_OF_STOCK → throws + no order**; **card decline → release called + no order**.
- Cypress `storefront-saga.cy.js` (headed): stock a product to 10 → storefront order qty 2 succeeds → on-hand 8 →
  order over remaining → blocked (out of stock).

## Status
- [x] Design (this doc)
- [x] marketplace reserve/confirm/release in `placePublic` (+ pom `commerce-contracts`, `MarketplaceClientsConfig`,
      `Order.reservationId`, `OrderDTO.items`, compensation on decline/write-failure)
- [x] monolith `/addProductStock` + `/productStock` proxies (`InventoryRestClient.postJsonString`/`getString`)
- [x] `OrderServiceTest` (reserve+confirm, COD/Card, OUT_OF_STOCK, decline→release) + Cypress `storefront-saga.cy.js`
- [x] **Cypress green (headed, 2026-06-26): `storefront-saga` 3/3 (10→order 2→8; over-stock rejected) +
      `storefront` 3/3 + `storefront-payment` 4/4 (regression: E2a/E2b specs updated to stock their product first).**
- Note: `ddl-auto: update` auto-adds `orders.reservation_id` — no Flyway migration needed.
- Gotcha: storefront checkouts that place an order now need their product **stocked** (`/addProductStock`) and must
  send the **real productId** (not a hardcoded `1`), else reserve → OUT_OF_STOCK blocks the order.

## Deferred
- **Reserve-hold-until-ship** model (confirm on fulfilment, not placement) + order **cancel → release/return**.
- Storefront product **stock UI** in the back-office (this slice ships the proxy/API only).
- Marketplace **recovery relay** for confirm failures (POS has one; marketplace logs + manual reconcile for now).
