# Store credit (SF-5 Model B)

Closes the store-credit item (SF-5 Model B, deferred) + audit R11. Cadence: Document → Design → Implement → Cypress →
next. Loyalty **points** are explicitly OUT of this slice (a clean follow-up) — this delivers store credit end-to-end.

## 1. Problem
Today a return/void that leaves the invoice **overpaid** hands cash back (a `REFUND` tender — "Model A"). Many
retailers instead keep the money and issue **store credit** the customer redeems on a later sale. There is no customer
credit balance, no `STORE_CREDIT` tender, and no liability account for "we owe the customer goods". Store credit is a
**liability** (org owes the customer) that must post to the GL and be redeemable at checkout.

## 2. Model — a credit ledger + a cached balance (mirrors AR)
- **`Customer.creditBalance`** (BigDecimal, default 0) — cached store-credit the customer holds (fast reads, like
  `dueAmount`). Recomputed from the ledger (`recomputeCredit`), never trusted from the client.
- **`store_credit_txn`** ledger (business Flyway V24): `id, organization_id, user_id, customer_id, amount` (+ issue /
  − redeem), `reason` (RETURN / REDEEM / ADJUST), `ref` (invoice no), `store_id`, `dated`. The balance = Σ amount per
  customer; audit-defensible + reversible. `StoreCreditService`: `issue(customerId, amount, ref)`,
  `redeem(customerId, amount, ref)` (rejects > balance), `balance(customerId)`, `recomputeCredit`.

## 3. Issue — a return refunded as credit (opt-in)
The return/void UI gains a **"Refund as: Cash | Store credit"** choice → request param `refundAs` (default **CASH**, so
current behaviour is unchanged). When `refundAs=CREDIT`, the overpayment that would have been a `REFUND` tender instead:
`storeCreditService.issue(customerId, overpay, invoiceNo)` → +balance, and the GL leg credits the liability (see §5)
rather than cash. Everything else (inventory restore, AR recompute, audit, per-line reversal) is unchanged.

## 4. Redeem — a STORE_CREDIT tender at checkout
- Add **`STORE_CREDIT`** to `PaymentMethod`. In `settle`, it **counts as paid** (real value, unlike `CREDIT`=on-account).
- Server validates: a `STORE_CREDIT` tender is capped at `min(customer.creditBalance, amountDue)`; over-redemption is
  rejected (never trust the client amount). On a successful sale, `storeCreditService.redeem(customerId, amount, inv)`
  → −balance. The checkout UI shows the customer's available credit and offers "Apply store credit".
- Guard: store credit needs a customer attached (no anonymous redemption).

## 5. GL — a new liability account + posting split
- **COA `2200` "Store Credit"** (LIABILITY, normal CREDIT) added to `DEFAULT_COA` (+ `ensureDefaults` seeds it for
  existing orgs on next post).
- `PostingEventRequest` gains **`storeCredit`** (the amount of this event funded by / issued as store credit).
  - **SALE** with a store-credit tender: the redeemed portion debits **2200** (reduce the liability) instead of Cash —
    `Dr 2200 (storeCredit) + Dr Cash (paid−storeCredit) + Dr AR (rest) = Cr Sales + Cr Tax`.
  - **SALE_RETURN** refunded as credit: the issued portion credits **2200** instead of Cash —
    `Dr Sales + Dr Tax = Cr 2200 (storeCredit) + Cr Cash (refund−storeCredit) + Cr AR (rest)`.
  - `storeCredit = 0` (the default) → today's postings exactly, byte-for-byte (no regression).

## 6. Scope by layer
- **commerce-contracts:** `PostingEventRequest.storeCredit`.
- **finance-service:** COA `2200`; `PostingService.postSale`/`postSaleReturn` split the store-credit leg to `2200`.
- **business-service:** `Customer.creditBalance` (Flyway V24) + `StoreCreditTxn` entity/repo + `StoreCreditService`;
  `PaymentMethod.STORE_CREDIT` (+ `settle` counts it paid); `SagaSellService` — validate+redeem store credit, pass
  `storeCredit` on the SALE event; `SellController.saleReturn`/`voidSell` — `refundAs=CREDIT` → issue + `storeCredit`
  on the SALE_RETURN event; `GET /customerCredit?customerId=` (balance for checkout). Reversal safety: a void of a sale
  that redeemed credit re-issues it; a void of a credit-issuing return claws the credit back (guard if already spent —
  reject like the partial-return guard).
- **monolith:** checkout — show available credit + "Apply store credit" tender (writes a STORE_CREDIT tender);
  return/void dialog — "Refund as Cash / Store credit"; customer grid — a Credit column; receipt — store-credit
  applied + new balance. Proxies: `/customerCredit`.
- **Cypress `store-credit.cy.js`:** overpay return → issue credit (balance rises, GL 2200 credited); next sale redeems
  it (paid via STORE_CREDIT, balance falls, GL 2200 debited); over-redeem rejected; cash-refund path unchanged.

## 7. Decisions for sign-off
- **D1** ledger + cached balance (§2) vs. a bare `creditBalance` field. Rec: **ledger + cached** (audit + reversibility,
  mirrors AR — the AR side already burned us when a balance was clobbered without a ledger).
- **D2** `refundAs` default **CASH** (opt-in credit) so nothing changes unless chosen. Rec: yes.
- **D3** GL liability `2200` + `storeCredit` split, default 0 = no regression (§5). Rec: yes.
- **Loyalty points: OUT** (separate slice). Rec: yes.

## 8. Status: IMPLEMENTED
- **commerce-contracts + finance:** `PostingEventRequest.storeCredit`; COA `2200` "Store Credit"; `PostingService`
  postSale (Dr 2200 for redeemed) / postSaleReturn (Cr 2200 for issued) split — `storeCredit=0` ⇒ unchanged postings.
- **business:** `Customer.creditBalance` + `StoreCreditTxn`/`StoreCreditRepo` + `StoreCreditService`
  (issue/redeem/balance/recomputeCredit, `CustomerRepo.updateCreditBalance` targeted); `PaymentMethod.STORE_CREDIT`
  (settle already counts it paid); Flyway **V24** (credit_balance + store_credit_txn + payment.method enum);
  `SagaSellService` caps+redeems a STORE_CREDIT tender + `storeCredit` on the SALE event; `saleReturn` `refundAs=CREDIT`
  issues + `storeCredit` on SALE_RETURN; `voidSell` re-issues the credit portion (rest cash); `updateSell` rejects
  editing a store-credit sale; `GET /customerCredit`; `CustomerDTO.creditBalance`.
- **monolith:** `/customerCredit` proxy; return dialog "Refund overpayment as Cash/Store credit" (`refundAs`); checkout
  "Store credit (N avail)" field (shown on customer-select via `/customerCredit`) → STORE_CREDIT tender + selected
  `customerId`; `calculateChange` + submit-guard count applied credit.
- **Cypress `store-credit.cy.js`:** return→issue (balance +100, GL 2200 credited) → redeem 60 (balance 40, 2200
  debited, receipt shows storeCreditApplied) → over-redeem capped to 0.
- **Polish (done):** customer grid **Credit** column (`CustomerDTO.creditBalance` already carried); receipt "Store
  credit applied / Store credit balance" lines (`CustomerHistoryDTO.storeCreditApplied` populated in `getReceipt` from
  the Σ STORE_CREDIT tenders). **Loyalty points** remain a separate future slice.
