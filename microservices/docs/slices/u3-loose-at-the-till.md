# U3 — selling a broken pack at the counter

**Status: DONE + GREEN 2026-08-27 — Cypress gate 9/9, business-service unit 206/206.** A cashier can now sell five tablets out of a pack of ten, from the till, without arithmetic. Branch: `feature/pack-loose-selling`.
Parent: `../pack-and-loose-selling-design.md` §3.3. Predecessor: [U2](u2-loose-sale-arithmetic.md) (the money).

U2 made a loose sale correct. **U3 is the slice that makes it reachable** — today a shopkeeper still cannot
sell a tablet, because the only caller is an API. Standard R7: a capability unreachable from the UI is not a
capability.

---

## 1. Review — what the till actually is

| Verified | Where |
|---|---|
| The quantity box is `#sellItems` | `businessDashboard.html` |
| The Enter chain is `sellItemDD → sellItems → sellSellRate → sellDiscountTypeDD → sellDiscount` | `pos-keyboard.js:38` |
| A field joins the chain only if present, visible and editable — configuration drives it for free | `pos-keyboard.js:104`, `common/enter-chain.js` |
| The scan grammar `12*CODE` is ONE pure exported function with digits-only validation | `business.js:275` `parseScanEntry` |
| The cart line object is built in one place and `data[]` is submitted as `sales` | `business.js:375` |
| The picker projection carries **`{id, name, sellingPrice}`** only — no `packSize` | `ProductPickerDTO`, PERF-8 |
| `/getUserProduct` **does** carry `packSize`, `looseUnit`, `allowLoose` since U1 | `CatalogController` |
| The monolith `SellDTO` already carries `soldUnit`/`soldQuantity` | U2 §13.2 |

## 2. ⚠ The finding that changes the design

The parent design says the quantity box takes the answer: *type `5L` for five loose*. **The codebase says the
box is a number**, and it is read numerically in **seven** places:

```js
business.js:1547   var qty = $('#sellItems').val()*1>0 ? $('#sellItems').val()*ONE : 1;
business.js:2051   if($("#sellItems").val()*1<=0){ ... }
business.js:2174   if($("#sellItems").val()*1<=0){ ... }
business.js:2278   var qty= $("#sellItems").val()*1>0?$("#sellItems").val()*ONE:1;
business.js:2348   var qty= $("#sellItems").val()*1>0?$("#sellItems").val()*ONE:1;
pos-keyboard.js:279 if (!(Number($('#sellItems').val()) > 0)) { ... }
                    (+1 commented-out copy at business.js:2307)
```

Every one is shaped `val()*1 > 0 ? … : 1`. So `"5L"` is `NaN`, `NaN > 0` is false, and **the line silently
becomes one pack.** No error, no warning, no log — the cashier types `5L`, the customer is charged for a full
pack, and the shelf loses ten tablets instead of five.

Making the literal design work means editing all seven, and **nothing checks that you found them all**. That
is the copy-point failure that has already cost this programme three defects — `gl_outbox`, the product row
projection, and the monolith `SellDTO`.

### The decision: `L` is a KEYSTROKE, never a character in the value

```
   cashier types:   5   L                     #sellItems value:  "5"      (always a number)
                        └──> intercepted in keydown
                             ├─ flips the line's unit to LOOSE
                             ├─ the toggle lights up, the hint line appears
                             └─ preventDefault() — the character never lands
```

**Seven read sites change to zero.** `soldUnit` lives in exactly one place — the line's own state — which is
also what the touch toggle and the scanner set. One model, three inputs, as §3.3 intended.

What the cashier types (`L`) and what they read (**tablets**) still differ, exactly as the parent design
argued they must: `L` is a constant, not the initial of `looseUnit`, so a shop with both *tablet* and
*teaspoon*, or one running in Urdu, is unaffected.

*This is U2 §5.1 again: the rule was right and the mechanism was wrong. Correct the mechanism, keep the rule.*

## 3. The three inputs

### 3.1 Keyboard — F7, with Alt+L as the alias  ·  DECIDED 2026-08-27

**`F7` toggles the unit on the current line. `Alt+L` does the same**, for tills whose F-row is missing or
captured by the OS. On a product that cannot be split the key is inert with a brief visual hint, rather than
silently doing nothing.

The owner reviewed three mechanisms against a working mock and chose this one. The reasoning that decided it:

| | |
|---|---|
| **It is this app's own convention** | `pos-keyboard.js` already binds `F2/F3/F4/F8/F9` with `Alt+s/p/r/e/c` as aliases, and its own comment says why Alt is the FALLBACK: *"kiosks and browsers that swallow function keys"*. F7 primary + Alt+L alias matches that ordering exactly, adding no new idiom |
| **No character can reach the quantity box** | so the seven numeric readers in §2 stay untouched — the whole point |
| ⭐ **Its failure mode is VISIBLE** | if the binding breaks, the toggle does nothing and the cashier notices. A letter-in-the-box mechanism that regresses produces `NaN`, silently sells **one pack**, and overcharges the customer with nothing reporting an error |

**Rejected: a bare `L`, and `Shift+L`.** Both put a letter in a numeric field — and `#sellItems` is
`type="text" inputmode="decimal"`, so the character genuinely lands. Neither is an industry standard; **there
is no standard key for this**. What the established systems share is a principle, not a keybinding: the unit
is a *visible control on the line*, and the keyboard is an accelerator layered on top of it.

⚠ **Matched on `event.code`, not `event.key`.** `e.key` is the character the LAYOUT produces: on an Arabic or
Urdu keyboard the physical L key sends `ل`, so `e.key === 'l'` never fires. `e.code === 'KeyL'` is the same on
every layout. This platform ships in six languages including ar/ur.

> **⚠ PRE-EXISTING DEFECT, LOGGED NOT FIXED.** The existing `ALT_ACTIONS` table matches on `e.key`, so
> `Alt+S` (Complete Sale), `Alt+P`, `Alt+R`, `Alt+E` and `Alt+C` **silently do nothing on a non-Latin keyboard
> layout**. Not introduced by U3 and deliberately not fixed here — U3 touches as little of this file as it
> can, and the owner has not confirmed whether any tenant types on such a layout. Its own slice.

**The Enter chain is untouched.** No new field joins `CHAIN`, so `sellItemDD → sellItems → sellSellRate → …`
is byte-for-byte what it is today. This matters: that chain has been broken twice by well-intentioned
changes, once by a capture-phase handler that swallowed Enter on an arrow-highlighted row.

### 3.2 Touch — a segmented toggle

`( pack │ tablet )` on the line, rendered **only** when `product.allowLoose && packSize > 1`. One tap. This is
how the feature is discovered; after that a cashier uses the keystroke.

Absent — not disabled — for an ordinary product, so the commonest till in the country looks exactly as it
does today.

### 3.3 Scanner — extend the grammar, do not invent one

`parseScanEntry` already returns `{qty, code}` and validates digits-only. It gains one optional suffix:

| Scanned | Returns |
|---|---|
| `CODE` | `{qty:1, unit:'PACK', code}` — unchanged |
| `12*CODE` | `{qty:12, unit:'PACK', code}` — unchanged |
| `5L*CODE` | `{qty:5, unit:'LOOSE', code}` |

⚠ **A scanned manufacturer barcode always means the PACK.** A GTIN is printed by whoever made the pack and
cannot mean "one tablet"; treating a scan as loose because the shop sells loose would mis-price the commonest
transaction there is. Own-sticker codes are U7, not this slice.

`parseScanEntry` is pure and exported, so its new case is unit-testable without a browser.

## 4. The hint line — the actual feature

```
  5 tablets · 12.00 each · uses 0.5 of a pack
```

Live, before the line is committed. **The cashier never computes.**

### 4.1 Where the number comes from — and where it must NOT

The loose rate is `ceilToCents(price × (1 + markup/100) ÷ packSize)`. Three ways to put it on screen:

| | verdict |
|---|---|
| Recompute in JS | ❌ **a second implementation of the rounding rule.** It would drift from the server's the day either changes, and the shop would quote one price and charge another |
| Call the server per keystroke | ❌ a remote call on the hot path, per character typed |
| **Server sends the rate with the product; JS multiplies** | ✅ |

So the **rate** stays server-derived — one implementation of CEILING and the markup, in `looseLine()` — and
the browser does `pieces × looseRate`, which is a multiplication, not a pricing rule.

`packSize`, `looseUnit`, `looseUnitPlural`, `allowLoose` and `looseRate` join the **picker projection**
(`ProductPickerDTO`), which the sale screen already loads once per section open and caches (PERF-8).
**No new request.**

### 4.2 ⚠ The hint is an estimate for a contract customer, and says so

For a B2B customer on a contract price, the server derives the loose rate from *that* price, which the browser
does not know until `quoteSellLines` returns. So the hint may differ from the final line.

**This is already true of pack lines today** — the quote re-prices after the line is added — so it is
consistent rather than new. **The server remains authoritative**; the hint is a cashier's aid, not a quote.
Stated here so nobody later "fixes" it by pricing in the browser.

## 5. What reaches the payload

The cart line object gains two fields, beside the existing `quantity`:

```js
{ productId, itemName, quantity: 1,            // packs — for display and the existing math
  soldUnit: 'LOOSE', soldQuantity: 5, ... }    // NEW — what the customer asked for
```

`data[]` is submitted as `sales`, and both the monolith `SellDTO` and the service already carry these (U2).
**The server ignores `quantity` on a LOOSE line and derives it** — so a browser that gets the conversion
wrong cannot mis-sell; it can only mis-display, and §4.2 already says the server is authoritative.

## 6. Refusals at the till

The server's five refusals (U2 §5) are unchanged and remain the real control. The till only avoids *asking*:

* the toggle and the `L` key are absent/inert on a product that cannot be split;
* a non-integer piece count is refused **in the box**, with the server's rule mirrored, not replaced;
* everything else — permission, pack size, absurd quantities — surfaces the server's message, through
  `apiMessage` (standard 8d).

## 7. Performance

* **No new request.** Five fields ride the picker projection the screen already fetches and caches.
* **No per-keystroke server call** — §4.1.
* **The Enter chain gains no field**, so no extra `usable()` walk per keypress.
* Five lean fields on a projection deliberately kept lean (PERF-8 took it from 618KB to 77KB): `packSize`
  (int), `allowLoose` (bool), two short strings and one decimal, on rows that already carry name and price.
  Measured before/after in the gate.

## 8. Security

* **The till is a display, not an authority.** Every refusal in U2 §5 is server-side; the toggle's absence is
  convenience, not a control.
* **`soldRate` and `packSizeSnapshot` are still server-populated** and ignored inbound.
* A browser posting `soldUnit: 'LOOSE'` for a forbidden product is refused exactly as an API caller is —
  that case is already green in `sell-loose.cy.js`.

## 9. Industry alignment

| System | At the counter | Taken |
|---|---|---|
| **Odoo POS** | unit chosen on the line, price per that unit | the line owns its unit |
| **SAP Retail** | alternative UoM with a conversion factor, chosen at entry | the frozen factor (U2) |
| **Square / Shopify POS** | no fractional units — a separate SKU per size | rejected: two SKUs = two stock balances that never reconcile |
| **Pharmacy systems (Marg, Medeil)** | strip/tablet toggle per line + a live per-piece price | the toggle **and the hint line** |

The hint line is the piece most small POS products omit and every pharmacy product includes — because the
arithmetic at the counter is the actual problem being solved.

## 10. The gate — `sell-loose-till.cy.js`

1. ⭐ **type `5`, press `L`, add the line — the bill says 60.00** and the stored line is `soldUnit LOOSE`,
   `soldQuantity 5`, `quantity 0.5`. *The whole slice in one case.*
2. ⭐ **`#sellItems` never contains a letter** — after pressing `L` its value is still `"5"`. The seven
   numeric readers are why.
3. ⭐ **the hint line shows before committing** — "5 tablets · 12.00 each · uses 0.5 of a pack".
4. **an ordinary product has no toggle and ignores `L`** — the till a shop uses today is unchanged.
5. **the Enter chain is unchanged** — item → qty → rate → discount → commit, with `L` pressed mid-run.
6. **`5L*CODE` in the scan box** adds a loose line; **`12*CODE` still adds 12 packs**; **`CODE` alone adds 1
   pack**.
7. **the toggle and the key agree** — reaching LOOSE either way produces the identical line.
8. **switching back to pack re-prices the line** and the hint disappears.
9. **a non-integer piece count is refused in the box**, with the reason shown.
10. **the picker projection still loads in one request**, and the payload grows by less than 10%.

Plus unit cases for `parseScanEntry`'s new grammar (pure, no browser).

## 11. What U3 deliberately does NOT do

* **No receipt or report changes** — U4. The line stores what it needs; printing it is the next slice.
* **No own-sticker barcodes** — U7.
* **No purchase in boxes** — U5.
* **No pricing in the browser** — §4.1, permanently.

---

## 12. Implementation log

| | |
|---|---|
| `SagaSellService.looseRateOf` | **extracted and made public** — the ceil-to-cents rule now has ONE implementation, used by the sale path *and* the till |
| `SellController./looseInfo` | per-product pack rules + the derived rate; degrades to pack-only on failure |
| monolith `/looseInfo` | pass-through, **no field-by-field projection** — deliberately, after U1 §5.1 |
| `loose-sell.js` | **new** — unit state, hint line, F7/Alt+L on `event.code`, cart decoration |
| `businessDashboard.html` | unit toggle + hint row, hidden unless the product allows loose |
| `business.js` | `validate()` before add, `decorate()` on the line, `onProductPicked()` on pick, `render()` in `calculateNetSell` |
| `parseScanEntry` | one optional `L` suffix on the existing multiplier — same function, not a second parser |
| six i18n bundles | +7 keys each, **1829 `ui.*` in lockstep** |

### 12.1 The scan path needed the SCANNED product's rules, not the line's

First cut read `LooseSell.info()` inside `scanAddToCart` — but that holds the rules for the product on the
**line**, which on a scan is usually a different product. It would have priced the scanned item using another
product's pack size. The caller now resolves `/looseInfo` for `ref.id` and passes it in, and the extra call
happens **only on a `5L*` scan** — an ordinary scan still costs exactly one request.

### 12.2 `global` is not defined in `business.js`

`loose-sell.js` is an IIFE taking `global`; `business.js` is a plain script. The three call sites written as
`global.LooseSell` would have thrown `ReferenceError` on the first product pick — caught by
`node --check`-style review before running, not by the gate.

### 12.3 Two spec helpers came from a GREEN spec, not from memory

`cy.openSellSection('sellDiv')` is the established command and **there is no `showSell` on `window`**. The
invented helper would have failed all nine cases before U3 ran — the same shape that lost the last three gate
runs. All nine selectors were then verified present in the template before the spec was finished.
