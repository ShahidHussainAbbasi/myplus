# Tax-filing register (output/input tax)

Closes the "tax-filing register" gap from `pos-retail-standards-audit.md` §2 (tax completeness). Cadence:
Document → **Design** → Implement → headed Cypress → next.

## 1. Problem
Sales apply + record tax (G3: `Sell.taxRate`/`taxAmount`, `CustomerHistory.taxTotal`, posted to the GL **TAX account
2100** as `Cr TAX` on a sale, `Dr TAX` on a return/void). But there is **no register** to answer "how much tax do I
owe the authority for period X?" — the core artifact for filing a sales-tax / VAT / GST return. (Purchases don't yet
capture input tax — see §4.)

## 2. Design — derive it from the GL TAX account (single source of truth)
Tax is a GL **liability** account (2100). Every taxable event already posts a **dated** line to it (sale → credit;
return/void → debit), so the TAX account's activity over a period **is** the filing figure — accrual-accurate and
self-reconciling (a return reduces tax in the period it happens, not retroactively). So the register is a **GL
report**, built in **finance-service** (which owns the GL), exactly like Trial Balance / P&L / Balance Sheet.

- **`GlService.taxRegister(from, to)`** — reads the TAX-account journal lines in `[from, to]` (scoped to the org),
  and returns:
  - `outputTax` = Σ credits to TAX (tax collected on sales),
  - `taxAdjusted` = Σ debits to TAX (returns / voids that gave tax back),
  - `netOutputTax` = outputTax − taxAdjusted,
  - `inputTax` = Σ debits to TAX from **purchases** (0 today — see §4),
  - `netPayable` = netOutputTax − inputTax,
  - `lines[]` = each TAX movement `{date, source (SALE/SALE_RETURN/…), ref (invoiceNo), debit, credit}` — the audit-
    grade register behind the totals.
- **`GET /api/finance/gl/tax-register?from=&to=`** (GlController) — org-scoped via `CurrentUser`, like the other GL reports.
- **Monolith**: `/taxRegister` proxy (GlController → FinanceRestClient) + a dashboard **Tax Register** button + a
  date-range dialog (mirrors the P&L / Balance Sheet dialogs).

```mermaid
flowchart LR
    S[Sale → Cr TAX] --> GL[(GL TAX account 2100)]
    R[Return / Void → Dr TAX] --> GL
    P[Purchase input tax → Dr TAX  (§4, not yet)] -.-> GL
    GL -->|taxRegister from,to| API[GET /gl/tax-register]
    API --> UI[Dashboard Tax Register: output − input = payable + line register]
```

## 3. Why the GL, not a sum of CustomerHistory.taxTotal
`CustomerHistory.taxTotal` is **mutated** by returns/voids (the header is re-settled), so summing it by invoice date
double-counts or retroactively changes a filed period. The GL posts each event as its **own dated line**, so the
period figure is correct and matches the books. Reuse > reinvent, and it's audit-defensible.

## 4. Input tax (purchases) — captured, and per-org configurable (Option 2)
Input tax is now real, and configurable per-org so small businesses aren't forced into VAT complexity (SaaS tiering —
see [[feedback_configurable_features]]). Configurability is expressed as **two independent, meaningful checkboxes** on
the **Tax Settings** screen (admin/owner) — each stands alone, no dependent/meaningless combos:

- ☑ **Sales tax (output)** = `TaxSetting.enabled` (existing) — charge/record tax on sales. Default OFF (a no-tax shop stays simple).
- ☑ **Purchase tax (input credit)** = `TaxSetting.inputTaxEnabled` (**new**, default OFF) — record tax paid on
  purchases (VAT/GST input credit).

Both independent: neither = no tax; output only = sales-tax regime; both = VAT/GST; input only = valid (reclaim-only).
The register adapts to whatever is on (output columns when Sales tax is on, input columns when Purchase tax is on);
no combination is broken. When **Purchase tax** is on:

When `inputTaxEnabled`:
- `Purchase.taxRate` / `taxAmount` (new columns) captured from the purchase form; the bill = net + tax.
- `postPurchase` posts `Dr Inventory(net) + Dr TAX(inputTax) / Cr Cash+AP(total)` — the `PURCHASE` event already
  carries `taxTotal` (contracts unchanged), finance just splits it.
- `PURCHASE_RETURN` reverses the input tax proportionally (`Cr TAX`).

The register **always reads the same TAX account**; the input columns are simply zero when the toggle is off.

## 5. The register maths (from the TAX account, grouped by journal source)
Every TAX-account line carries its `source` (SALE / SALE_RETURN / PURCHASE / PURCHASE_RETURN), so:
- `outputTax` = Σ Cr TAX (SALE), `outputAdjusted` = Σ Dr TAX (SALE_RETURN) → `netOutput = outputTax − outputAdjusted`
- `inputTax` = Σ Dr TAX (PURCHASE), `inputAdjusted` = Σ Cr TAX (PURCHASE_RETURN) → `netInput = inputTax − inputAdjusted`
- **`netPayable = netOutput − netInput`** (input tax is a credit against what you owe)

## 6. Decisions (locked)
| # | Decision | Choice |
|---|---|---|
| D1 | Source | **GL TAX account** (dated, accrual-accurate, self-reconciling) |
| D2 | Home | Register in **finance-service** (owns the GL); toggle + purchase tax in **business-service** |
| D3 | Scope | **Option 2** — output **and** input tax, **per-org configurable** via `TaxSetting.inputTaxEnabled` (default OFF) |

## 7. Phasing (each a checkpoint)
- **Phase A — register + output tax** — ✅ **DONE (green)**: finance `GlService.taxRegister(from,to)` + repo queries
  (`sumByAccountSourceInRange`, `ledgerForAccountInRange`) + `GET /gl/tax-register` (grouped by source) · monolith
  `/taxRegister` proxy + dashboard **Tax Register** button/dialog (`openTaxRegister`) · Cypress `tax-register.cy.js`
  (taxed sale → output tax; void → adjustment). No schema change. Build was finance-service + monolith.
- **Phase B — input tax + the two checkboxes** — ⬜ **TODO (resume here)**:
  1. `TaxSetting.inputTaxEnabled` column (business Flyway **V20**) + `TaxSettingDTO` field + `TaxService.saveSetting` persist.
  2. **Tax Settings UI**: two independent checkboxes — *Sales tax (output)* = `enabled`, *Purchase tax (input credit)* = `inputTaxEnabled`.
  3. `Purchase.taxRate` / `taxAmount` columns (business Flyway V20) + purchase-form tax field (shown only when *Purchase tax* is on).
  4. `PurchaseService.addPurchase`: when the org's `inputTaxEnabled`, compute purchase tax → put it on the `PURCHASE`
     event's `taxTotal` (contracts already carry `taxTotal`). `updatePurchase` / `purchaseReturn` carry it too.
  5. finance `PostingService.postPurchase`: split `Dr Inventory(total − tax) + Dr TAX(tax) / Cr Cash+AP(total)`;
     `postPurchaseReturn`: `Cr TAX` for the returned input tax proportionally.
  6. Register already reads the TAX account → input columns populate automatically; extend `tax-register.cy.js`
     (enable Purchase tax → taxed purchase → `inputTax` > 0 → `netPayable = netOutput − netInput`).

## 8. Test plan (headed Cypress — `tax-register.cy.js`)
1. ensureDefaults → a **taxed sale** → `GET /taxRegister` (today) shows `outputTax` > 0, `netPayable == netOutput`,
   and a `lines[]` credit entry for the invoice.
2. **Void** that sale → `outputAdjusted` up, `netOutput` back down (a debit line appears).
3. Balance check: `netOutput` equals the TAX-account balance on the Trial Balance for the range.
4. **Phase B:** enable `inputTaxEnabled` → a **taxed purchase** → register shows `inputTax` > 0 and `netPayable`
   drops by that input tax.

## 9. Build surface
Phase A: finance-service (`taxRegister` + repo query + `/gl/tax-register`) · monolith (`/taxRegister` proxy + Tax
Register dialog). Phase B: business-service (`TaxSetting.inputTaxEnabled` + Flyway; `Purchase.taxRate/taxAmount` +
Flyway; purchase-tax compute + form) · finance-service (`postPurchase`/`postPurchaseReturn` tax split) · monolith
(purchase-form tax field + Tax Settings toggle). Contracts unchanged (`PostingEventRequest.taxTotal` already exists).
