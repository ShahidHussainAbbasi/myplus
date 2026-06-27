# Slice 48 — E-commerce E2b: storefront online payment (sandbox)

After E2a (storefront, COD). Adds **online card payment** to the guest checkout. Since real PSP keys aren't wired,
this ships a **sandbox/mock charge** (deterministic: a `fail` token fails, anything else succeeds) so the full
pay → paid-order flow is real and testable. **Swapping in a real PSP (Stripe) is a config/SDK follow-up** (test
keys, charge call, webhook) — the model + flow here are PSP-ready.

## Model delta (marketplace `Order`)
- `paymentStatus` (`PENDING` | `PAID` | `FAILED`) · `paymentRef` (charge id). `paymentMode` already exists (COD | CARD).

## Flow
- Storefront checkout offers **Cash on Delivery** or **Card**.
- **COD** → order `NEW`, paymentMode `COD`, paymentStatus `PENDING` (as E2a).
- **Card** → `OrderService.placePublic` runs `PaymentGateway.charge(token, amount)` (sandbox): success → paymentStatus
  `PAID`, paymentRef set, order created; failure (`token == "fail"`) → `ValidationException` (no order created).

## API
- `POST /api/marketplace/public/order` (existing, anonymous) now accepts `paymentMode` + `cardToken`; returns the
  order with `paymentStatus`/`paymentRef`.

## UI (store.html)
- A payment toggle (COD / Card). Card shows a simple sandbox card form (number/expiry/cvc — **mock, not stored**);
  the "token" is `fail` only if the card number is `4000000000000002` (the classic decline test number), else `ok`.
- On success: "Payment received — order confirmed". On decline: error, cart kept.

## Tests
- `OrderService` sandbox charge unit test (PAID on ok token, FAILED/throws on `fail`).
- Cypress (headed): a Card checkout → order `PAID`/`CARD`; a declined card → no order; COD still `PENDING`.

## Status
- [x] Design (this doc)
- [x] Order `paymentStatus`/`paymentRef` + `PaymentGateway` sandbox + `placePublic` card path (+ `OrderDTO` payment fields)
- [x] store.html payment toggle + card form (decline test card `4000 0000 0000 0002` → `fail` token)
- [x] Tests authored — `OrderServiceTest` (PAID on ok / throws on `fail` / COD PENDING) + Cypress `storefront-payment.cy.js`
- [x] **Cypress green (headed, 2026-06-26): `storefront-payment.cy.js` 4/4 + `storefront.cy.js` 3/3 (E2a regression)**

## Deferred → real PSP
Real Stripe (test/live keys in Secrets Manager, `PaymentIntent` + 3DS, webhook to confirm async) replaces
`PaymentGateway.charge`; the `Order` payment fields + checkout flow stay.
