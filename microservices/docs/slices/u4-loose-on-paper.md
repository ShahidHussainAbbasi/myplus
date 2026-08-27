# U4 — what the customer reads

**Status: DONE + GREEN 2026-08-27 — Cypress gate 7/7, business-service 206/206 (0 skipped). U1–U4 complete: the feature is shippable.** Branch: `feature/pack-loose-selling`.
Parent: `../pack-and-loose-selling-design.md`. Predecessors: [U2](u2-loose-sale-arithmetic.md) (the money),
[U3](u3-loose-at-the-till.md) (the till).

U3 lets a cashier sell five tablets. **U4 is the slice that lets them hand over a receipt that says so.**

Today that receipt reads **`0.5 × 120.00`** — a quantity no customer recognises for goods they are holding in
their hand, at a rate they did not agree to. The line is arithmetically correct and commercially unreadable.
U2 stored `soldQuantity`, `soldRate` and `packSizeSnapshot` precisely so this slice could print them.

---

## 1. Review — every place a stored line becomes something a person reads

Enumerated **before** designing, because this is a copy-point problem and this programme has lost three
defects to exactly that shape (`gl_outbox`, the product row projection, the monolith `SellDTO`).

| # | Render point | Where | Today |
|---|---|---|---|
| 1 | **The receipt family** — 80mm, A4, trade invoice | `receipt.js` `lineMath()` + the column registry | `qty = num(s.quantity)` → **0.5** |
| 2 | The cart grid, manual add | `business.js:211` `obj.quantity` | **0.5** |
| 3 | The cart grid, scan add | `business.js:446` `n` | **5** |
| 4 | Sale detail report | `business.js:2713` `srNum(o.quantity)` | **0.5** |
| 5 | Report group/summary row | `business.js:2639` `g.quantity` | **0.5** |
| 6 | Edit — loading a line back into the till | `loadCartLineIntoForm` → `#sellItems` | **0.5** in the quantity box |

**Server side: none.** There is no `ReceiptLine` DTO and `InternalReceiptsController` never touches a
quantity — every document is rendered in the browser. That is a genuinely good outcome for this slice: six
call sites, all in two files, no service redeploy needed for the printing itself.

### 1.1 ⚠ A defect U3 introduced, found by this enumeration

**Points 2 and 3 disagree.** Add five tablets by hand and the cart shows **0.5**; add the same five by
scanning `5L*CODE` and it shows **5**. Same product, same sale, two numbers, neither labelled.

Not a printing bug — a live inconsistency in the screen a cashier watches while ringing up. It exists because
the two paths build their grid row independently, and U3 changed one of them. **U4 fixes it by construction**:
after this slice both read the same formatter.

*This is the argument for enumerating first, stated better than I could put it abstractly: the defect was six
hours old and nothing had noticed.*

## 2. The rule — display what the customer bought

| | shows | why |
|---|---|---|
| **A loose line** | `5 tablets @ 12.00` | it is what they asked for, what they are holding, and what they paid |
| **The pack equivalent** | available, not primary | the shelf and the stock report think in packs; the customer does not |
| **An ordinary line** | **exactly as today** | `soldUnit IS NULL` on every row written before U2, and on every pack sale after it |

The line total is **unchanged** in every case — `netAmount` is the same number it always was. U4 changes
*labels and quantities*, never money.

### 2.1 `packSizeSnapshot`, not the product's current pack size

A receipt reprinted next year must say what was sold **then**. If the product moves from packs of 10 to packs
of 12, a stored `quantity = 0.5` re-read against today's pack size becomes *six* tablets on a receipt for a
sale of five. U2 froze the snapshot on the line for this exact moment — **U4 must read it and never the
product.**

## 3. One formatter, six callers

```
/js/common/loose-format.js        pure, no jQuery, no DOM

  looseDisplay(line) -> { qty, unit, rate, isLoose, packs }
```

* `soldUnit !== 'LOOSE'` → `{ qty: line.quantity, unit: '', rate: line.sellRate, isLoose: false }`
  — byte-identical to what every caller does today;
* otherwise → `{ qty: soldQuantity, unit: looseUnitPlural, rate: soldRate, packs: quantity }`.

**Why a separate file rather than a function in `receipt.js` or `loose-sell.js`:** `receipt.js` renders in a
print window that does not necessarily load the till's module, and `loose-sell.js` binds keyboard handlers it
must not carry into a receipt. A pure module with no dependencies can be loaded by both — and unit-tested
without a browser, which is what makes six callers safe to converge.

**DRY, and the standing rule:** never define the same function in two files; common goes to a common module.
Six hand-written conversions would be six chances to disagree, which is precisely how points 2 and 3 already
did.

## 4. The edit path — the one that can lose money

`loadCartLineIntoForm` puts `line.quantity` into `#sellItems`. For a loose line that is **0.5**, in a box the
cashier reads as *pieces* and the seven numeric readers treat as *packs*.

Left alone, editing a loose sale and clicking Update would re-submit **half a pack of packs**. So U4:

* loads `soldQuantity` into the box and sets the unit toggle to LOOSE, so the screen shows what was sold;
* and the existing U3 path re-derives the conversion on submit, so the edit round-trips.

⚠ **Loose RETURNS are U6, deliberately.** A return has its own quantity semantics (part of a pack coming
back), and mixing it into a printing slice is how a slice stops being reviewable.

## 5. Refusals, and what does not change

Nothing new is refused. **No server change at all** beyond nothing — U4 is a rendering slice; the columns,
the arithmetic and the API are already in place and gated by U2.

## 6. Gate — `sell-loose-receipt.cy.js`

1. ⭐ **the receipt says "5 tablets @ 12.00"**, not "0.5 × 120.00" — and the line total is **60.00** either way.
2. ⭐ **an ordinary sale's receipt is byte-identical to today** — the regression that protects every shop that
   never breaks a pack.
3. **the cart grid agrees with itself** — five tablets added by hand and by `5L*CODE` scan render the same.
4. **the sale detail report shows pieces**, with the pack equivalent available.
5. **a reprint after the product's pack size changes still says 5 tablets** — `packSizeSnapshot`, not the
   product. *The case that proves §2.1.*
6. **editing a loose sale loads 5 into the quantity box**, with the toggle on LOOSE — and saving it unchanged
   leaves the invoice total unchanged.
7. **the trade invoice and the 80mm receipt** both render it, because they are separate layouts over one
   registry.
8. **a mixed invoice** — one pack line and one loose line — reads correctly on the same document, and the
   line amounts sum to the printed total.

Plus pure unit cases for `looseDisplay`: legacy line, pack line, loose line, and a loose line whose
`packSizeSnapshot` differs from the product's current pack size.

## 7. Performance

No new request, no new query, no server change. One small pure module added to the page; six call sites
replaced with a function call. The receipt renders from data already in the browser.

## 8. Security

Nothing new is exposed. `looseUnit`/`looseUnitPlural` are tenant-authored strings that reach the DOM, so they
go through `escHtml` at the render point — the platform's XSS-safe rendering rule — exactly as `itemName`
already does.

## 9. Industry alignment

| System | On the document | Taken |
|---|---|---|
| **SAP** | prints the SALES UoM and its quantity, with the base-unit conversion held on the line | print what was sold; keep the conversion |
| **Odoo** | invoice line shows `product_uom_qty` in the line's own UoM | the line's unit is the printed unit |
| **Pharmacy systems** (Marg, Medeil) | print strips and loose pieces as separate readable quantities | the customer's own words on the receipt |
| **All fiscal/tax regimes** | quantity and unit price must reconcile to the line total | ⭐ `netAmount` unchanged; only the LABEL changes |

The last row is the constraint that shapes everything above: a tax inspector reconciles `quantity × rate` to
the line total. Printing `5 × 12.00 = 60.00` reconciles. Printing `5 × 120.00` would not — which is why
`soldRate` was stored in U2 rather than derived at print time.

## 10. What U4 deliberately does NOT do

* **No loose returns** — U6.
* **No purchase in boxes** — U5.
* **No server change.** If this slice needs one, something has been designed wrong.

---

## 11. Implementation log

| | |
|---|---|
| `/js/common/loose-format.js` | **new** — `looseDisplay` / `looseQtyText` / `loosePacks` / `loosePackSize`. Pure: no jQuery, no DOM |
| `receipt.js` `lineMath` | reads the formatter; the `quantity` column prints `5 tablets` |
| `business.js` ×4 | both cart-grid rows, the report row, the report summary row |
| `business.js` `loadCartLineIntoForm` | loads `soldQuantity` and sets the toggle |
| `businessDashboard.html` | loads `loose-format.js` **before** `receipt.js` |
| **Java** | **none** |

### 11.1 One change covered FIVE outputs

`document-pdf.js` and `document-designer.js` both call `DocumentRenderer` — the same resolvers the printer
uses — so the single `lineMath` change reaches the 80mm receipt, the A4 invoice, the trade invoice, the PDF
download **and** the designer's live preview. Verified before writing the change, not assumed.

*That is what a shared renderer buys, and it is the opposite of what the six-point enumeration found on the
cart grid, where two independent code paths had already drifted apart.*

### 11.2 The gate drives the printer's own function

`DocumentRenderer.lineMath` is exported, so the carrying case calls **it** rather than a re-implementation of
it. A test that asserts through a proxy for the code under test proves the proxy.

### 11.3 The audit constraint is an assertion, not a comment

Every case that touches a loose line asserts `qty × rate ≈ total`. Printing "5 tablets" beside a rate of
120.00 would read beautifully and fail a tax inspection; the constraint is what forced `soldRate` to be
STORED in U2 rather than derived at print time.

### 11.4 ⚠ Gate run 1 — three failures, TWO REAL DEFECTS, and one of them was in U3

Not fixture problems this time. The gate found live bugs.

**1 · The stored line had no noun.** `sell` stores `soldUnit`/`soldQuantity`/`soldRate`/`packSizeSnapshot`,
but what a piece is *called* lives on the catalog product — so a stored loose line read back could only say
"5", never "5 tablets".

Fixed by **deriving it on read** in `getUserSell`, beside the `itemName`/`sku`/`description` enrichment that
was already there — not by adding columns. The reasoning matters: a product rename **already** changes what an
old receipt's item name says, so making the unit noun stricter than the product NAME would be inconsistent,
and two varchars on the highest-volume table in the system is a real cost for a cosmetic gain. The QUANTITY
stays frozen on the row (`packSizeSnapshot`) because *a wrong number is wrong, while a renamed noun is merely
dated*.

**2 · ⚠ THE TILL'S OWN MATHS WERE WRONG, AND U3 SHIPPED IT.** `sellLineMath` computes `qty × rate`; on a loose
line the quantity box holds PIECES and the rate box holds the PACK price. Five tablets of a 120.00 pack came
to **600.00** in the cart row and the running total — while the hint line, two inches above, said 60.00.

The stock guard was wrong the same way: `batchStock < qty` compared **5 pieces** against an on-hand counted in
**packs**, so the till would refuse sales the shop could make.

`LooseSell.lineOverride()` now substitutes packs + the effective pack rate, mirroring exactly what the server
stores, so the cart, the running total, the stock check and the invoice all agree.

**Why U3's gate missed it:** those nine cases asserted the hint line and the composed payload — both correct —
and never the cart's money column. *The payload was right and the screen was wrong, which is precisely the
combination a UI slice must test for.* U4's gate caught it only because it looks at what a person reads.
