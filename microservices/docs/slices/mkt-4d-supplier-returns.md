# Slice MKT-4d — a supplier answers for its own shortages, returns and settlement

**Status:** VERIFIED 2026-10-15. Gate `mkt-4d-supplier-returns.cy.js` **3/3 on a live stack**; manual case M-4-05
recorded. One product change: a supplier can no longer reject an order blaming "my supplier".

Requirements: **MKT-R11.4** (a rejection is recorded against the party responsible), **MKT-R20.5** (supplier stock:
returns and settlement), with R13.1/R13.2 (who bears a return, cash back at pickup) and R15.5 (commission) shown on a
supplier's order.

## 1. What a supplier is, and what follows

Shahid's ruling of 2026-10-15: a supplier is a **MyPlus business the operator marks** (MKT-4a). It owns its stock,
holds it and sends it itself. So on every supplier-stock line the party snapshot names the supplier as stock owner,
custodian and fulfiller, and the existing rules already give the right answer for returns and settlement. One rule
gave the wrong one:

| Question | Before | After |
|---|---|---|
| A supplier rejects with "My supplier could not deliver" | accepted: recorded with role SUPPLIER and **no responsible business**, so its scorecard excused the miss | refused in a sentence ("You are the supplier of these items, so the stock is yours: choose "Out of stock in my shop"."); the picker does not offer it |
| A supplier rejects with "Out of stock in my shop" | recorded against it (MERCHANT, its org) | unchanged |
| A shop rejects with "My supplier could not deliver" | recorded, excused | unchanged (control in 4d-01) |
| A faulty return of supplier stock | borne by the stock owner: the supplier; its rider collects; cash back at pickup | unchanged, now proved (4d-02) |
| A delivered supplier line | settles like a shop's: commission taken, payable after the return window | unchanged, now proved (4d-03) |

## 2. Trace (RULE 0)

- **Callers of the seller cause parser (`sellerCause`): 1**, `SellerOrderService.reject`. The guard sits right after it,
  before anything changes. The clock's NO_RESPONSE does not pass through it.
- **Where SUPPLIER_STALE_STOCK is chosen: 2**, the reject API (guarded) and the seller's picker in
  `marketplace-seller.js` (filtered when any line of the part is supplier stock).
- **Readers of the shortage's responsible role: 4 files**: the entity, the shortage service (records and decides), the
  seller-order repository (the two performance-fact queries, which excuse non-MERCHANT misses), the DTO. None changes:
  the fix stops the wrong value being written.
- **Returns:** the bearer is read from the line's party snapshot (unchanged); the collector sentence names "the seller's
  rider" for everything but MaxTheService's own stock (MKT-3d), which is right for a supplier.
- **Settlement:** excludes PLATFORM stock only, so supplier lines settle as a shop's (unchanged).
- No schema change.

## 3. Tests

- Unit: `MarketplaceOrderFlowTest.supplierOwnsItsShortage`: on supplier stock, SUPPLIER_STALE_STOCK is refused and the
  part stays OFFERED; MERCHANT_STALE_STOCK is recorded against the supplier. Suite: 60/60 in the class.
- Gate `mkt-4d-supplier-returns.cy.js` (3): the picker and the API refuse the cause, the own cause is recorded against
  the supplier, a shop still names its supplier (real UI); a faulty COD return is borne by the supplier, its rider
  collects and hands the cash back; a delivered line under a 0-day policy and T+0 settles with commission and adds up.
- Walk M-4-05, 4 steps and a cleanup, recorded.

## 4. Open items

- None for 4d. A supplier that is not a MyPlus business would need its own returns and settlement terms (see 4c §3).
