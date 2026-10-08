# Slice MKT-2d — cash on delivery: what a seller owes, and the money it pays

**Status:** BUILT 2026-10-08. Unit-green (marketplace-service 396 tests, 0 failures, 11 new; finance-service
`MarketplacePostingRulesTest` 7/7, 1 new). No migration. Gate `mkt-2d-cod-reconciliation.cy.js` **10/10 on a live stack**,
in two phases of the clock (§5). Manual cases M-2d-01..03 recorded.

Requirement: **MKT-R20.3** ("COD reconciliation"), under ruling **R-MKT-2** (online: the platform collects; cash on
delivery: the seller's rider collects and owes the platform the commission). Depends on MKT-1g (the ledger, the
operator's books, T+N settlement). Reuses the idea of the shop's cash-up (O7 D5): what was collected, what was handed
over, and a reason for any difference. Out of scope: booking the card receipt at capture (still booked when the line
settles, MKT-1g), and holidays in the business-day calendar (MKT-2f).

## 1. Document

Before this slice a cash-on-delivery sale left the seller's balance negative by the commission (MKT-1g), and nothing
else happened: nobody could record that the seller paid, nobody saw since when it owed, and a seller could owe for
ever while taking new cash orders.

| | Before | After |
|---|---|---|
| The seller pays MaxTheService | no way to record it (a "correction" was the only tool, and it posts to 4510) | **Record payment**: a REMITTANCE line on the seller's statement, the operator's journal Dr 1010 Bank / Cr 2400 |
| A payment that is less than owed | — | needs a note ("Rider still holds one order's cash"), shown on the statement, as a short drawer does at cash-up |
| More than owed | — | refused: "The seller owes Rs X. Enter at most that; record anything more as a correction." |
| The operator | saw a negative balance | **Cash orders: what sellers owe**: cash collected, paid, owes now, owed since, pay by; late sellers first, in red |
| The seller | "You owe MaxTheService Rs X in commission." | and "Please pay MaxTheService Rs X for your cash orders by <date>." (red once late) |
| A seller that does not pay | kept taking cash orders | the same, unless the operator ticks **Stop cash on delivery for a seller that has not paid in time** (off by default); then checkout says "This seller cannot take cash on delivery right now. Please pay online or choose another offer." Paying online still works |

**Owed** is the negative balance itself, never a second sum that could disagree with the statement. **Owed since** is
the day the balance last went below zero and stayed there: a seller that pays part keeps its date (the debt is smaller,
not newer); a seller that pays in full and sells again for cash starts a new clock. **Pay by** = owed since + the
operator's days to pay (default 7, 1–60). **Late** = today is after pay by.

### 1a. Trace (RULE 0)

**Readers of `mkt_settlement_entry`: 6 queries before, 7 now.**

| Query | Wants a REMITTANCE row? |
|---|---|
| `balance(org)` | **yes**: a payment brings the balance back toward zero |
| `balances()` (operator's accounts list) | **yes** |
| `findByOrganizationIdOrderByIdDesc` (statements, and now owed-since) | **yes**: it is a line on the statement |
| `findByOrderLineIdIn` (a statement line's settled figures) | unaffected: a payment has no order line |
| `existsByIdempotencyKey` | yes: `rem:<key>` records a payment once |
| `save` | the writer |
| `totalsOfType(type)` (**new**) | the report's cash collected and paid |

So 4 include it, 1 unaffected, 1 writer, 1 new.

**Writers of the ledger: 3 paths before (the settlement run, a payout marked paid, a correction; all through the one
private `append`, 5 call sites), 4 now** (the payment, a 6th call site). No other class saves a ledger row.
Nothing recomputes the ledger; the balance is a sum.

**Readers of `MarketplaceStatus.LedgerEntryType`**: the column is VARCHAR(24) with no CHECK; `REMITTANCE` (10) fits, so
no migration. The two screens print the type as text (seller's statement, operator's ledger): they show REMITTANCE.

**Callers of the COD rule at checkout: 1** (`MarketplaceCheckoutService.checkout`, cash only, after "does this seller
take cash at all"). The one-shop storefront (`CheckoutService`) is not the marketplace and is unchanged. The new
question runs only when the stop switch is on, so a checkout reads no ledger by default.

**The wire.** `SettlementDTOs.AccountView` gains `cod` (owed, owedSince, payBy, overdue, codStopped). `SettingsView` gains
`codRemitDays`, `codStopWhenOverdue`; `SettingsRequest` gains the same two (a field not sent is left as it is; a
two-argument constructor keeps the old callers). New: `CodRow`, `RemittanceRequest`. The monolith relays the JSON
untouched (`Map<String,Object>`), so no twin DTO.

**Finance.** New event `MKT_REMITTANCE` → `MarketplacePostingRules.remittance(amount)`: Dr 1010 / Cr 2400. Journal source
`MKT_REMITTANCE` (14) fits VARCHAR(20). The event key and the ref check make a redelivery a no-op, as for the other three.

**Defect found by the unit tests before any screen existed:** the first "owed since" walk stopped inside one order's
rows. A cash line settles as SALE (+52,000), COMMISSION (−4,160), COLLECTED_BY_SELLER (−52,000); read newest first, the
balance before the COLLECTED row is positive, so the walk thought the debt began with the newest order. The rows one
order line settled into are now one step, even across a page boundary (`walkAcrossPages`).

## 2. Design

`CodStandingService` (new) answers "what does this seller owe, since when, is it late, is its cash stopped". It is
separate from `MarketplaceSettlementService` so checkout can ask it without the settlement service's other edges. The
walk reads the statement index newest first, 100 rows a page, at most 50 pages.

`MarketplaceSettlementService.recordRemittance`: operator only; amount > 0 and ≤ owed; a reference (bank or receipt)
of at most 80 characters; a note when less than owed; `RM-%06d` from the platform's numbers; key `rem:<key>`; GL event
`MKT_REMITTANCE`; audit `MKT_REMITTANCE_RECORDED`. `codReconciliation()`: every seller that ever collected cash or owes;
late first, then the largest debt.

Settings (platform settings table): `cod.remitDays` (7), `cod.stopWhenOverdue` (false).

## 3. Screens

| Who | Where | What |
|---|---|---|
| Operator | Platform → Settlement and payouts → **Cash orders: what sellers owe** | days to pay, the stop switch; per seller: cash collected, paid, owes now, pay by + "Owed since …" / "Overdue. Owed since …" / "Cash on delivery is stopped for this seller."; amount (filled with what is owed), reference, reason, **Record payment** |
| Seller | Sale → Marketplace → Show statement | yellow "Please pay … by …", red when late, plus "Customers cannot choose cash on delivery from you until you pay." when stopped; REMITTANCE lines in the ledger |
| Customer | checkout, cash | "This seller cannot take cash on delivery right now. Please pay online or choose another offer." (basket: "{seller} cannot take cash on delivery right now. Please remove its items or pay online.") |

## 4. Endpoints

| Method | Path (monolith → service) | Who |
|---|---|---|
| GET | `/platform/mkt/codReconciliation` → `/mkt/operator/settlement/cod` | operator |
| POST | `/platform/mkt/recordRemittance` → `/mkt/operator/settlement/remittance` | operator: `{organizationId, amount, reference, note, idempotencyKey}` |
| GET/POST | `/platform/mkt/settlementSettings` (existing) | operator: adds `codRemitDays`, `codStopWhenOverdue` |

## 5. Tests

- **Unit** (`MarketplaceSettlementServiceTest`, 9 new): owedSinceAndOverdue, newerOrderKeepsTheDate,
  remittanceRecordedOnce, partPayment, newDebtNewClock, remittanceRefusals, codReconciliationRows, walkAcrossPages,
  codOperatorOnly. `MarketplaceOrderFlowTest` (2 new): codStoppedWhenOverdue (refused before any hold; a card still
  works), codStoppedNamedInBasket. Finance `MarketplacePostingRulesTest.remittance`.
- **Gate** `mkt-2d-cod-reconciliation.cy.js`, two phases, because "late" needs days to pass and nothing in the product
  moves the clock:
  1. `--env '{"mkt":"2d"}'`: 2d-01..07 on the usual stack. 2d-07 leaves Shahzad Mobile Shop owing one commission.
  2. The **whole** stack restarted under a clock 12 days later (every service and the monolith, `FAKETIME=+12d`, so
     tokens, holds and dates agree), then `--env '{"mkt":"2d","later":1}'`: 2d-08..10. Then the stack goes back.
  The money check reads the operator's trial balance (2400 moves by exactly what was paid), not the ledger.
  To start from a known state, the gate squares Shahzad Mobile Shop first: it records a payment for what it owes, or
  takes a positive balance to zero with a correction. On a test system that money is test money; it does mean a payout
  left REQUESTED by an earlier 1g run can no longer be paid (row 33 of the live verification: a payout cannot be
  withdrawn).
- **Manual** M-2d-01..03, Mobile Distributor as the seller (so the walk and the gate never share a debt). M-2d-03 runs
  in phase 2.

## 6. Open items

- Stopping cash orders is per seller and all or nothing. A limit ("stop when more than Rs N is owed") is not designed.
- The customer is told to pay online, which needs an account (MKT-1e2); the message does not say so.
- A payment covers the whole debt, not named orders; matching a transfer to orders is not designed.
- Booking the card receipt at capture is still not built (MKT-1g books it at settlement).
