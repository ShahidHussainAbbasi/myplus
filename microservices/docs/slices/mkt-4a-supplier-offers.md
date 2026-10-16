# Slice MKT-4a — dropship suppliers: marked by the operator, honest about stock

**Status:** BUILT 2026-10-15. Unit-green (marketplace-service 458 tests, 8 new). Migration V38. Gate
`mkt-4a-supplier-offers.cy.js` **6/6 on a live stack**. Manual case M-4-01 recorded.

Requirement: **MKT-R4.3** (SUPPLIER: owner = custodian = the supplier, which ships) and **MKT-R4.4** (supplier stock
is never shown as immediately available unless the supplier gives reliable availability and accepts within a defined
time). Part of MKT-4; the rest is 4b (acceptance and dispatch SLA, tracking), 4c (an API stock and price feed with
timeouts, circuit breakers and idempotent webhooks, R22.5) and 4d (supplier returns and settlement).

**Ruling (Shahid, 2026-10-15, decision card):** a supplier is a MyPlus business the operator marks as one, not an
external API-only company. It uses the portal, books, orders and payouts every seller already has.

## 1. Document

| | Before | After |
|---|---|---|
| The operator, Marketplace sellers → Approved | approve, suspend, reinstate | also, per seller: **"Supplier: ships its own stock to customers"** and **"Its stock counts are reliable"**, Save. The row carries a **Supplier** tag |
| A marked supplier's offers | — | SUPPLIER stock, whatever the request asks (the source follows who lists it, as the warehouse's are PLATFORM). Unmarked, they are MERCHANT again. A shop asking for SUPPLIER: "Only a business MaxTheService has marked as a supplier sells supplier stock." |
| The shopper, a supplier's offer | — | "Shipped by the supplier" under the name. Counts not trusted: **"The supplier confirms the stock within 20 minutes of your order"** instead of "Stock checked …". Trusted: "Stock checked …" as for any shop |
| An order from a supplier | — | the supplier has **20 minutes** to accept (the plan's `AcceptanceTerms` SUPPLIER window, until now unread), whatever the operator's value tiers say; a shop's window is unchanged |
| The warehouse | — | cannot be marked a supplier, and a supplier cannot be named the warehouse (each in a sentence) |

### 1a. Trace (RULE 0)

**Writers of `stock_source_type` on an offer: 2.** `MarketplaceOfferService.save` (now: warehouse → PLATFORM, marked
supplier → SUPPLIER, else what was asked, else MERCHANT; a kept SUPPLIER becomes MERCHANT once unmarked) and
`SupplierService.set` (re-stamps MERCHANT ↔ SUPPLIER only, never PLATFORM or CONSIGNMENT). **Readers: 11**, each
classified:
- want SUPPLIER as its own source (5): the projection copy, `PublicOfferService.candidate` (eligibility), the new
  `dropship` flag on the public offer, checkout's phase guard, the order-line snapshot;
- treat it as "not PLATFORM", which is right for a supplier (4): the projection's display name (the supplier's own
  name), the support courier text ("The seller's rider": the supplier ships), the 3 settlement queries (a supplier is
  settled like a shop), the public order-status JS (packing copy is PLATFORM only);
- unaffected (2): the seller's offer DTO copy, the save fallback.

**Where SUPPLIER becomes sellable: 4 callers of `enabledIn` / `checkOffer`.** Offer save (this org is marked), checkout
and public eligibility (`platform.supplierStock`, rewritten by `SupplierService` from `existsBySupplierTrue` on every
mark), the catalogue (MERCHANT only, unchanged). CONSIGNMENT stays off.

**Acceptance window: 2 writers of `acceptBy`**, checkout and the shortage reroute; both now pass whether the part is
supplier stock (from its lines' snapshot source), and `acceptMinutesFor(org, value, supplierStock)` answers 20 for it.

**Columns (V38), checked against the entities under `ddl-auto=validate`:** `mkt_seller_account.supplier` and
`supplier_stock_reliable` BIT(1) NOT NULL ↔ `Boolean`; `mkt_offer_projection.stock_confirmed` BIT(1) NOT NULL DEFAULT 1 ↔
`Boolean` (every existing row stays confirmed). The service started with V38 applied.

**The wire.** `PublicOffer` gains `dropship`, `stockConfirmed`, `confirmMinutes`. Named "dropship", not "supplier":
gate MKT-1c-06 forbids any public key containing "supplier" (R9.2: a shop's own suppliers never leak), and it caught the
first name used.

## 2. Screens

Operator: `marketplace-sellers.js` `supplierBox` on approved rows; monolith relay `/platform/mkt/sellerSupplier` →
`/mkt/operator/sellers/{org}/supplier`. Shopper: `marketplace-public.js` offer row (`.o-supplier`,
`.mkt-stock-unconfirmed`). 6 keys × 6 languages. Seeded tenant **owner.supplier@myplus.com** ("Phone Supplier", retail),
used by the MKT-4 gates and walk only.

## 3. Tests

- Unit (8): `SupplierServiceTest` (mark and unmark re-stamp and re-publish, reliable, refusals, the source switch);
  `MarketplaceOfferServiceTest.supplierStock`; `OfferProjectionServiceTest.supplierStockConfirmed`;
  `PlatformWarehouseServiceTest.refused` (a supplier as warehouse); `MarketplaceSettingsServiceTest.supplierWindow`.
- Gate `mkt-4a-supplier-offers.cy.js` (6), live: mark in the real UI; "Shipped by the supplier" and the 20-minute
  sentence; an order with a 20-minute window waits for the supplier; reliable → "Stock checked"; refusals; unmark.
- Walk M-4-01, 4 steps and a cleanup, recorded.

## 4. Open items

- The warehouse's row in Marketplace sellers still shows the supplier boxes; saving them is refused in a sentence.
- 4b–4d as above.
