# U0 — stock in base units

**Status: DONE + GREEN 2026-08-26 — unit 40/40 (0 skipped), migration verified against live data, sale gate green.**
Branch: `feature/pack-loose-selling`.
Parent: `../pack-and-loose-selling-design.md` §12b (option C, chosen 2026-08-26).

---

## 1. What this slice is for

Every other slice in the pack-and-loose design is additive. **This one changes what a stored number means**, so
it ships alone, before anything sells loose, while the migration is still arithmetic rather than archaeology.

The reason, in one line: **fractions of a pack do not divide cleanly and pieces always do.**

```
pack of 3, sell 1 loose
   packs:   0.3333…      never terminates, in Float OR Decimal
   pieces:  2            exact, forever
```

## 2. Review — what the code actually looks like

### 2.1 The finding that shapes the whole slice

**`inventory-service` is the sole owner of the stock tables.** Verified rather than assumed: the only
references to `StockEntry` outside that service are **two comments** — one in `SellController`, one in
`PharmaClientsConfig`. Every other service reaches stock through `InventoryClient`.

That boundary is what makes a unit change survivable. The migration is contained; what crosses the wire is a
separate, smaller decision (§4).

### 2.2 The fields that hold a quantity

| File | Field |
|---|---|
| `StockEntry` | `quantity`, `reservedQuantity` |
| `StockLevel` | `currentStock`, `minStockLevel`, `maxStockLevel`, `reorderPoint` |
| `ReservationPick` | `quantity`, `returnedQuantity` |
| `StockAdjustment` | `quantity` |
| `StockTransfer` | `quantity` |
| `StockDTOs` | three `quantity` fields |

All `Float`. Thirteen fields, one service.

### 2.3 ⚠ The contract is ALREADY inconsistent, and that is not this slice's doing

| Contract DTO | Type |
|---|---|
| `StockReservationLine.quantity` | **`BigDecimal`** |
| `StockPick.quantity` | **`BigDecimal`** |
| `PriceQuoteLine.quantity` | **`BigDecimal`** |
| `StockImportLine.quantity` | `Float` |
| `SaleReturnLine.quantity` | `Float` |
| `SaleRecordRequest…quantity` | `Float` |
| `InventoryClient.getStockLevel` | returns **`Float`** |

Reserving already speaks `BigDecimal` while importing speaks `Float`. **U0 does not have to resolve this**, and
should not try: converging the contract is a separate change with its own regression surface across six
services. This slice changes what inventory *stores*; §4 states exactly what it does at the boundary.

### 2.4 The data, measured

```
stock_entries                 2,559 rows
stock_levels                  2,075 rows
non-integer quantities            0        ← nothing fractional exists
```

**Every quantity in the system is already a whole number**, because nothing can currently be sold in
fractions. The migration is therefore an **identity** — and this is the cheapest it will ever be.

## 3. The change

### 3.1 Base unit = the smallest sellable piece

```
Product.packSize = 10        (U1 — not yet present)
   1 pack  =  10 base units
   stock stored in BASE UNITS, always
```

Until U1 lands, every product has no pack size, so **base unit == selling unit** and every stored number is
unchanged. That is what makes U0 shippable on its own: it is a change of *meaning* that, today, changes no
*value*.

### 3.2 Type: `BigDecimal(19,4)`, not `int`

The obvious reading of "integer base units" is an `int` column. **Rejected**, for a reason the review found:

* `StockEntry.quantity` also serves goods that are genuinely continuous — 2.5 metres of cable, 1.75 kg — and
  those exist in the system today. An `int` would round them away silently.
* `BigDecimal(19,4)` is exact for every base-unit count, and still holds a fraction for the continuous case.
* The reservation contract is already `BigDecimal`, so the seam gets *more* consistent, not less.

**The precision claim in §12b holds either way**: with pack sizes expressed in base units, a loose sale is
`5` pieces, not `0.333…` packs. The exactness comes from the *unit*, not from the column type — and choosing
`BigDecimal` keeps the slice from breaking cable and produce.

### 3.3 `EPS` comparisons are deleted, not loosened

`ReservationService` compares `remaining <= EPS` because float subtraction never lands on zero. With exact
decimals the tolerance is no longer masking anything, and leaving it in would hide a real shortfall of less
than one epsilon.

⚠ **Deleting a tolerance is a behaviour change and the riskiest line in this slice.** It ships with the FEFO
gate green in both the exact-fit and split-across-batches cases.

## 4. The boundary — what crosses the wire

**Unchanged in U0.** `InventoryClient` keeps its present signatures, and inventory converts at its own edge:

```
    caller ──(Float/BigDecimal, as today)──▶ inventory-service ──▶ BigDecimal(19,4) base units
                                                    │
    caller ◀─(Float/BigDecimal, as today)───────────┘
```

Because base unit == selling unit until U1, every caller sees exactly the numbers it sees now.

⚠ **The moment U1 gives a product a `packSize`, that stops being true** — `getStockLevel` would start
answering in pieces to callers who think in packs. **So U1 must ship the conversion at the boundary with it**,
and this document states the obligation rather than leaving it to be discovered:

> **U1 obligation:** `InventoryClient`'s level reads return **selling units** (base ÷ packSize) unless a caller
> explicitly asks for base units. The sale path already converts at `buildLines`; the *display* paths — stock
> grids, low-stock alerts, the picker's on-hand — must not silently start showing tablets.

> ### ✅ SETTLED IN [U2 §2](u2-loose-sale-arithmetic.md) — there is no conversion
>
> ⚠ **Read this before trusting the paragraph above.** U0 changed the column TYPE and described base units,
> but it multiplied nothing: every `packSize` was null, so the migration was an identity and **every stock row
> in the database is in SELLING UNITS to this day.**
>
> U2 faced the fork for real and chose to keep it that way — a loose sale of 5 tablets decrements **0.5
> packs**, exact in `DECIMAL(19,4)` for the pack sizes that dominate retail. Converting to true base units
> would mean multiplying every live stock row and changing every purchase, adjustment, transfer, import and
> count in the same deploy; its failure mode is a shop's on-hand out by a factor of `packSize`, against a
> bounded fraction-of-a-pack drift for the alternative.
>
> **So the obligation is closed, not deferred.** What this slice really delivered is *exact decimal quantities
> in selling units* — which is what makes 0.5 of a pack safe to store, and is the part that mattered.

## 5. Migration — `V8__stock_base_units.sql` (inventory-service)

```sql
ALTER TABLE stock_entries
    MODIFY COLUMN quantity          DECIMAL(19,4) NOT NULL DEFAULT 0,
    MODIFY COLUMN reserved_quantity DECIMAL(19,4) NOT NULL DEFAULT 0;

ALTER TABLE stock_levels
    MODIFY COLUMN current_stock     DECIMAL(19,4) NOT NULL DEFAULT 0,
    MODIFY COLUMN min_stock_level   DECIMAL(19,4) NULL,
    MODIFY COLUMN max_stock_level   DECIMAL(19,4) NULL,
    MODIFY COLUMN reorder_point     DECIMAL(19,4) NULL;
-- reservation_picks, stock_adjustments, stock_transfers likewise
```

**No value conversion.** Every row is a whole number today and stays that number; only the type and the
*meaning* change, and the meaning is identical until a product has a pack size.

⚠ **`MODIFY COLUMN` on a live table.** 2,559 and 2,075 rows are small, so the lock is brief — but it is a
lock, and it belongs in a deploy window rather than in the middle of a trading day.

## 6. Risks, stated

| Risk | Mitigation |
|---|---|
| A consumer does `float` arithmetic on a value now `BigDecimal` | compiler catches it — the type changes, so every call site is visited |
| Deleting `EPS` exposes a real shortfall | FEFO gate covers exact fit and multi-batch split |
| `MODIFY COLUMN` locks a live table | small tables; deploy window |
| A caller silently sees pieces after U1 | §4 states the obligation as part of U1's definition of done |
| Something reads the column outside inventory | verified: **nothing does** — the two hits are comments |

## 7. The gate — `stock-base-units.cy.js` + unit

**This slice's gate is a REGRESSION gate.** It ships no feature, so nothing new should be observable — and
that is precisely what has to be proved.

1. ⭐ **on-hand is unchanged** — record the level for several products before the migration and after; they
   must be **identical**, not merely close;
2. ⭐ **a whole sale still moves whole stock** — sell 3 of a product, on-hand falls by exactly 3;
3. **FEFO still splits** — a line larger than the first batch draws from two, and the picks sum to the line;
4. **FEFO exact fit** — a line exactly equal to one batch's remainder consumes it and stops, with `EPS` gone;
5. **the continuous case survives** — 2.5 metres of cable is still 2.5 after the migration, not 2 or 3;
6. **reservation → confirm → return** round-trips to the same number it started at;
7. **low-stock alerts** fire at the same thresholds as before;
8. **the full sale suite is green** — `sell`, `sell-return`, `purchase-return`, `order-fulfilment`,
   `installment-*` — because the point of this slice is that nothing changed.

## 8. Definition of done

* migration applied, `FlywayMigrationTest` green against an **empty** database;
* inventory-service unit tests green, `EPS` references gone;
* the eight gate cases above green;
* the wider sale and order suites green;
* §4's U1 obligation carried into the U1 slice document, not left here.

---

## 9. What this slice deliberately does NOT do

* **No contract change.** The `Float`/`BigDecimal` inconsistency in §2.3 predates this and is not resolved here.
* **No `packSize` anywhere.** That is U1.
* **No loose selling.** After U0 a shop can do exactly what it does today — which is the whole point.

---

## 10. Implementation log

**Compiles clean; `mvn -pl inventory-service test` → 40 tests, 0 failures, 0 skipped** — including
`ReservationServiceTest` (10 cases, real MySQL via Testcontainers) covering FEFO allocation, confirm,
release, expiry skipping, exact-batch returns and repeated partial returns.

### Two defects the conversion exposed, both pre-existing

**1 · Precision was being discarded at the boundary.** `ReservationService.reserve` did
`line.getQuantity().floatValue()` — the request already arrives as `BigDecimal` (`StockReservationLine`), so
the value was narrowed on entry and every downstream comparison needed an epsilon to survive it. That is
precisely what would have made a pack of 3 unallocatable: the last pieces never reach zero in float.

**2 · The availability check was generous by one epsilon.** `if (available + EPS < need)` means a shop could
be told it had stock it did not have, by up to 0.0001 — small, invisible, and wrong in the direction that
oversells. Now `available.compareTo(need) < 0`: exact, with no allowance.

### `EPS` is deleted, not loosened

Both `ReservationService.EPS` and the arithmetic that needed it are gone. A tolerance exists to absorb a
rounding artefact; once the arithmetic is exact, the same tolerance becomes a **silent allowance for being
wrong**. The comment left in its place says so, so nobody restores it as a safety measure.

### The boundary pattern — the design's key question, settled

`StockService` gained `in(Float)` / `out(BigDecimal)`:

```
    caller ──Float (unchanged contract)──▶ in() ──▶ BigDecimal(19,4) stored exactly
    caller ◀─Float (unchanged contract)─── out() ◀──┘
```

**What inventory stores is exact; what it publishes is unchanged.** No caller sees a different number, which
is what allows §2.3's contract inconsistency to stay untouched — that convergence is a six-service change and
does not belong in the slice that can break working code.

### Files changed

| | |
|---|---|
| `V8__stock_base_units.sql` | 6 tables, 11 columns → `DECIMAL(19,4)`; `products` deliberately untouched (D5) |
| Entities | `StockEntry`, `StockLevel`, `ReservationPick`, `StockAdjustment`, `StockTransfer` |
| Services | `ReservationService`, `StockService`, `StockImportService`, `ReservationExpiryWorker`, `AlertService` |
| Contracts | **none** — deliberately |

### The migration, verified against live data

| Check | Result |
|---|---|
| `flyway_schema_history` V8 | `success = 1` |
| Column types | all six tables `decimal(19,4)` |
| `stock_entries` / `stock_levels` rows | **2,559 / 2,075 — identical to the pre-migration measurement** |
| Values with a fractional part | **0** |
| Negative quantities | **0** |
| Service start | clean, healthy, no errors |
| Sale regression | **green** |

⚠ **`myplusdb_inventory.products` is still `float`** — the D5 table left deliberately untouched. Its four
level columns look exactly like the ones migrated here, which is precisely why the standard says not to act on
that resemblance.

### ⚠ A pre-existing inconsistency found while verifying — NOT caused by this slice

Total on-hand reads **62,634 across `stock_entries`** and **62,623 across `stock_levels`**: four products
disagree, by 2, 3, 3 and 3.

**All whole numbers, so a `float → decimal` change cannot have produced them** — a type change on whole values
is an identity. These are two tables updated on separate paths drifting apart transactionally, and the drift
pre-dates this work.

Recorded rather than absorbed into the U0 result. It deserves its own investigation: `stock_levels` is what
every screen and low-stock alert reads, so where the two disagree, **the shop is being told something the
batches do not support.**

### Process note

The baseline snapshot should have been taken **before** the rebuild, not reconstructed afterwards. The
guarantee held only because the row counts had been recorded in the design days earlier — documentation, not
method. On a migration that could have changed values there would have been nothing to compare against.
