# U7 — the shop's own sticker

**Status: DONE + GREEN 2026-08-29 — Cypress gate 11/11, catalog-service 50/50. U0–U7 COMPLETE: the whole pack-and-loose programme is green.** Branch: `feature/pack-loose-selling`.
Parent: `../pack-and-loose-selling-design.md` §3.3. Predecessors: U1–U6, all green.

A pharmacy that sells a lot of single tablets prints its own label — `LP-4471` — sticks it on the strip
holder, and wants scanning it to sell **one tablet**, with no keystroke.

Today that costs a typed marker: `1L*CODE`. U7 makes the sticker carry the meaning.

---

## 1. Review — what a scan resolves to now

| Verified | Where |
|---|---|
| The scan box parses `12*CODE` and (since U3) `5L*CODE` | `business.js` `parseScanEntry` |
| The code is resolved by `/lookupProduct` → catalog `/products/lookup?code=` | `CatalogController:242` |
| The query is **`barcode = :code OR sku = :code`**, org-scoped, active only, barcode ranked first | `ProductRepository:87` |
| It answers a `ProductRef` — a PRODUCT, with no notion of "how many" or "in what unit" | `ProductController` |
| A product has exactly **one** `barcode` column | `Product` |

**So a code today answers *which product*. A sticker has to answer *which product, in what unit, how many*** —
and there is nowhere to put the last two.

## 2. The shape: one small table, consulted first

```
product_barcode
  organization_id   13
  barcode           "LP-4471"      the label the shop prints
  product_id        88
  sold_unit         LOOSE          what this code MEANS
  quantity          1              and how many
```

`/products/lookup` gains an alias check **before** the existing query. A code that is not an alias resolves
exactly as it does today — which is every scan in every shop until someone registers a sticker.

### 2.1 ⚠ The response has to carry more than a product, without polluting `ProductRef`

`ProductRef` is a shared contract read by six services. `soldUnit` and `quantity` are **not properties of a
product** — they are properties of *this code*. Putting them on `ProductRef` would mean every consumer
carries two fields that are meaningless in their context, and one of them would eventually be read as though
it were the product's own.

So the scan path gets its own answer:

```java
ScanResolution { ProductRef product; String soldUnit; Float quantity; }
```

returned by a **new** `/products/scan?code=`. `/products/lookup` is left exactly as it is, so nothing that
resolves a code today changes behaviour.

*A field belongs to the thing it describes. When it does not fit, the answer is a new type, not a wider one.*

## 3. ⚠ The rule that makes this safe: an alias may never shadow a real barcode

If a shop registers a manufacturer GTIN as an alias meaning "1 tablet", **every scan of that pack sells one
tablet** — the commonest transaction in the shop, mis-priced, silently, until someone notices the takings.

So registering an alias is **refused** when the code already exists as any product's `barcode` or `sku` in
that org, and the product's own barcode/sku is refused as an alias. The check is server-side and org-scoped.

⚠ This is the same class of hazard as U6's pack/loose refund: a rule that looks like tidiness and is actually
the difference between a shop being right and being quietly wrong on its highest-volume line.

### 3.1 And the reverse: a real barcode must win a tie

Alias first sounds risky, and §3's refusal is what makes it safe — but refusals only bind *new* data. A code
that becomes a product barcode *after* an alias was registered would otherwise be shadowed forever. So the
resolution order is stated and gated: **alias, then barcode, then sku**, and the alias registration refuses
collisions in both directions.

## 4. What a sticker may say

| Field | Allowed | Refused |
|---|---|---|
| `soldUnit` | `LOOSE` or `PACK` | anything else |
| `quantity` | a whole number ≥ 1 | 0, negative, fractional |
| `LOOSE` | only when the product `allowLoose` and `packSize > 1` | otherwise — the same rule the till enforces |
| `quantity ≥ packSize` on a LOOSE sticker | allowed, and priced by U2's split | — |

A `PACK` sticker is useful too: `BOX-12` meaning "12 packs" saves a cashier typing `12*`.

## 5. Where a shop manages them

On the **product form**, under the pack rules U1 added — a small list with add/remove. That is where an owner
already decides what a pack holds and whether it may be split; a sticker that means "one tablet" is the same
decision, expressed for the scanner.

**No label printing.** Shops print labels with the tool they already own; inventing a print pipeline would be
a slice of its own and is not what makes this feature work.

## 6. Refusals

| # | Refused | Why |
|---|---|---|
| 1 | an alias colliding with any product's barcode/sku in the org | §3 — it would shadow a real product |
| 2 | a duplicate alias within the org | one code, one meaning |
| 3 | `LOOSE` on a product that may not be split | the same control the till enforces |
| 4 | a quantity that is zero, negative or fractional | half a tablet is not sold, so it is not scanned |
| 5 | an alias for a product in another org | tenancy — the query is scoped, and the write is checked |

## 7. Gate — `own-sticker-scan.cy.js`

1. ⭐ **scanning `LP-4471` adds ONE tablet** — the cart line is `1 tablet`, 12.00, and the stored line has
   `soldUnit LOOSE`, `soldQuantity 1`.
2. ⭐ **an ordinary scan is unchanged** — a manufacturer barcode still adds 1 pack, `12*CODE` still adds 12.
   *The regression that protects every shop that never prints a sticker.*
3. ⭐ **an alias cannot shadow a real barcode** — registering a code that is already a product's barcode is
   refused, with the reason.
4. **and the reverse** — a product cannot take a barcode that is already an alias.
5. **a `PACK` sticker for 12 adds 12 packs.**
6. **`LOOSE` is refused on a product that may not be split.**
7. **a fractional or zero quantity is refused.**
8. **a sticker for another org's product is refused** (anti-IDOR).
9. **removing a sticker restores ordinary lookup** for that code.
10. **`5L*LP-4471`** — a typed marker on an alias — resolves sensibly or is refused, but never silently
    multiplies twice. *Written to discover; see U6 §10.4 for why that phrasing is deliberate.*

Plus unit cases for the resolution order and the collision refusals.

## 8. Performance

One indexed lookup on `(organization_id, barcode)` **before** the existing query, on the scan path only —
which already makes one remote call per scan. A miss costs one index probe. The picker, the product list and
the sale path are untouched.

⚠ The alias table is read on **every scan**, so the unique index is not optional: it is what keeps the added
cost a probe rather than a scan of the tenant's codes.

## 9. Security

* The alias query is **org-scoped**, like every other catalog read (ARCHITECTURE-MULTITENANCY).
* Registering an alias checks the product belongs to the caller's org — a `productId` from a request is never
  trusted (anti-IDOR).
* `soldUnit=LOOSE` is re-validated against the product's `allowLoose` **at scan time**, not only at
  registration: a shop can switch loose selling off after a sticker exists, and the sticker must stop working
  the moment it does.

## 10. Industry alignment

| System | Own codes | Taken |
|---|---|---|
| **SAP / Odoo** | multiple barcodes per product, one per packaging level, each with its own quantity | ⭐ **a code carries a quantity, not just an identity** |
| **Retail POS generally** | shelf-edge and own-brand labels resolved through an alias table | the alias table, consulted first |
| **Pharmacy systems** | strip-holder labels that dispense a single unit | the loose sticker itself |

This is the one place in the programme where the established systems do exactly what we need and we take it
wholesale: **a barcode is a code that means "this many of this, in this unit"**, and a product's own barcode
is simply the case where that means "one of the selling unit".

## 11. What U7 deliberately does NOT do

* **No label printing**, no barcode image generation.
* **No multi-level packaging.** A sticker carries a quantity; it does not introduce a unit hierarchy —
  parent design §4 still holds.
* **No bulk import of stickers.** If a shop needs hundreds, that is the CSV import slice's job.

---

## 12. Implementation log

| | |
|---|---|
| `V13__product_barcode_alias.sql` | one table, `UNIQUE (organization_id, barcode)`; version read from `flyway_schema_history` (head V12) |
| `ProductBarcode` + repository | org-scoped, **no NULL-org fallback** — see §12.1 |
| `ScanResolution` (contract) | a NEW type, not two more fields on `ProductRef` |
| `ProductBarcodeService` | resolution order, both collision refusals, anti-IDOR on the product |
| `/products/scan`, `/{id}/barcodes` ×2, `/barcodes/{id}` | catalog |
| monolith `/scanProduct`, `/productBarcodes`, `/addProductBarcode`, `/removeProductBarcode` | pass-through, **no projection** |
| `business.js` scan path | resolves through `/scanProduct`; a typed marker outranks a sticker |
| product form | sticker list + add/remove, shown only on a saved product |
| six i18n bundles | +7 keys each, **1867 `ui.*` in lockstep** |

### 12.1 The repository has no NULL-organisation fallback, deliberately

`ProductRepository`'s scope clause is `organizationId = :orgId OR (organizationId IS NULL AND userId = :userId)`
— a fallback for products created before tenancy landed. **This table is new**: every row is written with an
organisation, so the fallback would have no legitimate row to find and would only be a way for one tenant's
sticker to resolve in another tenant's till.

*A compatibility clause copied into new code is not compatibility; it is a hole with a precedent.*

### 12.2 A typed marker outranks the sticker

`5L*LP-4471` means the operator said "five loose" **and** the sticker says "one tablet". If the sticker's
quantity were applied on top, one deliberate keystroke would silently become five.

So `sellScanAdd` records whether a marker was typed and only consults the sticker's unit/quantity when it was
not. The gate's last case exists to prove this rather than assume it.

### 12.3 What is enforced where

The collision rule spans two tables, so no database constraint can express it — it lives in
`ProductBarcodeService` and is checked in **both** directions. The migration carries a comment saying so,
because the unique index looks sufficient and is not: it stops two stickers sharing a code, and does nothing
about a sticker shadowing a product's own barcode.

`allowLoose` is re-checked **at scan time**, not only at registration: a shop can switch loose selling off
after a sticker is printed, and the sticker must stop working the moment it does.

### 12.4 ⚠ Gate run 1 — 10/11, and the one failure was a bare array

`GET /products/{id}/barcodes` returned a bare JSON **array**. The monolith's catalog client deserialises into
`Map<String,Object>`, and an array cannot become a Map — so the call threw, `ProxyErrors.failure` answered
`{success:false}`, and a list that had rows in it looked empty.

**Every other endpoint on that controller answers in `ApiResponse`. Mine was the outlier** — which is the
entire argument for a house envelope, and it is governing standard 8, which I wrote three days ago.

Worth noting what did NOT fail: the sticker resolved to one tablet, the collision refusals held in both
directions, the ordinary scan was unchanged, and the typed marker outranked the sticker. **The feature was
right; the plumbing on one read was not** — the same shape as U4's projection and U6's stock delta.

*A convention only protects the code that follows it.*

### 12.5 Gate run 2 — a fixture that destroyed its own uniqueness

`('5901234' + uniq()).slice(0, 13)` keeps a constant prefix and the SLOW leading digits of the timestamp and
throws the random tail away. Three consecutive calls return the identical code, so the run collided with an
earlier run's product and the lookup correctly answered the OLDER one.

**The assertion was right; the fixture was wrong.** *Slicing a unique value from the wrong end destroys the
only part that was unique.*

The same bug sat in the shadow-refusal case and had been passing by luck — it asserts a refusal, and a
colliding code is still refused. One `gtin13()` helper now slices from the right.
