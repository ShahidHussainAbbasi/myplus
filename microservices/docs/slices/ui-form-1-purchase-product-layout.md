# UI-FORM-1 — purchase and product form layout

Status: **IMPLEMENTED 2026-09-26 (user: "go ahead with the full layout") — gate RED 11/11 on the old build, awaiting
the monolith rebuild.** NOT committed.

## Test runs (2026-09-26, after the first rebuild)
- `form-layout.cy.js` **11/11 green**. Regression across 35 purchase/product specs (3 batches):
  - purchase: 11 specs, all green except `purchase-batch-expiry` 2 cases — the owner tenant (org 13) is shape
    **retail since 2026-09-23** (org_shape_history), which has no batchTracking; the old markup gated the field the
    same way. `purchase-rapid-entry` 1 red = MY spec edit (an extra Enter that walked past the vendor) — fixed, 28/28.
  - product: `product-import` 1 red = MY miss from PH-FORMULA (template header gained `formula`; the spec was never
    re-run) — fixed with TEMPLATE_HEADERS, 20/20. Not layout: `product-pack-size` 3 (org 13 retail = no looseSelling),
    `product-catalogue-complete` 4 + `product-screen-payload` 1 (fixture: <1000 products, the gate fails loudly by
    design), catalogue case 5 (2 getProductPage calls = grid + panel, both pre-existing), `product-existing-panel`
    "broken list" (waits on /getUserProduct; PS-1 moved the panel to /getProductPage).
  - others: `busy-controls` case 8 = **a REAL defect of this slice** (below). Not layout: `b2b-customer-type` (a
    stored setting default), `catalog-refs-cache` 3 (bash not found — my run was launched from cypress/e2e),
    `negative` (sale-screen hook).
- **Defect found by busy-controls case 8:** the folded panel unfolded when the SEARCH landed, so Save & Add Another
  moved between the pointer going down and the click; the click missed and nothing saved (probe: saveProduct called
  0 times, no error). Fix: unfold on the KEYSTROKE (`input` → syncExistingIdle) and reserve the unfolded panel's
  height (230px, phone 190px; the list gives way to the message line). New gate case "typing a name never moves the
  Save buttons" — red on the build under test (moved 673 → 698). Awaiting the second rebuild.

## Implementation notes (traced while building)
- **`form-horizontal` stays on both forms:** main.js finds forms by ORDINAL (`getElementsByClassName('form-horizontal')
  [tableV]`, 4 sites) — dropping the class would re-point every later form. Cells use `.fg-label`, never `.control-label`.
- **Buttons stay the LAST elements of each form:** `validateForm` checks `form.length - 2` elements, skipping the last
  two as the buttons.
- **Read-only figures stay INPUTS** (description, stock, total, profit, vendor dues): populateFormData posts disabled
  and readonly fields too (`totalAmount`, `netAmount`, `description`, `stock.stock`). They are restyled
  (`.fg-display`), not converted to text.
- **Sticky footer:** the OVERLAY is the scroll container; `.crud-box{overflow:hidden}` would have stopped `sticky`, so
  `.fg-modal` sets `overflow:visible` (which also means no new scroll container to clip bootstrap-select menus or the
  date picker).
- **Visibility hooks unchanged:** `.cap-off` / `.pos-hidden` are `!important` classes; the serial row's
  `data-pos-field="serial"` is now on BOTH cells (serial and condition), toggled per element (business.js 5907).
  Barcode: the label moved INSIDE `#prodBarcodeWrap`; business.js toggles both ids with the same flag.
- **Stickers (#prodStickerWrap) stay inside the loose row**, as before, so they still show and hide with it.
  Observation (not changed): that means a non-divisible product can never show its stickers, although a sticker can
  be a PACK sticker. Raise separately.
- "Already registered" is FOLDED, not removed: `.is-idle` hides the list box only; rows stay rendered; never folded on
  a load failure, "nothing registered yet", or a flagged duplicate (`syncExistingIdle`).
- Product title via `t('ui.js.newProduct' / 'ui.js.editProduct')` (it was an English literal). 11 new message keys in 6
  locales. Purchase: "Bill incl. tax: X (tax Y)" under Paid when purchase tax is on.
- **Specs updated for the new walk:** purchase-rapid-entry (chain list, data-kbd-skip, tax position, the walk, and the
  two unanswered-picker cases now start from the invoice #).
- **Gate `form-layout.cy.js` (11):** first draft asserted the new CLASSES and went red on the old build only for their
  absence — rewritten to judge GEOMETRY (controls inside the modal, no overlaps, Pack/Box side by side, Paid above the
  buttons, buttons on screen). Red on the old build for real reasons: Pack/Box stacked 34px apart; Save buttons at
  827-1685px, off screen at 768-1000px viewports. Request: "fix the fields alignment on the forms to
reduce the space and make more professional, impressive UI/UX".

## 1. Review (measured, demo.business@ — the tenant with the most fields: batch, serial, condition, bonus)
Screenshots at 1366 / 1024 / 390 px, heights measured from the DOM:

| Form | Desktop/tablet height | Phone height | Visible fields | Rows |
|---|---|---|---|---|
| Purchase | 582 px | **1461 px** | 17 | 9 |
| Product | 543 px (+ ~250 px "Already registered" panel) | **1093 px** | 17 | 8 |

### Purchase — defects (not taste)
- **P1 Broken row:** QTY / Bonus / Pack-Box / Packs-per-box / Stock in hand sum to **20 of 12** Bootstrap columns.
  The Pack/Box toggle wraps to the far LEFT edge, stacked vertically, outside the label column; Stock in hand
  floats under it.
- **P2 Amount Paid is not a `.form-group`** (a bare `<div>`, line 2248), so the three action buttons float onto
  the SAME line as the Paid box. The new "Due on this line" hint sits under it, beside the buttons.
- **P3 Bill header and item line are interleaved.** Batch # (a LINE field) sits beside the bill's invoice #;
  Purchase Date (a HEADER field, retained by Save & Add Another) sits near the bottom beside Expiry. So the
  operator can't see which fields carry over to the next line and which reset.

### Purchase — space
- Vendor row: right half empty. Description is a read-only input taking a full slot. Stock in hand, Total and
  Profit are disabled inputs, which look like fields to fill in.
- Phone: 1461 px, and the Save buttons are at the very bottom.

### Product — defects
- **R1 No title.** `<h4 id="ProductModalTitle">` is commented out (line 3719), although catalog-products.js sets
  "New Product"/"Edit Product" on it (366, 936). The operator can't tell whether they are adding or editing.
- **R2 Button words differ** from every other form here: "Submit" (the purchase form says "Save & Close").

### Product — space
- "Already registered" shows 40 of 3591 products (~250 px) before anything is typed. It is useful only once a
  name is being typed.
- Made-to-order and Tracking each take a full row for one or two checkboxes. Description is last, beside
  Manufacturer.

## 2. How others lay these out (market check)
- **Tally Prime / Marg ERP / Busy (the local leaders):** purchase = bill header first (party, supplier invoice
  no., date), then item lines, then a totals footer (amount, tax, bill, paid, balance).
- **Odoo:** header block (vendor, bill reference, date), line grid, totals on the right, sticky action bar.
- **Shopify / Stripe (SaaS forms):** labels ABOVE fields, grouped in titled sections, which read well from phone
  to desktop without two layouts.

## 3. Proposal
```
┌ New Purchase ─────────────────────────────────────────────── × ┐
│ BILL  (kept for the next line)                                  │
│ [Supplier invoice #] [Vendor ▼ · owes 12,400] [Date]            │
│─────────────────────────────────────────────────────────────────│
│ ITEM  · line 3 on this bill                                     │
│ [Product ▼ ................ ] + New product   in stock: 14      │
│ [Serial / IMEI .........] [Condition ▼]                         │
│ [Qty] [Bonus] (Pack|Box) [Packs/box]   [Batch #] [Expiry]       │
│ [Cost / unit] [Sell / unit] [Tax %]                             │
│─────────────────────────────────────────────────────────────────│
│ Line total 100.00 · Tax 0.00 · Bill 100.00 · Profit 20.00       │
│ [Paid 100.00]   Due on this line: 0.00                          │
├─────────────────────────────────────────────────────────────────┤
│ [Save & Add Another] [Save & Close] [Cancel]   Enter · Ctrl+Enter│  ← sticky footer
└─────────────────────────────────────────────────────────────────┘
```
1. **Labels above fields, one CSS grid** (`.form-grid`: 4 columns ≥1200, 3 ≥992, 2 ≥768, 1 on phone, matching the
   767/991/1199 responsive contract). This fixes P1 by construction: no hand-counted Bootstrap columns.
2. **Two titled sections, Bill and Item** (P3): the header fields move together to the top, and the section
   caption says they are kept. The line counter moves into the Item caption.
3. **Read-only numbers become text, not inputs:** Description becomes grey sub-text under the product, Stock in
   hand an inline chip, and Total/Profit a totals strip above Paid (fixes P2, with Paid in its own row).
4. **Sticky action footer** on the modal: the buttons are always visible, including on a phone.
5. **Product form:** restore the title (R1); "Submit" becomes "Save & Close" (R2); the same grid in sections
   **Basics** (name, SKU, barcode when shown, sell price, tax, unit, category) and **Details** (manufacturer, formula,
   bought-as, description), with an **Options** row holding the checkboxes inline; "Already registered" is
   collapsed until Name/SKU/barcode has 2+ characters.

Estimated: purchase desktop ~582 → ~420 px, phone ~1461 → ~1000 px with actions always visible; product similar.

## 4. What must not break (Rule 0 — traced)
- **Element ids and `name`s stay the same.** main.js `populateFormData`, `PURCHASE_LINE_FIELDS`/
  `PURCHASE_HEADER_FIELDS` (whose guard throws on an unlisted field), and ~40 specs address them.
- **The Enter chain is DERIVED from DOM order** (`EnterChain.fieldsIn('#Purchase')`), so moving fields MOVES the
  keyboard walk. Required: serial before quantity (SER-3d). `purchase-rapid-entry.cy.js` pins the exact walk in
  one assertion, which will be updated to the new order: invoice → vendor → date → product → serial →
  condition → qty → bonus → batch → expiry → cost → sell → tax → paid.
- **Verified against `PURCHASE_LINE_FIELDS` / `PURCHASE_HEADER_FIELDS`:** header (kept) = invoice, vendor, vendor
  dues, date, product picker, condition; line (cleared) = everything else, INCLUDING the tax rate. So Tax % sits in
  the Item section. Condition is kept on purpose (SER-3c: a carton is one grade), which is why the Item caption says
  only "line N" and the Bill caption says what is kept.
- **Show/hide hooks** move with their fields: `data-capability`, `data-pos-field`, `pos-hidden`, and the
  bootstrap-select WRAPPER rule (U15).
- The purchase and product modals are nested (PUR-INLINE): the product modal opens over the purchase modal.
  Check the z-order after the sticky footer is added.
- RTL (ar/ur): the grid must follow `dir=rtl`.

## 5. Gate
- `form-layout.cy.js`: at 1366/1024/390 px, no field overflows its row; Paid and the buttons are in different
  rows; the buttons are visible without scrolling; the product modal shows "New Product"/"Edit Product".
- Re-run: purchase-rapid-entry, purchase-paid-autofill, purchase-inline-product, purchase-in-boxes,
  purchase-multi-serial, pharma-formula, and the catalog-product specs.
