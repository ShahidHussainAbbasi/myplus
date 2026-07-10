# POS / Retail — Standards & Completeness Audit (post-finance)

Purpose: a **current** (after AR/AP/statements/GL) audit of the retail/POS lifecycle end-to-end, so gaps are known
**now** and fixed once in the **shared core** — not rediscovered when pharmacy / e-commerce / education reuse it.
Legend: ✅ done · 🟡 partial · ⬜ missing. Companion: `commerce-verticals-blueprint.md`, `sale-flow-audit-and-backlog.md`.

## 0. The headline — accounting completeness (the "will bite later" risk)
The GL now auto-posts, but **only NEW sales and NEW purchases post** (`SagaSellService`, `PurchaseService`). Every
other money/stock-mutating event is **invisible to the GL and/or AP/AR**, so the books drift the moment they're used:

| Event | Stock | AR/AP | GL journal | Gap |
|---|---|---|---|---|
| New sale | ✅ saga | ✅ due | ✅ SALE | — |
| New purchase | ✅ inventory | ✅ payable | ✅ PURCHASE | — |
| **Sale return** | ✅ inverse saga | 🟡 refund line (SF-5) | ✅ **SALE_RETURN wired** | reverses Sales/Tax/AR/Cash + COGS |
| **Sale edit** | ✅ delta | ✅ recompute | ⬜ **no adjustment** | GL keeps the original amounts (TODO) |
| **Purchase edit** | ✅ delta | ✅ recompute | ⬜ **no adjustment** | GL keeps the original (TODO) |
| **Purchase return** | ✅ reconcile −delta | ✅ payable cut + refund | ✅ **PURCHASE_RETURN** | done end-to-end |
| **Void / cancel** | ⬜ delete only | ⬜ | ⬜ | deletes bypass GL + audit |
| Receive Payment / Pay Vendor | n/a | ✅ | ✅ hook | — |

**Fix-once pattern (do before scaling to other modules):** centralize a **reversing/adjustment journal + AR/AP
delta** on every mutating event, the same way `PostingService` centralizes forward posting. If deferred, pharmacy
dispense-returns, e-commerce RMA, and education fee-reversals each rediscover the same hole.

## 1. Retail/POS lifecycle
| # | Step | UI | API | DB | Grade / gap |
|---|---|:--:|:--:|:--:|---|
| R1 | Onboard org / **store / terminal / register** | 🟡 | 🟡 | 🟡 | org+roles ✅; no store/terminal/register entity (cashier-shift exists) |
| R2 | Catalog (product/category/unit/**barcode**) | ✅ | ✅ | ✅ | barcode field exists; **barcode-scan sell UX ⬜ (commented out)** |
| R3 | Supplier + **PO → GRN → receive** | 🟡 | ✅ | ✅ | single-step purchase only; **no PO/GRN approval workflow** |
| R4 | Opening stock / **stock-take / cycle count** | 🟡 | ✅ | ✅ | adjust/transfer API ✅; **no cycle-count/variance UI** |
| R5 | Counter sale (scan→cart→qty/disc) | 🟡 | ✅ | ✅ | works; barcode-first + cart polish 🟡 |
| R6 | Tax on lines+totals | ✅ | ✅ | ✅ | G3; **multi-rate / inclusive-toggle / tax-filing register ⬜** |
| R7 | Tender (cash/card/credit/split/change) | ✅ | ✅ | ✅ | G5; **split collapses to one method in GL** |
| R8 | Finalize (saga) + **receipt** | ✅ | ✅ | ✅ | thermal receipt ✅ |
| R9 | **Sale return / refund** → inventory | ✅ | ✅ | ✅ | stock+money ✅ (G2/SF-5); **no GL reversal, no credit-note money in GL** |
| R10 | Hold/park + resume; **void line/sale** | 🟡 | 🟡 | 🟡 | park/hold ✅; **void/cancel as audited action ⬜** |
| R11 | Customer attach / **credit sale / store credit** | 🟡 | ✅ | ✅ | AR ✅; **store-credit/overpay ledger ⬜ (SF-5 Model B)** |
| R12 | Discounts / **coupons / loyalty** at POS | 🟡 | 🟡 | 🟡 | line discount only; coupons exist for e-com not POS; **loyalty ⬜** |
| R13 | Cash drawer / shift / X-Z | ✅ | ✅ | ✅ | day-close ✅ |
| R14 | Low-stock / near-expiry alerts on dashboard | 🟡 | ✅ | ✅ | StockAlert + inventory alerts exist; dashboard wiring 🟡 |
| R15 | **Purchase return / debit note** | ⬜ | ⬜ | ⬜ | **not built** (stock-out + AP reduction + GL reversal) |
| R16 | Reports: sales/margin/tax/stock/**financials** | ✅ | ✅ | 🟡 | Sale Detail + margin (SF-10) + AR/AP aging/statements + Trial Balance/P&L/BS ✅; **tax/GST report ⬜** |
| R17 | AR / AP / GL (books) | ✅ | ✅ | ✅ | Receive Payment, Pay Vendor, statements, aging, GL, P&L, BS ✅ |

## 2. Standards flags (cross-cutting — fix in the core, once)
- **Accounting reversal coverage (HIGH):** returns/edits/voids must post reversing journals + AR/AP deltas (see §0).
- **Posting reliability (HIGH):** GL/ledger posts are **best-effort fire-and-forget** — a failure silently drifts the
  books with no retry/reconcile. Standard = an **outbox + retry relay** (mirror the saga recovery relay) or a
  daily reconcile job. Applies to `postEvent`, `recordPayment`, `postPayment`.
- **Idempotency (MED):** sales have SF-3 idempotency keys; **purchase, receivePayment, payVendor, addPurchase,
  postEvent do NOT** → double-submit double-posts money/stock/GL. Extend the idempotency-key pattern to all money ops.
- **Void ≠ delete (MED):** row deletes bypass inventory reversal, AR/AP, GL and audit. Introduce a first-class,
  audited **void/cancel** that reverses everything.
- **Immutable audit log (MED):** only `userId` stamped; no append-only who/when/what on money & stock events.
- **Tax completeness (MED):** single per-product rate applied; no multi-rate, inclusive/exclusive per-org policy in
  all paths, or a **tax-filing (output/input tax) register** — needed for any real jurisdiction.
- **Period close / lock (MED):** GL entries are editable-by-absence-of-lock; add a period close that freezes a range.
- **Voucher/receipt numbering race (LOW):** `count(...)+1` (RCPT-/PV-) can collide under concurrency → per-org sequence.
- **Quantities `Float` (LOW):** stock/qty are `Float`; money is `BigDecimal(19,2)` ✅ — migrate qty to `BigDecimal` for exactness.
- **Multi-currency (LOW / future):** single implied currency; add currency + FX when needed.

## 3. Why this matters for the OTHER modules (the reuse argument)
The verticals **share this core**: pharmacy dispensing = the Sell saga; e-commerce orders = the same trade+inventory;
education fees will post to the same GL. So each gap above is inherited N times. Fixing them **in the core now**
(reversal posting, outbox reliability, idempotency, void, audit log) means pharmacy/e-com/education get correct books
for free. Fixing them later means retrofitting every module + reconciling historical drift.

## 4. Proposed remediation order (highest leverage first)
1. **Purchase Return (R15)** — ✅ **DONE** (`PurchaseService.purchaseReturn`: stock-out via reconcilePurchase −delta
   + AP reconcile + `recomputePayable` + GL `PURCHASE_RETURN`; `/purchaseReturn` + monolith proxy + purchase-row
   Return button/dialog; Cypress `purchase-return.cy.js`).
2. **Shared reversal posting (§0)** — ✅ groundwork DONE: `PostingService` now posts `SALE_RETURN` + `PURCHASE_RETURN`
   (mirror-image journals). **Sale return is WIRED** (`SellController.saleReturn` → `SALE_RETURN`, reverses
   Sales/Tax/AR + Cash refund + COGS/Inventory). **Still TODO: wire `updateSell`/`updatePurchase` edits to post an
   adjustment (reverse-then-repost) — currently the GL keeps the pre-edit amounts.**
3. **Void/cancel** as a first-class audited action (uses #2's reversal).
4. **Posting reliability** — outbox + retry relay for GL/ledger posts (no silent drift).
5. **Idempotency** on purchase/receivePayment/payVendor/postEvent.
6. **Immutable audit log** (money + stock events).
7. Then polish: tax-filing register, period close, store-credit/loyalty, GRN/PO, barcode-first UX, cycle-count.

> Each remains a slice: Document → Design → Implement (UI→API→DB) → mvn → headed Cypress → next.
