# Slice 52 — Marketplace saga recovery relay

`placePublic` reserves then confirms inline; if **confirm** fails (inventory transiently down, timeout), the order
was recorded but its stock stayed *held, not decremented*, with no automatic recovery — POS has a relay for exactly
this (slice 33, U3c), marketplace didn't. This slice adds the marketplace counterpart so a failed confirm is
re-driven automatically (`confirm` is idempotent).

## Reuse
- Mirrors business-service `SagaRecoveryRelay` verbatim: a `@Scheduled` job + `GatewayIdentityForwarding.runAs`
  per order's tenant + idempotent `InventoryClient.confirm`.

## Model delta
- **`Order.reservationStatus`** (`PENDING` | `CONFIRMED`, null for orders with no hold). `placePublic` saves the
  order `PENDING`, confirms, and on success flips it to `CONFIRMED`; a confirm failure leaves it `PENDING`.
  (`ddl-auto: update` adds the column — no migration.) Exposed on `OrderDTO` (out).

## Relay (`OrderSagaRecoveryRelay`)
- `@Scheduled(fixedDelayString = "${marketplace.saga.relay-delay-ms:60000}")` →
  `OrderRepository.findPendingReservations()` (reservationStatus PENDING, reservationId set, not CANCELLED) →
  for each: `runAs(STOREFRONT_USER, org)` `confirm(reservationId)` → mark `CONFIRMED`. Transient failure → left for
  the next tick. `@EnableScheduling` added to the app.

## Tests
- `OrderServiceTest` (Testcontainers + mocked InventoryClient): a `PENDING` order → relay confirms it + flips to
  `CONFIRMED`; an already-`CONFIRMED` order is ignored; happy-path placement reports `CONFIRMED` inline.
- Cypress `order-saga-relay.cy.js` (headed): a placed order reports `reservationStatus = CONFIRMED` end-to-end
  (checkout response + back-office). (The failure→relay path needs fault injection → unit-tested, not E2E.)

## Status
- [x] Design (this doc)
- [x] `Order.reservationStatus` + `placePublic` PENDING→CONFIRMED + `findPendingReservations` + `OrderSagaRecoveryRelay`
      + `@EnableScheduling` + `OrderServiceTest`
- [x] Cypress `order-saga-relay.cy.js` authored
- [x] **Cypress green (headed, 2026-06-26): `order-saga-relay` 1/1 + `order-cancel` 3/3 + `storefront-saga` 3/3 regression.**

## Deferred
- Surface PENDING/stuck reservations in the back-office (an ops view) + an alert if a hold stays PENDING beyond N ticks.
- Confirm-failure metrics/tracing (pairs with the observability backlog).
