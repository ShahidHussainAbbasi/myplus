# U1 — a product can say what a pack holds

**Status: DONE + GREEN 2026-08-27 — gate 6/6, catalog unit 50/50 (0 skipped).** `V11` applied to the live
schema, all seven columns present, **zero existing products changed behaviour**. Gate run 1 was 4/6; both
failures were in the READ path, not the feature (§5) — worth reading before adding any product field.
Branch: `feature/pack-loose-selling`. Parent: `../pack-and-loose-selling-design.md`.

---

## 1. What shipped

| Layer | Change |
|---|---|
| `V11__product_pack_size.sql` | `pack_size`, `loose_unit`, `loose_unit_plural`, `allow_loose`, `default_sell_unit`, `pack_changed_by`, `pack_changed_at` — idempotent, matching V8's pattern |
| `Product` | the five fields + the audit pair + `isLooseSellable()` |
| `ProductRef` (contract) | carries all five — **the seam U2 prices from** |
| `ProductDTO` + save + `toDto` | full round-trip |
| `FlywayMigrationTest` | **new** — catalog had none |

Verified on the live schema: seven columns, correct types, `allow_loose` defaults `0`, `default_sell_unit`
defaults `PACK`, and **zero products changed behaviour**. A column appearing must not make anything divisible.

## 2. ⚠ A migration that reported success and never ran

The pack migration was written as **`V10`**. A `V10` already existed — `V10__products_org_name_index.sql` —
so Flyway found version 10 in its history, **considered it applied, and never opened the file**, while
recording `success = 1`.

Nothing errored. catalog-service restarted healthy. The columns were simply absent, and **because the history
said applied, it would never have run again.**

**Cause:** the directory was read with `ls | tail -3`, which sorts **lexically** — V7, V8, V9 look like the end
of the list because V10 sorts above them. *Sort numerically when picking the next version, or read
`flyway_schema_history`, which cannot mislead.*

**And catalog-service had no Flyway migration test.** business-service, marketplace-service and pharma-service
each have one; catalog did not, which is exactly why this went unnoticed here and nowhere else.

The new test's carrying case asserts **no two migrations share a version**, checked against the **files** —
because a duplicate is precisely what makes the history unreliable: it shows one row for version 10 whether
one file exists or two.

## 3. ⚠ `@Builder.Default`, or a NOT NULL column takes a null

`allow_loose TINYINT NOT NULL` with `private Boolean allowLoose = Boolean.FALSE;` failed with
`Column 'allow_loose' cannot be null`: Lombok's builder **ignores field initialisers**.

Same shape as INST-1's defect #2 — an absent value reaching a NOT NULL column, the row dying after the caller
had committed. Caught here by `ProductRepoScopingTest` rather than in production, and the fix (`@Builder.Default`)
was already in use twelve lines away in the same file.

## 4. Two design decisions worth keeping

### `null` means "not supplied", never "clear it"

Every other setter in `applyDto` overwrites unconditionally, which is right for fields the form always posts.
These are new: the CSV import, the storefront admin and any integration written before U1 omit them entirely,
and reading that silence as *"set pack size to null"* would strip the configuration off every product they
touched.

### The audit gap was real

`products` records `created_by` only — so *"who allowed this course to be split?"* had **no answer at all**,
and `pack_size` and `allow_loose` decide what a customer is charged. `pack_changed_by` / `pack_changed_at` are
stamped on change.

Deliberately **not** routed through audit-service: catalog holds no audit client, and adding a cross-service
dependency for two fields is a larger change than the thing being audited. If catalog ever gains one, these
become the fallback rather than the record.

## 5. ⚠ Gate run 1 — the columns were right and the screen still saw nothing

Two failures. **Neither was in the code the slice was about.** The row was written correctly every time —
`pack_size 10, loose_unit tablet, allow_loose 1, default_sell_unit LOOSE`, confirmed straight from the table.
Both failures were on the way *back*.

### 5.1 The proxy's row projection is an ALLOW-LIST

`CatalogController.getUserProduct` builds each row **field by field**:

```java
row.put("name",  p.get("name"));
row.put("unit",  p.get("unit"));
...                                  // anything not named here is SILENTLY DROPPED
```

The column existed, the entity carried it, the DTO exposed it, catalog returned it — and the browser got
nothing, because this loop never mentioned it. Nothing errored; the field was simply absent.

**This is the `gl_outbox` defect wearing different clothes**: a new field needs *every* copy point or it
vanishes, and the number of copy points is not visible from where the field is declared. It cost a gate run
here; it cost an empty 4200 Sales Discount account in every tenant there.

A comment now sits on the projection saying so, because the next person to add a product field will be
standing exactly where I was.

### 5.2 A read failure that accused the write

The other failure read: **`the product was stored: expected undefined to exist`** — and the product *was*
stored. `/getUserProduct` returns `{status:"ERROR"}` with **no `collection` key** when its hop to catalog
fails, and the spec's `r.body.collection || r.body.data || []` flattened that into an empty array. A failed
read and a missing row became the same assertion, and the sentence pointed at the save path.

The spec now asserts the **envelope** — status is `SUCCESS`, the array is non-empty — before it searches, and
each failure names which one it was. *A tolerant read in a test is not robustness; it is a diagnosis pointed
at the wrong layer.* Same rule as `GenericResponse.collection`: assert the response, never coalesce it away.

## 6. Outstanding

* ~~**§4's boundary obligation**~~ — **CLOSED by [U2 §2](u2-loose-sale-arithmetic.md), by decision rather
  than by code.** U2 settled the question U0 and U1 both deferred: stock stays in **selling units**, so a
  loose sale of 5 tablets decrements 0.5 packs and `InventoryClient`'s level reads need no conversion at all.
  Stock grids, low-stock alerts and the picker stay in packs, exactly as today.

  Recorded here rather than deleted, because *an obligation that quietly evaporates is how a real one gets
  missed later*. The comment at the seam in `InventoryClient` now points at U2 §2 for the reasoning.
