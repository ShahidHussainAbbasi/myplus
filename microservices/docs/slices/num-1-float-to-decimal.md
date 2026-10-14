# NUM-1 — money and quantities off FLOAT (business-service) · standard D14

Status: **DESIGN — awaiting consent (2026-10-14).** Nothing changed yet.

## 1. Why

Standard D14 (SAAS-BUILD-STANDARDS §1b): money and quantities are `BigDecimal` / `DECIMAL`, never float. A float
cannot hold 0.075 (three tablets of a box of forty): it stores 0.0750000030, and every sum, return and conversion
carries the error forward. Measured on the live Docker DB, 2026-10-14:

| column | rows set | largest | float noise rows |
|---|---|---|---|
| `sell.quantity` | 3,359 | 630 | **33** |
| `sell.sold_quantity` | 96 | 35 | 0 |
| `sell.bonus_quantity` | 191 | 1 | 0 |
| `purchase.quantity` | 2,871 | 500 | 0 |
| `purchase.bonus_quantity` | 88 | 1 | 0 |
| `purchase.purchase_expense` | **0 (dead: no reader)** | — | — |
| `sale_return.quantity` | 290 | 5 | **26** |
| `sale_return.sold_quantity` | 39 | 10 | 0 |
| `sales_quote_line.quantity` | 362 | 15 | 0 |
| `sales_quote_line.sold_quantity` | 16 | 10 | 0 |

The noise sits exactly in the two **shelf-fraction** columns (`sell.quantity`, `sale_return.quantity` — 0.075 of a
box); every whole-number column is clean. The noise also LEAVES the service: `SagaSellService:791` sends
`BigDecimal.valueOf(sd.getQuantity())` — a Float widened to double — to inventory, whose pick columns then round it.

Not in scope: `customer.paid_amount`, `customer_history.received_amount` (FLOAT but unmapped, all NULL — D14 item 4,
reader-checked before any drop); the empty legacy `sells`/`purchases`/`customers` tables.

## 2. The change

| Layer | From | To |
|---|---|---|
| MySQL (Flyway **V83**) | `FLOAT` × 10 quantities, `FLOAT` `purchase_expense` | `DECIMAL(19,4)` × 10, `DECIMAL(19,2)` |
| Entities `Sell`, `Purchase`, `SaleReturn`, `SalesQuoteLine` | `Float` (8 fields + 1 money) | `BigDecimal`, `@Column(precision = 19, scale = 4)` (`purchaseExpense` 19,2) |
| business DTOs `SellDTO`, `PurchaseDTO`, `BusinessDTOs` (×2), `ReturnDocumentDTO` | `Float` (10 fields) | `BigDecimal` |
| monolith twins `com.web.dto.business.SellDTO`, `PurchaseDTO` | `Float` (5 fields) | `BigDecimal` |
| Arithmetic (compile-driven: every site fails to compile and is rewritten deliberately) | `float` math, `Float::sum`, `BigDecimal.valueOf(float)` | `BigDecimal` math, `compareTo`, scale 6 for division, one rounding at the store |

**V83** — `MODIFY` is idempotent; FLOAT→DECIMAL rounds to the column's scale, which is exactly the clean-up wanted
(0.0750000030 → 0.0750). Before it runs, a pre-flight query lists any value whose rounding would change it by more than
0.0001. **Run 2026-10-14: largest shift 0.000000024** (`sell.quantity`) and 0.000000006 (`sale_return.quantity`); every
noisy value is a float stand-in for a clean one (0.2, 0.175, 0.3, 1.4, 0.05 → exactly those). Values fit: largest 630
against 15 integer digits.

### Rules for the rewrite
1. **Never `BigDecimal.valueOf(float)`** and never `new BigDecimal(double)`. Parse from the DTO's BigDecimal.
2. **Compare with `compareTo`, never `equals`** — `new BigDecimal("1.0").equals(new BigDecimal("1.0000"))` is false,
   and it compiles. Every `equals`/`Objects.equals` on these fields is reviewed by hand (the compiler cannot help).
3. **Divide at scale 6, `HALF_UP`; round once when storing** (D14 rule 3). Shelf conversions keep U13's proportional
   rule (`packsForLooseReturn`), never pieces ÷ packSize.
4. **Text output through one helper** — `Quantities.plain(BigDecimal)` = `stripTrailingZeros().toPlainString()` — so a
   CSV, receipt, PDF or log line prints `10`, not `10.0000`. Found: `SellController` export lists (≈1069, 1091),
   `DuplicateBillLine.qty`. The browser is unaffected (`JSON.parse("10.0000") === 10`).
5. Native/JPQL `SUM` over these columns now returns `BigDecimal` instead of `Double` — 1 query found; its reader is
   rewritten with the rest.

## 3. Readers and writers (trace — to be completed by the compile pass)

Static count today (business-service main / tests / monolith): `getQuantity` 67/13/1 (overcounts — other classes
share the name), `getSoldQuantity` 7/2/0, `getBonusQuantity` 2/0/0, `getPurchaseExpense` 0/0/0, setters 28/25/1.
19 float idioms (`floatValue`, `Float.valueOf`, `0F` literals). Hot spots read so far: the sale-return path
(`SellController` ≈1803–1824, `float soldQty − retQty`), the sale-edit stock delta (≈1536–1540, `Float::sum`), the
purchase saga (`PurchaseService` 512, 839, 1102), `SaleCosting`, `InternalSalesController`, `SaleReportGrouping`.
Cross-service: inventory receives the quantity as JSON (already `BigDecimal` there) — **its pick columns are still
`decimal(38,2)` (drift, item 2), so the full benefit needs item 2 in the same release.**

## 4. Order of work and gates

1. Pre-flight SQL (read-only) + the red gate on the CURRENT build.
2. V83 + entities + DTOs + monolith twins; the user compiles business-service and the monolith; I fix each error by
   the rules above until both compile and `mvn test` is green (whole module, not one class).
3. Inventory item 2 (19,4 + `validate`) in the same release — deploy order: inventory → business → monolith.
4. Gate `num-1-decimal-quantities.cy.js`, red on the old build first:
   - **N1** sell 3 tablets of a 40-box: the sale line stores **0.0750** exactly, and inventory's pick for it is 0.0750
     (today: 0.0750000030 / 0.08);
   - **N2** sell 1 tablet of a 3-box, return it: stock returns to exactly where it was (no 0.0001 left behind);
   - **N3** an export (Sale Detail CSV) prints `3`, not `3.0000`;
   - **N4** regression: U13 sale-return 6/6, DOC-INT 8/8, quote-loose-units 3/3, a pack sale + purchase unchanged.
5. Manual case in the Test Book (section 30 style): the return of 1 of 3 tablets, step by step.

## 5. Risk

- **Largest arithmetic change in the sale path since U13.** Mitigation: compile-driven, rules 1–4, whole-module tests,
  the U13/DOC-INT/quote gates as regression, red→green gate.
- **`ddl-auto: update` in business-service** cannot shrink these columns again only because the entities will carry
  precision; switching business to `validate` is its own slice (it must be verified to start).
- Rollback: the new columns are wider and exact; rolling the code back to `Float` still reads them. No down-migration.

## 6. As built (2026-10-14) — scope grew during the trace; each growth agreed or bounded

| Layer | Changed |
|---|---|
| commerce-contracts | `StockImportLine.quantity`, `StockReturnLine.qty`, `StockPurchaseAdjust.delta`, `SaleReturnLine.quantity`, `SaleRecordRequest.Line.quantity` → `BigDecimal` (user agreed to include contracts). JSON-compatible both ways. |
| inventory | V16 re-asserts DECIMAL(19,4) on 10 quantity columns; entities carry `precision = 19, scale = 4`; reads of the contract fields exact; `in(Float)` now via `Float.toString` (was `BigDecimal.valueOf` — not exact); false "base units / stores exact" comments corrected (stock is in SHELF units). |
| business | V83 (10 FLOAT → DECIMAL(19,4), `purchase_expense` → (19,2)); entities `Sell`, `Purchase`, `SaleReturn`, `SalesQuoteLine`, records `SagaLine`, `LooseLine`; DTOs `SellDTO`, `PurchaseDTO`, `BusinessDTOs`, `ReturnDocumentDTO`, `StockDTO`; `Quantities` helper; every arithmetic site (sale edit delta, U13 return incl. the money-bearing refund fraction, purchase return + bonus clawback — now `floor(retained × bonus ÷ paid)`, saga reservation, costing, quotes, serial counts). `PurchaseDTO.quantity` gains `@Positive` (the `@ValidateEmpty` Float branch used to reject ≤ 0; changing the shared validator instead would have rejected `CustomerDTO.paidAmount = 0`). |
| marketplace | 7 builder sites pass `BigDecimal.valueOf(int)` (its quantities are whole). |
| monolith | `SellDTO`, `PurchaseDTO` twins: quantities + money (`totalAmount`, `netAmount`, `srp`, `itemStock`) → `BigDecimal` (the money twins sit on the retired `/addSelling` path). |

**Root cause of gate N1, traced:** saga `issued = BigDecimal.valueOf(Float 0.075f)` → 0.07500000298 → inventory pick column DECIMAL(38,2) stored 0.08 → confirm re-read the pick → 0.08 off the shelf (3.2 tablets per 3 sold). Both halves are fixed here.

**NUM-1b (follow-up, recorded):** inventory's READ API in Float (`getStockLevel(s)`, `getStockLevelDetail`, `StockDTOs`, `out()`); `ScanResolution.quantity` (would add catalog-service); `inventory ddl-auto: validate` (blocked by Hibernate 6's float/REAL check on `Warehouse.capacity` — needs a boot test); dead FLOAT columns `sell.return_reason`, `customer.paid_amount`, `customer_history.received_amount`; 10 specs' local `setMarkup` → `cy.setBusinessConfig`.
