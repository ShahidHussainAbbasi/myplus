# Slice MKT-1g — commission, the settlement ledger, T+N eligibility, manual payouts, GL posting

**Status:** BUILT 2026-10-04 — unit-green (marketplace-service 350/350 with `FlywayMigrationTest` run against MySQL 8,
`Skipped: 0`; finance-service 76/76 incl. `MarketplacePostingRulesTest`). Gate `mkt-1g-settlement.cy.js` **6/6 on a live stack** and manual
cases M-1g-01..06 **walked and recorded** 2026-10-04 ([live verification](../marketplace/live-verification-2026-10-03.md) §8).

Requirements: **MKT-R15.1** (T+N business days), **MKT-R15.2** (never payable before delivery and the return
conditions), **MKT-R15.3** (trigger), **MKT-R15.5** (the split reconciles), **MKT-R15.6** (immutable ledger),
**MKT-R16.1–16.3** (account, entries, payouts with approval and bank reference), **MKT-R22.3** (idempotency keys,
immutable entries). Depends on MKT-1e (orders, snapshots), MKT-1e2 (card payments), MKT-1f (returns, delivery date).
Rulings applied: R-MKT-2 (both directions), R-MKT-3 (operational ledger here, journals through finance).

## 1. Document

MaxTheService earns a commission on every order it routes, and owes each seller the rest of what the customer paid,
but only once the sale can no longer be undone: delivered, and the return days the line was sold with have passed.
Until then nothing is owed and nothing is in the ledger. Paid online, the platform holds the money and pays the
seller out; paid in cash, the seller's rider holds it and the seller owes the commission. Both sit in **one** running
balance per seller, which the operator pays out by hand, with a second operator approving.

### 1a. Trace (RULE 0)

| What | Where it was | 1g |
|---|---|---|
| Settlement arithmetic | `SettlementCalculator` + `CommissionPolicy` (MKT-1a), unit-tested, **no caller** | called per line by `MarketplaceSettlementService.figures` |
| Commission snapshot | `mkt_order_line.commission_basis/rate/fixed` (V28) | read from the snapshot, never from today's policy |
| Return days | `mkt_order_line.return_days` (V28) | the window, from the snapshot |
| Delivered at | `mkt_seller_order.delivered_at` (V30, `MarketplaceDeliveryHook`) | the trigger date T |
| Refunds on a line | `mkt_return` REFUNDED rows (V30) | the line settles net of them; commission only on what was kept |
| `settlement_status` | V28, always `NOT_ELIGIBLE`; machine in `MarketplaceStateMachines.SETTLEMENT` | moved by the sweeper and payouts only |
| GL accounts for commission / seller balances | **none** in finance's chart | 2400, 4500, 4510 added to `DEFAULT_COA` (back-filled by `ensureDefaults`) |
| A finance client in marketplace-service | **none** | `MarketplaceFinanceConfig`, called only by the outbox |

## 2. Design

### 2.1 Data (V31)

- `mkt_settlement_entry` — append-only: seller org, line or payout, `entry_type` (`LedgerEntryType` + new
  `COLLECTED_BY_SELLER`), debit, credit, ref, memo, unique `idempotency_key`. Every JPA column `updatable = false`;
  the repository is a bare `Repository` with `save` and reads only. Balance = `SUM(credit) − SUM(debit)`.
- `mkt_payout` — `PO-` number, seller, requested/approved amount, `REQUESTED → APPROVED → PAID`, bank reference,
  unique idempotency key, requester / approver / payer, `@Version`.
- `mkt_gl_outbox` — the operator's journals on their way to finance (the `expense_outbox` shape: whole JSON payload).
- `mkt_order_line.payout_id` — the payout that settles the line. Index `(settlement_status, seller_organization_id, id)`.

### 2.2 The split of one line

```
customer amount = line total + the order's delivery fee (first line) − refunds on returns
commission      = the snapshot's policy on the items kept (ITEMS / ITEMS_PLUS_DELIVERY / FIXED), never above them
payable         = customer amount − commission − delivery retained − fees − tax − reserve − adjustment
```

Phase 1 values: the seller's rider delivers, so the delivery fee is the seller's (retained 0); no processing fee,
reserve or commission tax is configured (the calculator carries them; tax waits for R-MKT-9). A change-of-mind
pickup fee the customer paid stays with the seller, whose rider did the pickup.

Worked example (unit test `workedExample`): goods 4,800 + delivery 200 = 5,000; 10% of items = 480; payable 4,520.
The source's flat Rs 500 shape is `fixedCommission`: payable 4,500 (4,150 in the source includes a processing fee
and reserve Phase 1 does not charge).

### 2.3 Eligibility (the sweeper, every 10 minutes, and the operator's "Settle what is due now")

```
not delivered                         NOT_ELIGIBLE
a return REQUESTED/APPROVED/RECEIVED  ON_HOLD
today < eligibleOn                    PENDING_RETURN_WINDOW
otherwise                             ELIGIBLE  → ledger rows + operator journal

eligibleOn = (delivered date + snapshot return days, 7 if none) + T+N business days, rolled to a business day
```

T+N is the operator setting `settlement.tPlusDays` (default 1, 0–10). Weekends are Saturday/Sunday; there is no
holiday list yet (MKT-2f). Each line settles in its own transaction; the rows' idempotency keys make a repeated or
racing run write each row once. Trigger: `DELIVERED_PLUS_RETURN_WINDOW` only in Phase 1 (R15.3's other triggers wait
for platform stock and consignment).

Ledger rows when a line becomes ELIGIBLE (key `line:{id}:…`): `SALE` credit = customer amount, `COMMISSION` debit,
and for cash on delivery `COLLECTED_BY_SELLER` debit = customer amount (the seller already has the cash, so it now
owes the commission: balance −480 in the example).

### 2.4 Payouts (four eyes)

1. Operator 1 **requests** a payout of the seller's whole positive balance (idempotency key; one in flight per
   seller; a zero or negative balance is refused in words). The seller's ELIGIBLE lines are tagged with it.
2. Operator 2 **approves** (the requester is refused: "Another person must approve this payout: you requested it.").
   Lines → APPROVED.
3. Anyone **marks it paid** with the bank reference: a `PAYOUT` debit row, lines → PROCESSING → PAID, the journal.
   Refused if a correction since approval left the balance below the payout.

Corrections: `ADJUSTMENT` rows (signed, reason required, idempotency key). There is no PUT/DELETE on a ledger row.

### 2.5 The operator's books (R-MKT-3)

Finance stays the only journal writer. The org whose ledger takes the commission is the platform setting
`settlement.booksOrg`, set by "Book commission in my organisation" or by an operator's first settlement action.
Until it is set, due lines wait (`waitingForBooks` in the run result) rather than settling into nobody's books.

| Event | Journal (finance `MarketplacePostingRules`) |
|---|---|
| `MKT_SETTLEMENT` | Dr 1010 cash the platform holds · Cr 4500 commission · Cr/Dr 2400 the difference |
| `MKT_ADJUSTMENT` | +x: Dr 4510 / Cr 2400 · −x: Dr 2400 / Cr 4510 |
| `MKT_PAYOUT` | Dr 2400 / Cr 1010 |

2400 **Marketplace Seller Balances** carries both directions, so it always equals the sum of the sellers' balances.
The card receipt is booked when the line settles, net of refunds; booking it at capture is not built yet (MKT-2d left it out).

### 2.6 Endpoints (monolith flat route → marketplace-service)

| Monolith | Service | Who |
|---|---|---|
| `GET /mkt/statement?status=` | `GET /mkt/settlement/statement` | seller (own org) |
| `GET /mkt/settlementAccount` | `GET /mkt/settlement/account` | seller |
| `GET /platform/mkt/settlementAccounts` · `settlementAccount?organizationId=` | `/mkt/operator/settlement/accounts[/{org}]` | operator |
| `POST /platform/mkt/runSettlement` | `POST /mkt/operator/settlement/run` | operator |
| `POST /platform/mkt/adjustLedger` | `POST /mkt/operator/settlement/adjust` | operator |
| `GET/POST /platform/mkt/settlementSettings` | `/mkt/operator/settlement/settings` | operator |
| `GET /platform/mkt/payouts` · `POST requestPayout` · `approvePayout` · `markPayoutPaid` | `/mkt/operator/payouts[/{id}/approve|mark-paid]` | operator |

### 2.7 Screens

Seller: Sale → Marketplace → **Settlement statement** (`#mktStatementTab`, `#mktStatementTable tr.mkt-line`, balance,
ledger). Operator: Platform dashboard → **Settlement and payouts** (T+N, books, settle now, balances, request /
approve / mark paid, ledger with "Record correction"). Six bundles.

### 2.8 Audit

`MKT_LINE_SETTLED`, `MKT_PAYOUT_REQUESTED`, `MKT_PAYOUT_APPROVED`, `MKT_PAYOUT_PAID`, `MKT_LEDGER_ADJUSTED`,
`MKT_SETTING_CHANGED`, each filed under the seller's org (the subject).

## 3. Open

- **R-MKT-8** (commission base): unchanged — the policy carries the basis; the gate's default policy is ITEMS.
- **R-MKT-9** (who invoices commission, and its tax): not built. Until ruled, commission tax is zero.
- Collecting what a cash-on-delivery seller owes (a negative balance): built in [MKT-2d](mkt-2d-cod-reconciliation.md).
- A holiday calendar and settlement reports are MKT-2f.
- Found live: a waiting line's payable date follows the current T+N; a payout request cannot be withdrawn (live verification §8).

## 4. Plan

1. ✅ V31 + entities + repositories; `FlywayMigrationTest` against MySQL 8.
2. ✅ finance: accounts 2400/4500/4510, `MarketplacePostingRules` + test, three event types.
3. ✅ `MarketplaceSettlementService` + `MarketplaceGlOutboxService` + controller; 17 unit tests.
4. ✅ Monolith relays, seller statement, operator payouts panel, i18n.
5. ✅ Gate rewritten without a test-only route (`/test/mkt/deliverAndSettleOne` is gone: the gate settles a real
   order sold under a 0-day policy with T+0).
6. ✅ Live run of the gate (6/6), recorded walk M-1g-01..06, live-verification §8.
