# Sale report by company and by vendor (task #18)

**Status:** COMPANY half **IMPLEMENTED**, awaiting gate (`cypress/e2e/business/sale-report-by-company.cy.js`).
VENDOR half **DESIGNED, NOT BUILT** — see §3. Branch `feature/pack-loose-selling`.

## 1. The two halves are not equally available

| | Available? | Why |
|---|---|---|
| **By company** (manufacturer / brand) | ✅ already in the data | `ProductRef.manufacturer` is resolved for every report line already, exactly as `category` is |
| **By vendor / supplier** | ❌ not derivable today | a `Sell` has no vendor — vendor belongs to the PURCHASE |

## 2. Company — shipped

The report already enriched each line from `ProductRef` and mapped `category` as a dimension. Company is the
same shape:

| Change | Where |
|---|---|
| `manufacturer` field | `SellDTO` |
| map it from `ProductRef` | `SellController.loadSR` |
| filter predicate | `SaleReportFilter` |
| `COMPANY` grouping | `SaleReportGrouping` — one enum constant, as that type promises |
| `company` dimension + `companiesFrom(rows)` | `report-filters.js` (the SHARED rail — every future report gets it) |
| dimension + CSV param | the SR screen |

Options come from the RETURNED ROWS, like category — so the filter can only offer companies the report
actually contains. No master-list read, and no option that matches nothing.

## 3. ⚠ Vendor — designed, deliberately not built

### The trap

The quick implementation is "last purchase vendor, stamped on the product". It is **wrong**, and wrong in the
worst way: it silently **reattributes historical sales** every time a shop changes supplier. Last month's
report changes because you bought from someone else this morning. A number nobody can trust is worse than a
column that is not there.

### The accurate route — and it is already half-built

```
SellBatch (business-service)   →  batchNo, productId, quantity, sellId
StockEntry (inventory-service) →  batchNo, supplierId          ← the supplier IS recorded
```

Sales already record **which batches they consumed** (`SagaSaleWriter.recordBatches`, from the FEFO picks the
reservation returns). So the sold unit's origin is knowable — the link is just not carried across the seam:
**`StockPick` does not include `supplierId`.**

### Recommended: STAMP AT WRITE, not derive on read

Follow the project's own standing rule. Add `supplierId` to `StockPick`, have inventory populate it from the
batch it picked, and stamp it onto `SellBatch` when the sale is written. The report then joins locally — no
cross-service call, no N+1 on a report over a month, and the attribution is fixed at the moment of sale so it
can never be rewritten by later purchasing.

**Cost:** a contract change (`StockPick`), an inventory change, a Flyway column on `sell_batch`, and the
writer stamping it.
**Limitation, unavoidable:** **no historical backfill.** Sales written before the change have no supplier and
never will — the same limitation `lastPurchaseRate` carries. The report must show those as "—" rather than
guessing, and the UI must say the data starts from the change.

### Alternative, if a read-time answer is wanted sooner

A batched inventory endpoint resolving `(productId, batchNo) → supplierId` for a whole page. Cheaper to build,
but it is a cross-service call on a report path, and it still cannot answer for batches that have since been
consumed and purged.

**This needs consent before building** — it touches a shared contract and adds a column.
