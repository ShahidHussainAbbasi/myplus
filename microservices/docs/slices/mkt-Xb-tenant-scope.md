# Slice MKT-Xb — tenant scope on every row, file, report, event and job

**Status:** VERIFIED 2026-10-16. No migration. Unit: marketplace-service 516 (3 new). Gate `mkt-Xb-tenant-scope.cy.js` 3/3 live, walk M-X-02 recorded ([live verification](../marketplace/live-verification-2026-10-03.md) §30).

Requirements: **MKT-R21.2** (organization_id on every tenant-owned entity, plus owner / custodian / seller / fulfiller
org), **MKT-R21.3** (every cache key, event, file, report and job carries the org scope), **MKT-R22.1** (the tenant
comes from the login).

## 1. What changes

| | Before | After |
|---|---|---|
| The seller's period summary (Sale → Marketplace → Statement) | figures on screen only | also "For Shahzad Mobile Shop, organisation 16" and **Download CSV**: `marketplace-statement-org16-<from>-to-<to>.csv`, opening `Seller,…` / `Organisation ID,16` / `Period,… to …`, then the lines on screen |
| The operator's settlement CSV | `settlement-report-<from>-to-<to>.csv` | `settlement-report-all-sellers-<from>-to-<to>.csv`; every line still starts with its seller's organisation id |
| The report (`SettlementReport`) | from, to, rows, totals | also `organizationId` (the login's; null for the operator's) and `scope` ("All sellers", or the seller's name, or "Organisation N") |
| `OfferChanged` (the stock-sync event) | offer id | offer id and the seller's organisation |
| Job warnings | "line 123 not settled", "stock sync for offer 5 failed", "Order #9 -> SHIPPED" | each names the organisation of the row it failed on |
| New entities | nothing stopped one without an organisation | `TenantScopeTest` fails until a new entity has an organisation column or is listed with a reason |

## 2. Design decisions (defaults taken)

- **Three kinds of row.** Tenant-owned rows carry their organisation(s). MaxTheService's own rows (the shopper and its
  order and payment, R8.1; the canonical catalogue; policies, settings, holidays) carry none, each listed with its
  reason. A row read only through a scoped parent (a support message under its case) names that parent.
- **Files are built in the browser from the figures on screen** (as 2f's): the file and the table cannot differ. The
  scope in the file comes from the report the server scoped to the login, never from the page.
- **No notification is sent** for settlement; the store's order notifications are logged with the order's organisation.

## 3. Trace (RULE 0)

- **Entities (27):** 18 carry an organisation (AgreementAcceptance, ConsignedReturn, ConsignedStock, ConsignmentAgreement,
  ConsignmentCount, Offer, OfferProjection, OrderLine, Payout, ProductSource, Return, SellerAccount, SellerOrder,
  SettlementEntry, Shortage, ShrinkageClaim, StockTransfer, SupportCase); 8 are MaxTheService's own (Customer,
  CustomerSession, Order, Payment, Product, Policy, PlatformSetting, Holiday); 1 child (SupportMessage → SupportCase).
  Offer and OrderLine carry all four parties (seller, stock owner, custodian, fulfiller).
- **Reports (2):** `SettlementReportService.mine` (scope = `access.org()`, from the token; the monolith relays only `from`
  and `to`, so an `organizationId` added to the request never arrives) and `.operator` (operator only, all sellers).
  `new SettlementReport` has 1 call site. Readers of the JSON: the seller screen, the operator screen, gates 2f and Xb.
- **Files (2):** the seller's statement CSV (new) and the operator's settlement CSV (renamed). Readers of the old name: gate
  2f-06, walk M-2f-01 and its manual case (updated).
- **Events (2):** `OfferChanged` (3 call sites, 1 listener, 2 test sites; now with the seller's org) and the GL outbox
  row (already `organization_id`, delivered as that org with `GatewayIdentityForwarding.runAs`).
- **Jobs (5):** settlement sweep (warnings name the line's seller org), order sweeper (works per seller order, whose row
  carries the org), stock-sync sweep (through `OfferChanged`, now with the org), performance refresh (per seller, keyed
  by org), GL relay (runs as each row's org).
- **Cache keys (1):** `LiveRouting.circuits`, keyed by the seller's organisation. No `@Cacheable`, no Redis in the service.
- **Column types:** unchanged; no migration.

## 4. Tests

- Unit: `TenantScopeTest` (2: every entity classified; Offer and OrderLine carry all four parties),
  `SettlementReportServiceTest.scope` (the login's org and name; "All sellers"; "Organisation N"),
  `OfferProjectionServiceTest.onlyLiveSyncs` (the event names the seller's org).
- Gate `mkt-Xb-tenant-scope.cy.js` (3), `--env '{"mkt":"Xb"}'`; regression 2f (the renamed operator file).
- Walk M-X-02.

## 5. Open items

- **Branch** (R21.2 "branch, warehouse where applicable"): the marketplace has no branch; the warehouse is the custodian
  organisation. A branch column waits until a seller sells from more than one branch.
- **Real notifications** (email/SMS) are not built; when they are, each must carry the organisation as the log does.
