# Slice 46 — E-commerce E1: Orders back-office (reuse the sale; add fulfilment)

Phase 3 (e-commerce), back-office first (public storefront deferred). **An order IS a trade sale** (the existing Sell
saga → stock/tax/payment/receipt), plus a **fulfilment lifecycle** tracked in `marketplace-service`. ECOMMERCE users
land on the shared dashboard relabelled "Store" (slice 36). Reuse-first, net-new = the order/fulfilment layer only.

## Model (marketplace-service, net-new, org-scoped)
- **`Order`** — `organizationId`, `userId`, `invoiceNo` (the trade sale), `customerName`, `total`, `fulfilmentStatus`
  (`NEW`→`PACKED`→`SHIPPED`→`DELIVERED` / `CANCELLED`), `shippingAddress`, `createdAt`, `updatedAt`.
- Marketplace-service joins the mesh (it already has cloud/eureka/config + the gateway route `/api/marketplace/**`,
  StripPrefix=2) — just needs the code + the SecurityConfig public-matcher fix (`/public/**` post-strip).

## API (marketplace + monolith proxy)
- `POST /orders` (record an order from a sale) · `GET /orders` (list, scoped) · `GET /orders/{id}` ·
  `PUT /orders/{id}/status {status}`.
- monolith `MarketplaceRestClient` + proxies: `/recordOrder`, `/getOrders`, `/updateOrderStatus`.

## UI / flow (reuse the Sell screen for ECOMMERCE)
- The ECOMMERCE sale uses the existing Sell screen ("Order" wording). **Post-sale hook** (`main.js`): when
  `window.MODULE === 'ECOMMERCE'`, after `addSell` success, `recordOrder(invoiceNo)` creates the Order (`NEW`).
- **Orders** screen (ECOMMERCE-only): list orders + advance fulfilment status (Pack → Ship → Deliver).

## Tests
- Cypress (headed): record an order → it lists → status advances to SHIPPED; Orders panel renders for ECOMMERCE.

## Status
- [x] Design (this doc)
- [x] marketplace `Order` + `FulfilmentStatus` + repo + `OrderService` + `OrderController` + `OrderServiceTest`
  (Testcontainers; added test deps) + SecurityConfig `/public/**` fix
- [x] monolith `MarketplaceRestClient` + `OrderController` proxy (`/getOrders`,`/recordOrder`,`/updateOrderStatus`) +
  post-sale hook (`main.js`) + **Orders** screen (`ecommerce.js`, MARKETPLACE-only)
- [x] **Naming reconciliation:** the vertical is **`MARKETPLACE`** (the seeded `demo.marketplace` user + service +
  gateway `/api/marketplace`), NOT `ECOMMERCE` — aligned slice-36 wiring (`module-theme.js`,
  `CommerceDashboardController`, `determineTargetUrl`, `AppController`) + the order UI/hook to `MARKETPLACE`;
  `loginAsMarketplace` Cypress cmd added.
- [x] Cypress `business/ecommerce-orders.cy.js` written
- [x] Build + restart + **Cypress GREEN (headed, 2026-06-26): `ecommerce-orders.cy.js` 2/2** — record→list→ship + Store dashboard renders (passed on re-run; first attempt hit the known monolith session-loop flakiness). **E1 DONE.**
