# PUR-PAID-1 — Paid follows the line's bill on a new purchase

Status: **GREEN 4/4 2026-09-26 — see run log below. IMPLEMENTED 2026-09-26 (monolith only: business.js, main.js, businessDashboard.html, 6 messages files).
Gate `purchase-paid-autofill.cy.js` RED 4/4 on the old build** (Paid '' where 100.00 expected; no hint element) —
awaiting the monolith rebuild for the green run. NOT committed.
**Run 2 (after rebuild): 2/4 + rapid-entry 28/28.** Read the red run:
- case 2 = SPEC error: the purchase grid DTO carries `paidAmount`/`totalAmount` but NO `dueAmount` (NaN). Case 1's
  `dueAmount || 0 == 0` had the same flaw and passed on anything. Both now assert what the endpoint returns. DB-checked
  directly: auto line paid 100.00 / due 0.00; typed line paid 40.00 / due −60.00.
- case 4 = PRODUCT defect: Save & Add Another blanks the line by id (no input event), so the last line's "Due on this
  line" stayed under the empty Paid box — the SER-3c class. Fixed: `refreshPurchasePaid()` in the line-clear.
  Awaiting a second monolith rebuild.
**Run 3 (after the 2nd rebuild): 3/4.** Case 4 = SPEC error: line 2 reused the same product with a blank batch on the
same bill, which DuplicateBillLine (DOC-INT) rightly refuses. Line 2 now uses a second seeded product, and the save
asserts `status == SUCCESS` so a refusal names itself.
**Run 4: GREEN 4/4** (+ `purchase-rapid-entry.cy.js` 28/28 on the same build). DB-checked (org of demo.business):
AUTO 100/100/0 · PART 100/40/−60 · NEXT line 1 100/40/−60, line 2 30/30/0 (total/paid/due). NOT committed.
Request: "on new purchase purchaseTotalAmount should be auto populated or reflected in purchasePaid so the user will
not forget, otherwise it will become due."

## Traced (Rule 0)
- **Server** (`PurchaseService:452-465`): `bill = totalAmount + inputTax`; input tax only when the tenant's
  `inputTaxEnabled` and the resolved rate (the line's, else the org default) > 0, `net × rate / 100` half-up to 2 dp.
  The purchase discount does NOT reduce the bill. `paid = paidAmount != null ? paidAmount : bill`,
  `due = paid − bill`. **A blank Paid already means "paid in full"** — nothing becomes due by forgetting.
- What the user saw: an EMPTY Paid box that looks unpaid, and a typed partial amount silently creating a due.
- `#purchaseTotalAmount` = `qty × rate`, `disabled`, but `populateFormData` reads every field, disabled included,
  so it IS posted as `totalAmount`.
- Paid is PER LINE: each line is its own purchase row; `purchasePaid` is in `PURCHASE_LINE_FIELDS`, cleared between
  lines so one payment is never repeated per line.
- The client already fetches the tax setting (`refreshPurchaseTaxRow` → `/getTaxSetting`: `inputTaxEnabled`,
  `defaultRate`).

## Design
1. **Shown:** Paid displays the line's bill (qty × rate + estimated input tax) and follows it as the line changes.
2. **"Auto" = empty, or still exactly the value the till last wrote** (`data('auto')`). No flag bookkeeping: a new
   line and Save-&-Add-Another clear the box → auto again; typing the bill exactly means the same thing.
3. **Sent:** while auto, Paid is sent BLANK → the server records `paid = bill` with its own exact tax and rounding.
   The client's figure is display only and can never disagree with the books.
4. **Typed:** a different amount is the user's; it is sent as typed, and a line under the box says
   **"Due on this line: X"** (or "More than this line's bill by X") so a due is visible and deliberate.
   Emptying the box returns it to auto (blank = paid in full, as the server has always read it).
5. **Editing an existing purchase:** never auto-filled — its Paid is a payment already recorded. The due/over
   line still shows.
6. No server change.

## Gate — `purchase-paid-autofill.cy.js` (red first)
1. new line 2 × 50 → Paid shows 100.00; the request carries a BLANK paidAmount; stored paid 100, due 0;
2. typed 40 → "Due on this line: 60.00"; stored paid 40, due −60;
3. emptied → refills with the bill;
4. Save & Add Another → the next line's Paid is auto again; the typed 40 is not carried over.
