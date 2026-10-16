# Slice MKT-5c — MaxTheService sells consigned stock; the owner is paid on sell-through

**Status:** VERIFIED 2026-10-16. Migration V42 (`mkt_offer` unique per org, product **and owner**; `mkt_consigned_stock`
gains `order_line_id`, `attempt`, `sale_invoice_no`, `purchase_id`, `unit_price`). Unit: marketplace-service 487 (11 new).
Gate `mkt-5c-consigned-sale.cy.js` **6/6 on a live stack**; manual case M-5-03 recorded as a walk
([live verification](../marketplace/live-verification-2026-10-03.md) §26).

Requirements: **MKT-R2.1** (consignee = holder/seller; ownership and payment change on sale per agreement), **MKT-R4.5**
(CONSIGNMENT: owner = consignor, custodian = consignee), **MKT-R15.4** (consignment settles on sell-through, after the
return period), **MKT-R20.5** (sell-through on the consigned stock ledger). Depends on 5a (the agreement's terms) and 5b
(the consigned stock, set aside in the owner's books, lot by lot).

## 1. What changes

| | Before | After |
|---|---|---|
| Consigned stock at the warehouse | held, never sellable | the party the agreement names to set the price (`priceSetBy`) presses **List for sale** on its row of "Consigned stock at MaxTheService": price, cities, delivery hours, warranty, returns → "Listed for sale by MaxTheService at Rs 52,000.00, under CA-…. Shoppers see 3 available." The other side has no button; the server refuses it: "Under CA-…, Mobile Distributor sets the selling price." |
| The listing | — | a **CONSIGNMENT offer of the warehouse**: seller = custodian = fulfiller = the warehouse, owner = the consignor (R2.1, R4.5). Live at once (the agreement is MaxTheService's approval; the operator can still suspend it); inside the operator's price limits. Shoppers see "MaxTheService" and "Sold and shipped by MaxTheService"; the owner is never a public field |
| The offer form | could ask for CONSIGNMENT (only the phase guard stopped it) | refuses it outright, and refuses editing or submitting a consigned listing: "Consigned stock is listed and priced under Consigned stock at MaxTheService, not on this form." |
| Terms "ownership passes on delivery" | — | listing refused: "Under CA-… ownership passes on delivery. Selling consigned stock on those terms is not available yet: MaxTheService can offer terms where it passes on sale." (open item) |
| What it can sell | — | the ledger balance less the units of orders still waiting for the warehouse; checkout counts it again under the agreement row's lock, so two shoppers are never promised the same unit. More than that: "This seller cannot deliver 3 to Karachi right now." |
| The warehouse accepts | its sale takes its own stock | first, per consigned line and lot: the lot's hold is let go, the **owner sells the units to MaxTheService** at the price less the agreement's commission (52,000 − 10% = 46,800), the rest of the lot is set aside again, **the warehouse buys them** on the owner's invoice, the ledger counts them SOLD. Then the warehouse's sale to the shopper, as for its own stock |
| Settlement | a line settles to its seller | a consigned line settles to its **owner** (the payee), on the agreement's commission, after delivery and the return window; MaxTheService collected the money, cash or card ("collected by PLATFORM"). It is on the owner's statement and payout, not the warehouse's |
| Rejected / expired orders | — | move nothing: the unit is free to promise again |

## 2. Trace (RULE 0)

- **`mkt_offer` unique key, (org, product) → (org, product, owner).** Every existing row has owner = its org (MERCHANT,
  PLATFORM) or the one supplier a dropship offer is restamped to (SUPPLIER), so all rows satisfy the wider key. **Readers
  of the old pair (1):** `MarketplaceOfferService.save`'s duplicate check, now `findFirst…StockSourceTypeNot(CONSIGNMENT)`
  (a seller's own offer of a product, never a consigned listing). **Readers of `findByOrganizationId` (4)**, all fine with
  the warehouse's consigned listings among its offers: `SupplierService` restamp (the warehouse is never a supplier),
  `OfferProjectionService.publishSeller` (republishes them: wanted), `PlatformWarehouseService` ×2 "has offers" (a
  consigned listing also keeps the warehouse named: wanted, it holds consigned stock anyway).
- **`StockSourceType.enabledIn`**: CONSIGNMENT rides on the warehouse switch (`platformStock`). **Readers (3):**
  `PhaseGuard.checkOffer` (offer form — refused before it now), `PhaseGuard.checkCheckout`, `OfferEligibility` (catalogue,
  checkout, reroute candidates). Only `ConsignedSaleService.list` creates a CONSIGNMENT offer.
- **The projection (1 writer).** `publish`: display name "MaxTheService" for CONSIGNMENT as for PLATFORM. `syncStock`: a
  CONSIGNMENT row is counted locally (`consignedAvailable`), never asked of inventory (the warehouse has none of it).
  `PublicOfferService.view`: `soldByMaxTheService` true for CONSIGNMENT.
- **Checkout.** `holdRequest` (**3 callers**: `ask` at checkout and reroute, `hold` — the seller's re-hold after a failed
  sale) skips CONSIGNMENT lines; a part left with nothing to hold remotely is held by the local count alone and never
  calls `LiveRouting` (which would refuse an empty request). `snapshot` (**2 callers**: checkout `create`, shortage
  `newPart`) copies the agreement's commission onto a CONSIGNMENT line (ITEMS at `commissionPct`/100, no policy id).
- **Reroute.** `MarketplaceShortageService.candidates` skips CONSIGNMENT offers: a routed hold cannot count consigned
  stock (open item).
- **`mkt_consigned_stock` writers: 2** — `receiveConsigned` (RECEIVED, 5b) and `ConsignedSaleService.row` (SOLD, VOIDED).
  **Readers: 7** — `heldFor` (both screens; now also the units sold), `heldUnits` (END rule), `heldAt` (warehouse
  change), `existsByTransferIdAndMovement` (RECEIVED once), `balance` (listing, checkout, projection), `lots` (the sale),
  `findByOrderLineIdAndMovement` + `countBy…VOIDED` (replay and attempt). SOLD rows carry the lot's declared value, so
  "Declared value" falls with what is sold.
- **Settlement: the payee.** `payee(l)` = owner for CONSIGNMENT, else seller. **Writers keyed on it (4):** the three ledger
  rows of `settleLine` and its audit row. **Readers (2 queries):** the statement and the payout request's eligible lines,
  both now `CASE CONSIGNMENT THEN owner ELSE seller`. `findDeliveredInStatus` already took every source but PLATFORM. The
  ledger balance, payouts and reports are keyed on the entries' org, which is now the payee.
- **Column types against the entities under `ddl-auto=validate`:** BIGINT `order_line_id` NOT NULL DEFAULT 0 ↔ `Long`,
  INT `attempt` ↔ `Integer`, VARCHAR(64) `sale_invoice_no`, BIGINT `purchase_id`, DECIMAL(19,2) `unit_price`. The
  service started under validate with V42 applied.
- **The wire.** The monolith relays `/mkt/consignedList` as a `Map`; `Held` and `Listing` reach the screen as JSON.

## 3. Tests

- Unit: `ConsignedSaleServiceTest` 9 (listing parties; who may list, on-delivery, nothing held, no agreement; the
  agreement's commission and the owed price; the promise count under the lock; the sale lot by lot in order, keys,
  serials split, re-hold of the rest, ledger rows; replay moves nothing and short is refused before anything moves; a
  refused purchase voids the sale, re-holds the lot, writes VOIDED, and the next try is attempt 1; own stock untouched;
  the hold request skips consigned lines; availability never below zero). `MarketplaceOfferServiceTest` +1,
  `MarketplaceSettlementServiceTest` +1 (payee and collected-by), `SupplierServiceTest` updated (the switch).
- Gate `mkt-5c-consigned-sale.cy.js` (6), `--env '{"mkt":"5c"}'`: the warehouse cannot list under owner-priced terms and
  the form refuses CONSIGNMENT; the owner lists in the real form and the public offer reads MaxTheService, 3 available,
  no owner name; 1 bought leaves 2 and 3 more is refused; Accept in the real UI: the owner's on-hand falls by 1, its free
  stock does not, the warehouse has one more purchase, 2 held / 1 sold; delivered and settled, the line is on the
  owner's statement at 52,000 / 5,200 / 46,800, collected by MaxTheService, and not on the warehouse's; a rejected order
  frees its unit.
- Walk M-5-03, 5 steps and a cleanup.
- 12 message keys × 6 languages.

## 4. Open items

- **Ownership passing on delivery** is refused at listing. The warehouse's sale needs the units on its shelf at Accept, so
  moving them later means selling from stock it does not own yet; to be built with its own design.
- **A refused sale after the units moved** (the warehouse's sale to the shopper fails after its purchase): the units are
  the warehouse's own, bought on credit, and a retried Accept replays and sells them. If the part is then rejected, they
  stay the warehouse's and it owes the owner the purchase: correct books, but not "consigned" any more.
- **A refunded return of a consigned unit** restocks the warehouse's own shelf (it bought it); the owner is settled only
  for what was kept, but its sale to MaxTheService stands. Sending such a unit back to the owner belongs with unsold
  returns.
- **Reroute** never moves a part onto a consigned listing.
- **Two checkouts at once** of the last units may both be refused (each counts the other's lines while it waits); never
  both promised.
- The shopper's order view carries the four party org ids on each line (pre-existing, every source), so the owner's id
  is in it, never its name.

## 5. Next

- Counts and shrinkage claims (R12.1–12.3, M-5-02), unsold returns (lets an agreement end and the gate's stock go back).
