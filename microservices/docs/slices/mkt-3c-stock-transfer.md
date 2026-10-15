# Slice MKT-3c — a seller sends stock to the MaxTheService warehouse

**Status:** BUILT 2026-10-15. Unit-green (marketplace-service 451 tests, 7 new; business-service 572). Migration V37
(`mkt_stock_transfer`). Gate `mkt-3c-stock-transfer.cy.js` **6/6 on a live stack**. Manual case M-3-04 recorded.

Requirement: **MKT-R20.4** (platform stock: transfer into the warehouse) and **MKT-R4.2** (the warehouse owns what it
holds). Depends on 3a (the named warehouse, matched products) and on the two internal seams of business-service.

**The ruling taken (default, not yet confirmed by Shahid).** MaxTheService BUYS what it receives. Received, a transfer
is two ordinary documents in two tenants' books: a **sale** in the seller's (to the customer "MaxTheService", no tender,
so MaxTheService owes the seller) and a **purchase** in the warehouse's (from the seller as supplier, nothing paid).
There is no marketplace commission, settlement line or payout: the warehouse pays the seller through payables like any
supplier. Consignment (the seller still owns it on the warehouse's shelf) would be a different slice.

## 1. Document

| | Before | After |
|---|---|---|
| A seller with stock MaxTheService also carries | no way to move it to the warehouse; the only path was an off-system sale and a manual purchase | Sale → Marketplace → **Send stock to MaxTheService**: product (only those MaxTheService has matched too), quantity, price a unit, note. "Sent to MaxTheService as ST-… . It is yours until the warehouse receives it." The seller may Cancel while it waits |
| The warehouse | nothing | **Stock sent to MaxTheService**: each transfer with the seller's name; **Receive** (one serial per unit when the seller's product is serial-tracked) or **Not taken** with a reason the seller reads |
| Received | — | the seller's sale (INV-…) and the warehouse's purchase on that invoice number; the IMEIs leave the shop's shelf and arrive on the warehouse's; "Received. It is in your stock, and you owe <seller> Rs …" |
| The warehouse's books refuse the purchase | — | the seller's sale is voided, nothing is received, the transfer waits again; the warehouse reads why ("Serial / IMEI tracking is not switched on for your business, …") |

### 1a. Trace (RULE 0)

**The new table, `mkt_stock_transfer`: 3 readers, 6 writers, all in `StockTransferService`.** Readers:
`findBySellerOrgIdOrderByIdDesc` (the seller's list), `findByWarehouseOrgIdOrderByIdDesc` (the warehouse's list),
`findById` (every decision, via `load`, which also checks the screen's version). Writers: `offer` (save), `cancel` and
`reject` (`decide` → save), `receive` (the RECEIVING claim, saveAndFlush; the final RECEIVED save), `back` (save, OFFERED
again). No other service reads it. Column types checked against the entity under `ddl-auto=validate`: BIGINT ids,
VARCHAR status/serials/notes, DECIMAL(19,2) price, INT quantity/attempt/version, DATETIME(6) times: the service starts.

**Writers of the two documents: 1 each, through the seams every channel uses.** The sale: `TradeClient.recordSale`, now
**4 callers** (order acceptance, dispatch invoice, the walk-in marketplace sale, and this); the 3 existing ones are
unchanged. The purchase: `TradeClient.recordPurchase` → `POST /internal/purchases` (new), **1 caller** (this). The
controller holds no money logic: it maps the request onto `PurchaseService.addPurchase`, which already does stock in,
serials, the payable, tax, GL and idempotency. It sets `paidAmount = 0` on purpose (left null, the purchase path treats
a bill as cash paid in full) and `totalAmount = quantity × unit cost` (what the purchase form posts).

**Readers of the new purchase: every purchase reader, and all of them WANT it** (35 queries in `PurchaseRepo`; none
needs to tell a transfer apart): it is a real purchase the warehouse owes. Same for the seller's sale.

**The wire.** `PurchaseRecordRequest` / `PurchaseRecordResult` in commerce-contracts (one copy, no DTO twin). The
anti-IDOR rule matches the sale seam: the body's org must be the authenticated org.

**Identity on each hop, and why the purchase does NOT use `AsOrg`.** The warehouse person is the caller of Receive, so
the purchase travels with their own forwarded identity, `X-Org-Caps` included. Sent through `AsOrg` (user 0, no
capability header), business-service resolved the warehouse's capabilities from its own local settings store, which does
not hold the tenant's shape: it treated expiry tracking as ON for a retail warehouse that has it OFF, and demanded an
expiry date nobody can give on a transfer (found by gate 3c-06; live verification row 76). The seller's sale and its
void still go through `AsOrg`, because the seller is not the caller; see Open items.

**Idempotency and races.** Keys are `MKT-ST-nnnnnn-<attempt>-sale` / `-purchase`. Receive first claims the row
(RECEIVING, flushed): a racing Cancel or Not taken then loses on the version. A Receive interrupted after the claim is
retried with the same keys, so it replays rather than books twice. A refused purchase voids the sale and bumps
`attempt`, so the next try uses new keys (the old sale key would replay the voided invoice).

## 2. Screens

`fragments/marketplace-seller.html` `#mktTransferBox` (shown when the business can sell on the marketplace), driven by
`marketplace-seller.js` (`mktTransfersLoad`, `mktTransferSend`, row buttons). The monolith relays `/mkt/transfers`,
`/mkt/transferOffer`, `/mkt/transferCancel`, `/mkt/transferReject`, `/mkt/transferReceive`. 26 message keys × 6 languages.

## 3. Tests

- Unit `StockTransferServiceTest` (7): offer refusals and both product ids; only the two parties (others read "Transfer
  not found."); stale screen, decided transfer, Not taken needs a reason; one serial per unit before either book is
  written; Receive order, keys, invoice as the bill number; a refused purchase voids the sale with attempt+1; a refused
  sale writes nothing else.
- Gate `mkt-3c-stock-transfer.cy.js` (6), live: the real form; anti-IDOR; Cancel in the UI; Not taken with a reason; a
  purchase the warehouse refuses voids the sale and leaves the IMEIs on the shop's shelf; Receive with IMEIs: invoice,
  purchase (Rs 80,000, nothing paid, supplier = the shop), IMEIs moved, a second Receive refused.
- Walk M-3-04, 5 steps, recorded.

## 4. Open items

- **Capabilities on `AsOrg` calls (pre-existing, platform-wide).** A call stamped with `AsOrg` carries no
  `X-Org-Caps`, so the callee answers capability questions from its local settings store, which can differ from the
  tenant's real (auth-computed) set. 3c avoided it for the warehouse's purchase; the seller's sale here, and the order
  and dispatch paths, still rely on it. A fix needs the target tenant's effective set from auth on the internal hop.
- Two Receives of the same transfer at the same instant: the second loses on the row version, but only after its sale
  and purchase calls replayed under the same keys (idempotent, so no double booking, but wasted calls).
- The ownership ruling (bought vs consignment) awaits Shahid's confirmation.
