# Payables move to finance — one accounts-payable subledger (and EX-4 expense bills)

**Status:** DESIGN — awaiting go-ahead on the phase plan (a rewrite of a live money flow: STANDARDS "confirm big
rewrites"). Ruling (user, 2026-10-02, EX-4 review): **"Move payables to finance."** Branch `feature/expense-management`.

## 1. Document

A bill a business owes — for stock or for electricity — must sit in ONE payables list: one supplier balance, one
aging, one statement, one payment that settles either (QuickBooks Bills, Xero Bills). Today payables live in
**business-service** and know only purchases. EX-4 (expense bills: Dr expense / Cr 2000 AP, paid later) cannot join
them without the GL's 2000 Accounts Payable disagreeing with the supplier balances — a control account that does not
equal its subledger.

finance-service is the money spine and already holds half of a subledger: every supplier **payment** and its
**allocation** to each purchase (`payment`, `payment_allocation` docType `PURCHASE`). What it lacks is the open
**documents**. This programme gives finance those documents, moves the reads and then the settlement there, and lets
every source — purchases, opening balances, expense bills, later any vertical — add a payable the same way.

## 2. Current state — the trace (RULE 0)

**Writers of "what is owed to a supplier" — 6**

| # | Writer | Where | Effect |
|---|---|---|---|
| W1 | purchase add | `PurchaseService:467` | `paid`, `due = paid − bill` on the purchase |
| W2 | purchase edit | `PurchaseService:643` | same, recomputed |
| W3 | purchase return / debit note | `PurchaseService:831` | `paid` adjusted |
| W4 | opening AP | `OpeningBalanceService:213` | a purchase row carrying the opening amount |
| W5 | supplier payment | `VenderService.payVendor:268` | FIFO `paid += applied` per bill; finance `recordPayment(DISBURSEMENT, allocations)` |
| W6 | stamped balance | `VenderService.recomputePayable` | `vender.due_amount = −Σ purchase.due` |

**Readers — 8**

| # | Reader | Where |
|---|---|---|
| R1 | supplier aging | `FinanceReportService.vendorAging` |
| R2 | supplier statement | `FinanceReportService.vendorStatement` |
| R3 | statement CSV | `FinanceReportController.vendorStatementCsv` |
| R4 | supplier list due column | `VenderController:114` |
| R5 | supplier credit-limit check on a purchase | `PurchaseService:75` (purchase hot path) |
| R6 | customer↔supplier combined position (DR-2) | `PartyRoleService:119` |
| R7 | open bills for FIFO | `PurchaseRepo.findOpenPurchasesByVendor` |
| R8 | balance sum | `PurchaseRepo.sumDueByVendor` |

Finance today: `payment` + `payment_allocation` (docType/docId/docNo); `postPayment(DISBURSEMENT)` always Dr 2000
/ Cr cash·bank. No payable documents, no aging.

## 3. Standards

| Dimension | Rule |
|---|---|
| Business | One payables subledger whose total equals GL 2000 at every moment (control account = subledger). A document is never edited after payment — a change is a credit/debit adjustment document |
| SaaS / tenancy | `organization_id` on every row; party referenced as `(party_type, party_id)` — finance never owns suppliers (party-agnostic ledger) |
| Live modules | **Strangler fig, per tenant, reversible**: shadow → reconcile → switch reads → switch settlement. Each switch is an org setting (`finance.payables.source = BUSINESS|FINANCE`); flipping back restores today's path. No step without a reconciliation gate at **zero difference** |
| Boundaries | Sources (business purchases, expense bills, opening AP) **report** documents; finance **owns** the subledger and settlement. Purchase screens keep showing paid/due — as a projection finance keeps current |
| Patterns | Transactional outbox at every source (payload JSON) · idempotent intake keyed `(org, source, source_ref)` · `common-subledger` `SubledgerService` FIFO allocation (already shared) · CQRS read model for aging/statements · feature flag per tenant · reconciliation report as a standing control |
| Performance | The purchase hot path (R5 credit-limit) must not call finance synchronously — the supplier balance is **stamped** on the vendor by an event when finance's balance changes (stamp-at-write) |
| Testing | Each phase: unit + Testcontainers Flyway; a reconciliation spec (business Σ due = finance Σ open = GL 2000) per tenant; the existing AP/aging/statement/pay-vendor specs must stay green unchanged until their phase switches them |

## 4. Phase plan

| Phase | What | Gate (must be green before the next) |
|---|---|---|
| **FP-1** | finance `payable_doc` (org, party_type, party_id, source PURCHASE·OPENING_AP·PURCHASE_RETURN·EXPENSE_BILL, source_ref, doc_no, doc_date, due_date, amount, settled, status, version) + idempotent intake API + owner-run, previewed **backfill** from business purchases + **reconciliation report** | backfill twice = same rows; reconciliation diff = 0 for owner.business, owner.pharma, owner.mobile |
| **FP-2** | business emits a payable event from W1–W4 (outbox, payload JSON); payVendor's existing finance allocation marks `settled` on the docs | after a purchase / edit / return / opening AP / payment, reconciliation diff still 0 (spec drives each writer) |
| **FP-3 = EX-4** | **Expense bills**: expense-service records a bill (vendor, category lines, due date) → Dr expense / Cr 2000; reports `EXPENSE_BILL` to finance's subledger | the bill is in finance's open payables and GL 2000 moves by it; reconciliation (business purchases + bills) = GL 2000 |
| **FP-4** | **Reads switch** (flag): aging, statement, CSV, supplier due (R1–R4, R6) served from finance; vendor balance stamped by finance event for R5 | with the flag on, the existing aging/statement specs give the same figures as with it off, plus bills |
| **FP-5** | **Settlement switch**: Pay Supplier allocates FIFO in finance across purchases AND bills; purchase paid/due becomes a projection updated by finance events | pay-vendor spec green on the finance path; a payment spanning a purchase and a bill settles both |
| **FP-6** | Retire business as source: `recomputePayable` reads the projection; remove dual paths after N weeks at zero diff | flag removed per tenant only after a clean reconciliation history |

**Why not EX-4 first, alone:** a bill posting to 2000 before FP-1/FP-2 exist would create exactly the control-account
gap this ruling is meant to prevent. So EX-4 is FP-3: it lands into a subledger that already reconciles.

## 5. Risks

| Risk | Mitigation |
|---|---|
| Live AP money path changed | flag per tenant, reversible; reconciliation gate at every phase; existing specs unchanged until their switch |
| Backfill double-counts | idempotent on (org, source, source_ref); preview before apply; owner-run |
| Purchase hot path slowed | no synchronous finance call; balance stamped by event |
| A source drops a field | payload-JSON outboxes only (the gl_outbox lesson) |
| Another session edits business purchase/vendor code | check `git status` per phase; deploy from a clean worktree |
| Non-business verticals have no suppliers | bills from school/farm/welfare wait for party-service suppliers (party_type stays generic so they plug in) |

## 6. Decisions needed before FP-1
- Approve the phase plan (or cut it).
- Backfill scope: all tenants, or owner-run per tenant from a button (recommended: owner-run, previewed).
