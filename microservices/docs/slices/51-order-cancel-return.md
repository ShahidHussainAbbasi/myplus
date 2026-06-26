# Slice 51 — Order cancel returns stock (E7 inverse)

The complement of slice 49: a storefront order **holds + decrements** stock on placement, so **cancelling** it must
give that stock back. Reuses the **existing G2 inverse saga** (`InventoryClient.returnStock` → inventory
`/reservations/{id}/return`, built for POS returns in slice 34) against the `reservationId` stored on each order.

## Reuse
- G2 inverse saga (`returnStock` / `returnPicks`) — restores to the sale's original batches (the reservation picks).
- The order status pipeline (`updateStatus`) + monolith `/updateOrderStatus` proxy — cancel is just a transition to
  `CANCELLED`; no new endpoint.

## Model delta
- **`OrderItem`** child entity (productId, quantity, price) — orders now persist their lines (`@OneToMany` on Order,
  cascade). Needed because a cancel must know what quantities to return; previously only the total was stored.
  (`ddl-auto: update` creates `order_items` — no migration.)

## Flow (`OrderService.updateStatus`)
- Transition **into** `CANCELLED` (from a non-cancelled state) **and** the order has a `reservationId` **and** lines
  → build `StockReturnRequest` from the order's `OrderItem`s → `inventoryClient.returnStock(reservationId, …)`.
- **Idempotent:** only on the first transition (re-cancel is a no-op for stock). Best-effort: a return failure leaves
  the order cancelled and is logged for reconcile (doesn't block the cancellation).
- **POS-origin orders** (`record()`, no reservationId) cancel without touching inventory (their stock was the POS saga).
- Identity: the back-office cancel runs authenticated, so the gateway identity is forwarded to inventory normally
  (the reservation is found by org scope).

## UI (ecommerce.js)
- Orders table gains a **Cancel** button (shown until DELIVERED); confirms, calls `/updateOrderStatus` CANCELLED,
  reloads. "Order cancelled — stock returned."

## Tests
- `OrderServiceTest` (mocked InventoryClient): cancel → `returnStock` once; re-cancel → still once; POS order → never.
- Cypress `order-cancel.cy.js` (headed): order qty3 → on-hand −3 → cancel → restored + status CANCELLED; re-cancel →
  restored once (not twice); back-office **Cancel button** → restored.

## Status
- [x] Design (this doc)
- [x] `OrderItem` persistence + `updateStatus` cancel→return + ecommerce.js Cancel button + `OrderServiceTest`
- [x] Cypress `order-cancel.cy.js` authored
- [x] **Cypress green (headed, 2026-06-26): `order-cancel` 3/3 (cancel restores + CANCELLED; re-cancel once; UI button)
      + `storefront-saga` 3/3 regression.**

## Deferred
- Partial cancel / partial return of an order (this is full-order cancel).
- Refund on cancel for a paid (CARD) order — the sandbox PSP has no refund yet; pairs with the real-PSP slice.
- Reserve-hold-until-ship (would make pre-ship cancel a `release`, not a `return`).
