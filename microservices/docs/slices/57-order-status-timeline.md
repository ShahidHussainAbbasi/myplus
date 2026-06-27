# Slice 57 — Order status timeline + notifications

Tracking (slice 56) shows only the *current* status. This records a **notification event** on every fulfilment
transition (placed → packed → shipped → delivered / cancelled) and surfaces the **timeline** on the tracking page,
so a customer sees the order's history. A `NotificationService` records each event and is the seam for real
email/SMS delivery (logged for now — actual delivery is a config follow-on, like the sandbox PSP).

## Changes
- **marketplace-service**
  - `OrderEvent` entity (orderId, status, channel, recipient, note, createdAt) + `OrderEventRepository`
    (`findByOrderIdOrderByCreatedAtAsc`).
  - `NotificationService.notify(order, status, note)` — appends an `OrderEvent`; channel `EMAIL` when the contact
    looks like an email (logged "would email…"), else `LOG`. Best-effort (never breaks the order flow).
  - `OrderService`: emit `NEW` on `placePublic`/`record`; emit the new status on `updateStatus` (incl. CANCELLED).
  - `OrderTrackDTO.events` — the timeline (status + time); `trackPublic` loads it.
- **monolith** `store.html` — the Track panel renders the status timeline.

## Tests
- `OrderServiceTest` (Testcontainers): placing an order records a `NEW` event; advancing status appends an event.
- Cypress `storefront-timeline.cy.js` (headed): place → track shows `NEW`; back-office advance to `PACKED` → track
  shows `NEW` then `PACKED`.

## Status
- [x] Design (this doc)
- [x] `OrderEvent` + `OrderEventRepository` + `NotificationService` + OrderService hooks (placePublic/record/updateStatus)
      + `OrderTrackDTO.events` + store.html timeline + `OrderServiceTest` timeline case
- [x] Cypress `storefront-timeline.cy.js` authored
- [x] **Cypress green (headed, 2026-06-26): storefront-timeline 2/2 + storefront-track 2/2 + order-cancel 3/3 regression.**
- Note: marketplace-service only; `ddl-auto: update` creates `order_events`. No contract/gateway change.

## Deferred → real delivery
- Wire actual email (reuse education-service's SMTP `EmailService` pattern) + SMS provider; `NotificationService`
  already shapes the recipient/channel. Per-customer notification preferences.
