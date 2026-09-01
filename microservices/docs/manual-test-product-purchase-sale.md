# Manual test script — Product registration → Purchase → Sale

**Purpose.** A tester-runnable script for the three screens that carry the whole commerce story: the
**product master** (`#ProductDiv` / `#ProductModal`), the **purchase** form (`#purchaseDiv`) and the **sell**
screen (`#sellDiv`). Part 1 is a review of the registration form as it stands today, with the defects a
tester should expect to hit. Part 2 is the scenario list.

Companion to `manual-test-use-cases.md` (journeys / demo shot list) — this one is deliberately field-level.

## Accounts and setup

| Tenant | Account | Password | Why this one |
|---|---|---|---|
| POS / retail counter | `owner.business@myplus.com` | `Demo@2025!` | plain `retail` shape — the baseline form |
| Pharmacy | `owner.pharma@myplus.com` | `Demo@2025!` | pack/loose, batch + expiry, FEFO |
| Mobile shop | `owner.mobile@myplus.com` | `Demo@2025!` | serial/IMEI + condition grading |
| Privilege ladder | `admin.business@`, `user.business@` | `Demo@2025!` | members of the owner's org — proves the privilege gate, not org scoping |

Capabilities are per tenant. `serialTracking`, `batchTracking`, `bonusSchemes` and `conditionGrading` hide
their own controls (`[data-capability]` → `.cap-off`) when the tenant lacks them, so **a missing field is a
capability question before it is a bug**. Check Configuration before filing.

---

# Part 1 — Review of the product registration form

## What the form is

`businessDashboard.html` §`#ProductDiv` opens a full-viewport overlay (`#ProductModal`) driven by
`/js/business/catalog-products.js`, writing to catalog-service through the monolith proxies
`/addProduct`, `/updateProduct`, `/deactivateProduct`, `/activateProduct`, `/productNameCheck`,
`/getCatalogProduct`, `/getUserProduct`.

Alongside the form sits the **"Already registered" panel** — the tenant's whole catalogue (including
deactivated products), narrowed live by what is typed into Name / SKU / Barcode, with an *"edit this one"*
link on every row. It exists so a near-miss is corrected instead of duplicated.

## Field rules as implemented

| Field | id | Required | Client rule | Server rule |
|---|---|---|---|---|
| Name | `#prodName` | **yes** | non-blank, else `showFormError`; on blur, `/productNameCheck` **advises** on a namesake | `@NotBlank`; `checkName` reports, never rejects |
| SKU / Code | `#prodSku` | no | blocked on blur + on save when it duplicates the loaded index; blank → `null` | blank → `NULL` (many products may share "no code"); a real duplicate → `DuplicateResourceException` |
| Barcode | `#prodBarcode` | no | narrows the panel only | trimmed to null — **no uniqueness check** (see F1) |
| Sell Price | `#prodPrice` | no | `s2n()` — blank becomes `0` | stored as given |
| Tax | `#prodTaxCode` / `#prodTax` | no | a chosen code hides the % box and sends `taxCodeId` with `taxRate:null`; "Custom rate…" sends the reverse | the code's rate wins in `resolveRate()` |
| Unit + pack size | `#prodUnit`, `#prodPackSize` | no | `packSize > 1` reveals the loose row; dropping below 2 clears `allowLoose` and forces `PACK` | **`null` = "not supplied", never "clear it"**; a change to `packSize`/`allowLoose` stamps `packChangedBy/At` |
| Loose unit + plural | `#prodLooseUnit(Plural)` | no | visible only with a pack size | `null` leaves the stored value |
| Loose selling | `#prodAllowLoose`, `#prodDefaultSellUnit` | no | always posted (`false` / `PACK` when hidden) | audited alongside pack size |
| Tracking | `#prodRequiresSerial`, `#prodTracksBatch` | no | **separate call** `/setProductTracking`, and only for capabilities the tenant holds | ADMIN-gated **and** capability-gated; a refusal arrives as HTTP 200 + `success:false` |
| Category | `#prodCategory` / `#prodCategoryNew` | no | inline add POSTs `/addCategory` | id resolved scoped; free-text name is find-or-create |
| Manufacturer | `#prodManufacturer` / `#prodManufacturerNew` | no | **no master table** — options are the distinct brands already on this org's products; inline add is local until save | plain string |
| Own stickers | `#prodStickerWrap` | no | only on a **saved** product (`/addProductBarcode`) | a sticker may not shadow a real barcode |

## Findings

| # | Severity | Finding | Proved by |
|---|---|---|---|
| **F1** | **High** | **Barcode uniqueness is enforced nowhere.** The client duplicate check covers SKU only (`isDuplicateSku`), and `ProductService.create/update` check only `existsBySkuScoped` — there is no `existsByBarcode` in `ProductRepository`. Two active products can carry the same EAN, after which `/products/lookup` and `/products/scan` resolve whichever the query returns first. | PR-7 |
| **F2** | Medium | **A pack size cannot be un-set.** `fromDto` treats `packSize == null` as "not supplied" (right, for CSV / integration callers), and the form posts `null` for an emptied box. Clearing "Holds N pieces" therefore hides the loose row *in this session* but leaves the product divisible in the database. `allowLoose` *does* clear, so the product ends up with a pack size and no permission to split it. | PR-11 |
| **F3** | Medium | **The own-stickers panel is nested inside the loose row.** `#prodStickerWrap` sits inside `#prodLooseWrap`'s `.col-sm-4 > .row`, so `loadProductStickers()` "shows" it into a hidden ancestor: on a saved product with `packSize ≤ 1` the panel never appears, and when it does appear it is squeezed into a one-third-width column. A shop that prints PACK stickers for non-divisible goods cannot reach the feature. | PR-14 |
| **F4** | Medium | **A product cannot be renamed from the form.** `editProduct()` ends with `updateReadOnly(true)`, which does `$('#prodName').prop('disabled', true)`. Nothing in the product path calls `updateReadOnly(false)` — only the global `[id^="reset"]` click handler does, so Name stays disabled when the modal is reopened. | PR-16 |
| **F5** | Low | **A blank Sell Price is stored as `0`, not `null`** (`s2n('') → 0`), so an unpriced product reads as "free" in pickers and reports rather than "no price set". | PR-1 |
| **F6** | Info | The client SKU check reads a **snapshot** taken when the form opened; a colleague registering the same code a minute ago is invisible to it. The server is the real gate — and a failed index load must render the panel's **error** state, never an empty list that reads as "nothing is registered". | PR-5, PR-8 |
| **F7** | Info | Duplicate **names are legal by design** (same product, different pack or maker). The blur check only advises and highlights the namesake row. Do not file this. | PR-6 |

---

# Part 2 — Scenarios

Each case gives **who / setup / steps / expected / watch for**. Record PASS/FAIL in the sheet at the end.

## A. Product registration

### PR-1 — Bare minimum: name only
**Who:** `owner.business@` · Registration → Product → **New Product**

1. Type a name only (`Manual Test Widget`). Leave SKU, barcode, price, tax, unit, category, manufacturer blank.
2. Submit.

**Expected:** saves; the row appears in `#tableProduct`; the SKU column is empty; the product is offered in the
purchase and sell pickers.
**Watch for (F5):** the Price column shows `0.00`, not blank. Note whether that is acceptable for the shop.

### PR-2 — Name is mandatory on both sides
1. **New Product** → leave Name empty → Submit → inline error, no request sent.
2. Enter only spaces → Submit → same refusal (the client trims).
3. *(API)* `POST /addProduct` with `{"name":""}` → refused by `@NotBlank`, message *"Product name is required"*.

**Expected:** a nameless product cannot be created by either route.

### PR-3 — Two products with no SKU
1. Register `Widget A` with no SKU. 2. Register `Widget B` with no SKU.

**Expected:** both save.
**Watch for:** *"Product SKU already exists: "* — that is the old blank-collides-with-blank bug, and means a
blank is reaching the column as `''` instead of `NULL`.

### PR-4 — Duplicate SKU is refused
1. Register `Widget A` with SKU `MT-100`.
2. **New Product** → SKU `MT-100` → **tab out of the field**.
3. Correct it to `MT-101` and save.
4. Repeat step 2 with `mt-100` (different case).

**Expected:** step 2 turns the SKU box red immediately with the reason, and Submit is blocked; step 3 saves;
step 4 is also blocked by the client (it compares lower-cased).
**Watch for:** what the **server** does with `mt-100` if the client check is bypassed — the query is an exact
match, so case-folding depends on the column collation. Record the answer.

### PR-5 — Duplicate SKU created in another session (the stale-index case)
**Setup:** two browsers, both `owner.business@`.

1. Browser A: open **New Product** (the index snapshot is taken now) and leave it open.
2. Browser B: register `SKU-DUP`.
3. Browser A: enter the same `SKU-DUP` and Submit.

**Expected:** the client lets it through (its snapshot predates B), and **the server refuses** with
*"Product SKU already exists: SKU-DUP"* surfaced in the form. Nothing is created.

### PR-6 — Duplicate name advises, and offers the fix
1. Register `Panadol 500mg`.
2. **New Product** → type `panadol 500mg` → tab out.
3. Now type something into SKU, then into Barcode.
4. Click **"edit this one"** on the highlighted row.

**Expected:** step 2 warns and highlights the existing row in the panel; the save is **not** blocked (F7).
Step 3 repaints the panel and the highlight **survives**. Step 4 loads that product into the form and clears
the warning — no twin is created.

### PR-7 — Duplicate barcode *(expect a defect — F1)*
1. Register `Widget A` with barcode `8901234567890`.
2. Register `Widget B` with the same barcode. Record whether it saves.
3. Go to **Sell** → scan / type `8901234567890` into `#sellScan` → Enter.

**Expected today:** step 2 **saves** (no check exists); step 3 resolves to one of the two, arbitrarily.
**Report as:** F1. The desired behaviour is a refusal at step 2, mirroring the SKU rule.

### PR-8 — The panel must never lie about an empty catalogue
1. Stop catalog-service (or block `/getUserProduct` in devtools).
2. Open **New Product**.

**Expected:** the panel shows the red *"could not load the registered products"* message.
**Fail if:** it shows "no products registered yet" or an empty list — a false all-clear given directly before
the operator registers a duplicate.

### PR-9 — Inline category and manufacturer
1. In the form, type a new category name → **+** → it is added, selected, and the **visible button text updates**.
2. Type a new manufacturer name → **+** → same, but no request is sent.
3. Save. Reopen **New Product**.

**Expected:** the category persists (a real entity, via `/addCategory`); the manufacturer appears in the
dropdown only **after** the product carrying it was saved (F7 — no master table).
**Watch for:** a button still reading "— none —" while the hidden `<select>` holds the value — that is the
bootstrap-select refresh regression.

### PR-10 — Editing must not silently rewrite the manufacturer
**Setup:** a product whose brand is used by no other product.

1. Open it for edit. 2. Change only the description. 3. Save. 4. Reopen.

**Expected:** the manufacturer is unchanged.
**Fail if:** it has become the alphabetically-first brand — a `.val()` on a missing option falling back to
option 0.

### PR-11 — Pack rules, and un-setting them *(expect a defect — F2)*
1. New product, Unit `strip`, **Holds** `10` → the loose row appears. Fill `tablet` / `tablets`, tick
   **May be sold by the piece**, set **Sales start as pieces**. Save.
2. Reopen it: every one of those values must round-trip.
3. Clear the **Holds** box → the loose row hides and the tick clears. Save.
4. Reopen it.

**Expected 1–2:** an exact round-trip.
**Expected today at 4:** the loose row is **back**, still showing pack size 10, with "may be sold by the piece"
now off. Report as F2 — the intended behaviour is that clearing the box makes the product non-divisible.
**Also check:** the pack-rule audit stamp (`packChangedBy` / `packChangedAt`) moves on step 1 and step 3.

### PR-12 — Tax code vs custom rate
1. Configuration → Tax codes: ensure two codes exist (e.g. `GST 17%`, `Zero 0%`).
2. New product → pick `GST 17%` → the custom `%` box hides → save.
3. Reopen: the code is preselected, the `%` box still hidden.
4. Switch to **Custom rate…** → enter `5` → save → reopen.

**Expected:** the product list shows 17% at step 3 and 5% at step 4. A chosen code sends `taxRate:null`;
"Custom rate…" sends `taxCodeId:null`.
**Watch for:** switching to Custom and leaving the box empty — record whether the product silently ends at 0%.

### PR-13 — Tracking flags are policy, gated twice
1. As `owner.mobile@` (has `serialTracking`): tick **Needs a serial / IMEI** → save → reopen → still ticked.
2. As `owner.business@` (retail, no serial capability): the checkbox carries `cap-off` / is not visible.
3. On a **serial-tracked** product, edit *any other field* as a user whose tenant lacks the capability, and save.

**Expected:** step 3 must **not** clear `requiresSerial` — the flag is omitted from the payload, and omitted
means "leave alone".
**Watch for:** a refusal arriving as **HTTP 200 with `success:false`** — assert the envelope, not the status.

### PR-14 — Own stickers *(expect a defect — F3)*
1. As `owner.pharma@`, open a **saved** product with **Holds = 10** → the *Own stickers* panel is visible.
   Add code `LP-4471`, unit **Piece**, qty `1`. It lists; remove it; it goes.
2. Now open a saved product with **no pack size** (e.g. a shampoo bottle).
3. Confirm the panel is absent on a **new, unsaved** product.

**Expected today at 2:** no sticker panel at all, because it is nested inside the loose row. Report as F3.
Step 3's absence is intended — a sticker needs a product to point at.

### PR-15 — Save & Add Another (batch cataloguing)
1. New product: set Manufacturer, Category, Tax code and Unit, plus name / SKU / price → **Save & Add Another**.
2. Enter a second product's name / SKU / price → **Save & Add Another**.
3. **Submit** the third.
4. Open an **existing** product for edit and press **Save & Add Another**.

**Expected:** the modal stays open; Manufacturer / Category / Tax / Unit **persist**; Name, SKU, Barcode,
Price, Description **clear**; focus lands on Name; the counter reads *2 products added* after step 2; step 3
saves and closes; the grid refreshes without resetting the retained dropdowns. Step 4 behaves as a plain
save-and-close (there is nothing to add another of).

### PR-16 — Rename an existing product *(expect a defect — F4)*
1. Open a product for edit. 2. Try to change the Name. 3. Close, open a different product, try again.

**Expected today:** the field is disabled — a rename is impossible from the UI, and it stays disabled on the
next product too. Report as F4: a typo in a product name is not correctable by the person who made it.

### PR-17 — Deactivate, show inactive, reactivate
1. Tick a product → **Delete** → confirm.
2. Check the purchase and sell pickers.
3. Tick **Show inactive** → **Reactivate**.

**Expected:** step 1 *deactivates* (history intact, never a hard delete); the product disappears from both
pickers; it still **owns its SKU**, so registering a new product with that SKU is still refused; step 3
restores it to the list and to both pickers.

### PR-18 — Operator text is escaped
1. Register a product named `<img src=x onerror=alert(1)>`.
2. View it in the grid, in the "Already registered" panel, in the purchase picker and on a receipt.

**Expected:** rendered as literal text everywhere. No alert.

### PR-19 — Privilege ladder
Repeat PR-1 and PR-17 as `admin.business@` and then `user.business@`.

**Expected:** `user.` may register but is refused the deactivate; `admin.` may do both. A refusal must be a
readable message, not a silent no-op.

### PR-20 — Tenant isolation
1. Note a product id created under `owner.pharma@`.
2. Log in as `owner.business@` and request `/getCatalogProduct?id=<that id>`.

**Expected:** not found / refused. It must not appear in this org's list, panel or pickers.

## B. Purchase (goods in)

### PU-1 — First receipt: stock in, rates stamped
**Setup:** `Manual Test Widget` from PR-1, on hand 0.

1. **New Purchase** → invoice `PINV-001`, product = the widget, QTY `10`, **P/U Price** `80`,
   **S/U Price** `120`, purchase date today → **Save & Close**.
2. Look at the product row: **On hand**, **Last purchase**, **Last sale**, **Price**.
3. Record a second purchase for the same product with **P/U 85 and the S/U box left blank**.

**Expected:** at step 2, on hand `10`, last purchase `80`, last sale `120`, and the catalog **Sell Price
becomes 120** (re-price on receive). At step 3, last purchase becomes `85` while last sale and the selling
price are **unchanged** — a cost-only receipt must never re-price what the shop charges.

### PU-2 — Batch and expiry
**Who:** `owner.pharma@` (needs `batchTracking`).

1. Receive product `X`: batch `B-1`, expiry **next month**, qty 10.
2. Receive `X` again: batch `B-2`, expiry **next year**, qty 10.
3. Go to **Sell** and pick `X`.

**Expected:** the batch / expiry banner names **B-1** (earliest expiry — FEFO), not the newest receipt.
On hand is 20.

### PU-3 — Buying by the BOX
1. New purchase, product `X`, toggle **Box**, **Packs per box** `10`, QTY `2`, P/U `1000`.
2. Read the hint line before saving.

**Expected:** the hint states what will actually be stored — **20 packs at 100 each**. After saving, on hand
rises by 20 and the last purchase rate is `100`, not `1000`. Nothing downstream mentions "box".

### PU-4 — BOX with no packs-per-box
Repeat PU-3 but leave **Packs per box** empty, then save.

**Expected:** a refusal, or an explicit prompt.
**Fail if** it silently assumes 1 — that is the tenfold costing error the toggle exists to prevent. Record the
actual behaviour either way.

### PU-5 — Bonus units *(needs `bonusSchemes`)*
1. Receive QTY `10`, **Bonus** `2`, P/U `80`.
2. Repeat as a tenant without the capability.

**Expected:** on hand rises by **12**; the bill totals **10 × 80 = 800**; the bonus shows as its own figure on
the purchase row, never folded into QTY. At step 2 the Bonus box is not shown at all.

### PU-6 — Credit purchase and the vendor's prior balance
1. Pick a vendor → the **Outstanding due** box appears with what that vendor was already owed.
2. Receive 10 × 100 = 1000, **Amount paid** `400` → save.
3. Open a new purchase for the same vendor.
4. Repeat with **Amount paid left blank**.

**Expected:** the payable rises by 600 and the Outstanding due box reflects it at step 3. A blank Amount paid
means paid in full and must create **no** payable.

### PU-7 — Rate floor
Try to save with **P/U Price** `0`, then `-5`, then blank.

**Expected:** all three refused (`min=0.01` plus the submit guard). A zero-cost bill must never reach COGS.

### PU-8 — Serial / IMEI on receipt *(`owner.mobile@`)*
On a product flagged **Needs a serial / IMEI**:

1. QTY `3`, two IMEIs → refused: *"…is for 3 unit(s) but 2 serial number(s) were entered."*
2. QTY `3`, the same IMEI twice plus one more → refused: *"…was entered twice."*
3. QTY `3`, three IMEIs, one already in stock → refused: *"…is already in stock."*
4. QTY `3`, three good IMEIs, **Condition = Used** → saves; the register holds three units graded `USED`.
5. Paste three IMEIs **comma-separated** instead of line-separated → accepted.
6. Leave a trailing blank line → still accepted (blank lines are dropped, not counted).
7. On a product **without** the flag, leave the serials box empty → saves normally.

**Expected:** every refusal happens **before** anything is written — no half-saved purchase to unpick.

### PU-9 — Save & Add Another (one delivery, many lines)
1. Line 1 with vendor + invoice # + date → **Save & Add Another**.
2. Line 2, then line 3 → **Save & Close**.
3. Repeat using `Ctrl+Enter` to save and `Esc` to cancel.

**Expected:** vendor, invoice # and date persist across lines; the line counter increments; all three lines
land on the same purchase invoice.

### PU-10 — Purchase tax row
Turn the org's **Purchase tax** setting off → the *Purchase tax (%)* row is hidden. Turn it on → the row
appears, a blank rate falls back to the org default, and the input tax is added to the bill.
*(Leave the setting as you found it — a server-wide switch must not be left flipped.)*

### PU-11 — Edit a purchase
1. Open an existing purchase for edit.
2. Change QTY from 10 to 8 and save.

**Expected:** the invoice # and the product picker are read-only; stock moves by the **delta** (−2), never by
re-adding 8. Rates re-stamp on the product per PU-1's rule.

### PU-12 — Deactivated product
Deactivate a product, then open **New Purchase** → it is not in the picker.

## C. Sale

### SA-1 — Barcode-first selling
1. **Sell** → focus `#sellScan` → type the barcode of the PU-1 widget → Enter.
2. Scan an unknown code.
3. Scan a **deactivated** product's barcode.

**Expected:** step 1 adds the line with qty 1 at the catalog price, then clears the scan box and keeps focus;
step 2 gives a readable message in `#sellScanMsg` and adds no line; step 3 is refused with a reason.

### SA-2 — The shop's own sticker *(needs PR-14's sticker)*
Scan `LP-4471` (Piece × 1) → the line is added as **one piece**, not one pack, at the per-piece price.

### SA-3 — Selling loose
**Setup:** pharmacy product, Holds 10, may be sold by the piece, pack price 120, 5 packs on hand.

1. Pick it → the **Pack / Piece** toggle appears; press **Piece** (or `F7` / `Alt+L`).
2. Enter qty `5`.
3. Complete the sale.
4. Try the same on a product that may **not** be split.

**Expected:** the hint reads *"5 tablets · 12.00 each · uses 0.5 of a pack"*, and the per-piece price comes
from the server (`/looseInfo`), not from arithmetic in the browser. On hand falls from 5 to **4.5**; the
receipt shows pieces and its total equals what the hint quoted. At step 4 the toggle does not appear at all.

### SA-4 — Sellable vs on-hand
**Setup:** 10 on hand, of which a batch of 4 is expired.

**Expected:** the sell screen offers **6**; asking for 8 is refused with a reason naming *sellable* stock —
not a bare "Insufficient stock" sitting next to an on-hand figure of 10.

### SA-5 — Serial-tracked sale *(`owner.mobile@`)*
1. Add a serial-tracked handset to the cart with the **Serial / IMEI** box empty → the sale is **refused** by
   the server.
2. Enter an IMEI that is in stock and graded **USED** → the condition shows beside the box **before** payment.
3. Enter an IMEI that is not in stock → refused.
4. Complete the sale, then try to sell the same IMEI again → refused (it has left the shelf).
5. A non-tracked line in the same cart (e.g. a charger) demands no serial.

### SA-6 — Batch traceability
Sell the PU-2 product → the invoice line records **B-1**, and the sale detail report can show which batch left
the building. When B-1 is exhausted, the next sale draws from B-2.

### SA-7 — Customer-first pricing
1. Add a product to the cart **with no customer selected** — note the plain catalog rate.
2. Now select the wholesale customer who has a contract price.

**Expected:** the line re-prices, and `#sellPriceReason` states **why**. Rules never stack — the most specific
live rule wins alone.

### SA-8 — Credit limit
Sell to a customer beyond their limit → a warning that asks for a decision, not a silent block and not a
silent pass.

### SA-9 — Edit an invoice
1. Open a saved invoice for edit → the customer is locked.
2. Change a line quantity and save.

**Expected:** the **same** invoice is updated — no second invoice, no duplicated row — and stock moves by the
delta.

### SA-10 — Void
Void an invoice as `admin.business@` → stock returns, the document is marked void with a trail, and the
reversal reaches the ledger. `user.business@` is refused.

### SA-11 — Tax on the line
Sell the PR-12 product → the line tax uses the **tax code's** rate, and the same figure appears on the receipt
and in the tax register.

### SA-12 — Split tender and change
Take part cash, part card; over-tender the cash portion → change is computed and the invoice balances.

## D. The golden thread — one product, end to end

Run this as one sitting; it doubles as the recording script.

1. **Register** `Golden Widget`, SKU `GW-1`, barcode `8909998887776`, unit `pack`, **Holds 10**, loose unit
   `piece` / `pieces`, may be sold by the piece, tax code `GST 17%`.
2. **Purchase** 10 packs @ **80**, S/U **120**, batch `GW-B1`, expiry next year, vendor on credit, paid 400.
3. Product list: on hand **10**, last purchase **80**, last sale **120**, price **120**.
4. **Sell** 2 packs and 5 pieces to a walk-in customer.
5. Check on hand: `10 − 2 − 0.5 =` **7.5**.
6. Check the invoice: 2 × 120 plus 5 × 12, tax at 17%, batch `GW-B1` on both lines.
7. Sale Detail Report: the invoice appears with the right customer, tax and margin
   (`120 − 80` per pack, `12 − 8` per piece).
8. Vendor statement: 600 outstanding from step 2.

**Any figure that disagrees at step 5, 6 or 7 is a stop-and-report, not a retry.**

---

## Result sheet

| Case | Build / date | Tenant | Result | Notes |
|---|---|---|---|---|
| PR-1 … PR-20 | | | | |
| PU-1 … PU-12 | | | | |
| SA-1 … SA-12 | | | | |
| Golden thread | | | | |

Expected failures on today's build: **PR-7 (F1)**, **PR-11 step 4 (F2)**, **PR-14 step 2 (F3)**,
**PR-16 (F4)**. Anything else that fails is new.
