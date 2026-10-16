# Slice MKT-Xa — who answers for an order

**Status:** VERIFIED 2026-10-16. No migration (read-only). Gate 3/3 live, walk M-X-01 recorded ([live verification](../marketplace/live-verification-2026-10-03.md) §29). Unit: marketplace-service 513 (4 new, `MarketplaceOrderFlowTest.parties*`). Gate `mkt-Xa-parties.cy.js` (3), walk M-X-01.

Requirements: **MKT-R23.1** (who answers for what is always visible), **MKT-R8.1** (MaxTheService owns the customer
relationship, support, complaints and return coordination), **MKT-R3.1** / **MKT-R3.2** (each line names its seller,
stock owner, custodian and fulfiller). Depends on 1e (the order and its snapshot lines), 3b (parcels), 5c (consigned
lines, where the owner is not the seller).

## 1. What changes

| | Before | After |
|---|---|---|
| Platform → Marketplace orders | number, total, seller, items, city, status | also **Who answers for it** under the number; pressed, a row opens below the order (pressed again, it closes) |
| The row | — | MaxTheService's part: "Customer relationship: MaxTheService", "Support: MaxTheService: support, complaints and returns"; then each seller's part: the seller and the carrier; then each line: where the stock comes from, its owner, custodian and fulfiller **by name**, the warranty ("Samsung Pakistan, 12 months") and the returns ("7 days, coordinated by MaxTheService") as recorded at checkout |
| A party not decided yet | — | says why, never blank: "None yet: no seller has accepted", "Not sent yet: the fulfiller books it at dispatch", "None: this part did not go ahead", "No warranty", "No return window; complaints go to MaxTheService", "Not recorded", "Organisation N (no seller account)" |
| A shop, or a bad number | — | refused (operator only); "No such order." |
| The orders list | opening the panel and choosing a filter at once could list every order twice | only the newest load draws the table |

## 2. Design decisions (defaults taken)

- **Operator only, read only.** The shopper already sees MaxTheService as the seller (5c) and the seller sees its own
  part; the full picture, with the owner of consigned stock, is MaxTheService's. No new table: everything comes from
  the line's snapshot (R3.1: the parties are fixed at checkout, so the answer does not move when an offer changes).
- **Names, not ids.** The warehouse organisation reads "MaxTheService warehouse"; a seller its display name; an
  organisation with no seller account says so.
- **The carrier is the parcels' carriers** once sent (several, distinct, in order); a parcel with no carrier is "The
  fulfiller's own delivery".
- **Labels are translated (6 bundles), values are the server's English** like every other relayed message.

## 3. Trace (RULE 0)

- **Writers: none.** The slice adds no column, row or table; `ddl-auto=validate` is unaffected.
- **Readers used (6), all existing:** `MarketplaceOrderRepository.findByOrderNo` (the number, trimmed and upper-cased),
  `MarketplaceSellerOrderRepository.findByMktOrderId` (parts, sorted by id as `parts()` does),
  `MarketplaceOrderLineRepository.findBySellerOrderIdOrderByIdAsc`, `MarketplaceSettingsService.warehouseOrg`,
  `MarketplaceSellerAccountRepository.findByOrganizationId` (cached per call), and `parcels(storeOrder(so))`, which reads
  a store order only once the part is accepted and only when it is the seller's own (3b's guard).
- **Callers (4 new methods):** `parties` ← `MarketplaceOrderController GET /mkt/operator/orders/{orderNo}/parties` (1);
  `partyName`, `carrier`, `warranty` ← `parties` only (1 each).
- **Access:** `access.assertOperator()` first, before any read. The monolith route `/platform/mkt/orderParties` is
  `ROLE_ADMIN` and lets only `[A-Z0-9-]{1,40}` through to the path, so no other route can be reached through it.
- **The wire:** `Parties` → `PartParties` → `LineParties` records, relayed as a `Map`; the screen reads `customer`,
  `customerRelationship`, `support`, `parts[].seller|carrier|lines[]`, `lines[].productName|quantity|stockSource|seller|
  stockOwner|custodian|fulfiller|warranty|returns`.

## 4. Tests

- Unit (`MarketplaceOrderFlowTest`, 4): every party named for a merchant line; a consigned line with the warehouse and
  an owner with no account, then nothing recorded; the carrier through Accept, parcels and a part that did not go
  ahead; operator only and "No such order.".
- Gate `mkt-Xa-parties.cy.js` (3), `--env '{"mkt":"5c,Xa"}'` (5c places the consigned order Xa-02 opens; task
  `latestOrderOfSource`).
- Walk M-X-01.

## 5. Open items

- **The shopper's and the seller's view** of the same facts stay as they are (the shopper sees MaxTheService; the seller
  sees its own part). Showing the consignor its consigned sales is 5c's ledger, not this panel.
