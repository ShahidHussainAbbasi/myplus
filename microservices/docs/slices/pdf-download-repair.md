# Download PDF — the repair

**Status:** ✅ **GREEN** (2026-09-07). Gate: `cypress/e2e/business/invoice-pdf-download.cy.js`.
**Ask:** *"yes fix the download pdf"* — the long-standing "Download PDF does nothing" report.

---

## 1. It was two independent defects, and the second was the big one

### D-1 🔴 pdfmake has no weighted star widths — **every** PDF failed

`columnWidths` returned `c.width + '*'` — `'5*'`, `'39*'` — under a comment stating *"pdfmake takes
star-weights, and a percentage is exactly a weight."* **That is false.** pdfmake accepts a number, the bare
string `'*'`, or `'auto'`, and nothing else.

Given `'5*'` it never resolves the value: `_calcWidth` stays as that string, and pdfmake then adds it to the
running x-offset. A numeric accumulator becomes text, and the library throws:

```
unsupported number: 085*1639*1612*1615*1613*0024
```

— those widths concatenated with the cell paddings between them.

**Every document whose profile declares column widths failed, which is every built-in preset**, and it failed
**silently**: both emitters wrap the render in `.catch(fail)`, so `.download()` produced no file and no error.
That is the larger half of the original report.

Fixed by turning the profile's percentages into **absolute points** against the printable area, normalised by
the actual sum. A bare `'*'` per column would also render, but it divides the page equally — a line number
would get the same width as an item description — so the designed layout would be lost. Verified against the
running app: `24.96, 194.72, 59.91, 74.89, 64.91, 79.88` pt, which with 6×8 pt padding is exactly the
547.28 pt A4 printable width.

Page geometry (`PAGE_MARGINS`, `PAGE_WIDTH`, `paperOf`) is now stated once and shared by the widths and the
document definition, because a table sized for one sheet on a document printed at another is how they came
apart in the first place.

### D-2 🟠 A batch of N invoices asked the browser for N downloads

The Sale Detail Report fired one download per invoice, 400 ms apart, under a comment admitting browsers
*"throttle or silently drop a burst"*. They do something more specific: Chrome and Edge treat the **second**
automatic download from a page as a permission decision and silently drop everything after it. **Spacing
could never have fixed it — the limit is on the COUNT, not the rate.**

`downloadInvoicesPdf` now emits ONE document, one invoice per page, sharing `documentBlocks`/`docDefinition`
with the single emitter. It is also the better document: twenty files in a downloads folder is a worse answer
than one twenty-page PDF.

---

## 2. ⚠ What was NOT wrong — the recorded theory, disproved

The defect was recorded as "the ~2 MB pdfmake bundle fails to load silently". A browser probe showed
`ensurePdfMake()` resolving, `pdfMake.vfs` present, `createPdf()` building, and `.download()` writing a real
3,888-byte file. The library loads fine.

That probe was also **misleading**, and worth knowing why: it happened to exercise a document with no declared
column widths, which takes the `'*'` branch and does render. One passing sample was read as "the library is
not the problem", and D-1 sat behind it.

---

## 3. ⭐ Why the gate does not assert a file on disk

It did, and both download cases timed out against an empty `cypress/downloads` — **including the
single-invoice case**, which exercises the long-standing path this slice never touched. A build where the fix
was broken could not have failed that case. What the empty folder measured was the test browser's handling of
a `blob:` download, not the product.

The cases assert the **real pdfmake output** instead: exactly one document created for three invoices, the
page count, and that `getBuffer()` returns `%PDF-` bytes. That is not a spy — a spy on `downloadInvoicesPdf`
would pass on a build that called it and produced nothing, which is the exact failure being fixed.

Two further traps, both of which cost a run:

* **pdfmake MUTATES the definition it is given**, rewriting every `table.widths` entry into
  `{width, _minWidth, _maxWidth, _calcWidth}`. Inspecting afterwards reads pdfmake's working state, not what
  the product built — it reported even `'auto'` as illegal. The recorder now deep-copies before handing the
  object over.
* **The render error is swallowed** by `.catch(fail)`, so a case that only inspects the definition passes
  while the document does not render. `renderToBytes` is what makes the failure visible, and it reports the
  offending widths with their paths so the cause is named rather than inferred.

---

## 4. Changes

| File | Change |
|---|---|
| `js/business/document-pdf.js` | absolute-point widths; shared page geometry; `downloadInvoicesPdf`; `emitPdf` shares `docDefinition` |
| `js/business/business.js` | `#srDownloadInvoices` emits one file; `ui.js.downloadNPages` |
| `cypress/e2e/business/invoice-pdf-download.cy.js` | 6 cases, incl. the width regression and single-document render |

## 5. Not done — open product question

These invoices resolve to `RETAIL_RECEIPT_80MM` (`layoutMode:'auto'`, no trade customer), so a bulk download
from the Sale Detail Report renders **80mm till slips onto A4 pages**. It renders correctly now, but whether a
report-driven bulk download should produce A4 invoices instead is a product decision, not a bug fix.
