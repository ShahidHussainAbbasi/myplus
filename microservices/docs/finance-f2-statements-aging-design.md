# finance — Phase F2: Party Statements + Aging — Design

**STATUS: F2 DONE ✅ (headed-Cypress green — `finance-reports.cy.js`: AR+AP aging buckets + statement running balance).** Defaults shipped: buckets 0–30/31–60/61–90/90+, age basis = due date (fallback doc date), statements + aging together. Party-agnostic `AgingCalculator`/`StatementBuilder` (no AR/AP duplication); Flyway V15 (indexes only); Statement buttons + Aging dialogs in the monolith.

**Branch:** `feature/finance-ledger` · **Slice:** F2 (statements/aging) · **Roadmap:** AR ✅ → AP ✅ → **F2 ✅** → General Ledger (next)
**Companions:** `finance-service-design.md` (AR), `finance-ap-vendor-payments-design.md` (AP).

## 0. Why F2 now
AR and AP each track a running balance and a subledger of payments. F2 turns that into the two reports every business + accountant expects, and that a GL/ERP later builds on:
1. **Statement of account** (per party) — a chronological ledger of the party's documents (invoices/bills) and payments with a running balance (what a customer/vendor is sent to reconcile).
2. **Aging** (AR & AP) — outstanding balances bucketed by age (**0–30 / 31–60 / 61–90 / 90+**) so you see who's overdue and by how much.

Pure **read/projection** layer — no new writes, no schema change. It composes the data already owned by AR/AP + the shared ledger.

## 1. Ownership & composition (bounded contexts kept clean)
- **business-service owns the source documents** (AR `CustomerHistory` invoices, AP `Purchase` bills) with their `dated`/`dueDate`, `grandTotal`/`totalAmount`, `paidAmount`, `dueAmount`.
- **finance-service owns the payment ledger** (receipts/disbursements + allocations) — already exposes `GET /api/finance/payments?partyType=&partyId=`.
- **business-service composes** the reports: aging is computed purely from its own open docs (**no finance call**); a statement merges its docs with the party's finance payments (one on-demand finance read).

```mermaid
flowchart LR
    subgraph BS[business-service — composes]
      DOCS[open docs: CustomerHistory / Purchase]
      AGING[AgingCalculator (pure)]
      STMT[StatementBuilder (pure)]
    end
    FS[(finance-service GET /payments)]
    DOCS --> AGING
    DOCS --> STMT
    FS -. payments (statement only) .-> STMT
    AGING --> API[/customerAging /vendorAging/]
    STMT --> API2[/customerStatement /vendorStatement/]
```

## 2. Party-agnostic (SOLID, no AR/AP duplication)
Both reports work identically for customers and vendors, so — mirroring the `SubledgerService`/`OpenDoc` approach — F2 uses two **pure, party-agnostic** helpers over a light row view, each fed by an AR adapter and an AP adapter:
- `AgingCalculator.bucketize(List<AgingRow>, asOf)` → bucket totals. `AgingRow` = `{ outstanding, ageDate }`.
- `StatementBuilder.build(List<StmtLine>)` → ordered lines + running balance. `StmtLine` = `{ date, docNo, type(BILL|PAYMENT), debit, credit }`.
No AR-vs-AP copy; adding education-fees/welfare later reuses them.

## 3. Data → report

### Aging (self-contained in business-service)
For each **open** doc of the party set (AR: `CustomerHistory.dueAmount < 0`; AP: `Purchase.dueAmount < 0`):
- `outstanding = −dueAmount`, `ageDate = dueDate ?? dated`, `ageDays = asOf − ageDate`.
- bucket into 0–30 / 31–60 / 61–90 / 90+; sum per party + grand totals per bucket.
Returned per-party (customer/vendor name + bucket columns + total) for a report grid, org-scoped.

### Statement (business docs + finance payments)
For a party over an optional `[from,to]`:
- **BILL lines** from its docs: date=`dated`, docNo=`invoiceNo`/`purchaseInvoiceNo`, debit=`grandTotal`/`totalAmount` (AR: what they owe; AP: what we owe).
- **PAYMENT lines** from finance `GET /payments`: date=`paidOn`, docNo=`receiptNo`/voucher, credit=`amount`.
- sort by date, compute **running balance** (opening + Σ debit − Σ credit), closing = the party's current `dueAmount` (cross-check).

## 4. API (business-service; monolith proxies each)
| Method | Path | Purpose |
|---|---|---|
| GET | `/customerAging` | AR aging buckets per customer (+ totals), org-scoped |
| GET | `/vendorAging` | AP aging buckets per vendor (+ totals), org-scoped |
| GET | `/customerStatement?customerId=&from=&to=` | AR statement of account |
| GET | `/vendorStatement?venderId=&from=&to=` | AP statement of account |

**commerce-contracts:** add `FinanceClient.listPayments(partyType, partyId)` (`@GetExchange("/payments")`) so business-service can pull a party's ledger for the statement. (finance-service endpoint already exists.)

## 5. Performance (standing priority)
- Aging needs **no finance call** and is one scoped query + a single in-memory pass. Index the age/scoped columns: `customer_history(organization_id, due_amount)` and reuse V14's `purchase(vender_id, due_amount)`; add `dated`/`due_date` where the bucket sort needs it (Flyway **V15**, indexes only — no columns).
- Statement makes **one** finance read per party, on demand (not a hot path); never per-doc.
- Keep the pure calculators allocation-light; paginate the aging grid client-side (DataTable) like the Sale Detail Report.

## 6. UI (reuse report patterns)
- **Aging report** screen (AR + a vendor toggle) — a DataTable like `tableSellReport`: party · 0–30 · 31–60 · 61–90 · 90+ · Total, with bucket totals in the footer + a KPI (total overdue).
- **Statement** — open from the Customer/Vendor row (a "Statement" button next to Receive/Pay) → modal/printable view of the ledger lines + running balance (reuses the receipt print pattern for a PDF later).

## 7. Test plan
- **Unit (pure):** `AgingCalculator.bucketize` (boundary ages 30/31/60/61/90/91) + `StatementBuilder` running-balance (debits/credits interleaved, closing == dueAmount).
- **Cypress:** seed invoices/bills dated in different buckets → assert `/customerAging` + `/vendorAging` bucket sums; a paid-then-partially-paid party → assert `/customerStatement` lines + running balance.

## 8. Decisions to confirm (gate before implementing)
1. **Aging buckets** — standard **0–30 / 31–60 / 61–90 / 90+**? (or 0–30/30–60/60–90/90+ inclusive edges, or add 120+.)
2. **Age basis** — from **due date** (`dueDate`, falling back to doc date) vs always the **document date**. (Due-date is the accounting-standard for "overdue".)
3. **Statement scope now** — include finance **payment lines** (needs the new `FinanceClient.listPayments`) in this slice, or ship **aging first** and add statements as F2b?

## 9. Cadence
Document → **Design (this)** → confirm decisions → Implement (contracts `listPayments` → business calculators+API → monolith proxy+UI) → Test (`mvn` unit + Cypress) → headed Cypress GREEN → next (**General Ledger**).
