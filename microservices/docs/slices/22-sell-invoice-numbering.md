# Slice 22 — Sell invoice numbering (per-organization)

Status: **IMPLEMENTED — awaiting build + Cypress** 🔨. Format `INV-000123` (per-org). Builds on
[`21-business-org-scoping.md`](21-business-org-scoping.md) (a sale already carries `organization_id`).
Follows [`../ARCHITECTURE-MULTITENANCY.md`](../ARCHITECTURE-MULTITENANCY.md).

## Document — what & why

Every new sale logged from `sellDiv` must get a **system-generated invoice number**, unique and
gap-free **per organization** (one invoice book per business, shared by the owner + all staff — the
reason slice 21 had to land first). The number is shown to the cashier after the sale and printed on
the receipt. A sale = one `CustomerHistory` (header) + N `Sell` lines, so the number lives on
`CustomerHistory`.

## Design

### Data model
`CustomerHistory` gains two fields:
```java
@Column(name = "invoice_seq")
private Long invoiceSeq;     // per-org running number (1,2,3…); the ordering/uniqueness key
@Column(name = "invoice_no")
private String invoiceNo;    // display form, e.g. INV-{org}-000123
```
Plus a **per-tenant unique constraint** so the race can never commit:
```java
@Table(name = "customer_history", uniqueConstraints = {
    @UniqueConstraint(columnNames = "customer_history_id"),
    @UniqueConstraint(name = "uq_ch_org_invoice_seq", columnNames = {"organization_id", "invoice_seq"})
})
```
No drop migration (new constraint only). Existing rows have `invoice_seq = NULL` (MySQL treats NULLs
as distinct, so the unique constraint accepts all legacy rows; they simply have no number).

### Generation strategy — MAX+1 per org, inside the existing `addSell` transaction
`addSell` is already `@Transactional`; allocation happens in `saveUpdateCustomerHistory` (same tx) for
a **new** sale only (`customer_history_id` null):
```
seq = customerHistoryRepo.maxInvoiceSeqForOrg(orgId) + 1   // COALESCE(MAX,0)+1, scoped to org
ch.setInvoiceSeq(seq);
ch.setInvoiceNo("INV-" + String.format("%06d", seq));
// saved by addSell; unique(org, seq) guarantees no duplicate can ever commit
```
**Correctness is enforced by the `unique(organization_id, invoice_seq)` constraint**, not by app logic
— a duplicate physically cannot persist. No counter table. No in-transaction retry: once the surrounding
`@Transactional` hits the constraint it is rollback-only, so a (rare) concurrent collision fails that one
sale via the existing atomic ERROR handler ([[project-db-connection-tx-standard]]) and the cashier
re-submits, getting the next number. This is acceptable for the expected single/few-cashier-per-org load;
if heavy same-org concurrency ever appears, swap in a counter row with `SELECT … FOR UPDATE` (no schema
churn for callers).

Repo:
```java
@Query("select coalesce(max(ch.invoiceSeq),0) from CustomerHistory ch where ch.organizationId = :orgId")
Long maxInvoiceSeqForOrg(@Param("orgId") Long orgId);
```

### Format
**`INV-{6-digit-zero-padded-seq}`** → e.g. `INV-000123` (chosen 2026-06-13). No org id in the display
text; the org still scopes the series internally via `invoice_seq`. Kept as an opaque `invoice_no`
string + numeric `invoice_seq`, so the format can change later without disturbing the ordering key.

### Endpoint / response
`POST /addSell` unchanged in shape; the generated number is returned so the UI can show/print it:
`GenericResponse("SUCCESS", invoiceNo, <invoiceNo or small object>)`. (`getUserSell` rows already
include the `CustomerHistory`, so the number flows to the sales list/receipt automatically once mapped
into `CustomerHistoryDTO`.)

### UI (monolith `sellDiv`)
- After a successful sale, show the returned invoice number (toast + on the printed receipt).
- `CustomerHistoryDTO` + the sell-row mapping expose `invoiceNo` so it renders in the sales table and
  on the receipt/print view. (A "next #" preview on the open form is **not** included — previewing
  before commit would either reserve or mislead; the number is assigned at save.)

### Security / correctness
- `invoiceSeq` is allocated server-side from the token-derived `orgId` — never client-supplied.
- Uniqueness enforced at the DB (`unique(org, seq)`), not just app logic.
- Returns/reverts (`saleReturn`/`revertSell`) do **not** mint new invoice numbers (they adjust/delete);
  numbers are only minted on `addSell`. Gaps from deleted sales are accepted (standard for POS).

## Architecture & UML

### Architecture (flowchart)
```mermaid
flowchart LR
  JS["sellDiv JS"] -->|POST /addSell| GW[api-gateway] --> C["SellController.addSell @Transactional"]
  C --> CHS["CustomerHistoryService"]
  CHS --> INV["allocate seq: MAX(seq for org)+1"]
  INV --> R["CustomerHistoryRepo.maxInvoiceSeqForOrg / save"]
  R --> DB[("myplusdb.customer_history<br/>+ invoice_seq, invoice_no<br/>unique(org_id, invoice_seq)")]
  C -->|GenericResponse{invoiceNo}| JS
```

### Sequence (allocation + race retry)
```mermaid
sequenceDiagram
  participant JS as sellDiv
  participant C as SellController.addSell (@Transactional)
  participant R as CustomerHistoryRepo
  participant DB as myplusdb
  JS->>C: POST /addSell (customer + sales)
  C->>R: maxInvoiceSeqForOrg(orgId)
  R->>DB: SELECT COALESCE(MAX(invoice_seq),0) WHERE org_id=:org
  DB-->>C: maxSeq
  C->>C: seq=maxSeq+1; invoiceNo=INV-org-seq
  C->>DB: save CustomerHistory (org_id, invoice_seq, invoice_no)
  alt unique(org,seq) violation (concurrent sale)
    DB-->>C: DataIntegrityViolation
    C->>R: re-read maxInvoiceSeqForOrg(orgId); seq++ ; save (retry once)
  end
  C-->>JS: SUCCESS + invoiceNo
```

## Implement (checklist)
- [x] `CustomerHistory`: `invoiceSeq` + `invoiceNo` fields + `unique(organization_id, invoice_seq)`
- [x] `CustomerHistoryRepo.maxInvoiceSeqForOrg(orgId)`
- [x] allocation in `CustomerHistoryService.saveUpdateCustomerHistory` (new sale only) inside the existing `addSell` `@Transactional`; unique constraint guarantees no duplicate (no in-tx retry — see strategy)
- [x] return `invoiceNo` in the `addSell` response (`GenericResponse` object field)
- [x] `CustomerHistoryDTO` exposes `invoiceNo`/`invoiceSeq`; `getUserSell` ModelMapper carries it to the sales list
- [x] monolith `sellDiv`: `showSaleSuccess()` toast with the invoice number on `addSell` success (main.js)
- [ ] business-service compiles & boots (Hibernate adds the two columns + composite unique) — **awaiting build**

Status: **IMPLEMENTED — awaiting build + Cypress.** Format `INV-000123` (per-org series).

## Test
- Happy: log a sale → response carries `INV-{org}-000001`; row has `invoice_seq=1`; next sale → `000002`.
- Per-org isolation: org A and org B each start their own series at 1 (no cross-tenant collision).
- Shared org: owner sale then staff sale (same org) → consecutive numbers in one series.
- Concurrency: two near-simultaneous sales never get the same number (unique + retry).
- Legacy: pre-slice sales (`invoice_seq` NULL) are untouched; new sales number from MAX+1.
- Cypress (headed): **added** to `business/sell.cy.js` → "Invoice Numbering (slice 22)" block — asserts
  `addSell` returns `INV-######` and a second sale increments the sequence (reads the number from the
  response `object` or `message`). Awaiting headed run.
