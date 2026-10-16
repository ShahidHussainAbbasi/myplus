# Slice MKT-5b — consigned stock into the warehouse: it stays the seller's

**Status:** VERIFIED 2026-10-16. Migration V41 (`mkt_stock_transfer` gains `kind`, `agreement_id`, `hold_key`; new
`mkt_consigned_stock`). Unit: marketplace-service 476 (5 new), inventory-service 1 new, business-service 3 new.
Gate `mkt-5b-consigned-stock.cy.js` **6/6 on a live stack**; manual case M-5-04 recorded
([live verification](../marketplace/live-verification-2026-10-03.md) §25).

Requirements: **MKT-R2.1** (the consignor owns the stock; the consignee holds it), **MKT-R4.5** (CONSIGNMENT: owner =
consignor, custodian = consignee), **MKT-R20.5** (the consigned stock ledger). Depends on 5a (the agreement and
`activeFor`) and 3c (the transfer to the warehouse and its screens).

**Default taken, put to Shahid on a decision card (2026-10-16): consigned stock stays in the SELLER's books.** It is
the seller's asset until it sells, set aside so the shop cannot sell it; the warehouse's books never carry it. This is
how consignment is booked (SAP "special stock": the owner keeps the asset), and it lets a consigned sale (5c) be an
ordinary seller sale with MaxTheService's commission. The alternative put on the card: the stock goes on the
warehouse's shelf at no cost and no bill, the warehouse sells it and owes the seller its share.

## 1. What changes

| | Before | After |
|---|---|---|
| A seller with an ACTIVE agreement | Send stock to MaxTheService only sells it to MaxTheService (3c) | a second choice, **"On consignment: it stays mine"**. The price box becomes "Value a unit" (the declared value, for claims and the ledger), the button "Consign to MaxTheService". "Sent to MaxTheService on consignment as ST-…, under CA-…. It stays yours, on the warehouse's shelf once received." |
| A seller with none | — | the choice is not shown; the server refuses CONSIGN: "You have no active consignment agreement with MaxTheService. Accept its terms under Consignment agreements first, or sell the stock to MaxTheService instead." |
| The warehouse | Receive = a sale in the seller's books, a purchase in its own | for a consigned row ("On consignment, CA-…"): **no sale and no purchase**. "Received on consignment under CA-…. It is <seller>'s stock on your shelf: nothing is owed until it sells." |
| The seller's books | — | the units are set aside by a hold that never lapses: on hand unchanged, free to sell down by the quantity. More than is free: "The seller's books do not show 50 × … free to set aside, so nothing was received: …" and the row waits again |
| Both | — | **Consigned stock at MaxTheService**: per product (and, for the warehouse, per owner) the units held and their declared value |
| Ending the agreement | always, with a reason | refused while stock is held: "The warehouse still holds N units consigned under this agreement. They must be sold or returned before it can end." Accepting new terms still replaces it; the stock carries over |
| Changing or removing the warehouse | refused while it has live offers | also refused while it holds consigned stock |

## 2. Trace (RULE 0)

- **`mkt_stock_transfer`: 3 readers, all WANT consigned rows** (the seller's list, the warehouse's list, `findById`
  for every decision); they show the kind and the agreement. **Writers: 5** — `offer` (kind, agreement), `cancel` and
  `reject` (unchanged), `receive` (now branches on the kind before writing any book), `back` (unchanged). The 3c path
  is unchanged for `kind` BUY, which every existing row gets from the column default.
- **`mkt_consigned_stock`: 1 writer** (`receiveConsigned`, once per transfer: `existsByTransferIdAndMovement` and the
  unique key `(transfer_id, movement)`). **Readers: 4** — `heldFor` (both screens), `heldUnits` (the END rule in
  `ConsignmentAgreementService.decide`), `heldAt` (the warehouse change in `PlatformWarehouseService.set`), and the
  existence check above.
- **`activeFor`: 0 callers before, 3 now** — `offer` (CONSIGN needs it), `receive` (re-checked: an agreement may end
  between sending and receiving; the warehouse is told to choose Not taken) and `view` (whether to show the choice).
- **The hold seam.** `StockHoldRequest` gains `holdKind` (null = ORDER, so the **3 existing `holdStock` call sites**
  — `OrderStockHoldService` (the OMS order hold), `LiveRouting` (checkout and reroute), `MarketplaceCheckoutService.hold`
  (the seller's re-hold) — send null and are unchanged). business-service `InternalStockController`
  passes it on and refuses CHECKOUT. `StockReservationRequest.HoldKind` gains CONSIGNMENT; its **2 readers** in
  inventory: `ReservationPolicy.expiryFor` returns null for it (no deadline), `ReservationService` passes it through.
- **Every reader of `reservations` (5 queries):** `findByReservationIdScoped`, `findByIdempotencyKeyScoped` (the replay
  of a retried Receive: same key, same hold), `lockById` (the expiry worker, reached only from the two below),
  `findExpired` and `findExpiredScoped` (both sweeps), which carry `expiresAt IS NOT NULL`: a consignment hold is never
  swept. Sellable stock is what FEFO can hold, so the seller's own offers and its till stop counting the held units.
- **Column types against the entities under `ddl-auto=validate`:** VARCHAR(16) `kind`/`movement`, BIGINT ids,
  VARCHAR(64) `hold_key`, INT `quantity`, DECIMAL(19,2) `unit_value`, VARCHAR(2000) `serials`, DATETIME(6) `created_at`.
- **The wire.** The monolith relays the transfer JSON as a `Map`, so `kind` passes through without a twin DTO.

## 3. Tests

- Unit: `StockTransferServiceTest` +3 (offer needs the active agreement and names it; Receive sets aside with
  CONSIGNMENT under `MKT-ST-nnnnnn-<attempt>-consign`, writes no sale or purchase, counts once on a replay; a refused
  hold or an ended agreement receives nothing). `ConsignmentAgreementServiceTest` +1 (no END while held; replace still
  works). `PlatformWarehouseServiceTest` +1. `OrderHoldExpiryTest` +1 (no deadline).
  `InternalStockControllerHoldKindTest` 3 (default ORDER, CONSIGNMENT passed on, CHECKOUT refused).
- Gate `mkt-5b-consigned-stock.cy.js` (6), `--env '{"mkt":"5b"}'`: no agreement, no consignment (form and server);
  consign in the real form; Receive in the real form with no purchase and the owner's free stock down by 3; both
  consigned stock lists; END refused for owner and operator; more than is free is refused and Not taken.
- Walk M-5-04, 5 steps and a cleanup.
- 13 message keys × 6 languages.

## 4. Open items

- **Serials are recorded as scanned, not checked** against the seller's books here: no seam reads a tenant's serials
  without selling them. A consigned sale (5c) writes the seller's sale with them, which checks them.
- **The seller's offer of the same phone** keeps its last synced quantity until its next stock sync (pre-existing:
  `availableQty` is the projection's last sync); the hold itself already stops the units being sold.
- **Nothing comes back yet.** Consigned stock leaves the ledger only by being sold (5c) or returned (unsold returns).
  Until then an agreement with stock held cannot end, and the gate's consignor (Mobile Distributor) keeps its units.

## 5. Next

- **5c**: the warehouse lists consigned stock (CONSIGNMENT offers: owner the seller, custodian and fulfiller the
  warehouse); a sale releases the units from the hold and is the seller's sale; settlement on sell-through (R15.4).
- Then counts and shrinkage claims (R12.1–12.3, M-5-02), unsold returns.
