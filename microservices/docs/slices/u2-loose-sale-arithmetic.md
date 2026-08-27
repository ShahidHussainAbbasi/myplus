# U2 — selling a broken pack, and the money it makes

**Status: DONE + GREEN 2026-08-27 — Cypress gate 12/12, business-service unit 206/206 (0 skipped), incl. 13 `LooseLineTest` cases and a ~3,400-combination rounding sweep.** Branch: `feature/pack-loose-selling`.
Parent: `../pack-and-loose-selling-design.md` §6, §7, §8. Predecessors: [U0](u0-stock-base-units.md) (exact
decimal quantities), [U1](u1-product-pack-fields.md) (a product knows what a pack holds).

U2 is **the money slice**. U0 changed what a number is stored as and U1 added five columns; neither could
charge a customer the wrong amount. This one can.

---

## 1. Review — what is actually there today

| Verified | Where |
|---|---|
| `SagaSellService.buildLines` prices **server-side** from `ProductRef`; a browser rate is an override | `SagaSellService:613-626` |
| The line total is **derived**, never taken from the client — `lineTotal = soldRate × qty` | `SagaSellService:626` |
| `SagaLine.quantity` and `Sell.quantity` are **`Float`**; every money field is `BigDecimal(19,2)` | `SagaLine:12`, `Sell:68` |
| The reservation is built from that same `quantity`, one line each | `SagaSellService:127` |
| `assertMarginPolicy` compares **money to money** (`Σ netAmount` vs `Σ costPrice × quantity`) | parent §6.4 |
| Settings are declared in `BusinessSettingsCatalog` and read by key; `money(...)` and `intOf(...)` exist | `SettingEntry:62,90` |
| **`stock_entries`: 2,562 rows, `fractional = 0`** | measured today |

## 2. ⚠ The contradiction that had to be settled first

The parent design says two incompatible things, written at different times:

* **§6.5** — *"Stock always moves in the selling unit… selling 5 tablets decrements 0.5 packs."*
* **U0 §3.1** — *"stock stored in BASE UNITS, always"* — the smallest sellable piece.

**Only one can be true, and U2 is where it stops being theoretical**, because U2 is the first code that sends
a quantity to inventory for a product that has a `packSize`.

### What U0 actually shipped

U0 changed the **type** (`Float → DECIMAL(19,4)`) and *described* base units, but it multiplied nothing: at
the time every `packSize` was null, so base unit and selling unit were the same number and the migration was
an identity. **Every stock row in the database today is in SELLING UNITS** — 20 means twenty packs. Nothing
has converted them, and U1 handed out pack sizes without touching stock.

### The fork

| | **A · stock stays in selling units** | **B · stock moves to base units** |
|---|---|---|
| 5 tablets of a 10-pack | −0.5 packs | −5 pieces |
| Existing rows | untouched | **every row × its packSize** |
| Purchases, adjustments, transfers, imports, counts | untouched | all must convert |
| Display (grids, low-stock, picker) | unchanged | must convert back or start showing tablets |
| Exactness | exact when `packSize` divides 10,000 (2, 4, 5, 8, 10, 16, 20, 25, 50, 100…); otherwise a residue ≤ 0.0001 packs | exact always |
| If it goes wrong | a fraction of a pack drifts | **stock wrong by a factor of `packSize`** |

**Decision: A.** Not because B is wrong in principle — B is what SAP and Odoo do — but because of what the two
failures look like. A's worst case is a bounded, self-correcting drift: for a pack of 3 it takes on the order
of a thousand packs sold loose to conjure one phantom tablet, and the stock-count screen (U6) resolves it the
way shops already resolve shrinkage. B's worst case is a shop's on-hand out by 10× on the day the conversion
misses one write path — immediate, visible, and unrecoverable without counting the whole shop.

B is also not a slice; it is U5 (purchase in boxes), U6 (counts), the display layer and a migration of live
stock, all of which must land in the *same deploy* or the shop's stock is wrong in between. Sequencing the
money behind that is how a feature stops shipping.

**So A, and the drift is stated rather than hidden** — §7 says what it is and what corrects it.

### ✅ This retires an obligation rather than fulfilling it

U0 §4 and U1 §6 both recorded that **U2 must convert at `InventoryClient`'s boundary**, or stock grids and
low-stock alerts would silently start showing tablets. Under decision A **there is no conversion, so there is
nothing to convert**: stock, display, alerts and the picker all stay in packs, exactly as today.

The obligation is **closed by the decision, not by code** — and both predecessor documents are updated to say
so, because an obligation that quietly evaporates is how a real one gets missed later.

## 3. What the sale stores

Selling 5 tablets from a 120.00 pack of 10, no markup:

| Column | Value | Kind | Why |
|---|---|---|---|
| `Sell.quantity` | **0.5** | stock | selling units — what leaves the shelf |
| `Sell.sellRate` | **120.00** | money | per **selling** unit, so `0.5 × 120 = 60.00` ✓ |
| `Sell.totalAmount` / `netAmount` | **60.00** | money | unchanged in kind |
| `Sell.soldUnit` | `LOOSE` | NEW | what the customer bought |
| `Sell.soldQuantity` | **5** | NEW | so the receipt can say "5 tablets" |
| `Sell.soldRate` | **12.00** | NEW | per piece, for the receipt |
| `Sell.packSizeSnapshot` | **10** | NEW | frozen — §3.2 |

### 3.1 ⚠ The money is computed in the unit the customer bought

The obvious implementation converts first and multiplies second:

```java
qty       = 5 / 10          = 0.5
lineTotal = 0.5 × 120.00    = 60.00        // ✓ … for a pack of 10
```

For a pack of **3** that same code gives `qty = 0.3333…`, and `0.3333… × 120` is `39.999…` — a number that
becomes 40.00 only because something rounds it afterwards. **The line's total would depend on a rounding step
rather than on the sale.**

So the order is reversed:

```java
lineTotal = soldQuantity × looseRate      // 5 × 12.00 = 60.00   exact, any pack size
quantity  = soldQuantity ÷ packSize       // 0.5                 stock + reporting only
```

**Money is derived from the unit the customer actually bought; `quantity` is a conversion for the shelf.** The
identity `netAmount ≈ quantity × sellRate` then holds to the cent by construction rather than by luck, and
`quantity` carries whatever residue the division leaves instead of the customer's bill carrying it.

*This is the INST-5a rule in a new costume: **a total is allocated, never derived by rounding a proportion.**
There, a proportion rounded into a total left a customer two paisa in credit while the trial balance balanced.*

### 3.2 `packSizeSnapshot` is frozen at write

`quantity = 0.5` and `soldQuantity = 5` agree only while `packSize = 10`. Edit the product to 12 tomorrow and
that historical 0.5 silently becomes *six* tablets — on the receipt, on the return, and in every report that
re-reads a finished sale. Stamped at write, joining `Sell.costPrice` and `order_items.product_name`.
**Stamp at write, never derive on read.**

### 3.3 Nothing downstream learns a new concept

Tax, COGS, GL, credit, aging, statements and installments see a line with a quantity and a rate, as now.
**No new `PostingEventRequest` field** — and a design that adds no posting field cannot reproduce the 4200
defect, where one new field needed five copy points and vanished from every tenant's books for months.

`assertMarginPolicy` needs no special case: it compares 60.00 against `100.00 × 0.5 = 50.00`. Correct, and
untouched — the same fact seen from the other end as §3.1.

## 4. The loose rate

```
looseRate = ceilToCents( sellingPrice × (1 + looseMarkupPct/100) ÷ packSize )
```

in `BigDecimal` throughout — `120/3` in binary floating point is `39.999999…`, and a `ceil` applied to that is
a defect waiting for the right price.

**Rounded UP, deliberately.** 100 ÷ 3 = 33.33; three sold singly return 99.99 for goods priced 100. Rounding
down loses money on **every broken pack**, invisibly, on the fastest-moving lines in the shop. The rate stays
editable on the line, so a shop that wants to absorb the paisa still can.

**Derived, never stored.** A second stored price is a second thing to maintain, and the day it drifts the shop
sells tablets at last year's rate while the pack is current.

**Base = the pre-tax, pre-discount `sellingPrice`** (parent §7.2) — the only ordering under which 1 pack +
5 loose of the same product under a 10% line discount discounts each by 10% of its own line. A rate derived
after discount would discount the loose part twice.

### 4.1 The setting

`pos.sale.looseMarkupPct`, group **Sale**, default **`0`**, declared with `SettingEntry.money(...)` because it
is a decimal — 2.5% is a real answer — not an integer. Default 0 leaves behaviour identical for a shop that
does not want it, and every trade in parent §1 prices loose above the pack rate, so shipping without it is a
feature shops decline to switch on.

## 5. What the server refuses

Each is a server-side refusal, not a UI guard — the till is one caller and U3 is not written yet. All are
`ValidationException`, a business refusal, which must **not** mark a caller's transaction rollback-only.

| # | Refusal | Why it must be server-side |
|---|---|---|
| 1 | `soldUnit=LOOSE` on a product with `allowLoose=false` | the permission is the whole control |
| 2 | `soldUnit=LOOSE` with `packSize` null or ≤ 1 | there is nothing to divide |
| 3 | a **non-integer** `soldQuantity` on a LOOSE line | half a tablet is not a thing this sells |
| 4 | `soldQuantity ≤ 0` | |
| 5 | a pack size above 10,000 | one piece of it rounds `quantity` away to 0.0000 — a line that takes money and moves no stock |

### ⚠ 5.1 Whole packs are priced as packs — and it is not a special case

The design first wrote this as refusal #5: *"`soldQuantity ≥ packSize` is converted to 1 PACK."* **The
implementation found a better rule**, and the doc is corrected rather than the code bent to match it:

```
pieces  ÷  packSize  →  whole packs  +  remainder
total   =  wholePacks × packRate  +  remainder × looseRate
```

| asked | pack | priced as |
|---|---|---|
| 5 tablets | 10 | 5 × loose |
| 10 tablets | 10 | **1 pack** — markup never applies |
| 25 tablets | 10 | **2 packs + 5 loose** |

The original rule handled the 10 case and had no answer for 25. This one handles both, and **"ten tablets of a
ten-pack costs the pack price" stops being a rule at all** — it falls out of the arithmetic. That matters
commercially: with a markup set, ten loose tablets would otherwise cost more than the sealed pack sitting
beside them, and the customer can see both prices.

*A special case that disappears when the rule is stated properly was never a special case; it was a symptom of
stating the rule wrongly.*

## 6. Migration — business-service

```sql
ALTER TABLE sell
    ADD COLUMN sold_unit          VARCHAR(8)     NULL,   -- NULL = an ordinary line, as every existing row is
    ADD COLUMN sold_quantity      DECIMAL(19,4)  NULL,
    ADD COLUMN sold_rate          DECIMAL(19,2)  NULL,
    ADD COLUMN pack_size_snapshot INT            NULL;
```

**Nullable, no default, no backfill.** Every existing row is an ordinary pack sale and must stay
distinguishable from one explicitly recorded as `PACK`. Idempotent `ADD COLUMN` blocks in V8's pattern, and
the version number read from `flyway_schema_history` — **not** `ls | tail`, which sorts lexically and is what
made U1's first migration report success while never opening the file.

## 7. Risks, stated

| Risk | Mitigation |
|---|---|
| A loose line priced per pack (10× undercharge) | ⭐ the carrying gate case asserts the **money**, not the columns |
| `quantity` residue accumulates as phantom stock | bounded (§2); U6's count screen corrects it; stated openly |
| A browser sends `soldUnit=LOOSE` for a product that forbids it | §5 refusals are server-side |
| `packSize` edited later re-interprets old sales | `packSizeSnapshot` frozen at write |
| A widened `SagaLine` breaks `MarginPolicyTest`'s positional helper | the record's own comment says so — **it has happened three times**; same commit |
| A new `Sell` field is dropped on the way to a screen | U1 §5.1 — the monolith's product projection is an allow-list; check every copy point |

## 8. The gate — `sell-loose.cy.js`

1. ⭐ **5 tablets of a 120/10 pack cost 60.00** — invoice total, the line's `netAmount`, and
   `netAmount == quantity × sellRate` to the cent. *The case the slice exists for.*
2. ⭐ **stock falls by half a pack, not by five packs** — on-hand before and after, exactly.
3. **the customer's version is recorded** — `soldQuantity 5`, `soldUnit LOOSE`, `soldRate 12.00`.
4. **a pack sale is identical to today** — same quantity, rate, total, tax, and `soldUnit` **null**.
5. **a pack of 3 does not lose a paisa** — 1 loose of a 100.00/3 pack bills **33.34**; three *separate*
   single sales bill 100.02, not 99.99. Three on **one line** is a whole pack and bills exactly 100.00.
6. **markup 10% applies to loose only** — the pack still sells at 120.00; a tablet at 13.20, not 12.00.
7. **a product that forbids splitting refuses a loose line** (§5 #1).
8. **2.5 tablets is refused** (§5 #3).
9. **10 tablets of a 10-pack costs the pack price**, markup not applied, and **25 tablets is 2 packs +
   5 loose** (§5.1).
10. **the margin guard still fires** on a loose line sold below cost — it must not have been fooled by the
    unit change.
11. **a mixed basket** — 1 pack + 5 loose of the same product, one invoice, totals correct, stock −1.5.
12. **the trial balance moves by the invoice total**, because the books are the point.

Plus `mvn test` unit cases for the rate arithmetic: a sweep across pack sizes 2…24 × a range of prices,
asserting `looseRate × packSize ≥ sellingPrice` always (the shop never loses on a broken pack) and that the
excess is under one cent per piece.

## 9. What U2 deliberately does NOT do

* **No till UI.** That is U3. U2 is reachable only through the API and the gate drives it that way —
  deliberately: the arithmetic gets proved before a screen makes it convenient.
* **No purchase in boxes** (U5), **no receipt or report changes** (U4), **no loose returns** (U6).
* **No base-unit conversion** — §2 closes that obligation rather than deferring it again.

---

## 10. Industry alignment — what the established systems actually do

Checked before committing to §2, because "sell a fraction of a purchase unit" is one of the oldest problems in
commerce software and re-inventing it would be indefensible.

| System | How it models this | What we took |
|---|---|---|
| **SAP** (Material Master: base UoM + alternative UoM with a conversion factor) | one **base unit**, every other unit converts to it | **the vocabulary and the frozen conversion** — SAP stores the factor on the document, not only on the material |
| **Odoo** (`uom_id` / `uom_po_id`, category-scoped) | the line names its own UoM; stock is held in the reference UoM | **the line names its unit** — `soldUnit` is Odoo's `uom_id` |
| **NetSuite / Dynamics 365** (unit-of-measure sets, pricing per unit) | prices can differ per unit, not merely divide | **the markup** — `looseMarkupPct`, because a broken pack is genuinely worth more per piece |
| **Square / Shopify POS** | *no* fractional units; shops create a separate SKU per size | **the warning** — this is the shortcut we deliberately did not take (§10.1) |

**Where we deliberately differ from SAP/Odoo:** they hold stock in the base unit. §2 keeps it in the selling
unit because they arrived at base units *before* they had live tenants, and we would be converting a running
shop's on-hand. **The same destination reached by a different road** — U5/U6 can still move to base units
later, on purpose, with the migration and the purchase side in one deploy. That option stays open precisely
because `packSizeSnapshot` is frozen on every line: historical sales stay interpretable across the change.

### 10.1 The shortcut we did not take

The common small-POS answer is a **second product** — "Panadol pack" and "Panadol tablet" — which needs no
arithmetic at all. It is rejected because the two SKUs hold **two independent stock balances**, so selling a
tablet does not reduce the pack, the shop's on-hand is wrong the moment anyone breaks a pack, and no report
can reconcile them. *A model that cannot represent the fact that these are the same goods will produce
numbers that disagree with the shelf, forever.*

### 10.2 The pattern, named

**Value Object + Strategy at the pricing seam.** `LooseLine` is an immutable value object computed by a pure
static function of (request, product, resolved rate, policy); `buildLines` composes it into the existing line
pipeline and nothing else in the system learns a new concept (§3.3).

* **Open/Closed** — U3's till, U5's purchases and any future channel call the same function; adding a caller
  adds no branch here.
* **Single responsibility** — the arithmetic knows nothing about HTTP, JPA, transactions or tenants, which is
  exactly why it is unit-tested as pure logic on every `mvn test`.
* **DIP** — `ProductRef` is the contract, so catalog's persistence model can move without touching pricing.
* **DRY** — the loose rate is derived in **one** function. A second stored price is a second thing to maintain,
  and the day it drifts the shop sells tablets at last year's rate while the pack is current.

## 11. Performance

The hot path here is *the sale*, and the standing rule is that inter-service calls stay off it.

* **No new remote call.** `packSize` and `allowLoose` ride on the `ProductRef` that `buildLines` **already
  fetches** for every line — this was the reason U1 put them on the contract rather than adding a lookup.
* **No new query.** Nothing reads `sell` differently; four columns on an existing insert.
* **The markup setting is read once per sale**, not per line, alongside the existing per-sale reads
  (`taxService.settingsFor`, `pharmacy.rx.requirePrescription`) — a per-line read would multiply config
  round-trips by basket size on every sale in the system.
* **The arithmetic is O(1) `BigDecimal`** on a path that already does I/O; it is not measurable beside the
  catalog and inventory calls surrounding it.
* **No index needed.** The new columns are written and read by primary key with the sale; adding an index to
  four nullable columns nothing filters on would cost every insert and buy nothing.

Net effect on the sale path: **unchanged** for a shop that never breaks a pack, which is every shop until the
feature is switched on per product.

## 12. Security

* **The permission is the product's, and it is enforced server-side.** `allowLoose` is checked in
  `looseLine`, not in the browser — the till is one caller, and U3 does not exist yet. An API client, a CSV
  import or a future integration meets the same refusal.
* **Prices are never taken from the client.** `looseRate` derives from the rate the server resolved for that
  line (catalog, contract or an override already judged by `assertMarginPolicy`). A caller cannot post
  `soldRate` and be believed — it is server-populated and ignored inbound, and the DTO says so.
* **`packSizeSnapshot` is server-stamped**, so a caller cannot claim a pack held 100 pieces and buy the shop
  out at 1/100th of the price.
* **Tenancy is unchanged.** Every read here goes through the already-scoped `catalogClient`/`ProductRef`; no
  new query, so no new place to forget `org_id` (ARCHITECTURE-MULTITENANCY).
* **The margin guard still runs**, unchanged and *before* any reservation or write — a loose line sold below
  cost is refused or warned exactly as a pack line is (§3.3).
* **Refusals are `ValidationException`**, a business refusal — it must not mark a caller's transaction
  rollback-only, or a tidy "not sold by the piece" becomes "Transaction silently rolled back".

---

## 13. Implementation log

### Files

| | |
|---|---|
| `V51__sell_loose_columns.sql` | 4 nullable columns, idempotent, version read from `flyway_schema_history` |
| `Sell`, `SellDTO` (service) | the four fields + the division-of-labour comment |
| `SagaLine` | widened — and the three positional test helpers updated **in the same commit** |
| `SagaSellService` | `looseLine()` + `normaliseSoldUnit()`; the markup read once per sale |
| `SagaSaleWriter` | maps the four onto `Sell` |
| `BusinessSettingsCatalog` | `pos.sale.looseMarkupPct`, group Point of Sale, default 0 |
| **monolith `SellDTO`** | the four fields — see §13.2 |
| `InventoryClient` | the stale U1 obligation comment corrected to say it is closed |
| `LooseLineTest` | **new**, 13 cases, pure logic, no Docker |
| `sell-loose.cy.js` | **new**, 13 cases |

### 13.1 A `DECIMAL` column under a `Float` field will not start the service

The design specified `sold_quantity DECIMAL(19,4)`; the entity field follows `Sell.quantity`'s `Float`.
Hibernate's `validate` refused the whole context:

```
Schema-validation: wrong column type encountered in column [sold_quantity] in table [sell];
found [decimal], but expecting [float(23)]
```

Six errors, all from `FlywayMigrationTest` — **which is exactly the test U1 found catalog-service was
missing.** business-service had one, so this cost minutes rather than a deployment. `sold_quantity` is now
`FLOAT`, matching `quantity` and `bonus_quantity` beside it; exact either way, because a whole number below
2²⁴ is represented precisely and non-whole piece counts are *refused*, not rounded.

### 13.2 ⚠ The same defect shape, a third time — the monolith's `SellDTO`

`com.web.dto.business.SellDTO` is a typed DTO annotated `@JsonIgnoreProperties(ignoreUnknown = true)`. A field
the browser posts that is not declared there is **dropped in silence** on the way to business-service.

Had this been missed, U3's till would have posted `soldUnit: "LOOSE"`, the proxy would have discarded it,
`buildLines` would have priced a whole pack, and **the customer would have been charged 120.00 for five
tablets** — with no error anywhere, because that is a perfectly well-formed sale.

Three instances in this programme now, all the same shape:

| | |
|---|---|
| `gl_outbox` | a new posting field needed 5 copy points; **4200 was empty in every tenant for months** |
| `getUserProduct` row projection (U1 §5.1) | hand-copied allow-list; cost a gate run |
| monolith `SellDTO` (here) | typed allow-list with `ignoreUnknown` |

**The rule this earns:** when a field must cross a service boundary, *count the copy points before writing the
first one* — the compiler checks none of them, and every one of these failed silently.

### 13.3 The sweep was verified by breaking it

`the_loose_rate_never_undercharges_for_a_whole_pack` sweeps ~3,400 combinations (pack sizes 2–24 × prices on
an odd step, so thirds and sevenths are hit). A sweep that passes with the fix disabled proves nothing, so
`CEILING` was temporarily changed to `HALF_UP`: **2 failures, first at pack of 3.** Restored, 13/13 green.

*INST-5a's lesson, applied before the fact this time: a fixture that divides cleanly is not a test of
rounding.*

### 13.4 Two spec helpers were verified against the running service, not guessed

Both would have failed silently rather than loudly:

* `/productStockLevels` returns `levels` as a **map keyed by product id** (`{onHand, sellable, held, expired}`),
  not a list of rows. Guessed as a list, every stock assertion reads 0 and **passes**.
* `/getUserSell` returns a **flat list of lines**, each carrying its `customerHistory` — not invoices with a
  nested `sales[]`. Read the other way, `line.netAmount` is `undefined` and every money assertion compares
  `NaN`.

* `/addPurchase` is **form-encoded with flat `stock.*` fields** and answers `{status:"SUCCESS"}` — not a JSON
  `purchases[]` payload, and it has no `success` flag. The first version of the helper failed **all 12 cases**
  with *"quantity: Blank/Null Not Allowed"* before a single line of U2 was exercised.

Also: `addSell` answers with the invoice number in `object` (the `GenericResponse` envelope), not `invoiceNo`.

### 13.4b ⚠ A negative assertion against a field that does not exist ALWAYS passes

The sale endpoints answer with a `GenericResponse` — `{message, error, status, object, collection}` — and
**there is no `success` field**. So `r.body.success` is `undefined`, and that cuts both ways:

| written | on a good sale | on a sale that should have been refused |
|---|---|---|
| `expect(r.body.success).to.eq(true)` | **fails** — loudly, harmlessly | — |
| `expect(r.body.success).to.not.eq(true)` | — | **PASSES** |

The first cost a gate run. **The second is the dangerous one**: three refusal cases reported green while
asserting nothing whatsoever — they would have passed had the server happily sold five tablets of a product
marked "may not be split".

*A green test that asserts against a field that does not exist is worse than a red one.* The envelope is now
read through two helpers, `expectSale` and `expectRefused`, so it is asserted in one place and cannot drift
per call site — and `expectRefused` demands the ERROR status **and** a matching reason, so it cannot pass by
accident again.

Same family as the `collection`/`data` trap already recorded: GenericResponse puts lists in `collection`, and
a helper that tolerates the wrong shape reports a passing test instead of a broken one.

### 13.4c ⭐ A fixture that was wrong in twelve cases and passed in eleven

The last failure standing was the pack of 3: **32.00 where 33.34 was expected.** 32 is `96 ÷ 3`, and 96 is
`80 × 1.2` — the markup my stock-in fixture used to invent a selling rate.

**`PurchaseService.stampRatesOnProduct` pushes `stock.bsellRate` into the catalog as the product's selling
price.** That is the "last rates" feature working exactly as designed — stamp at write, don't derive on read.
The fixture was quietly repricing every product it stocked.

It was wrong in **all twelve** cases. Eleven passed because `cost 100 × 1.2 = 120` happened to equal the price
those tests wanted. Only the case with a different cost (80) and a price that does not divide (100 ÷ 3)
diverged — and it is the case that exists *because* it does not divide.

Three things this earns:

* **A coincidence in a fixture can hold up a whole suite.** Eleven green assertions rested on an arithmetic
  accident; had every product cost 100 and sold at 120, U2 would have shipped with a fixture that silently
  overwrote prices, and the first shop to buy at a different margin would have found it.
* **`stockIn` now takes the price EXPLICITLY** and asserts it was supplied, so the restamp is a visible no-op
  rather than an invisible rewrite. All 12 call sites were checked to match their product.
* **The awkward case earns its place.** The pack of 3 was written to test *rounding*; it caught a fixture
  defect instead. A test whose numbers divide cleanly cannot tell you which of your assumptions is holding it
  up. (INST-5a said the same thing about a total derived from a proportion.)

### 13.5 ⚠ Three gate runs, three fixture defects, zero product defects

U1's second run and U2's first run were both lost to **fixtures**, not to the feature:

| Run | What broke | The product code was |
|---|---|---|
| U1 #1 | the monolith's row projection dropped the new fields | correct |
| U1 #1 | a tolerant read blamed the write | correct |
| U2 #1 | `/addPurchase` sent in the wrong shape | never reached |

Every one was a **guess about a shape that could have been read**. The fixtures did their job — they failed
loudly and named the endpoint — but a fixture that has to fail to teach the shape costs the user a full run.

**The rule: copy a fixture from a spec that is currently green, or read the controller. Never compose one from
memory of how a similar endpoint looked.** `purchase-batch-prefill.cy.js` had the exact call the whole time.
This is the same class of mistake as guessing UI selectors, which the user has already had to correct twice in
this programme.
