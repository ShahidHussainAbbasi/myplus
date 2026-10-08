# Slice MKT-2f — settlement reports and the bank-holiday calendar

**Status:** BUILT 2026-10-08. Unit-green (marketplace-service 422 tests, 0 failures, 13 new). Migration V34 (one table,
one index), applied on the live database. Gate `mkt-2f-settlement-reports.cy.js` **8/8 on a live stack**. Manual cases
M-2f-01..03 recorded.

Requirement: **MKT-R20.3** ("settlement reports"), with R15.1 (a line is payable on a BUSINESS day), R15.6 (the ledger
is the only record of money: a report reads it, never a copy) and R22.1 (the operator's screens are the operator's).
Depends on MKT-1g (ledger, statement, T+n) and MKT-2d (cash orders: collected and paid in). Out of scope: a stored or
emailed monthly report, per-tenant calendars (one calendar: MaxTheService pays from one bank), and holidays imported
from a public calendar.

## 1. Document

| | Before | After |
|---|---|---|
| The operator | a balance per seller and one seller's ledger at a time; nothing over a period, nothing to hand the bank | Platform → **Settlement and payouts** → **Settlement report**: for any period of up to 366 days (this month by default), one row per seller: **Closing** first, then Opening, Sales, Commission, Fees and tax, Reserve, Refunds, Corrections, Cash kept by seller, Paid in by seller, Paid out, Lines, and **All sellers**. **Download CSV** gives the same table |
| The operator | payable days skipped Saturday and Sunday only: on Eid a line became "payable" on a day the bank is shut | **Bank holidays**: add a future weekday with a name; a line whose payable day it is becomes payable on the next business day. Only a day after today can be added or removed |
| The seller | its statement lines and ledger | the statement adds **Period summary**: its own row of the report, with "Owed to you at the start/end" or "You owe MaxTheService at the end" |
| The statement | a delivered line read "Not delivered yet" until the next settlement check (every 10 minutes) | it reads "Delivered" (found on screen in this slice's walk) |

**The figures.** Every value is from the seller's side: credit − debit of its ledger rows whose `effective_at` falls in
the period. Each ledger type is in exactly one column:

| Column | Entry types |
|---|---|
| Sales | SALE |
| Commission | COMMISSION |
| Fees and tax | DELIVERY_FEE, PROCESSING_FEE, TAX |
| Reserve | RESERVE, RESERVE_RELEASE |
| Refunds | REFUND, REVERSAL |
| Corrections | ADJUSTMENT |
| Cash kept by seller | COLLECTED_BY_SELLER |
| Paid in by seller | REMITTANCE |
| Paid out | PAYOUT |

Opening = the seller's balance before the period. **Closing = Opening + every column**, which is the seller's balance at
the end of the period. Lines = SALE rows. A type added later without a column fails `everyTypeHasAColumn` at build time.

**A day is a day in Pakistan.** The ledger writes `effective_at` with `LocalDateTime.now()` under the UTC default zone
(`UtcDefaultTimeZone`); the operator picks a day in the tenant's zone. 1 October starts at 30 September 19:00 in the
ledger (`SettlementReportService.startOf`). Found by the trace, before any test: without it a sale at 01:00 on the 1st
would have been reported in September.

### 1a. Trace (RULE 0)

**Readers of `mkt_settlement_entry`: 6 before this slice, 2 added.** `balance`, `balances`, `totalsOfType` (2d),
`findByOrderLineIdIn` (statement), `findByOrganizationIdOrderByIdDesc` (account), `existsByIdempotencyKey`: none
changes. Added: `periodTotals(from, to)` and `balancesBefore(before)`, both read-only, both over
`effective_at` (V34 index `idx_mkt_entry_effective (effective_at, organization_id)`; before it none).

**Writers.** The report writes nothing. Holidays write `mkt_holiday` only, and an audit event (`MKT_HOLIDAY_ADDED`,
`MKT_HOLIDAY_REMOVED`).

**Callers of `eligibleOn`: 2, both moved to the calendar.** `settleLine` (the run) and `statement` (the screen). Each
builds the calendar once per call (`calendar.calendar()`, one query, tens of rows a year), so the run and the screen
always agree. Before, both used a static weekends-only calendar.

**Other users of a calendar: 0.** `BusinessDayCalendar` is used only by `MarketplaceSettlementService`. `CodStandingService`
(2d, pay-by date) counts calendar days, by its own rule, and is unaffected. `T+n` in 1g counts business days via the same
`eligibleOn`.

**Why only a future day.** A line's payable day is computed, never stored: walking forward from the end of its return
window, `eligibleOn` reads only days up to its result. A line already payable has a result of today or earlier, so a
holiday added or removed after today cannot move it, and no line's history changes. A day on or before today is refused.

**The wire.** New `SettlementDTOs.ReportRow`, `SettlementReport`, `HolidayView`, `HolidayRequest`. The monolith relays the
JSON untouched (`Map<String,Object>`), so no twin DTO. `from`/`to` are ISO dates passed through.

**Column types.** `mkt_holiday`: `holiday_date DATE` ↔ `LocalDate` (the id), `name VARCHAR(80)` ↔ `String` (length 80),
`created_by_user_id BIGINT` ↔ `Long`, `created_at DATETIME(6)` ↔ `LocalDateTime`. The service started under
`ddl-auto=validate` after V34 (checked: `show create table`, flyway history 34 success).

## 2. Design

`SettlementReportService`: `operator(from, to)` (operator only), `mine(from, to)` (the caller's own organisation),
static `rows(...)` and `total(...)` (pure). `SettlementCalendarService`: `calendar()`, `list()`, `add()`, `remove()`
(operator only for the last three). Refusals, in a sentence: "The start of the period is after its end.", "Choose a
period of at most 366 days.", "Choose the day.", "Give the holiday a name, for example "Eid ul-Fitr".", "Keep the name
under 80 characters.", "A holiday can be added only for a day after today: lines already payable keep their day.",
"That day is a weekend: nothing is paid on it anyway.", "That day is already a holiday.", "That day is not a holiday.",
"A holiday on or before today cannot be removed: lines were made payable around it."

The CSV is built in the browser from the figures on screen (with a byte-order mark so Excel reads it as UTF-8): the file
and the table cannot differ.

## 3. Screens

| Who | Where | What |
|---|---|---|
| Operator | Platform → Settlement and payouts → **Settlement report** | From / To, Show, Download CSV; Closing second (13 columns do not fit: the breakdown scrolls) |
| Operator | Platform → Settlement and payouts → **Bank holidays** | Day, Name, Add holiday; the list, Remove on a future day, "Past: kept" on the others |
| Seller | Sale → Marketplace → Statement → **Period summary** | From / To, Show; the 11 figures of its row |

## 4. Endpoints

| Method | Path (monolith → service) | Who |
|---|---|---|
| GET | `/platform/mkt/settlementReport?from=&to=` → `/mkt/operator/settlement/report` | operator |
| GET | `/mkt/settlementReport?from=&to=` → `/mkt/settlement/report` | the seller, its own row |
| GET | `/platform/mkt/holidays` → `/mkt/operator/settlement/holidays` | operator |
| POST | `/platform/mkt/addHoliday` {date, name} → `/mkt/operator/settlement/holidays` | operator |
| POST | `/platform/mkt/removeHoliday` {date} → `/mkt/operator/settlement/holidays/remove` | operator |

## 5. Tests

- **Unit** `SettlementReportServiceTest` (7): everyTypeHasAColumn, rowsAddUp, openingOnly, period_, tenantDay,
  periodRefused, access. `SettlementCalendarServiceTest` (4): addAndCalendar, addRefused, remove, operatorOnly.
  `MarketplaceSettlementServiceTest`: holidayIsSkipped, runUsesTheHolidays (2 new).
- **Gate** `mkt-2f-settlement-reports.cy.js` (8), `--env '{"mkt":"2f"}'`. Report figures are checked against the ledger
  (each seller's closing = its balance; one period's closing = the next one's opening) or as differences (a correction of
  Rs 12.34 moves Corrections and Closing by exactly that, and nothing else; undone by the opposite correction). 2f-08
  builds its own line with 3 return days, adds a holiday on its payable day, and removes it again.
- **Manual** M-2f-01..03.

## 6. Open items

- On the test stack the report's Closing for today and the account balance differ (Shahzad Mobile Shop: −226,720 and
  −4,160). Checked: the difference, 222,560, is exactly the ledger rows dated after today, written by earlier runs under
  a later faked clock. In production `effective_at` is the time of writing, so no row is dated after today. Gate 2f-02
  compares with a period ending 60 days ahead, where the two agree for every seller.
- The ledger screens (statement, operator ledger) show `effective_at` as its UTC date, so a row written between 00:00
  and 05:00 in Pakistan shows the day before. Pre-existing (1g); the report itself uses the tenant's day.
- Holidays are one list for the whole marketplace. A seller in a region with a different bank holiday is paid by
  MaxTheService's bank, so its calendar is the one that matters.
