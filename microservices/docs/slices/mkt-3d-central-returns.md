# Slice MKT-3d — returns of MaxTheService's own stock

**Status:** BUILT 2026-10-15. Unit-green (marketplace-service 444 tests, 0 failures, 1 new). No migration. Gate
`mkt-3d-central-returns.cy.js` **4/4 on a live stack**. Manual case M-3-03 recorded.

Requirement: **MKT-R20.4** (central returns for platform stock), **MKT-R4.2** (the warehouse is owner, custodian and
fulfiller of PLATFORM stock) and **MKT-R13.2** (the return path), with R22.1 (the operator's queue). Depends on MKT-1f
(cases, returns, the seller's "Item received"), 3a (the warehouse) and 3b (Sale → Orders).

**What was already true, and verified rather than rebuilt.** A return of a warehouse line already lands at the
warehouse: it is the line's seller, so the return is tasked to it, "Item received" writes the credit note in its books
and puts the item back into its stock, and the refund (card, or cash handed back at pickup) is the same code as any
shop's. This slice changes what was wrong around that path, nothing in it.

## 1. Document

| | Before | After |
|---|---|---|
| The customer, return approved | "Your return RT-… is approved. The seller's rider will collect it from your address." for every return, MaxTheService's own phone included | MaxTheService's own stock: "… MaxTheService will collect it from your address."; a shop's: unchanged |
| The seller, "Write off" outcome | "Write off: unusable", with nothing saying the item still exists. It is sent as `quarantine=true`, exactly like Quarantine | "Write off: unusable (goes to Quarantine, where you dispose of it)" in all 6 languages |
| A marketplace seller that tracks neither batches nor expiry (the warehouse, most phone shops) | no Quarantine screen: the menu item and the section were `batchTracking,expiryTracking` only, so a written-off or quarantined return could never be disposed of | Register → **Quarantine** shown to marketplace sellers too; Dispose works |
| The operator, more than 100 cases waiting | the list shows the first 100 (urgent first, then the longest waiting): **the newest cases were never reachable** | "1–100 of 152", "‹ Previous", "Next ›"; the status buttons start again at the first page |

### 1a. Trace (RULE 0)

**Writers of the approval message: 1**, `MarketplaceSupportService.decide` (the APPROVED branch). It now names
`collector(r)`. **Callers of `collector`: 1** (that branch). It reads the return's order line and its
`stockSourceType`: `PLATFORM` → "MaxTheService", anything else or no line → "The seller's rider". Readers of the message:
2, both unchanged (the customer's case view, the operator's case view).

**Writers of the outcome: 1**, `received()`. `OUTCOMES` = RESTOCK, QUARANTINE, WRITE_OFF; it sends
`quarantine = !"RESTOCK".equals(outcome)` to the seller's stock: **WRITE_OFF and QUARANTINE do the same thing**, a lot
with `restockable=false` (verified in gate 3d-02: the warehouse's quarantine list grows by 1). Nothing links that lot to
the return; it is disposed of from the Quarantine screen like any other. The label now says so; the behaviour is
unchanged (a real write-off at receipt would be a new stock path, not taken here).

**The Quarantine screen: 2 gates on it, both widened** (the menu `li#navQuarantine` and `#QuarantineDiv`), any-of
`batchTracking,expiryTracking,marketplaceSelling` (capabilities.js `needs.some`). The menu permission
`register.quarantine` is unchanged.

**The operator's case list: callers of `loadCases`: 4** (open the panel, a status button, after any action on a case,
the new pager). After an action the list reloads the page it was on. A page past the end (a case resolved on the last
page) falls back to the first. The service already paged (`size` capped at 100); only the screen never asked for page 2.

**The wire.** No DTO changed. **Column types.** None added.

## 2. Screens

| Who | Where | What |
|---|---|---|
| Customer | My orders → the help request | "MaxTheService will collect it from your address." |
| Warehouse, any seller | Sale → Marketplace → Tasks from MaxTheService | the new Write off label |
| Warehouse, any marketplace seller | Register → **Quarantine** | the written-off or quarantined item, Dispose |
| Operator | Platform → Support cases | the pager under the list |

## 3. Tests

- **Unit** `MarketplaceSupportServiceTest.collector`: a shop's line → "The seller's rider will collect…"; a `PLATFORM`
  line → "MaxTheService will collect…".
- **Gate** `mkt-3d-central-returns.cy.js` (4), `--env '{"mkt":"3d"}'`: 01 the two messages, warehouse and shop; 02 in the
  real screens, the warehouse writes the return off (the new label), the customer is REFUNDED, the quarantine list grows
  by one, Register → Quarantine → Dispose removes it; 03 control, the shop's return received with RESTOCK; 04 the
  operator pages to the last case (the stack has 152 waiting).
- **Manual** M-3-03: the whole return in the real screens, customer → operator → warehouse → Quarantine.
- **Regression** on the same build: 1f 12/12, 3b 6/6, 3a 6/6.

## 4. Open items

- WRITE_OFF and QUARANTINE remain one stock action. Whether a write-off should leave the stock at receipt (no
  quarantine step) is a product ruling, not taken here.
- The quarantine lot carries no link to its return; the screen shows the product, not the RT number.
- The operator's queue has no search by case or order number; paging is the fix taken.
