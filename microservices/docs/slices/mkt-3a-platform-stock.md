# Slice MKT-3a — the MaxTheService warehouse sells its own stock

**Status:** BUILT 2026-10-09. Unit-green (marketplace-service 439 tests, 0 failures, 10 new). No migration: the
warehouse is one row of `mkt_platform_setting`; the stock source already had a column on the offer and the line. Gate
`mkt-3a-platform-stock.cy.js` **6/6 on a live stack**. Manual case M-3-01 recorded (it replaces the design-only case of
the same id).

Requirement: **MKT-R4.2** (PLATFORM stock: owner MaxTheService, custodian and fulfiller the platform warehouse) and
**MKT-R20.4** (platform stock), with R10.5 (platform: held until picked) and R22.1 (operator only). Depends on MKT-1c
(offers), 1e (the acceptance window and the hold), 1g (settlement). Out of scope, each its own slice: warehouse
pick/pack screens beyond Incoming orders (3b), stock transfer from a seller to the warehouse (3c), central returns (3d).

## 1. Document

| | Before | After |
|---|---|---|
| The operator | no platform stock: `PLATFORM` existed in the enum and was refused by the phase guard | Platform → **Marketplace policies** → **MaxTheService warehouse**: choose the organisation whose stock MaxTheService sells as its own. Only an approved seller with no offers of its own is listed |
| The warehouse | — | an ordinary tenant (seeded `owner.warehouse@myplus.com`, "MaxTheService Warehouse"): it receives stock, proposes products and lists offers through the same screens as any shop. Its offers are `PLATFORM` |
| A shopper | — | the row names the seller **"MaxTheService"** with "Sold and shipped by MaxTheService" under it; after "Place order": "MaxTheService is packing your order. In stock at MaxTheService and set aside for you. Packed within 23:59:59." |
| The warehouse's clock | — | 24 hours to accept (accepting is the pick: serials scanned, the sale and invoice in its books), not the shops' minutes. Countdowns show h:mm:ss from one hour up |
| Settlement | — | a warehouse line is **never settled**: no commission, no payout, no row in the statement. MaxTheService does not owe itself |
| Another seller | — | cannot list `PLATFORM` stock ("Only the MaxTheService warehouse sells MaxTheService's own stock.") and cannot apply under the platform's name ("That is MaxTheService's own name. Enter the name of your business.") |

**Why one named tenant and not a new kind of stock.** R-MKT design row MKT-3: "warehouse = an operator-owned org on the
existing inventory". Its stock, holds, sale, invoice and serials go through the paths every seller already uses; only
the name shown, the window and settlement differ.

**Why the phase stays 1.** `PhaseGuard`'s phase number also opens the multi-seller checkout. Raising it to 3 for
platform stock would have lifted the one-seller rule silently. A separate flag (`platformStock` = a warehouse is named)
opens `PLATFORM` alone (`StockSourceType.enabledIn`).

### 1a. Trace (RULE 0)

**Writers of the offer's stock source: 1, changed.** `MarketplaceOfferService.save`. The source is now derived, never
taken from the request: the warehouse always gets `PLATFORM`, anyone else asking for `PLATFORM` is refused, anyone else
gets what it had. The line copies the offer's source at checkout (unchanged).

**Writers of `accept_by`: 2, both changed.** `MarketplaceCheckoutService` (transaction 2) and
`MarketplaceShortageService.newPart` now call `acceptMinutesFor(sellerOrg, value)`: the warehouse gets the `PLATFORM`
hold window (1440 minutes), everyone else the value rules of 2-06. Readers of `accept_by`: 4, none changed (2-06 §1a).

**The hold outlives the window.** A marketplace hold is `HoldKind.ORDER`, 3 days by default
(`DEFAULT_ORDER_HOLD_MINUTES`), longer than the 24-hour pick window: the stock cannot be released under a waiting order.

**Readers of the seller's display name: 4, all changed.** `MarketplaceCheckoutService.sellerName` (order page and
parts), `MarketplaceSupportService.sellerName` (support threads), `OfferProjectionService.publish` (the projection's
name), `PublicOfferService.view` (adds `soldByMaxTheService`). The tenant's own name ("Central Warehouse") is never shown
to a shopper (gate 3a-02, walk step 3).

**Readers of order lines: 7 repository methods.** 2 exclude `PLATFORM`: `findDeliveredInStatus` (the settlement run)
and `statement` (its query and its count query). 5 unaffected: `findById`, `findBySellerOrderIdOrderByIdAsc`,
`findBySellerOrderIdIn` (order views, which want the line), and `findBySellerOrganizationIdAndSettlementStatusAndPayoutIdIsNull`
and `findByPayoutId` (payouts), which a warehouse line never reaches because the run leaves it `NOT_ELIGIBLE`.

**Ledger writers: 6 `append` calls in 4 methods of `MarketplaceSettlementService`.** The 3 in `settleLine` are the
ones affected: it never sees a warehouse line now. `markPaid`, `adjust` and `recordRemittance` (1 each) are operator
actions on a seller's account, which the warehouse never gets (gate 3a-05 asserts no account).

**The setting.** Key `platform.warehouseOrg`, value the organisation id, or "" for none. Saved through the operator
path with an audit event `MKT_WAREHOUSE_SET` / `MKT_WAREHOUSE_REMOVED` (old and new id).

**The wire.** New `SellerDTOs.Warehouse(organizationId, organizationName, liveOffers, candidates)` and
`WarehouseRequest(organizationId)`; `OfferDTOs.PublicOffer` gains `soldByMaxTheService`. The monolith relays the JSON
untouched (`Map<String,Object>`), so no twin DTO.

**Column types.** None added.

## 2. Design

`PlatformWarehouseService`: `view()` and `set()` (operator only). `set` checks the operator's choice first, then the
current warehouse:

- "Choose an approved seller account."
- "This organisation already has offers of its own. Choose one with none: its offers would become MaxTheService's."
- "The warehouse has offers live or waiting for approval. Suspend them before removing the warehouse." (or "changing")

Saved: "<name> is the MaxTheService warehouse. Its offers read "Sold and shipped by MaxTheService"." or "No warehouse:
MaxTheService sells no stock of its own." Naming the current warehouse again is a no-op.

`isPlatformName`: spaces, dots, underscores and hyphens removed, lower-cased, contains "maxtheservice". A seller
application under such a name is refused.

## 3. Screens

| Who | Where | What |
|---|---|---|
| Operator | Platform → Marketplace policies → **MaxTheService warehouse** | the list of eligible organisations, Save, the sentence under it |
| Shopper | product page | "MaxTheService" / "Sold and shipped by MaxTheService" |
| Shopper | order page | "MaxTheService is packing your order", "Packed within h:mm:ss" |
| Warehouse | Sale → Marketplace → Incoming marketplace orders | unchanged screen; the countdown starts at 24:00:00 |

## 4. Endpoints

| Method | Path (monolith → service) | Who |
|---|---|---|
| GET | `/platform/mkt/warehouse` → `/mkt/operator/warehouse` | operator |
| POST | `/platform/mkt/warehouse` {organizationId \| null} → the same | operator; null removes the warehouse |

## 5. Tests

- **Unit** (10 new): `PlatformWarehouseServiceTest` (5: name, refused, changeOnlyWhenEmpty, operatorOnly,
  platformName), `PhaseGuardTest.platformStock`, `OfferEligibilityTest.platformStock`,
  `MarketplaceSettingsServiceTest.warehouseWindow`, `MarketplaceOfferServiceTest.platformStock`,
  `MarketplaceSellerServiceTest.platformNameRefused`.
- **Gate** `mkt-3a-platform-stock.cy.js` (6), `--env '{"mkt":"3a"}'`: the warehouse named in the real form and a shop
  with offers refused; the warehouse's offer `PLATFORM` and "Sold and shipped by MaxTheService" on screen; another
  seller refused `PLATFORM` on its own offer and refused the endpoint; 24-hour window on the part, the shopper's page and
  the warehouse's countdown; delivered warehouse line `NOT_ELIGIBLE` while a shop's control line in the same run moves to
  `PENDING_RETURN_WINDOW`, no account, report row or statement line for the warehouse; removing the warehouse refused
  while its offer is live. Cleanup: every order it placed is fulfilled or rejected.
- **Manual** M-3-01.

## 6. Open items

- **Settlement of the warehouse's sales** is "not settled" pending Shahid's ruling (the alternative, settled at 0%
  commission, removes the two query exclusions).
- The public offer's `availableQty` is the projection's last stock sync; it does not drop the moment a hold is taken
  (seen in walk M-3-01, pre-existing behaviour for every seller). The hold itself is taken at checkout.
- The warehouse stays named on a shared stack, and each gate or walk run leaves one more live offer of its own.
