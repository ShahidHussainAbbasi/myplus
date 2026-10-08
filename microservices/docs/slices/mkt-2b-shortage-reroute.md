# Slice MKT-2b — a part its seller does not fulfil: the cause, the reroute, the shopper's approval

**Status:** BUILT 2026-10-08. Unit-green (marketplace-service 374 tests, 0 failures; 11 new). `FlywayMigrationTest` 6/6
on MySQL with V32. Gate `mkt-2b-shortage-reroute.cy.js` **8/8 on a live stack**; every earlier gate re-run green (0a–2a 83/83). Manual
cases M-2b-01..07 **walked and recorded** ([live verification](../marketplace/live-verification-2026-10-03.md) §10).

Requirements: **MKT-R11.1** (a short part is rerouted), **MKT-R11.2** (anything the shopper would notice needs their
approval), **MKT-R11.3** (never another variant without them), **MKT-R11.4** (the cause and the responsible party are
recorded), **MKT-R12.4** (a recorded cause is never a debit). Depends on MKT-2a (parts per seller, `follow`, refunds per
part). Out of scope: partial fulfilment of a part (`PARTIALLY_FULFILLED`), live routing deadlines (MKT-2-05) and
value-based acceptance terms (MKT-2-06).

## 1. Document

When a seller rejects its part of an order, or does not accept it in time, MaxTheService now keeps a record: what
went wrong (the cause), whose side it was on (the party), and what happened to the customer. The record is information
only. Nobody is charged for it. The seller can dispute it, and MaxTheService decides. The customer never sees the
cause; they hear about a shortage only when another seller is being looked for.

The operator can also turn on **Platform → Marketplace policies → "When a seller cannot fulfil a part, find another
seller"**. It is **off by default**. When it is on, MaxTheService looks for another seller of the **same phone**:

| The other seller | What happens |
|---|---|
| Same price or lower, and delivers no later | The part moves to them without asking. A card gets the difference back. |
| Dearer, or delivers later | The customer sees the offer on their order page and chooses within **30 minutes**. |
| Dearer, and the customer paid by card | Not offered: the card is never asked for more. |
| Nobody | Only that part ends, and its money goes back. If it was the last part, the order is cancelled. |

The customer can still cancel the whole order while an offer waits for them.

### 1a. Trace (RULE 0)

**New table `mkt_shortage` (V32).** One row per unfulfilled part (`uk_mkt_shortage_part`).
- Writers: `begin` (inside the reject and expiry transactions), `reassign`, `propose`, `accept`, `finish`,
  `cancelWithOrder`, `releaseProposal`, `dispute`, `rule`. That is 9 writers, all in `MarketplaceShortageService`.
- Readers outside the service: 3. They are the shopper's order view (`MarketplaceCheckoutService.parts`), the seller's
  list and row (`SellerOrderService.mine`, `viewOf`). The operator's list reads it inside the service.

**New column `mkt_seller_order.shortage_pending`.**
- Readers: 2. `follow` counts a pending part as waiting, so the order does not end while another seller is being tried.
  The account view lets the customer cancel while a part is pending or an offer waits for them.
- Writers: 5 (`begin`, `reassign`, `accept`, `finish`, `cancelWithOrder`). Each clears it in the same transaction that
  records the outcome.

**A replacement part is an ordinary part** (`replaces_seller_order_id` set). Each of MKT-2a's 8 readers of
`mkt_seller_order` was checked against it:

| Reader | The replacement part |
|---|---|
| Order page and tracking | shown as its own row; the short part's row says where it went |
| Seller's Incoming list, accept and reject | the new seller sees, accepts or rejects it like any part |
| Expiry sweeper | it has its own accept-by time, and a missed one is itself recorded and rerouted |
| Orphan sweeper (UNASSIGNED older than 2 minutes) | unaffected: the part is saved already OFFERED |
| Customer cancel | ends it with the other waiting parts |
| Support | the case goes to the new seller for its lines |
| Delivery hook | unaffected (per part) |
| Settlement | its lines settle; the short part's lines never deliver, so they never settle |

That is 6 readers that include it and 2 that are unaffected.

**The money.** The charge has one key. A part refund is keyed `part:{sellerOrderId}` (MKT-2a). The difference on a
cheaper move is refunded under the **short** part's key. If the new part ends later, its own total is refunded under
its own key, so the two never collide. The order's total follows what the customer now pays (`reprice`).

**The hold.** An offer to the customer holds the other seller's stock under its own key, kept on the shortage row (not on
a part, because the orphan sweeper would cancel an UNASSIGNED part after 2 minutes). Accepting hands the hold to the new
part. Declining, expiry and a customer cancel release it. A failed release is retried by the sweeper
(`proposal_held = 1`).

**The wire.** `RejectRequest` gained `cause` (the 2-argument constructor is kept). `PartView` gained `shortage`.
`SellerOrderView` gained `shortage`. `AcceptWindow` gained `reroute` (the 1- and 2-argument constructors are kept).

## 2. Design

### 2.1 Record (inside the transaction that ends the part)

`begin(part, parent, parts, cause, evidence, reasonIfEnded)` writes the record, then decides:
- **Switch off, or the order is already cancelled:** the order follows as before, and the record's result is
  `LINE_CANCELLED` or `ORDER_CANCELLED` at once.
- **Switch on:** the part is marked `shortagePending`, the record is `PENDING`, and `resolve` runs after the commit.

The seller names the cause when it rejects: `MERCHANT_STALE_STOCK` (the default), `SUPPLIER_STALE_STOCK` or
`PLATFORM_SYNC_DEFECT`. The clock records `NO_RESPONSE` itself. A seller can never pick it.

### 2.2 Resolve (after the commit)

1. **Candidates.** The same canonical products, from sellers not already in this order, eligible for the city and the
   quantity exactly as the catalogue shows them. A seller must be able to supply every line of the part. At most 3 are
   tried.
2. **Silent** (`SubstitutionPolicy`: price ≤ and promise ≤): hold, then a new part OFFERED with its own accept-by time.
   Result `REASSIGNED`.
3. **Otherwise:** hold, then the offer waits on the record for 30 minutes. Result `SUBSTITUTION_REQUESTED`.
4. **None,** or every hold refused: the part ends and its money goes back.

A crash between the steps leaves the record `PENDING`. The sweeper retries it after 2 minutes and ends it after 3
attempts. Every transition re-reads the row and checks `PENDING` under `@Version`, so two runs cannot both act.

### 2.3 The customer answers

- **Accept:** `POST /marketplace/public/orders/{no}/shortages/{id}/decision` with `{phone, accept: true}`, proven by
  the order's phone as tracking is. From My orders, `/marketplace/account/orders/{no}/shortages/{id}/decision` proves
  it by the account instead. The new part is OFFERED to the other seller at the price the customer saw.
- **Decline**, or no answer in 30 minutes: the part ends, the hold is released, and the money goes back.
- **Cancel the order:** every pending or offered record ends with it (`CANCELLED`).

### 2.4 Dispute and ruling (no money)

The seller disputes a `RECORDED` cause, giving a reason. The operator decides `UPHELD` or `OVERTURNED`, also with a
reason the seller sees. Neither step touches a payment, the ledger or a payout.

## 3. Screens

| Who | Where | What |
|---|---|---|
| Customer | order page, My orders | "Looking for another seller"; "Moved to {seller} at the same or a lower price"; the offer with its price difference, promise, countdown, Accept and Decline; "You chose…", "You declined…" |
| Seller | Incoming orders | a Cause list beside Reject; on a rejected or expired part, the cause and its status, "Dispute this cause"; a "Rejected (cause and disputes)" filter |
| Operator | Platform → Marketplace policies | the reroute switch, with its hint |
| Operator | Platform → Unfulfilled parts | Disputed / Recorded / All; cause, the seller's words, what happened for the customer, minutes to resolve; Uphold or Overturn with a reason |

## 4. Endpoints

| Method | Path | Who |
|---|---|---|
| POST | `/marketplace/public/orders/{no}/shortages/{id}/decision` | anyone with the order's phone |
| POST | `/marketplace/account/orders/{no}/shortages/{id}/decision` | the signed-in owner of the order |
| POST | `/mkt/rejectOrder` | seller: now takes `cause` |
| POST | `/mkt/shortageDispute` | seller: `{id, note}`, its own records only |
| GET | `/platform/mkt/shortages` | operator: `?status=&page=&size=` |
| POST | `/platform/mkt/shortageDecide` | operator: `{id, outcome, note}` |
| GET/POST | `/platform/mkt/acceptWindow` | operator: `reroute` |

## 5. Tests

- **Unit** (`MarketplaceOrderFlowTest`): switchOffRecordsCauseOnly, silentReassign, proposalAccepted,
  proposalDeclined, proposalExpires, cardNeverAskedMore, cashMayBeAskedMore, noCandidateEndsTheLine,
  cancelDuringProposal, expiryReroutes, disputeAndRuleMoveNoMoney.
- **Gate:** `mkt-2b-shortage-reroute.cy.js`, cases 2b-01..08. The placeholder cases MKT-2-02..04 moved here.
- **Manual:** M-2b-01..07 in the walk, step by step, each with its cleanup.

## 6. Open items

- A part is moved whole. Splitting it across two sellers (`PARTIALLY_FULFILLED`) is not designed.
- A card order is only offered alternatives that cost no more, because the card token is not kept. Asking a card holder
  to pay a difference would need a stored payment method.
- A dispute changes nothing automatically. Using upheld causes in seller scoring is a later slice.
