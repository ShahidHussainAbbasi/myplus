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
| **Sale edit** | ✅ delta | ✅ recompute | ✅ **reverse+repost** | `updateSell` posts SALE_RETURN(old)+SALE(new) |
| **Purchase edit** | ✅ delta | ✅ recompute | ✅ **reverse+repost** | `updatePurchase` posts PURCHASE_RETURN(old)+PURCHASE(new) |
| **Purchase return** | ✅ reconcile −delta | ✅ payable cut + refund | ✅ **PURCHASE_RETURN** | done end-to-end |
| **Void / cancel** | ✅ reverse | ✅ recompute | ✅ **SALE_RETURN / PURCHASE_RETURN** | `voidSell`/`voidPurchase`; hard-delete retired; soft VOID + read-only |
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
- **Posting reliability (HIGH):** ✅ **DONE for business→finance `postEvent`** — transactional outbox (`gl_outbox`,
  V16) + `GlOutboxService` (enqueue in-tx + `@TransactionalEventListener(AFTER_COMMIT)` real-time delivery +
  `@Scheduled` retry relay via `runAs`). The last hop is behind a `GlEventPublisher` seam (`HttpGlEventPublisher`
  today, `gl.publisher` flag) so a broker (Redis Streams — already in the stack — / Rabbit / Kafka) is a drop-in
  later, not a rewrite. Producers (sale/purchase/returns/edits) now enqueue instead of fire-and-forget → no silent
  drops. **Remaining:**
  finance's intra-service `recordPayment`→`postPayment` hook (lower risk, same JVM) could get a flag/retry later.
- **Idempotency (MED):** ✅ **DONE** — shared `IdempotencyService` + `idempotency_record` (V18) guards
  `receivePayment` / `payVendor` / `addPurchase` (client key + submit-lock; replay returns the same result, no double
  charge). GL `postEvent` deduped via an outbox `event_key` + finance `gl_processed_event` unique claim (closes the #4
  duplicate-journal window). Design `finance-idempotency-design.md`; Cypress `idempotency.cy.js`. (Purchase-form client
  key deferred — server guard already protects it.)
- **Void ≠ delete (MED):** ✅ **DONE** — hard-delete retired; `voidSell`/`voidPurchase` reverse inventory + AR/AP +
  GL and soft-stamp the document VOID (read-only). Dedicated `VOID_INVOICE` privilege deferred to #6.
- **Immutable audit log (MED):** ✅ **DONE** — standalone **audit-service** (own DB, append-only `audit_event`,
  idempotent ingest, org-scoped reads) fed by business-service's **`audit_outbox`** (`AuditService`, atomic capture +
  AFTER_COMMIT/relay delivery via `AuditClient`, the #4 outbox pattern). All 10 money/stock ops emit; dashboard Audit
  Log view. Plug-and-play: finance/inventory adopt by adding the client + an outbox emit. Design `finance-audit-log-design.md`.
- **Tax completeness (MED):** 🟡 **Tax-filing register DONE (Phase A)** — output-tax register from the GL TAX account
  (`GlService.taxRegister`, dashboard Tax Register); per-org configurable (Sales tax toggle). **Phase B TODO** =
  input tax (purchase-tax capture + `Dr TAX` posting) behind a *Purchase tax* toggle → net payable. Still open:
  multi-rate. Design `finance-tax-register-design.md`.
- **Period close / lock (MED):** ✅ **DONE** — **finance-service is the single source of truth** (`period_lock`, one
  row/org, Flyway **V4**; `PeriodLockService`). `GlService.postJournal` refuses to post into a locked date (hard
  backstop). business-service reads the lock (`PeriodLockGuard`, short per-org TTL cache to stay off the hot path) and
  gates all 10 mutating ops: new ops (sale/purchase/receipt/vendor-pay/returns) check **today**, in-place ops
  (edit/void) check the **original document date**. Owner/admin close/reopen from the Finance panel (Period Close tab)
  → monolith proxy (`ADMIN_PRIVILEGE`) → finance. Design `finance-period-close-design.md`; Cypress `period-close.cy.js`.
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
2. **Shared reversal posting (§0)** — ✅ **DONE**: `PostingService` posts `SALE_RETURN` + `PURCHASE_RETURN`
   (mirror-image journals). Wired into **sale return** (`saleReturn`), **purchase return**, AND **edits**
   (`updateSell`/`updatePurchase` now reverse-the-old + repost-the-new = the edit's delta). Every mutating event
   now posts to the GL → **no silent drift**. Cypress `gl-edit-adjustment.cy.js`.
3. **Void/cancel** as a first-class audited action (uses #2's reversal) — ✅ **DONE**: `voidSell`
   (`customerHistoryId` or `invoiceNo` + reason) + `voidPurchase` (`purchaseId` + reason) reverse the whole document
   through the return path (inventory restore → AR/AP recompute → GL reversal via #4 outbox), soft-stamp the header
   `VOID` (status/voided_by/voided_at/void_reason, Flyway **V17**) and make it read-only (edit/return/re-void
   rejected). Hard-delete (`deleteSell`/`deletePurchase`) retired to no-op stubs. Rejected if a partial return already
   exists. Dashboard Void buttons + monolith proxies. Design `docs/finance-void-cancel-design.md`; Cypress
   `void-cancel.cy.js`.
4. **Posting reliability** — ✅ **DONE** (transactional `gl_outbox` + afterCommit delivery + `@Scheduled` retry relay
   via `runAs`; producers enqueue instead of fire-and-forget). Cypress `gl-outbox.cy.js`.
5. **Idempotency** — ✅ **DONE**: shared `IdempotencyService` + `idempotency_record` (V18) for
   receivePayment/payVendor/addPurchase (client key + submit-lock, replay = same result); GL `postEvent` deduped via
   outbox `event_key` + finance `gl_processed_event` (V3). Cypress `idempotency.cy.js`.
6. **Immutable audit log** — ✅ **DONE**: standalone **audit-service** + business-service `audit_outbox` producer
   (`AuditService`), all 10 money/stock ops emit, dashboard Audit Log view. Cypress `audit-log.cy.js`.
7. Tax-filing register (Phase A output + Phase B input) — ✅ **DONE**. Period close / lock — ✅ **DONE** (finance
   single-source; see §2). Then polish: multi-rate tax, store-credit/loyalty, GRN/PO, barcode-first UX, cycle-count.

> Each remains a slice: Document → Design → Implement (UI→API→DB) → mvn → headed Cypress → next.

## 5. Remaining TODOs (resume here)
Core backlog #1–#6, tax-filing register (Phase A+B) and **period close** are complete. Open items, roughly by leverage:

- [x] **Tax register — Phase A (output) + Phase B (input tax)** — ✅ DONE. Two independent Tax-Settings checkboxes
  (Sales tax `enabled` / Purchase tax `inputTaxEnabled`), `Purchase.taxRate/taxAmount`, purchase-form tax field,
  `PURCHASE`/`PURCHASE_RETURN` event `taxTotal`, finance `postPurchase`/`postPurchaseReturn` `Dr/Cr TAX` split,
  `tax-register.cy.js` (output + input purchase/void/edit). Design `finance-tax-register-design.md`.
- [x] **Period close / lock** — ✅ DONE (finance single-source; see §2). Design `finance-period-close-design.md`;
  Cypress `period-close.cy.js`.
- [ ] **Multi-rate tax** — more than one rate per invoice + per-rate breakdown on the register.
- [ ] **`VOID_INVOICE` privilege** — needs a microservice method-security mechanism (business-service has none today);
  its own cross-cutting slice. Void currently inherits login + tenant scoping.
- [ ] **Propagate the common-security `runAs` fix** — only business/audit-service were rebuilt against it; a
  full-reactor `mvn clean install` (all services stopped) before any real deploy keeps every relay consistent.
- [ ] **finance intra-service payment-hook retry** — `recordPayment`→`postPayment` is still best-effort (same-JVM, low risk).
- [ ] **Idempotency stale-PENDING reaper** — reap an idempotency_record left PENDING by a crash between claim-commit and work-commit.
- [ ] Polish backlog: store-credit/loyalty, GRN/PO approval, barcode-first sell UX, cycle-count/variance.

> Cadence per item: Document → Design → Implement (UI→API→DB) → mvn → headed Cypress → next.
