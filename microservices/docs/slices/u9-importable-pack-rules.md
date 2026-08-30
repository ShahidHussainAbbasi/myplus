# U9 — the pack rules, importable

**Status: DONE + GREEN 2026-08-30 — `product-import.cy.js` 37/37, catalog-service 50/50.**
Branch: `feature/pack-loose-selling`.

A pharmacy switching loose selling on has to say what a pack holds for **every medicine it splits**. By hand,
on a 1,200-product catalogue, that is not a chore — it is the reason a shop does not adopt the feature at all.

---

## 1. Review

| Verified | Where |
|---|---|
| The importer declares columns via `ColumnSpec` — `text` / `number` / `integer` / `oneOf` | `common-import` |
| Every rule is about **one cell**: required, max length, a number, one of a set | `ColumnSpec.validate` |
| `ImportEngine` collects per-row problems and **refuses the whole file** if any row is bad | `ImportEngine:136` |
| `ProductImportSpec` carries 11 columns — none of them the pack rules | `ProductImportSpec:79` |
| A green spec asserts the template header **exactly** | `product-import.cy.js` `HEADERS` |

## 2. What was missing, and it was not just columns

Four columns are obvious: `packSize`, `looseUnit`, `looseUnitPlural`, `allowLoose`.

**The rule that spans them was not.** `allowLoose = true` with no pack size is a contradiction the product
form *cannot produce* — it hides the loose fields until a pack size above 1 is entered, and unticks the box
when one is cleared. An import must not be a way around that, or a shop ends up with products the catalogue
says are splittable and the till refuses to split, with nothing on either screen explaining the disagreement.

`ColumnSpec` can only say things about **one cell**. So `ImportSpec` gains:

```java
default String validateRow(CsvReader.Row row) { return null; }
```

Called after every column has validated on its own; the default means **every existing importer is
untouched**. *A contradiction between two individually valid cells is still a row the operator did not mean
to write.*

### 2.1 Refused, not silently corrected

Quietly turning `allowLoose` off would import the file "successfully" and leave the operator believing 1,200
products were configured when some were not. The engine already refuses the **whole file** on any bad row, so
they are told before anything is written — which is the moment the information is useful.

## 3. ⭐ The property that protects existing data

**A blank `packSize` means "not supplied", never "make this indivisible."**

This is the same rule the product form follows (U1 §4), and it matters more here: without it, re-importing
last year's price list over a configured catalogue would **quietly un-split every medicine in it**, and the
failure would surface days later at the counter as *"this product is not sold by the piece"*.

The gate proves it directly — create a product with pack rules, re-import it from an old-style file that has
no pack columns at all, and assert the rules survived.

## 4. What is deliberately NOT importable

`defaultSellUnit`. It is a per-till preference with a safe default (`PACK`), changeable on the form in one
click, and one more column to get wrong on the sheet that matters most. **Every column on an import template
is a chance for a shop to mis-key its whole catalogue**, so a column earns its place or stays off.

## 5. Gate — 5 cases added to `product-import.cy.js`

1. ⭐ **a pack-rule row creates a splittable product** — 10 / tablet / tablets / true.
2. ⭐ **`allowLoose` with no pack size is refused, with the reason.**
3. **a pack size of 1 is refused** for the same reason — a pack of one is not divisible.
4. ⭐ **an old-style file without the pack columns does not strip them** (§3).
5. **an ordinary row is unaffected** — no pack size, not splittable, exactly as before.

⚠ **The existing `HEADERS` constant had to change.** A green spec asserts the template header exactly, and
four new columns change it — so U9 necessarily edits a passing test. That is the contract working: the
template and the parser are asserted to agree, and widening one without the other is caught immediately.

## 6. Performance

No new query and no new pass over the file: `validateRow` runs inside the loop that already validates every
column, and returns `null` for every importer that does not override it.

## 7. Security

Nothing new is exposed. The import is already `ADMIN_PRIVILEGE`-gated and org-scoped; these columns write the
same fields the product form writes, through the same service.
