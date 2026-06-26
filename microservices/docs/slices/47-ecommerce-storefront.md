# Slice 47 — E-commerce E2a: public storefront (browse → cart → guest checkout)

Phase 3, the customer-facing surface. Unlike POS/pharmacy (staff dashboards), the storefront is **public** — no
login. A shopper browses a store's products, adds to a cart, and places a **guest order** that lands in the
back-office Orders (slice 46). Reuse the proven public pattern (`DemoController` → gateway open route).

## Scope (E2a — bounded; PSP + multi-store are follow-ups)
- **Single demo store** identified by `orgId` in the request (multi-store slugs later).
- **Payment = Cash on Delivery** stub (status NEW, paymentMode=COD). Online **PSP** = E2b.
- **Guest order** records an `Order`; inventory decrement/saga integration = E2b (COD orders reconcile at fulfilment).

## Architecture (reuse the public-feature pattern)
```
shopper → monolith /store (public page, permitAll)
        → monolith /storefront/products?org= , /storefront/checkout  (public controllers)
        → gateway OPEN routes (JwtAuthenticationFilter anonymous allow-list):
            /api/catalog/public/products?org=     → catalog-service (active products for org)
            /api/marketplace/public/order         → marketplace-service (guest Order)
```
- **Gateway:** add `/api/catalog/public/` + `/api/marketplace/public/` to `JwtAuthenticationFilter` anonymous
  allow-list (joins the existing `/api/campaign/public/`, `/api/appointment/public/`).
- **catalog-service:** `GET /api/catalog/public/products?org={orgId}` — active products for that org (anonymous;
  org explicit since there's no JWT identity). SecurityConfig permit `/api/catalog/public/**` (post-strip n/a — catalog has no StripPrefix).
- **marketplace-service:** `POST /public/order` — `{organizationId, customerName, customerContact, shippingAddress,
  total, items[]}` → `Order` (NEW, COD, source=STOREFRONT). Public path already permitted (`/public/**`).
- **monolith:** public `StorefrontController` (`/store` page + `/storefront/products` + `/storefront/checkout`
  proxying the gateway open routes via RestTemplate, like `DemoController`); permit these in `SecSecurityConfig`.
- **UI:** `store.html` — product grid → add to cart → cart → checkout form (name/contact/address) → place order →
  confirmation. New public page (Tailwind/Bootstrap), not the dashboard.

## Model delta
- `Order` += `source` (POS | STOREFRONT), `paymentMode` (COD | …), `customerContact`. `invoiceNo` null for
  storefront orders (no trade sale yet).

## Tests
- Cypress (headed, no login): visit `/store?org={demoOrg}` → products render → add to cart → checkout → success;
  then (as demo.marketplace back-office) the order appears in `/getOrders` with source=STOREFRONT.

## Status
- [x] Design (this doc)
- [x] gateway: `/api/catalog/public/` + `/api/marketplace/public/` added to JwtAuthenticationFilter anonymous allow-list
- [x] catalog: `PublicProductController` `GET /api/catalog/public/products?org=` + repo finder (already permitted)
- [x] marketplace: `PublicOrderController` `POST /public/order` + `OrderService.placePublic` + Order `source`/`paymentMode`/`customerContact`
- [x] monolith: public `StorefrontController` (`/store` page + `/storefront/products` + `/storefront/checkout` proxying the gateway open routes) + `store.html` + `SecSecurityConfig` permits + **🛍️ Shop link on the landing page**
- [x] Cypress `business/storefront.cy.js` written
- [x] Build + restart + **Cypress GREEN (headed, 2026-06-26): `storefront.cy.js` 3/3** — public browse + guest COD checkout → back-office order (source=STOREFRONT) + store page renders. **E2a DONE.**
  > Fix during verify: anonymous checkout POST needed CSRF exemption (`/storefront/**` added to `SecSecurityConfig` ignore list — same as book-a-demo).

## Deferred (E2b+)
PSP/online payment (extends G5) · inventory decrement via saga on order · multi-store (slug/subdomain) ·
customer accounts/login · order tracking page · reviews.
