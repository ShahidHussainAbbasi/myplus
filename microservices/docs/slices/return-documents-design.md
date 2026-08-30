# Return documents — credit notes and debit notes (task #15)

**Status:** ✅ **SHIPPED + GREEN** — `cypress/e2e/business/return-documents.cy.js` 5/5, 2026-08-30.
**Branch:** `feature/UI-UX`. **Unblocks:** task #16 (vendor-filtered debit notes), task #19 (bulk invoices).

The ask was *"the option to print invoices of purchase and sale return"*. The review below changed two things
about what that means, so read §1 before §3.

---

## 1. What the review found

### 1.1 These are not "return invoices". They are credit notes and debit notes — and the schema already says so.

| Flow | Entity | Document number | Correct trade name |
|---|---|---|---|
| Sale return | `SaleReturn` | `creditNoteNo` / `creditNoteSeq` | **Credit note** |
| Purchase return | `PurchaseReturn` | `debitNoteNo` / `debitNoteSeq` | **Debit note** |

Both numbers are already allocated, already serialised per org through `DocumentNumberService.next(...)`
(`CREDIT_NOTE` / `DEBIT_NOTE`), already formatted by `InvoiceNumbers.creditNote/debitNote`, and already
uniqueness-guarded — `UNIQUE(organization_id, debit_note_seq)` on the purchase side.

So the numbered document exists. **Only the rendering is missing.** The UI and the presets are named credit
note / debit note accordingly; calling a credit note an "invoice" is wrong accounting terminology, and the
codebase already knows better (`"Sale returned. Credit note " + creditNoteNo`).

### 1.2 ⚠ Correction to an earlier finding: there is nothing to GROUP.

An earlier note in task #15 said the gap was a missing `findByCreditNoteNo` / `findByDebitNoteNo`, on the
assumption that one note spans several returned lines. **That is not how the code behaves.**

- `saleReturn` takes a single `sellId` (one sold LINE) and allocates a fresh credit note per call.
- `purchaseReturn` takes a single `purchaseId` (and `Purchase` is itself per-line) and allocates a fresh
  debit note per call, writing exactly one `PurchaseReturn` row.

**One note = exactly one row.** No grouping query is needed, and writing one would have been solving a problem
that does not exist.

### 1.3 The real gap is ASSEMBLY, not grouping — a return row cannot draw itself.

`SaleReturn` stores `productId`, not a product name. It stores no rate, and no customer.

| The document needs | `SaleReturn` has it? | Where it comes from |
|---|---|---|
| Note no, date, reason, qty, amount | ✅ | the row |
| Original invoice ref | ✅ `invoiceNo` | the row |
| Product **name** / SKU | ❌ only `productId` | catalog `ProductRef` — same resolve `getUserSell` already does |
| **Customer** | ❌ | the original `Sell` → `CustomerHistory` |
| Unit rate | ❌ only a total amount | the original `Sell` line |
| Tenant header | ❌ | the document profile, as every other document |

`PurchaseReturn` is the same shape: it has `venderId` but no vendor name, `productId` but no product name.

So the build is **one scoped read per side that assembles a renderable model**, not a repo grouping method.

### 1.4 ⚠ A product observation, flagged not decided

Because a note is allocated per line, a customer returning three items receives **three credit notes**. That
is what the data model produces today. Changing it is a business decision with ledger consequences (each note
already posts its own `SALE_RETURN` GL event and its own AR movement), so this slice does **not** change it.

**It is designed around, though** — see §2.1. The document renders a LIST of lines and today is always handed
a list of one. If returns are ever grouped, the document does not change.

---

## 2. Design

### 2.1 The one decision that matters: the model carries `lines[]`, not a line

The renderer takes `{ header, party, lines[], totals }` with `lines.length === 1` today.

The alternative — a single-line model matching the row exactly — is simpler *right now* and would have to be
thrown away the first time returns are grouped, taking the preset, the PDF layout and the designer bindings
with it. A list of one costs nothing and makes §1.4 a data change rather than a rewrite.

### 2.2 It is a PRESET, not a print path

`receipt.js` already owns document layout through `DocumentRenderer`:

```
DocumentRenderer = { buildHtml, toPrintModel, withInvoice, resolveProfile, PRESETS, FIELD_WHITELIST, … }
PRESETS = { TRADE_INVOICE_A4, RETAIL_RECEIPT_80MM, DELIVERY_CHALLAN_A4, DISPENSE_RECEIPT_80MM }
```

`document-pdf.js` draws a resolved model through `toPrintModel`; `document-designer.js` previews through the
**same** `buildHtml`. The file states its own rule:

> *"if you find yourself deciding here whether a row should appear, or what a column is called, the logic
> belongs in receipt.js instead."*

**So this slice adds two presets — `CREDIT_NOTE_A4` and `DEBIT_NOTE_A4` — and no new emitter.** Print, PDF
download, lazy pdfmake loading (~900KB, `LazyExport.ensurePdfMake`) and the designer all come for free. Adding
layout logic to `document-pdf.js` is the documented wrong move.

### 2.3 Flow

```mermaid
flowchart TD
    A["Return row in the grid<br/>(Sell Return / Purchase Return)"] -->|"Print / Download"| B{"Which side?"}

    B -->|sale| C["GET /creditNote/{id}"]
    B -->|purchase| D["GET /debitNote/{id}"]

    C --> E["SellController<br/>findByIdScoped → anti-IDOR"]
    D --> F["PurchaseController<br/>findByIdScoped → anti-IDOR"]

    E --> G["assemble ReturnDocumentDTO<br/>+ ProductRef name/SKU<br/>+ customer from the original Sell<br/>+ rate from the sold line"]
    F --> G

    G --> H["DocumentRenderer<br/>CREDIT_NOTE_A4 / DEBIT_NOTE_A4"]
    H --> I["buildHtml → iframe → window.print()"]
    H --> J["toPrintModel → document-pdf.js → PDF"]

    style G fill:#fde68a,stroke:#b45309,color:#111
    style H fill:#bfdbfe,stroke:#1d4ed8,color:#111
```

The amber box is the only genuinely new server work. The blue box is configuration of something that exists.

### 2.4 Tenancy — non-negotiable

Both reads take the note by **id, scoped**, never by note number alone. A document endpoint keyed on a
guessable string is an IDOR waiting to happen, and `saleReturn` itself already demonstrates the required
pattern (`inMyTenant(...)` plus `myStore(...)` — *"a return can only be taken at the store that sold it"*).
The read follows the same rule: same tenant, same store visibility, `findScoped` NULL-org fallback on the sale
side.

**Purchase side note:** `PurchaseReturnRepo.findScoped(orgId)` has no `userId` fallback, unlike the sale side.
**This was checked and is correct** — V33 created `purchase_return` (*"the supplier side has no record
whatsoever"*), so no legacy null-org rows exist. Not a defect; recorded so it is not "fixed" later by someone
pattern-matching against the sale side.

---

## 3. Work

### ⚠ Two corrections made during implementation

**(a) The DTO is business-service-internal, not a shared contract.** The monolith proxies these as raw
`Map<String,Object>` and never deserializes them, so `ReturnDocumentDTO` lives in
`business_service/dto/`. Putting a single-service view type in `commerce-contracts` would pollute a module
that exists for genuinely shared cross-service contracts.

**(b) The documents are keyed on the NOTE NUMBER, not the row id.** §2.4 originally called a by-number lookup
an IDOR. That reasoning was wrong: a row id is exactly as guessable as `CRN-000007`, and the protection in
both cases is the **scope predicate inside the query**, not the unguessability of the key. Keying on the note
number is also what makes the feature reachable — see §3.1.

| # | Change | Where |
|---|---|---|
| 1 | `ReturnDocumentDTO` — header/party/lines/totals, `lines[]` per §2.1 | `business-service/dto` |
| 2 | `GET /creditNote?no=` + `findByCreditNoteNoScoped` | `SellController`, `SaleReturnRepo` |
| 3 | `GET /debitNote?no=` + `findByDebitNoteNoScoped` | `PurchaseController`, `PurchaseReturnRepo` |
| 4 | Monolith proxies for both | `SellController` / `PurchaseController` (monolith) |
| 5 | `CREDIT_NOTE_A4` + `DEBIT_NOTE_A4` presets | `receipt.js` |
| 6 | Print + Download buttons on both return grids | `business.js` |
| 7 | i18n keys, six locales | `messages*.properties` |
| 8 | Gate | `cypress/e2e/business/return-documents.cy.js` |

### What the gate must assert

- A credit note renders with its **number**, the **original invoice reference**, the product **name** (not an
  id — that is the enrichment in §1.3 actually working), qty and amount.
- Same for a debit note, with the **vendor** name.
- **Anti-IDOR: another tenant's note id returns not-found.** Asserted on the ENVELOPE — refusals arrive as
  HTTP 200 with `status:"ERROR"` / `success:false`, so an HTTP-status assertion would pass against a leak.
- The PDF download path produces a file (the lazy pdfmake load actually resolves).
- Per the domain-gate standard: run as the feature's own tenant **and** owner / admin / user.

### 3.1 ⚠ The reachability problem, found during implementation

**There is no returns list screen.** `getSaleReturns` exists in business-service and in the monolith proxy,
but nothing in `business.js` calls it, and there is no purchase-returns list either. Returns are taken through
per-row dialogs and then disappear from the UI.

So an id-keyed endpoint would have had **no entry point at all** — the operator never sees a row id, only the
message *"Sale returned. Credit note CRN-000007"*. Shipping that would have repeated the exact failure this
codebase has hit four times (C1's inert service, C3's unregistered catalog, C6's invisible policy, PERF-4's
never-run gate): working code nobody can reach, with every API test green.

Two consequences, both deliberate:

- The endpoints key on the **note number**, which is what the operator is actually given.
- The print is **offered at the moment the return is taken** (`offerReturnDocument`, shared by both flows),
  through the standard `uiConfirm` — that is when the customer is still at the counter and the paper is
  wanted. Offered, not auto-printed: a shop that does not hand out credit notes should not get a print dialog
  on every return.

**Follow-up, not done here:** a returns LIST screen, so a note can be reprinted later. It is also what
task #16 (vendor-filtered debit notes) and task #19 (bulk invoices) will need, and `getSaleReturns` +
`findDebitNotesForVender` already exist to feed it. Recorded rather than quietly bundled — printing at the
counter is the whole of what was asked for, and reprint-later is a screen.

---

# Part 2 — the returns LIST screen (task #21)

**Status:** DESIGN → implementation follows. Unblocks tasks #16 and #19.

Part 1 shipped the documents but left them reachable only at the counter: once the print prompt is dismissed,
a credit note cannot be found again. This part is the screen that makes them permanent.

## R1. What exists, and the asymmetry

| | List endpoint | Proxy | UI |
|---|---|---|---|
| Sale returns | ✅ `getSaleReturns` (findScoped, org + user NULL-fallback) | ✅ | ❌ nothing calls it |
| Purchase returns | ❌ **does not exist** | ❌ | ❌ |

So the sale side needs only a screen; the purchase side needs an endpoint first. `PurchaseReturnRepo.findScoped(orgId)`
already exists to back it — no new query, and no `userId` fallback for the reason recorded in §2.4.

## R2. ONE screen, two modes — not two screens

The two lists differ in exactly three things: the endpoint, the party column (customer vs supplier), and the
document label. Everything else — the table, the empty state, the reprint action, the scoping — is identical.

Two screens would mean two copies of "render a list of returns", and the second would drift from the first the
moment either changed. So: `showReturns(mode)` where mode is `'credit'` or `'debit'`, one `#ReturnsDiv`, one
render function parameterised by mode.

Two nav entries, though, because a return belongs with its own flow:

- **Sale returns** → `snavSell`, beside Parked Sales and Quotes
- **Purchase returns** → `snavPurchase`, beside New Purchase

## R3. Reprint reuses part 1 exactly

The per-row action calls `printReturnDocument(kind, noteNo)` — the same function the counter prompt calls. No
second print path, and no new endpoint: `/creditNote?no=` and `/debitNote?no=` already take the note number,
which is precisely why part 1 keyed on it.

A row whose note number is null predates the note series and has no printable document. Those rows still LIST
(they are real returns and hiding them would misstate history) but show no reprint action — the same rule the
counter prompt applies, and better than a button that always fails.

## R4. Visibility

`getSaleReturns` uses `findScoped(orgId, userId)`, so the USER/ADMIN/SUPER hierarchy already applies: a plain
user sees their own returns, an admin/owner the org's. The new `getPurchaseReturns` matches it. No new
privilege — a cashier who took a return is exactly the person who needs to reprint it, and the note is not
more sensitive than the invoice it reverses.

## R5. Work

| # | Change | Where |
|---|---|---|
| 1 | `GET /getPurchaseReturns` — scoped list | `PurchaseController` (business-service) |
| 2 | Monolith proxy | `PurchaseController` (monolith) |
| 3 | `#ReturnsDiv` + two nav entries | `businessDashboard.html` |
| 4 | `showReturns(mode)` + `renderReturns(mode, rows)` | `business.js` |
| 5 | i18n, six locales | `messages*.properties` |
| 6 | Gate | `cypress/e2e/business/returns-list.cy.js` |

### What the gate must assert

- Both modes list rows, and the party column shows a NAME (the same enrichment point as part 1 — a list of
  ids would pass a status check and be useless).
- **Reprint from the list reaches the document** — the whole purpose; assert `printReturnDocument` is invoked
  or the fetch fires, not merely that a button is drawn.
- A row without a note number shows no reprint action.
- Scoping: a plain user does not see another user's returns.

---

## ⚠ Defect found by the suite: a whitelist entry needs TWO files

`document-designer.cy.js` ("the browser renderer and the server validator agree on every field key") failed
after part 1: the five new header keys existed in `receipt.js` `FIELD_WHITELIST` and **not** in
`DocumentProfileValidator.java` (`HEADER_FIELDS`).

**Real consequence:** an owner designing a document with a credit-note field would have had the layout
**rejected on save** — the server validates against its own list, so the template silently fails to persist.

The validator states the rule in bold in its own javadoc:

> *"KEEP IN STEP WITH THE RENDERER … Adding a field means editing both, and each file carries a comment
> pointing at the other."*

The duplication is deliberate — *"a server that trusts the client's list is not validating anything"* — which
is correct, and is exactly why a cross-checking gate exists. Fixed; the five keys are now in both.

**Rule for any future field:** `receipt.js` FIELD_WHITELIST **and** `DocumentProfileValidator` HEADER_FIELDS /
LINE_FIELDS / TOTAL_ROWS, in the same change, or `document-designer.cy.js` fails.

---

# Part 3 — supplier filter and bulk print (task #16)

**Status:** IMPLEMENTED, awaiting gate — `cypress/e2e/business/debit-note-supplier-filter.cy.js`.

## S1. Mostly reuse, by design

`PurchaseReturnRepo.findDebitNotesForVender(venderId, orgId, userId)` already existed for the AP statement,
with the same org + user NULL-fallback scoping. So the filter is a **reuse**, not a new query — and one that
cannot be pointed at another tenant's supplier, because the scope predicate lives inside it.

`GET /getPurchaseReturns` now takes an optional `venderId`. Filtered **in SQL**: "this supplier's debit notes"
on a distributor with years of returns must not load every row to discard most of them.

The supplier picker reads `getUserVenders` — the same list the purchase form uses. One definition of "which
suppliers does this tenant have".

⚠ `getUserVenders` answers with **`<option>` markup, not JSON**. Use `bgGet`, not `bgJson`: forcing a JSON
parse on markup yields nothing and fails silently. (Caught during implementation — the first version called a
non-existent `getUserVender` and expected `{collection}`.)

## S2. Bulk print is ONE job, not N dialogs

**The trap:** calling `printReturnDocument` in a loop fires `window.print()` per document. Twenty notes would
stack twenty dialogs on the operator and the browser would drop most of them — it works on three rows and
fails on a real supplier's month.

`buildHtml` returns a COMPLETE document, so the strings cannot simply be concatenated (the second document's
`<head>` would land inside the first one's body). `printCombined` parses each, lifts its body out, and
re-wraps the set in the first document's head with a page break between them. One print dialog, N pages.

Fetched in parallel but **ordered by the request array**, not by whichever response arrived first — a
supplier's notes printing in random order is a poor document to hand over.

**This settles the bulk question left open in tasks #16 and #19:** combine into one job client-side. Task #19
(bulk from the Sale Report) should follow this rather than inventing a second answer. A server-side document
job is still the right move past a few hundred documents; nothing here needs it yet.

## S3. ⚠ Gate corrections against GATE-RUNBOOK

Reviewing the runbook exposed two faults in this cluster's earlier gates:

1. **Wrong tenant.** #15 and #21 ran only as `owner.business@` (POS). Supplier returns are a DISTRIBUTION
   concern, so this gate runs as `owner.marketplace@` via `cy.loginAsMarketplaceOwner()` — which also
   validates against `/getOrders`, the endpoint that tenant actually owns.
2. **No privilege ladder (rule 4).** Added. ⚠ `user.marketplace@` **does not exist** — the marketplace org's
   non-owner member is `booker.marketplace@myplus.com` (`ROLE_ORDER_BOOKER`, no `ADMIN_PRIVILEGE`), which is
   the stronger fixture anyway: it proves a rep with no admin rights can still read the register.
   *Existence is not eligibility — check `commands.js` for the account before writing the ladder case.*

**Still owed on #15/#21:** a privilege-ladder pass of their own.

---

# Part 4 — invoice documents from the Sale Detail Report (task #19)

**Status:** IMPLEMENTED, awaiting gate — `cypress/e2e/business/sale-report-invoices.cy.js`.

## I1. The distinction that defines the slice

The report already had Print / Excel / PDF buttons. **Those are DataTables exports: they dump the TABLE.**
What was missing is the invoice DOCUMENT for the sales listed — tenant header, customer, lines, totals,
document number — produced by `receipt.js` / `document-pdf.js`.

Restyling a table export would have looked finished and shipped the wrong artifact, so the gate asserts
`getReceipt` is called and returns an invoice, which a grid export would never touch.

## I2. ⚠ The unit is an INVOICE, not a report row

The report lists sale **lines**, so a three-line sale appears three times. `srVisibleInvoiceNos()` takes the
DISTINCT invoice numbers from the rows **currently rendered** — so the buttons follow the DataTable's own
search/filter state, and cannot produce a document the operator was not shown.

De-duplication is the strongest assertion in the gate: handing someone the same invoice three times is worse
than missing one, because a duplicate in a stack reads as a second charge.

## I3. ⚠ A fifth unreachable feature found

`downloadInvoicePdf()` and `downloadChallan()` have existed in `document-pdf.js` **with no caller anywhere in
the codebase**. This screen is the first to reach `downloadInvoicePdf`. The gate asserts it is *invoked with a
real invoice number*, not that a button renders.

Running tally of "shipped but unreachable" in this codebase: C1's inert service · C3's unregistered catalog ·
C6's invisible policy · PERF-4's never-run gate · `getSaleReturns` with no screen · now `downloadInvoicePdf`.
**A capability with no caller is the default failure mode here, not an anomaly.**

## I4. Bulk decisions, and why they differ between print and download

| | Choice | Why |
|---|---|---|
| Print | ONE job, N pages (`printCombined`) | N calls to `window.print()` stack N dialogs and the browser drops most — works on 3 rows, fails on a real month |
| Download | ONE PDF per invoice, sequential (400ms apart) | a merged PDF needs pdfmake concatenation, and the per-invoice file is what a manager attaches to an email; a simultaneous burst gets throttled or silently dropped |
| Confirm | print always; download at ≥10 files | N files is N browser downloads — a manager who meant to print should not receive two hundred |

Each invoice keeps its **own** resolved profile: a trade account still prints A4, a walk-in still prints the
slip. Forcing one paper size would make the bulk copy differ from the single copy of the same document —
exactly the drift `document-pdf.js` exists to prevent.
