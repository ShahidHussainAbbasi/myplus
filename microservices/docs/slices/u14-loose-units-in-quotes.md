# U14 — a sales quote can be made in loose units ("10 tablets")

**Status (2026-09-17 16:45): BUILT + DEPLOYED, gate NOT yet run.** Ruling = Option A (honour the accepted quote).
V66 applied 11:37:46Z; business-service unit 288/0 (QuoteLooseUnitsTest 8). The user deployed the working tree at
~16:34 local, before the RED run, so this slice has NO red evidence. `cypress/e2e/business/quote-loose-units.cy.js`
runs GREEN-only once myplus-54's survey releases Cypress. Earlier status line: DESIGN, awaiting the user's ruling on §4. Raised 2026-09-17 in the pack/loose flow
review (gap #4). Build freeze in force: nothing deployed until the chunked survey reports.

## 1. The gap (verified)

`SalesQuoteLine` has `productId, productName, quantity, unitPrice, priceReason, discount, lineTotal` — no `soldUnit`,
no `soldQuantity`. A pharmacy cannot quote "10 tablets": it can only quote 0.25 of a box at the box price, which is
the same unreadable figure U13 removed from the Sale Return screen.

It is used: **122 of 218 quotes on dev belong to PHARMA tenants** (orgs 12, 15, 25, 42).

## 2. How a quote becomes a sale today

`SalesQuoteService.convert` → `toSaleRequest` → `SagaSellService.addSell` — the till's own path, so tax, COGS, GL,
credit policy and idempotency (`QTE-<id>`) are not reimplemented. Each line passes `quantity` and the **snapshotted**
`unitPrice` as `sellRate`, because "the customer accepted these numbers".

`addSell` already sells loose: given `soldUnit=LOOSE` + `soldQuantity`, `SagaSellService.looseLine` derives the shelf
quantity, the per-piece rate and the line total — `packRate` = the line's `sellRate` if given (so a quoted pack
price IS honoured), else the catalog price.

## 3. Design

1. **V66** (V65 is myplus-54's credit-note snapshot): `sales_quote_line` gains `sold_unit VARCHAR(16) NULL`,
   `sold_quantity DECIMAL(19,4) NULL`, `sold_rate DECIMAL(19,4) NULL`, `pack_size_snapshot INT NULL`. Nullable —
   every existing quote line is a pack line and reads exactly as before.
2. **Create/edit** a quote line with `soldUnit=LOOSE, soldQuantity=10`: priced with the SAME `looseLine` rule the
   till uses (one implementation — never a second loose formula in the quote service), snapshotting pieces, per-piece
   rate, pack size, and the shelf quantity/line total it produced.
3. **Convert**: a loose quote line crosses to the sale as `soldUnit` + `soldQuantity` + the quoted pack price.
4. **Screens/documents**: the quote editor and printed quote use `looseQtyText` — "10 tablets @ 7.79".
5. **Refused, not reinterpreted**: LOOSE on a product that may not be split; fractional pieces.

## 4. ⚠ THE RULING NEEDED — what if the price moves between quote and conversion?

A loose line's per-piece rate is `packRate ÷ packSize × (1 + looseMarkupPct)`. The quote snapshots the pack price,
but `looseMarkupPct` is a shop setting read **at conversion**, and `packSize` is read from the product **at
conversion**. So if the owner changes the markup (or the pack size) after the customer accepted, the invoice would
charge a different amount from the accepted quote — silently.

| Option | What the customer is invoiced | Cost |
|---|---|---|
| **A. Honour the quote (recommended)** | exactly the accepted `lineTotal`; conversion passes the snapshotted per-piece rate and pack size, and `addSell` is told to use them for this line | a small, explicit override path in `looseLine` for quote conversions only |
| B. Refuse to convert | nothing — conversion fails with "the price changed since this quote; re-quote" when the recomputed total differs | simplest; the customer must be re-quoted |
| C. Re-price at conversion | today's price | silent change to an accepted quote — contradicts the existing snapshot rule; **not recommended** |

Option A matches what the quote already does for pack lines (the snapshotted `unitPrice` wins). B is the
conservative choice if the owner prefers never to bypass current pricing.

## 5. Gate (to be written, not run, under the freeze)

**Unit:** loose quote line priced identically to a till loose sale (same inputs → same total); V66 nullable; convert
carries LOOSE through; the §4 rule (A: accepted total invoiced after a markup change / B: conversion refused).
**Cypress:** quote 10 tablets of a 40-box → reads "10 tablets"; accept → convert → invoice line `soldQuantity` 10,
`quantity` 0.25, total = the quoted total; a non-splittable product refused.

**Regressions:** `sales-quote*` specs, `sell-loose`, `b2b-*` (quotes are a B2B feature).
