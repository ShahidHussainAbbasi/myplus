# Slice MKT-3b — the shopper sees packed, sent and delivered

**Status:** BUILT 2026-10-15. Unit-green (marketplace-service 443 tests, 0 failures, 1 new). No migration. Gate
`mkt-3b-delivery-progress.cy.js` **6/6 on a live stack**. Manual case M-3-02 recorded.

Requirement: **MKT-R20.4** (platform pick/pack and platform delivery: what the shopper sees of them) and **MKT-R18.5**
(the shopper tracks the order). Depends on MKT-1e (tracking by number and phone), 1f (`delivered_at`), 3a (the
warehouse) and O5b (parcels: carrier, tracking, the only shipment writer).

**Scope ruling (default taken, decision card open):** tracking only. The pick list and scan-to-pack bench are OMS O5d,
whose design doc says "no code until approved"; it was never approved, so this slice writes no packing path. Next:
3d (central returns), then 3c (seller-to-warehouse transfer, which needs its own ruling).

## 1. Document

| | Before | After |
|---|---|---|
| The shopper, one seller | "Confirmed by …" from Accept until after delivery, and still "… will deliver and collect" once delivered | "Packed by …" · "On its way from …" (or "Shipped by MaxTheService") with the carrier and tracking number · "Delivered". Cash on delivery adds "Pay Rs. 52,000 in cash when it arrives." |
| MaxTheService's own stock after Accept (the pick) | "Confirmed by MaxTheService" | "MaxTheService is packing your order. Picked and set aside for you. Packing is next." |
| Several sellers | each part "Confirmed: … will deliver", then "Delivered" | each part says the same progress as above, on its own line |
| My orders | "Confirmed" | a one-seller order's state is its progress ("Packed by …"); each part of a several-seller order likewise |
| A seller without the storefront module (every marketplace shop, the warehouse) | **no menu path to Orders**, where it marks Packed, records the parcel and marks Delivered: only the API | **Sale → Orders** (shown to marketplace sellers) |

The page asks again every 30 seconds while a part is on its way, and stops once it is delivered.

### 1a. Trace (RULE 0)

**Writers: 0.** The marketplace stores nothing new. The store order's fulfilment status and its parcels are read at
view time; MKT-1f's rule stands (the marketplace keeps one delivery fact, `delivered_at`, and no copy of the states).

**Readers added: 2,** both in `MarketplaceCheckoutService.parts`: `OrderRepository.findById(storeOrderId)` and
`ShipmentRepository.findByOrderIdOrderByShipmentSeqAsc`. Read only for an ACCEPTED or HANDED_OVER part, and only when
the store order's organisation is the part's seller (otherwise nothing is shown; unit `deliveryProgress`).

**Callers of `parts()`: 1 (`view`), which has 9 call sites:** checkout's answer (3: a replay, the race, the result),
anonymous tracking (1), the operator's order list (1), the account view (1, `MarketplaceAccountService`) and the
shopper's answer to a shortage (3, `MarketplaceShortageService`). All carry the two new fields; none needed a change. The operator list
(up to 100 orders) pays up to two reads per accepted part; noted, not optimised.

**The wire.** `PartView` gains `fulfilment` (the store order's status name) and `parcels`
(`ParcelView(shipmentNo, carrier, trackingNumber, shippedAt, status)`). The anonymous view is proven by number AND
phone; it never carries the parcel's invoice, note, lines or sender (gate 3b-04 asserts the exact key set). The
monolith relays the JSON untouched, so no twin DTO.

**Readers of `PartView` in the browser: 2.** `marketplace-public.js` (order page, one part and several) and
`marketplace-account.js` (My orders), which uses the same `progress()` through `mktPage`.

**Column types.** None added.

## 2. Screens

| Who | Where | What |
|---|---|---|
| Shopper | order page | the progress sentence and detail above |
| Shopper | My orders | the same words as the order's state |
| Seller, warehouse | **Sale → Orders** (new menu entry for marketplace sellers) | the existing O5b screen: Mark PACKED, Ship (carrier, tracking), Mark DELIVERED |

## 3. Tests

- **Unit** `MarketplaceOrderFlowTest.deliveryProgress`: nothing before Accept (no store order read at all); after
  Accept the status and the parcel without its invoice or note; nothing when the store order is another seller's.
- **Gate** `mkt-3b-delivery-progress.cy.js` (6), `--env '{"mkt":"3b"}'`: no delivery facts before Accept; picked,
  packed, sent (carrier and tracking; the exact parcel keys), delivered (and no promise left) on the warehouse's
  order in the real page; a shop's order still reads "Confirmed by …", then "On its way from …".
- **Manual** M-3-02: the whole road in the real screens, the warehouse working in Sale → Orders.
- **Regression** on the build merged with the base: 3a 6/6, 1e 10/10, 1e2 9/9, 1f 12/12, 2a 8/8, 2b 8/8.

## 4. Open items

- O5d (pick list, scan-to-pack) waits on its own design approval.
- A parcel's own delivery (O7 keying) is not shown per parcel; the part reads "Delivered" from the store order.
