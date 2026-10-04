# Slice MKT-2a — one basket, several sellers: the parent order and its parts

**Status:** BUILT 2026-10-04. Unit-green (marketplace-service 363 tests, 0 failures). Gate `mkt-2a-multi-seller.cy.js`
**8/8 on a live stack**; the earlier gates 0a–1g re-run green after the change (75/75). Manual cases M-2a-01..08
**walked and recorded** ([live verification](../marketplace/live-verification-2026-10-03.md) §9).

Requirements: **MKT-R17.2** (a parent order with a child per seller, each with its own promise), **MKT-R20.3** (a
multi-seller cart), plus MKT-R17.1 (Phase 1's one-seller rule stays the default), R10.2, R10.5, R13.1, R8.2 and R22.1
as each applies per part. Depends on MKT-1e (orders, acceptance), MKT-1e2 (accounts, card payments) and MKT-1f
(support). Out of scope: shortage rerouting (R11.x), live routing deadlines and the rest of MKT-2.

## 1. Document

A customer can put phones from two shops in one basket and check out once. They get one order number and one
payment. Each shop sees, accepts or rejects only its own part, delivers it on its own promise, and is paid for it
on its own. When one shop says no, only that part ends: its stock goes back and, if paid online, its amount is
refunded. The rest of the order goes ahead.

The operator decides whether this is allowed: **Platform → Marketplace policies → "Customers can buy from several
sellers in one order"**. It is **off by default**. While it is off, a basket with two sellers is refused
("Items from different sellers must be checked out separately."), which is Phase 1's R17.1 rule.

### 1a. Trace (RULE 0)

There is **no schema change**. `mkt_seller_order` already had `mkt_order_id`, and one order could already own a list
of parts (`findByMktOrderId`). What was missing was every place that assumed the list had exactly one entry.

**Readers of `mkt_seller_order`:** 31 call sites in 7 services, through 8 finders. Each was classified:

| Reader | Before | MKT-2a |
|---|---|---|
| `MarketplaceCheckoutService.view` (order page, tracking) | `parts.get(0)` | every part, as `sellerOrders[]`; the parent's countdown is the longest |
| `SellerOrderService` incoming list and view | the part's lines, the order's total | the part's lines **and the part's own total** (`partTotal`) |
| `SellerOrderService.accept` / `reject` | ended the order | **the parent follows its parts** (`follow`) |
| `MarketplaceOrderSweeper` (expiry) | ended the order | `follow` + the part's own refund |
| `MarketplaceAccountService` cancel and "My orders" | one part | cancels every OFFERED part; refused once any part is accepted |
| `MarketplaceSupportService.open` | the order's one part | the part of the line the customer picks (`lineId`) |
| `MarketplaceDeliveryHook` | per part (by store order) | unaffected: it already works on one part |
| `MarketplaceSettlementService` | per line, first line per part | unaffected: it already works per line and per part |

That is 6 readers that change and 2 that are unaffected.

**Writers of the part status:** 8 call sites in 5 services: checkout, accept, reject, expiry, customer cancel and
delivery. Every one now ends in `follow(parent, parts, reason)`.

**Recomputing writers:** there are two, the parent's status and its payment status. Both are now derived from the
parts: the status by `follow`, the payment by the refunds recorded per part (`send`).

**The wire:** `CheckoutRequest` gained `lines[]`. The 10-argument and 8-argument constructors are kept, so the
single-offer request is unchanged. `OrderView` and `AccountOrderView` gained `sellerOrders[]`. `CaseView` gained
`sellerName`, and `AcceptWindow` gained `multiSeller`.

**Behaviour change:** a request with both `offerId` and `lines` now uses `lines` (it was: `lines` ignored). Walk case
M-1e-02 was rewritten to the new contract: with the switch off, two sellers are refused.

## 2. Design

### 2.1 Checkout

1. `wants(req)` turns the basket into wanted lines. The same offer twice is merged, and two different prices for it
   are refused. Each item is limited to 1–10, and a basket holds at most 10 different items.
2. Each line is checked against its live offer, as before. Messages name the product when the request is a basket.
3. `PhaseGuard.checkCheckout(lines, settings.multiSeller())` refuses more than one seller unless the switch is on.
4. The lines are grouped by seller. A seller that does not take cash on delivery refuses a COD basket by name.
5. **Transaction 1:** the parent plus one part per seller, each part with its own lines (its own snapshot).
6. **Hold** each part's stock. At the first refusal ("{seller} no longer has enough stock. Please remove its items and
   place the order again.") every part already held is released and the order is cancelled. **All or nothing.**
7. **Charge once** for the whole basket (card). A decline releases every part.
8. **Transaction 2:** every part becomes OFFERED (or PAYMENT_PENDING) with the same accept-by time.

### 2.2 The parent follows its parts

| Parts | Parent |
|---|---|
| any part ACCEPTED or HANDED_OVER | CONFIRMED |
| none accepted, none waiting (all rejected, expired or cancelled) | CANCELLED |
| otherwise | unchanged (SUBMITTED) |

A customer can cancel while no part is accepted. Cancelling ends every waiting part together. Once any part is
accepted, cancelling is a support case, as in MKT-1e2.

### 2.3 Money per part

- **A part ends while others go ahead:** `refundPart(order, part, partTotal)` under a pessimistic lock on the order
  (`lockById`), with the idempotency key `part:{sellerOrderId}`. A second reject of the same part refunds nothing.
- **The last part ends:** `refundIfCancelled` refunds **only the remainder** (the charge minus the part refunds), under
  the same lock.
- The payment status is PARTIALLY_REFUNDED after a part refund and REFUNDED when nothing is left.
- `reconcile` also routes the `part:` keys, so a refund lost in transit is retried like any other.

### 2.4 Support per part

The customer names the item (`lineId`), and the case goes to that item's seller. With several confirmed parts and
no item chosen, the request is refused with "Choose the item you need help with.". Each seller part has its own open
case (`findFirstByMktOrderIdAndSellerOrgIdAndStatusNot…`). A return is received against its own line's part.

## 3. Screens

| Who | Where | What |
|---|---|---|
| Customer | product page | "Add to basket" next to "Buy from …"; "Basket (n)" at the top |
| Customer | basket | one group per seller, quantity and Remove per line, the basket total, a note on per-seller delivery |
| Customer | order page | "Waiting for the sellers to confirm" / "Confirmed by some sellers" / "Confirmed"; one row per seller with its total, its countdown or result, and "its amount is refunded to your card" |
| Customer | My orders | one row per seller part; "Partly refunded"; Get help asks for the item first; the help thread names the seller (multi-seller orders only) |
| Seller | Incoming orders | only its own part: its lines and its total |
| Operator | Platform → Marketplace policies | the switch, with its hint |

The basket lives in the browser (`localStorage` key `mkt.basket`). It is re-priced from the live offers each time it
opens, and an item no longer offered to the city is marked and blocks placing until it is removed.

## 4. Endpoints

| Method | Path | Change |
|---|---|---|
| POST | `/marketplace/public/checkout` | `lines: [{offerId, quantity, expectedPrice}]` |
| GET | `/marketplace/public/orders/{no}` | `sellerOrders[]` |
| GET | `/marketplace/account/orders` | `sellerOrders[]` per order |
| POST | `/marketplace/account/orders/{no}/cases` | `lineId` picks the part |
| GET/POST | `/platform/mkt/acceptWindow` | `multiSeller`; POST saves only the fields sent |

## 5. Tests

- **Unit:** `MarketplaceOrderFlowTest` covers multiSellerOffRefused, splitsPerSeller, oneRefusalCancelsAll,
  cardChargedOnce, partsLiveOnTheirOwn, lastPartEndsTheOrder, expiryAfterAcceptKeepsTheOrder,
  customerCancelsEveryPart, multiSellerSwitch and basketBounds. `MarketplacePaymentServiceTest` covers
  partRefundedOnce, remainderAfterPart and cashPartNothing.
- **Gate:** `mkt-2a-multi-seller.cy.js`, 2a-01..08. MKT-2-01 in the MKT-2 placeholder spec moved here.
- **Manual:** M-2a-01..08 in the walk, step by step, each with its cleanup.

## 6. Open items

- The seller's promised-by time is per part. The order page shows each part's result, but not its promised-by date.
- One delivery fee per part is charged as configured per offer. A basket-level delivery discount is not designed.
- Shortage rerouting of a rejected part to another seller (R11.1–R11.3) is the next MKT-2 slice.
