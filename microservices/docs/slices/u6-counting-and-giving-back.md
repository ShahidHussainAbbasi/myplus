# U6 — counting the shelf, and taking tablets back

**Status: DONE + GREEN 2026-08-29 — Cypress gate 9/9. The discovery gate found a REAL stock defect (§10.4); loose returns were otherwise already correct, as the review predicted.** Branch: `feature/pack-loose-selling`.
Parent: `../pack-and-loose-selling-design.md` §11. Predecessors: U1–U5, all green.

Two things a shop does that the feature cannot yet express:

* a customer brings **3 tablets back**;
* the owner counts the shelf and the screen says **9.5**.

---

## 1. Review — and it changes the shape of this slice

| Verified | Where |
|---|---|
| A sale return is expressed as an **EDIT of the invoice** — the line is reduced and the difference becomes a credit note | `SellController.updateSell` |
| ⭐ **`updateSell` shares `buildLines`** — the comment says so | `SellController:1199` |
| `SaleReturn` carries **`sellId`** — the original sale line | `SaleReturn:53` |
| `SaleReturn` has **no batch column**; returns restock through the ordinary path | `SaleReturn` |
| `SaleReturnLine.quantity` is `Float` | `commerce-contracts` |
| The product grid shows **sellable** on-hand, with an expired badge, from `/productStockLevels` | `business.js:1472` |

### 1.1 ⭐ Loose returns are probably ALREADY WORKING

The parent design assumed U6 would build them. The review says otherwise, and the chain is short:

```
   U2 put the conversion in buildLines
   updateSell SHARES buildLines                     -> an edited line re-prices by the same rules
   U4 made the edit screen load soldQuantity + set the toggle
```

So reducing a five-tablet line to two should already price the credit note at three tablets and return
**0.3 packs** to stock — because nothing on that path knows it is doing anything special.

**This is what stamping at write bought.** `soldUnit`, `soldQuantity`, `soldRate` and `packSizeSnapshot` were
frozen on the line in U2 for the receipt; the return path gets them for free because it starts from
`sellId`.

⚠ **"Probably" is not "verified", and this is money.** So U6's first job is **not to build** but to
*discover*: gate the behaviour, then fix only what the gate finds. A slice that assumes a feature is missing
and rebuilds it can easily produce a second, disagreeing implementation — which is the failure this programme
has spent five gate runs learning to avoid.

### 1.2 What is genuinely missing

**The count screen speaks packs to a person counting tablets.** `9.5` is arithmetically true and
operationally useless: nobody counts half a pack. It means *9 sealed packs and 5 loose tablets*, and until the
screen says that, a shop cannot reconcile its shelf.

## 2. What U6 does

| | |
|---|---|
| **A · Verify loose returns** | gate the existing path end to end. Fix only what fails |
| **B · Render on-hand in the counter's language** | `9.5` → **9 packs + 5 tablets**, wherever a person reads a quantity |
| **C · Refuse what cannot be given back** | §4 |

**No new table, no new column, no new endpoint** unless A proves one is needed. That is a prediction the
gate will test, not an assumption to build on.

## 3. B — the shelf, in the counter's language

```
   stored          shown
   9.5      →      9 packs + 5 tablets
   10       →      10                        (unchanged — an ordinary product)
   9.3333   →      9 packs + 1 tablet        (see §3.1)
```

Rendered by **one function**, beside U4's `looseDisplay` in `/js/common/loose-format.js`, because this is the
same question in a different place and the six-caller lesson from U4 stands.

### 3.1 ⚠ The residue must not become a phantom tablet

U2 chose to keep stock in **selling units**, accepting a bounded drift: `1/3` of a pack stores as `0.3333`,
so three single sales from a pack of 3 leave `0.0001` behind rather than exactly zero.

Rendering that naively gives *"0 packs + 0.0003 tablets"* — which is worse than the number it replaces,
because it looks like a defect. So the split **rounds the piece count to whole pieces** and shows nothing
when the remainder is under half a piece. The stored number is untouched; only the reading changes.

*A display that invents precision the data does not have is a lie told confidently.*

## 4. C — what cannot be given back

| # | Refused | Why |
|---|---|---|
| 1 | more pieces than the line sold | already enforced for packs; must hold per piece |
| 2 | a **fraction** of a piece | half a tablet is not a thing this sells, and not a thing it takes back |
| 3 | a loose return against a line sold as a **PACK** | the customer bought a sealed pack; taking back 3 of its tablets is a different transaction the shop must price deliberately, not a partial return |

⚠ **#3 is a real decision, not pedantry.** Allowing it would let a customer buy a sealed pack at 120 and
return 7 tablets at the loose rate — 7 × 13.20 = **92.40 back on goods they paid 120 for**, keeping 3 tablets
for 27.60 when the shop's own loose price is 39.60. **The refund arbitrage is the shop's loss.** A shop that
wants to accept it can, as a fresh sale of the remainder — deliberately, at a price it chooses.

## 5. What U6 deliberately does NOT do

* **No batch-level return.** The parent design says a returned piece should go back *"to the batch it came
  from"*, and `SaleReturn` has no batch column. Restocking to the wrong batch affects FEFO order, not
  quantity — a real but second-order concern, and adding a column plus a migration to a slice whose main job
  is verification would bury the thing being verified. **Recorded as an open item, not silently dropped.**
* **No stock-count workflow.** Rendering on-hand in pieces is not the same as a count-and-adjust sheet;
  that is its own slice with its own approval path.
* **No own-sticker barcodes** — U7.

## 6. Gate — `sell-loose-return.cy.js`

**Written to DISCOVER, not to confirm.** If A is already working, these pass unchanged and the slice is
mostly B.

1. ⭐ **sell 5 tablets, take 3 back** — the credit note is **36.00** (3 × 12.00), not 3 × 120.00 and not a
   whole pack.
2. ⭐ **stock returns 0.3 of a pack** — on-hand goes 9.5 → 9.8, not 9.5 → 12.5.
3. ⭐ **the customer is refunded what they paid** — the invoice total falls from 60.00 to 24.00.
4. **returning all 5 leaves the invoice at zero** and stock back at 10.
5. **a pack return is unchanged** — the regression protecting every existing shop.
6. **more pieces than were sold is refused.**
7. **2.5 tablets back is refused** (§4 #2).
8. **a loose return against a PACK line is refused** (§4 #3) — with the reason shown.
9. **on-hand reads "9 packs + 5 tablets"** on the product grid.
10. **an ordinary product still reads as a plain number** — no unit words anywhere.
11. **the residue does not print** — a product left at `0.0001` packs reads `0`, not `0 packs + 0.0003 tablets`.

Plus pure unit cases for the pack/piece split, including the residue.

## 7. Performance

No new request or query. The split is arithmetic on the levels map the product grid already fetches once.

## 8. Security

Nothing new is exposed. The refusals in §4 are server-side — the return path is `updateSell`, which already
re-prices through `buildLines`, so a hand-crafted request meets the same rules as the screen.

## 9. Industry alignment

| System | Returns of a split unit | Taken |
|---|---|---|
| **SAP** | credit memo references the billing document and its UoM | derive the unit from the line being returned |
| **Odoo** | a return picking is created from the delivery, in the same UoM | the return inherits, never re-declares |
| **Pharmacy systems** | loose returns to the opened strip; sealed packs kept separate | ⭐ §4 #3 — a sealed pack and an opened one are different goods |
| **Retail generally** | refund at the price actually paid, not the current price | `soldRate` from the line, never recomputed |

The pharmacy row is the one that shaped §4 #3: a returned tablet cannot restore a *sealed* pack, and a system
that pretends otherwise will sell a sealed pack it does not have.

---

## 10. Implementation log

| | |
|---|---|
| `loose-format.js` `shelfText()` | the pack/piece split, beside U4's `looseDisplay` — same question, same module |
| `business.js` product grid | on-hand renders `9 + 5 tablets`; ordinary products unchanged |
| `SellController.updateSell` | §4 #3 — a line sold as a PACK cannot be returned by the piece |
| `sell-loose-return.cy.js` | **new**, 9 cases, written to DISCOVER |
| **new tables / columns / endpoints** | **none** — as predicted in §2 |

### 10.1 The on-hand cell now needs escaping, and did not before

The old renderer built its HTML from numbers only, and said so: *"numbers only (no user data) → XSS-safe"*.
`shelfText` puts the tenant's own `looseUnitPlural` into that cell, so the comment stopped being true the
moment the feature landed. The value goes through `escHtml`.

*A safety comment is an assertion about the data, and it expires the moment the data changes.*

### 10.2 The refusal is deliberately strict

§4 #3 refuses **any** LOOSE line on an edit whose prior line for that product was a PACK — not only a
reduction. A cashier correcting a genuinely mis-keyed unit must void and re-ring.

That is friction, and it is chosen: distinguishing "a correction" from "a refund at the wrong rate" needs the
edit to reason about direction and price simultaneously, and a subtle rule on a path that moves money out of
the till is worse than an obvious one. **If the gate or a shop shows this is too strict, the fix is to relax
it with a stated rule — not to leave it vague now.**

### 10.3 What the gate is really for

Cases 4–6 assert behaviour **no U6 code implements**. If they pass, the review's central claim is confirmed:
`updateSell` shares `buildLines`, so loose returns were already correct the moment U2 landed, and this slice
is mostly the count screen. If they fail, they will say exactly where — and only then is there return code to
write.

*A gate that can only confirm what you built tells you nothing about what you assumed.*

### 10.4 ⭐ What the discovery gate found — the shelf, not the money

The review predicted loose returns were already working. **It was half right, and the half it got wrong was
worth the whole slice.**

The MONEY was correct from the first run: `updateSell` shares `buildLines`, so a reduced loose line re-priced
exactly as designed. But the **stock delta** is computed from the RAW DTO, before that conversion:

```java
for (SellDTO s : dto.getSales())
    delta.merge(pid, -s.getQuantity(), Float::sum);   // raw, unconverted
```

`SellDTO.quantity` **defaults to `1F`**, so a loose return carrying only `soldQuantity` contributed −1 PACK:

```
old 0.5  −  new 1.0  =  −0.5      the return TOOK another half pack
on-hand  9.5 → 9.0               instead of 9.5 → 9.8
```

**Only the shelf was wrong** — the invoice, the credit note and the customer's refund were all correct. That
is the kind of error a shop discovers weeks later at a stock count, with no way to trace which sale caused it.

**Why nothing had noticed:** U3's browser code sets `quantity` before posting, so from the sale screen the DTO
arrives pre-converted. **The server was trusting the browser to perform a conversion it had promised to
perform itself.** U2 states it plainly — *"the server ignores quantity on a LOOSE line and derives it, so a
browser that gets the conversion wrong cannot mis-sell"* — and that was true in `addSell` and quietly untrue
here. It worked from the screen and was wrong from every other caller.

The fix derives `quantity` from `soldQuantity ÷ packSizeSnapshot` **before** the delta — the pack size in
force *when the line was sold*, not the product's current one, or every historical return mis-restocks the day
a shop changes its packs.

> **The lesson, stated for the next slice:** a guarantee that holds on one entry path is not a guarantee. U2's
> claim was written about `addSell` and read as though it were about the system. **Two paths shared
> `buildLines` and only one of them shared the promise.**

### 10.5 One case was replaced for asking an ambiguous question

The original "return everything by editing the line to zero" was dropped. A line with no quantity is not
obviously a return rather than a mistake, and a shop returning a whole sale **voids** it. Asserting behaviour
there would have been testing a guess about an input the server may rightly refuse.

It became a second partial return at a different ratio (5 → 1, expecting +0.4), because *a conversion bug that
happens to be right at one ratio is not fixed*.
