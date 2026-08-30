# U12 — printing the shop's own labels

**Status: DONE + GREEN 2026-08-30 — label-print.cy.js 10/10, with the barcode asserted as DRAWN (rect/path children), not merely present.** Branch: `feature/pack-loose-selling`.
The last of the items U6/U7 declined.

U7 lets a shop register `LP-4471` to mean *one tablet*. **Nothing in the system can produce that label.** A
sticker code that cannot be printed as a scannable barcode is a code the shop has to write by hand and then
cannot scan — which is the whole point of it.

---

## 1. Review

| Verified | Where |
|---|---|
| ⭐ **No barcode encoder exists anywhere** — client or server, no JsBarcode, no ZXing, nothing | searched |
| The repo already vendors 8 third-party JS files (pdfmake, jszip, DataTables, Chart) | `js/business/*.min.js` |
| A document pipeline exists — `DocumentRenderer` + `document-pdf.js` (pdfmake) with per-format presets | `receipt.js` |
| Stickers are readable per product | U7 `/productBarcodes?productId=` |
| The CSP blocks external hosts, so any library must be **vendored**, never a CDN | Artifact/CSP rules |

**So the label LAYOUT has a home and the barcode IMAGE has nothing to draw it.** That single gap is the whole
slice.

## 2. What a label has to carry

```
   ┌──────────────────────────┐
   │  Panadol 500mg           │   the product, so a human can check the shelf
   │  1 tablet        12.00   │   what this code MEANS, and the price for it
   │  ▌▌ ▌ ▌▌▌ ▌ ▌▌ ▌▌▌ ▌     │   the barcode — the only part a scanner reads
   │  LP-4471                 │   the code as text, so a failed scan is still keyable
   └──────────────────────────┘
```

The last line matters more than it looks: **a barcode that will not scan must still be usable.** Printing the
code as text under it turns a bad print into a slow sale rather than a dead product.

## 3. ⚠ THE DECISION: where the barcode image comes from

There is no encoder in the codebase, and this is the one part of the slice I will not choose silently.

| | |
|---|---|
| **A · Vendor JsBarcode** (~30 KB, MIT) into `js/business/`, exactly as pdfmake and jszip already are | **Recommended.** A barcode is read by hardware the shop already owns; correctness is non-negotiable and an established encoder has been read by millions of scanners. Consistent with how this repo already handles third-party JS. **⚠ I cannot download it — you would drop the file in, then everything else here works unchanged.** |
| **B · I implement Code128 myself** (~120 lines, tested against published reference vectors) | No dependency, and Code128 is a small deterministic spec. **But a subtle checksum error produces labels that look perfect and do not scan**, discovered at the counter by a queue. I can verify against reference patterns; I cannot verify against your scanner. |
| **C · Export the sticker list as CSV** and let the shop print with the tool it already owns | Smallest and safest — no dependency, no encoder, ships today. It is a *lesser* answer: it does not print a label, it hands the codes to something that does. Many shops already own Dymo/Zebra software or a barcode font in Excel. |

**My recommendation: A, with C shipped alongside regardless.** C costs almost nothing, is useful on its own,
and means the shop is not blocked if the label sheet does not suit its printer. B is where standard 7a —
*do not re-invent what established systems already solved* — argues against me writing an encoder for
hardware I cannot test against.

## 4. The slice, assuming A

* **`labels.js`** — pick products (or "all stickers"), choose a sheet layout, render, print.
* **Layout as data, not code.** Labels per row, page margins and label size come from a small preset table —
  A4 3×8, A4 2×7, and a 40×25 mm roll — because every shop's stationery differs and a hard-coded grid fits
  exactly one of them.
* **Rendered as HTML and printed by the browser**, not pdfmake. A label sheet is a page of divs; pulling in
  the PDF path would add 900 KB to a screen that needs none of it. *(PERF-4 recorded pdfmake as ~70% of the
  remaining bundle — it is not a tool to reach for casually.)*
* **What gets printed:** one label per sticker by default, with a quantity box for "print 30 of this one",
  because a shop labelling a shelf needs many copies of one code, not one copy of many.

## 5. Refusals and edges

| # | | |
|---|---|---|
| 1 | a product with **no stickers** is skipped, not printed blank | a blank label wastes stationery and confuses the shelf |
| 2 | a code containing characters Code128 cannot encode | refused at registration (U7) rather than at print time |
| 3 | a print of **zero** labels | the button does not arm — the same rule as U11's Apply |

## 6. Gate — `label-print.cy.js`

1. ⭐ **a sticker renders a label carrying its code, product name and meaning** — `LP-4471`, `Panadol`,
   `1 tablet`.
2. ⭐ **the code appears as TEXT as well as a barcode** — §2, so a failed scan is still keyable.
3. **a quantity of 30 renders 30 labels.**
4. **a product with no stickers is skipped.**
5. **the layout preset changes labels per page** — 3×8 gives 24, 2×7 gives 14.
6. **the CSV export lists every sticker** — code, product, unit, quantity (option C, independently useful).
7. **nothing prints when nothing is selected.**

## 7. Performance

One read of the sticker list; rendering is DOM. No pdfmake, so the screen costs nothing to the bundle beyond
`labels.js` and the encoder.

## 8. Security

The sticker list is already org-scoped (U7). Codes and product names are tenant-authored text reaching the
DOM, so both go through `escHtml` — the same rule U6 §10.1 flagged when the unit noun first reached a cell
that had previously been numbers only.

## 9. What U12 does NOT do

* **No label DESIGNER.** Presets, not a drag-and-drop canvas. A shop that needs bespoke labels has
  `document-designer.js` for documents; a label designer is its own product.
* **No direct printer integration** — no ZPL, no network printers. The browser's print dialog.

---

## 10. The pinned case, and flipping it

Before the encoder was vendored the gate asserted that the screen **REFUSED to print** — because rendering
empty barcode boxes would let a shop spend a sheet of stationery on stickers that cannot be scanned, and find
out at the counter.

With the file in place that case flips, and now asserts the thing that actually matters:

    cy.get('svg.lbl-code').find('rect, path').should('have.length.greaterThan', 0)

**An empty `<svg>` would satisfy "the element exists" and print as a blank box** — precisely the failure the
refusal existed to prevent. Asserting the DRAWN children is the difference between proving the encoder ran
and proving an element is present.

`window.print` is stubbed, so the sheet is inspectable without a printer dialog.

*Pinning behaviour that is about to change, then flipping it in one place when it does, is the pattern U8 used
for the prescription-unit question: the test tells you what to change when the answer arrives.*

## 11. Verified about the vendored file

| | |
|---|---|
| Present at `js/business/JsBarcode.all.min.js` | 66 420 bytes |
| Loads and exports `JsBarcode` | as a function, checked outside a browser |
| Advertises `CODE128` | the format `labels.js` requests |
