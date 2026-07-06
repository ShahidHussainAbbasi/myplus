# Sale Flow — Audit, Bugs & Gaps, and Backlog

**Branch:** `feature/finance-ledger` · **Status of this doc:** Document phase only. **Every item below advances only on explicit confirmation** (Document → Design → Implementation → Testing), initiated by either side. Nothing here is implemented yet unless its row says so.

> Workflow rule (also in memory): no design/implementation/testing starts without confirmation. User runs all builds/restarts/Cypress; Flyway for every schema change; Cypress gate per slice.

## 1. Scope audited
End-to-end sale form: **Sale** (`main.js` → `SagaSellService` → `SagaSaleWriter`), **Edit** (`SellController.updateSell`), **Return** (`SellController.saleReturn`), and **Display** (`getSellInvoice`, `getReceipt`, Sale Detail Report). Payments/dues via `recomputeDue` + finance-service (Receive Payment).

## 2. Current flow (as-is)

```mermaid
flowchart TD
    subgraph Sale[Sale · addSell]
      A[main.js cart + tenders] --> B[SagaSellService: price + tax + DISCOUNTED base]
      B --> C[reserve stock FEFO]
      C --> D[SagaSaleWriter.writePending: totals + settle + lines PENDING]
      D --> E[confirm stock -> CONFIRMED]
    end
    subgraph Edit[Edit · updateSell]
      F[loadSellForEdit: clears Received] --> G[stock delta reserve/return]
      G --> H[delete + recreate lines from CLIENT data]
      H --> I[header paid/due from CLIENT only]
    end
    subgraph Return[Return · saleReturn]
      J[return qty guard] --> K[inventory returnStock / import]
      K --> L[refund tender + reduce line pro-rata]
      L --> M[re-settle header totals + recomputeDue]
    end
```

**Divergence:** Sale computes totals+tax+discount **server-side (authoritative)**; Edit **trusts the client** and does **not** recompute totals — this is the root of the High bugs.

## 3. What's already solid
Money in `BigDecimal`; server-side tax service (incl/excl); saga stock (reserve→PENDING→confirm) + recovery relay; **due is derived** (`recomputeDue`); Return restores stock via inverse saga with pro-rata partials + header re-settle; anti-IDOR + role scoping throughout.

## 4. Bugs & gaps (backlog)

Legend — Phase: ⬜ not started · 🟨 in progress · ✅ done. All rows are ⬜ (Document only) unless noted.

### 🔴 High

| ID | Bug / gap | Where | Impact | Doc | Design | Impl | Test |
|----|-----------|-------|--------|-----|--------|------|------|
| SF-1 | `updateSell` never recomputes invoice totals — FIXED via shared `SagaSaleWriter.applyInvoice` (used by add+edit) | `SagaSaleWriter.applyInvoice`, `SellController.updateSell` | Edit now recomputes subTotal/taxTotal/grandTotal; `Σ lines == grandTotal` | ✅ | ✅ | ✅ | 🟨 run |
| SF-2 | `updateSell` rebuilt lines from client — FIXED via shared `SagaSellService.buildLines` (tax on discounted base + discount + catalogPrice + soldRate) | `SagaSellService.buildLines` | Edit no longer reverts the discount fix; add/edit converge | ✅ | ✅ | ✅ | 🟨 run |
| SF-3 | Duplicate-invoice risk on retry/double-submit — `idempotencyKey` is a fresh UUID per call; `writePending` not deduped; no client submit-lock | `SagaSellService.addSell` + `main.js` | Network retry / double-click can create two invoices | ✅ | ⬜ | ⬜ | ⬜ |

**SF-1 + SF-2 recommended together:** extract a shared `recomputeInvoice(ch, lines)` (totals + tax + discount + catalog snapshot) used by **add, edit and return** so there is ONE authoritative compute path.

### 🟡 Medium

| ID | Bug / gap | Where | Impact | Doc | Design | Impl | Test |
|----|-----------|-------|--------|-----|--------|------|------|
| SF-4 | Edit clears "Received" — PARTLY FIXED: edit now shows **"Already paid"** + preserves it server-side; Received = ADDITIONAL payment; due preview counts prior paid | `business.js` loadSellForEdit/calculateChange, `applyInvoice` | Prior payment no longer forgotten on edit | ✅ | ✅ | 🟨 partial | 🟨 run |
| SF-5 | Return refund doesn't reduce header `paidAmount`; customer credit floored to 0 (lost) | `saleReturn` 758 + `recomputeDue` | Invoice shows paid > grandTotal; return/overpay credit vanishes (no customer-credit concept) | ✅ | ⬜ | ⬜ | ⬜ |
| SF-6 | No-tender sale (received 0, not CREDIT) skips `settle` → `paidAmount` may be null | `main.js` 412 + `SagaSaleWriter` 73-81 | Inconsistent paid/due for unpaid non-credit sales | ✅ | ⬜ | ⬜ | ⬜ |

### 🟢 Low / standards

| ID | Bug / gap | Where | Impact | Doc | Design | Impl | Test |
|----|-----------|-------|--------|-----|--------|------|------|
| SF-7 | Client-side float money math for cashier change/due preview | `business.js` calculateChange/calculateNetSell | On-screen number can drift (persisted data safe — server recomputes) | ✅ | ⬜ | ⬜ | ⬜ |
| SF-8 | Submit guard precedence — FIXED: grouped explicitly + allows editing a fully-paid invoice | `main.js` 389 | No-item owing state no longer slips through; fully-paid edit submittable | ✅ | ✅ | ✅ | 🟨 run |
| SF-9 | Cart shows discount value but not type (amount vs %) | sell cart | Minor UX ambiguity | ✅ | ⬜ | ⬜ | ⬜ |
| SF-10 | No line-level cost ⇒ no true margin/profit anywhere | data model | Reports can't show profit | ✅ | ⬜ | ⬜ | ⬜ |
| SF-11 | Return has no reason / credit-note document | `saleReturn` | Weak audit trail on returns | ✅ | ⬜ | ⬜ | ⬜ |

## 5. Missing vs. standard POS/commerce backend
- One authoritative compute path for add **and** edit (SF-1/SF-2).
- Idempotent order submission (SF-3).
- Customer credit / overpayment ledger — overpayments + return-credits currently vanish (SF-5); natural home = finance-service.
- Void/cancel as a first-class audited action (distinct from row delete).
- Edit = payment-aware (show paid-to-date, add a payment) — partly served by Receive Payment.

## 6. Related pending todos (project backlog)

| Task | Status | Doc | Design | Impl | Test |
|------|--------|-----|--------|------|------|
| #2 Receive Payment / AR subledger (finance-service) | ✅ DONE (built, green) | ✅ | ✅ | ✅ | ✅ |
| #1 Purchase Return (end-to-end) | Pending — decisions open (stock-only vs vendor payable; UI) | ✅ | ⬜ | ⬜ | ⬜ |
| Finance Phase 2 — AP subledger (vendor payments; pairs with Purchase Return) | Pending | ⬜ | ⬜ | ⬜ | ⬜ |
| #3 Full General Ledger (double-entry) | Pending — after AR+AP | ⬜ | ⬜ | ⬜ | ⬜ |
| Customer-due clobber fix (profile edit) | ✅ DONE (on this branch) | ✅ | ✅ | ✅ | ✅ |
| Sale discount → due fix (addSell) | ✅ DONE (on this branch) | ✅ | ✅ | ✅ | ✅ |
| Write off expired stock (Product screen) | Deferred (flagged) | ⬜ | ⬜ | ⬜ | ⬜ |
| `adjustStock` DECREASE ↔ batch reconcile (product-side) | ✅ DONE (applyStockDelta) | ✅ | ✅ | ✅ | ✅ |

## 6a. DESIGN — SF-1 + SF-2 (make `updateSell` authoritative)  ⟵ awaiting approval to implement

**Goal:** one authoritative compute path so **add, edit (and return)** produce identical totals, tax, discount and catalog snapshots. Today add = saga-authoritative, edit = client-trusted.

**Root cause recap:** `updateSell` recreates lines from client data (no server tax, no discount, no catalogPrice) and never recomputes `grandTotal/subTotal/taxTotal`.

### Refactor (2 extractions + edit rewrite)

1. **`SagaSellService.buildLines(CustomerHistoryDTO dto) : List<SagaLine>`** — extract the existing per-line loop (catalog price + `soldRate` + `resolveDiscount` + tax on the **discounted base** + `catalogPrice` snapshot). `addSell` calls it, then derives its reservation lines from the returned `SagaLine`s (productId + qty). **No behaviour change for add.**

2. **`SagaSaleWriter.applyInvoice(CustomerHistory ch, List<SagaLine> lines, CustomerHistoryDTO dto, AuthenticatedUser user, boolean replaceLines)`** — extract from `writePending`: compute `subTotal/taxTotal/grandTotal`, settle, save header, (optionally) delete existing `Sell` lines, write authoritative `Sell` lines (discount + catalogPrice + tax + soldRate), `recomputeDue`. `writePending` calls it with `replaceLines=false`; `updateSell` with `replaceLines=true`.

3. **`SellController.updateSell`** keeps its edit-only concerns (anti-IDOR, keep `invoiceSeq/invoiceNo`, **stock-delta** reserve/return), then replaces its hand-rolled lines + header block with:
   `List<SagaLine> lines = sagaSellService.buildLines(dto); sagaSaleWriter.applyInvoice(ch, lines, dto, user, true);`

```mermaid
flowchart LR
    subgraph shared[shared authoritative path]
      BL[SagaSellService.buildLines] --> AI[SagaSaleWriter.applyInvoice: totals+settle+lines+recomputeDue]
    end
    addSell --> BL
    updateSell -->|after stock-delta| BL
    return -.reuse later.-> AI
```

### Key decision — settlement on EDIT (needs your pick)
Edit clears "Received", so no new tender is usually sent. Authoritative model (mirrors the Return path `paid − grandTotal`):
- **Default (recommended):** keep the invoice's **existing `paidAmount`**; if the cashier enters a new payment on edit, **add** that tender; then `due = paid − grandTotal` (server total). Fixes SF-1/SF-2 AND stops the edit from forgetting prior payment (bonus toward SF-4). Behaviour change: the client-sent `dueAmount` is no longer trusted — the server derives it.
- **Alternative (minimal):** keep trusting client `paid/due`, only recompute totals + lines. Smaller change but leaves due/total able to disagree.

### Files
`SagaSellService.java`, `SagaSaleWriter.java`, `SellController.java` (business-service only). No schema change, no contracts/monolith change.

### Test plan (Cypress + logic)
- Edit a discounted sale → `grandTotal` = discounted net, `due` correct (discount NOT reverted on edit) — extends `sale-discount.cy.js`.
- Edit changing qty → `grandTotal/subTotal/taxTotal` recomputed; `Σ lines == grandTotal`; due = paid − grandTotal.
- Edit preserves prior payment (no re-entry needed).
- Regression: `product-crud.cy.js` sold-rate/catalog-snapshot still pass on edited lines.

---

## 7. Recommended order (each awaits confirmation)
1. **SF-1 + SF-2** — make edit authoritative (shared `recomputeInvoice`). Highest impact; also protects the discount fix.
2. **SF-3** — idempotent submission.
3. **SF-4 + SF-5** — edit paid prefill + return credit (ties to a customer-credit ledger in finance-service).
4. **SF-6, SF-8** — settle/guard hardening.
5. **SF-7, SF-9, SF-10, SF-11** — polish / standards.
