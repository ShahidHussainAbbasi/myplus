# U11 — counting the shelf

**Status: DONE + GREEN 2026-08-30 — stock-count.cy.js 7/7.** Branch: `feature/pack-loose-selling`.
Closes the second of the items U6 §5 declined.

A shop counts its shelf and reconciles what it holds against what the system claims. U6 made the *display*
readable — `9 + 5 tablets` instead of `9.5`. It did not give anyone a way to **write down what they counted**.

---

## 1. Review — the adjusting already exists; the counting does not

| Verified | Where |
|---|---|
| `/stock/adjust` takes `productId`, `INCREASE`/`DECREASE`, `quantity`, `reason` | inventory-service |
| `StockAdjustment` records quantity, type, **reason, adjustedBy, adjustedAt** | inventory-service |
| The monolith proxies it as `/adjustProductStock` | `CatalogController:465` |
| The product grid already shows on-hand as **packs + pieces** (U6) | `business.js` `shelfText` |
| ⭐ **No count screen exists anywhere** — no `stockCount`, no `physicalCount`, no count sheet | searched |

**So the primitive is there and the workflow is not.** A shopkeeper today corrects stock one product at a
time, from the product grid, by typing a difference they worked out in their head.

## 2. ⚠ The decision: no count-session table

The obvious design is `count_session` + `count_line` with statuses — DRAFT → COUNTED → APPROVED → APPLIED.
**Rejected for the first slice**, for a reason this programme has now met three times:

**`StockAdjustment` is already the record.** It carries the quantity, the direction, the reason, who did it
and when. A session table would be a *second* record of the same fact, and the day the two disagree — a
session marked APPLIED whose adjustments partly failed — the shop has two answers and no way to choose.

What a session buys is **approval before applying**, which matters when the counter and the approver are
different people. That is a real requirement for a large shop and not one for a single-owner pharmacy, so it
is a **second slice, on evidence**, not a guess baked into the first.

> Stated so it is a decision and not an omission: **U11 ships counting without approval.** If a shop needs a
> second pair of eyes before stock moves, that is `count_session`, and it should be built when someone asks.

## 3. What the screen does

```
  Panadol 500mg     system  9 + 5 tablets      counted [ 9 ] packs [ 3 ] tablets     variance  −2 tablets
  Shampoo 200ml     system  12                 counted [ 11 ]                        variance  −1
  Brufen 400mg      system  4 + 1 tablets      counted [ 4 ] packs [ 1 ] tablets     variance  —
```

* **Counted in the unit the shelf is in.** A divisible product gets two boxes — packs and loose pieces —
  because that is what a person holding the shelf actually counts. U6 made the system's number readable; this
  makes the counter's number writable in the same language.
* **Variance is shown live**, in pieces for a divisible product and in units otherwise.
* **A blank line is not a zero.** Only rows the counter actually filled are adjusted — a count sheet is
  almost never finished in one pass, and treating "not counted yet" as "counted zero" would wipe the shelf.

### 3.1 What gets written

One `StockAdjustment` per product with a non-zero variance, `INCREASE` or `DECREASE`, with the reason
stamped: `"Stock count 2026-08-30"`. Nothing else. **The adjustments are the count record.**

## 4. ⚠ Risks, because this writes stock in bulk

| Risk | Answer |
|---|---|
| **Bulk destructive write** | a confirm dialog naming the number of products and the total variance — `uiConfirm`, never `window.confirm` |
| **Partial application** — some adjustments succeed, some fail | ⚠ **Accepted and reported, not hidden.** Each product is its own adjustment; there is no transaction across them. The screen reports which succeeded and which did not, and the sheet keeps the failures so they can be retried. *Pretending it is atomic when it is not would be the worse lie.* |
| A blank row treated as zero | §3 — only filled rows count |
| Counting the wrong unit | the boxes are labelled with the product's own words (`packs`, `tablets`) |
| A stale sheet — stock moved while counting | the variance is recomputed against live on-hand at submit, and a row whose system quantity changed since it was loaded is **flagged, not silently applied** |

**The last one is the one that matters.** A count sheet is filled over minutes or hours while the shop keeps
selling. Applying a variance computed against a number that has since moved would *create* the discrepancy it
was meant to fix.

## 5. Gate — `stock-count.cy.js`

1. ⭐ **counting a divisible product in packs + pieces produces the right variance** — system 9.5, counted
   9 packs 3 tablets, variance **−2 tablets** (−0.2 packs).
2. ⭐ **applying it moves on-hand to exactly what was counted** — 9.5 → 9.3.
3. ⭐ **a row left blank is not touched** — the regression that stops a half-finished sheet wiping the shelf.
4. **an indivisible product counts in plain units** — no pack/piece boxes.
5. **zero variance writes nothing** — no adjustment row, no audit noise.
6. **the adjustment carries the reason** — `Stock count <date>`, with who and when.
7. **a row whose system quantity moved since loading is flagged, not applied** (§4).
8. **an increase and a decrease in one sheet both apply** — the sign is per row, not per sheet.

## 6. Performance

The sheet reads `/productStockLevels` once — the same call the product grid already makes — and posts one
adjustment per varying row. A 1,200-product catalogue loads in one request; a typical count corrects a handful
of rows.

## 7. Security

`/adjustProductStock` is already privilege-gated and org-scoped; U11 adds no new write path, only a screen
that calls it more than once. Each adjustment carries `adjustedBy`, so a bulk count is as attributable as a
single correction.

## 8. What U11 deliberately does NOT do

* **No approval workflow** — §2, and it is a decision, not an oversight.
* **No count history screen.** The adjustments are queryable and carry the reason; a dedicated history view is
  a report, not this slice.
* **No partial-count resume across sessions.** The sheet lives in the browser until it is applied.

---

## 9. Implementation log

| | |
|---|---|
| `stock-count.js` | **new** — the sheet, live variance, the confirm, the per-row report |
| `businessDashboard.html` | `#StockCountDiv`, a nav entry beside Product, the script tag |
| six i18n bundles | +18 keys each, **1886 `ui.*` in lockstep**, properly translated rather than English-filled |
| **server** | **none** — `/adjustProductStock` already existed |

### 9.1 The arithmetic is exported

`stockCountVariance(systemQty, packSize, countedPacks, countedPieces)` is on `window`, so the gate asserts it
directly rather than through five DOM interactions. That is U8 §8's lesson applied *before* the fact rather
than after it: **a gate that cannot execute the code you changed is asserting the artefact.**

Verified outside a browser on five shapes, including `4.1 packs of 10, counted 4 packs 1 tablet -> 0` — the
case where the stored quantity carries U2's rounding residue and the counter is nonetheless right.

### 9.2 The re-read before applying

`applyAll` re-reads `/productStockLevels` **first** and skips any row whose system quantity moved since the
sheet was loaded.

That is the one risk in this slice that could make things *worse* rather than merely fail. Every other failure
mode here leaves the shelf as it was; this one writes a correction computed against a number that has since
changed, and so **creates** the discrepancy it was meant to fix.

### 9.3 What the screen refuses to do quietly

* **A blank row is not a zero.** `countedPieces` returns `null` for an untouched row, and `varyingRows` skips
  it. Opening this screen and pressing Apply does nothing at all — the button will not even arm.
* **A zero variance writes nothing.** No adjustment row, no audit noise for a shelf that was already right.
* **A partial application is reported.** Each product is its own adjustment with no transaction across them,
  so the summary names how many were adjusted, how many moved while counting, and how many failed. A screen
  that said "done" while three rows silently failed would leave a shop believing its shelf matched.
