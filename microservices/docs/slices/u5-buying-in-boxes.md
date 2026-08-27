# U5 — buying in boxes

**Status: DESIGN — awaiting consent.** Branch: `feature/pack-loose-selling`.
Parent: `../pack-and-loose-selling-design.md` §11. Predecessors: U1–U4 (complete and shippable).

A shop buys a **box of 10 packs for 1000**. The form asks for a quantity and a rate, so the buyer types
`10` and `1000` — and the system now believes a pack costs **1000** instead of 100.

**A tenfold cost error, in the number COGS, the margin guard and every profit report read from.**

---

## 1. Review — the goods-in line as it is

| Verified | Where |
|---|---|
| The quantity box is `#purchaseQuantity` (`type=number`, `name="quantity"`) | `businessDashboard.html:1547` |
| Cost is `#purchasePurchaseRate`, sell is `#purchaseSellRate` | `:1561`, `:1566` |
| `calculateNetPurchase` computes `total = qty × rate` | `business.js:2305` |
| `/addPurchase` is form-posted; the service reads `quantity` + `stock.bpurchaseRate` | `PurchaseService` |
| ⚠ **A purchase RESTAMPS the product's selling price** from `stock.bsellRate` | `stampRatesOnProduct:393` |
| `lastPurchaseRate` is stamped from `bpurchaseRate` — and U2 reads it as unit COGS | `SagaSellService:635` |

**The error is not hypothetical and it is not new.** It exists today for every product, with no loose selling
anywhere near it. What loose selling changes is *visibility*: a tenfold cost error hides behind a fat pack
margin and starts refusing sales the moment thin per-piece margins sit beside it — the margin guard reads
exactly this number.

## 2. ⚠ Reconciling with §4 — "one level, forever"

The parent design **rejects a unit-of-measure engine**: no `uom` table, no conversion graph, no resolver, no
normalising on read. A "box" looks like a second level of packing, and therefore looks like the thing §4
forbids.

**It is not, and the distinction is the whole design of this slice:**

```
   box  ──converted AT ENTRY──▶  packs + per-pack cost  ──▶  stored
    ▲                                                          │
    └── exists only in the buyer's head and this one form      └── nothing downstream ever hears the word "box"
```

* **Nothing stores a box.** Not the purchase row, not the stock entry, not the product.
* **Nothing reads a box.** No normalisation, no resolver, no per-read cost — §4's actual objection.
* **It is one number used once**, in the same breath it is typed.

*A UoM engine is a model. This is an input aid.* The test that settles it: delete the feature tomorrow and
every stored row is still correct and still means the same thing. That is not true of a real second level.

## 3. Where the box size comes from — typed, never defaulted

The obvious convenience is a `packsPerBox` column on the product, defaulted into the form. **Rejected**, and
the reason is the same one that motivates the slice:

**Box sizes vary by supplier and by shipment.** The same medicine arrives as 10s this month and 12s next. A
stale product default would be *silently wrong for this delivery* — and it would be wrong with the confidence
of a pre-filled field, which is worse than an empty one. **The slice exists to prevent a unit mistake; it must
not institutionalise one.**

So: **entered per line, every time, with no default.** One number, beside the unit choice, visible on the row
it applies to. No new column, no migration, no stale data.

⚠ This is deliberately a *different answer* from U1's `packSize`, which IS stored on the product — because a
pack's contents are a property of the goods, while a box's contents are a property of the **shipment**.

## 4. What the server does

```
    entered:   unit = BOX,  quantity = 10,  packsPerBox = 10,  rate = 1000.00
    stored:    quantity = 100 packs,        bpurchaseRate = 100.00 per pack
```

**The conversion is server-side**, in `PurchaseService`, for the reason U2 established: the browser proposes,
the server decides. A browser that converts wrongly would write wrong stock *and* wrong cost, and the error
would be indistinguishable from a typo.

The form still shows the buyer the converted figures before saving (§6) — but what is stored is computed
once, on the server, from what was typed.

### 4.1 What does NOT convert

**The selling price stays per pack.** `#purchaseSellRate` is the shelf price, and a shop prices its shelf in
packs whatever it bought in. Converting it would be an invention: nobody types a box's retail price.

⚠ This matters more than it looks, because `bsellRate` **restamps the product's selling price**
(`stampRatesOnProduct`). Converting it would silently reprice the product to a tenth of its shelf price —
the same class of error the slice exists to prevent, in the opposite direction. *This is not speculation: a
fixture that derived `bsellRate` arithmetically is exactly what broke U2's gate (§13.4c).*

## 5. Refusals

| # | Refused | Why |
|---|---|---|
| 1 | `unit = BOX` with `packsPerBox` missing, ≤ 0, or non-integer | there is nothing to convert by, and half a pack in a box is not a thing |
| 2 | `packsPerBox` above 10,000 | the same guard as `MAX_PACK_SIZE`; a typo of 100000 must not create a million packs |
| 3 | a rate of 0 with `unit = BOX` | free goods have their own field; a zero box cost stamps a zero unit cost into COGS |

Server-side, as always — the form is one caller.

## 6. The screen

```
  QTY  [ 10 ]   ( pack │ box )   packs per box [ 10 ]      Rate [ 1000.00 ]

  ┌────────────────────────────────────────────────────────┐
  │  10 boxes = 100 packs · 100.00 per pack · 10,000.00    │  ← before saving
  └────────────────────────────────────────────────────────┘
```

The same shape as U3's hint line, and for the same reason: **the buyer sees the converted figures before
committing**, so a wrong box size is caught by the person who knows the answer.

`packs per box` and the hint appear **only** when the unit is BOX. A shop that never buys in boxes sees the
form exactly as it is today.

## 7. Gate — `purchase-in-boxes.cy.js`

1. ⭐ **10 boxes of 10 @ 1000 stores 100 packs at 100.00 each** — the stored purchase row, not the screen.
2. ⭐ **`lastPurchaseRate` on the product becomes 100.00, not 1000.00** — the number COGS and the margin guard
   read. *The defect this slice exists for.*
3. ⭐ **on-hand rises by 100 packs**, not 10.
4. **an ordinary pack purchase is unchanged** — the regression that protects every existing shop.
5. **the selling price is NOT converted** — `bsellRate` 150 stays 150 on the product (§4.1).
6. **the hint shows the conversion before saving**.
7. **BOX with no packs-per-box is refused**, server-side, with the reason shown.
8. **a non-integer packs-per-box is refused**.
9. **margin after a box purchase is right** — sell one pack at 150 bought at 100 → margin 50, not −850.
   *The end-to-end proof that the cost error is gone.*

Plus pure unit cases for the conversion in `PurchaseService`.

## 8. Performance

No new request, no new query, no new column. One conversion in a method that already runs per purchase line.
The form's hint is arithmetic on values already in the browser.

## 9. Security

The conversion is server-side; `packsPerBox` is an input, not an authority — it cannot make the server accept
a cost it would otherwise reject, only change how one is expressed. The refusals in §5 are enforced in
`PurchaseService`, so an API caller meets them too.

## 10. Industry alignment

| System | Goods-in | Taken |
|---|---|---|
| **SAP** | `uom_po` — a purchase order UoM distinct from the stock UoM, with a conversion factor | **the purchase unit is separate from the stock unit** |
| **Odoo** | `uom_po_id` on the product, per-line override | the per-line override — *not* the product default (§3) |
| **Marg / pharmacy** | box/case entry with a per-line conversion at goods-in | typed per shipment |
| **All of them** | store the base unit and the unit cost | ⭐ nothing downstream hears "box" |

We deliberately differ from Odoo on one point: **no product-level default.** Odoo's `uom_po_id` assumes a
stable purchase unit per product; in this trade the box size is a property of the shipment, and a stale
default is the very error being prevented.

## 11. What U5 deliberately does NOT do

* **No `packsPerBox` column**, no migration — §3.
* **No box on any stored row.** If a later slice needs to *report* in boxes, that is a different capability
  and it should be designed as one.
* **No purchase returns in boxes** — the return screen keeps its own units.
* **No third level.** A box of cartons of packs is not this system, per §4.
