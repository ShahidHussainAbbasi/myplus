# Multi-rate tax — tax-code master

Closes the **Multi-rate tax** item in `pos-retail-standards-audit.md` §5. Cadence: Document → **Design** → Implement
(UI→API→DB) → headed Cypress → next. Builds on the completed tax register (Phase A output + Phase B input).

## 1. Problem
A single org-wide rate (today: `TaxSetting.defaultRate` + an optional per-product `Product.taxRate`) breaks the moment
a merchant sells items at **different** rates on one invoice — which VAT/GST regimes (Pakistan, India GST 0/5/12/18/28,
EU/UK VAT) and especially **pharmacy** (medicines zero/exempt, cosmetics standard) require. Merchants also can't type a
raw % on every product and re-type it across thousands when a statutory rate changes. The standard model is a **named
tax-code (tax-class) master** — `Standard 18%`, `Reduced 5%`, `Zero 0%`, `Exempt` — with each product assigned a code,
the line inheriting the product's rate, and the receipt/register **broken down by rate**.

## 2. Key finding — the hot paths already resolve a per-line rate
The sale saga already computes tax **per line** from the catalog product's rate:
`SagaSellService.buildLines` → `product.getTaxRate()` (from `ProductRef`) → `taxService.taxForLine(base, rate, setting)`.
Purchases carry `Purchase.taxRate/taxAmount`; sell lines carry `Sell.taxRate/taxAmount`. So **per-line multi-rate is
already plumbed end-to-end** — what's missing is (a) a maintainable *master* behind the raw rate, (b) product→code
assignment UI, and (c) per-rate **reporting**. This makes the slice much smaller and lower-risk than it first appears.

## 3. Design decisions

### D1 — the tax-code master lives in **catalog-service** (next to products)
Rationale: product→tax-code is then a **local** relationship (no cross-service FK), and — critically — catalog keeps
resolving the rate into `ProductRef.taxRate` exactly as today, so **the sale/purchase pricing hot paths do not change
at all** (perf-safe, low-risk, honours "review/reuse existing"). `TaxSetting` (enabled / mode / default rate / label /
reg-no) stays in business-service as the org *policy*; a tax **code** only supplies a *rate*. The org default rate
remains the fallback when a product has no code.

- **catalog Flyway V2**: `tax_code` (`id`, `organization_id`, `name`, `rate` DECIMAL(19,2), `is_default` bit, `active`
  bit, `created_at`, `updated_at`; unique `(organization_id, name)`), + `product.tax_code_id BIGINT NULL`.
- **`Product.taxCodeId`** (local, nullable). Resolution when building `ProductRef`:
  `rate = taxCodeId != null ? taxCode.rate : product.taxRate` (legacy fallback) `: null` → org default downstream.
  Because the product references the **code** (not a copied number), editing a code's rate propagates to all its
  products at next read — the "change the standard rate once" property.
- Keep the existing `product.taxRate` column as the legacy/custom fallback (no destructive migration).

### D2 — `ProductRef` unchanged in shape
`ProductRef.taxRate` continues to carry the **resolved** rate. No new field is required for pricing (business/storefront
untouched). *(Optional, deferred: add `taxCodeId`/`taxCodeName` to `ProductRef` only if the receipt wants to print the
code name; the rate alone already drives the per-rate grouping.)*

### D3 — per-rate **breakdown** is sourced from the transactional lines, not the GL
The GL posts **aggregate** output/input tax to the single `TAX` account (2100), so it has no per-rate detail. The
standard "tax summary by rate" is therefore a **business-service** report over the sell + purchase lines (which carry
`tax_rate` + `tax_amount`), grouped by rate over `[from, to]`:
`GET /taxBreakdown?from&to` → rows `{ rate, outputTaxable, outputTax, inputTaxable, inputTax }` (+ totals). The existing
GL-sourced **net-payable** register (finance) stays as the summary; this adds the per-rate detail beneath it. The GL
stays single-TAX-account (documented: per-rate GL sub-accounts are a future extension only if a jurisdiction demands it).

### D4 — receipt shows a per-rate tax summary
The receipt already loads the invoice lines with `taxRate`/`taxAmount`; group them client-side into a
"Taxable @R% = X · Tax = Y" summary block (monolith JS only, no API change).

### D5 — no auto-seeding (owner-managed), with an optional ensure-defaults
Owners create their codes on the Tax Codes screen. (A future `ensure-defaults` could seed `Standard`(=org default),
`Zero`(0), `Exempt`(0) idempotently — deferred; keep the slice focused.) Exactly one code may be `is_default` per org;
assigning a new default clears the previous (mirrors a single-row-flag upsert).

## 4. Scope by layer
- **catalog-service:** `TaxCode` entity/repo/service (org-scoped CRUD, single-default invariant, anti-IDOR),
  `TaxCodeController` (`/api/catalog/tax-codes` GET/POST/PUT/DELETE), `Product.taxCodeId` + resolve into `ProductRef`,
  Flyway **V2**. Pure resolution unit test.
- **commerce-contracts:** (only if D2-optional taken) — otherwise unchanged.
- **business-service:** `TaxBreakdownService` (group sell + purchase lines by `tax_rate`, org-scoped, `[from,to]`) +
  `GET /taxBreakdown`. Pricing unchanged. Unit test for the grouping.
- **monolith:** Tax Codes management screen (owner-gated CRUD) + proxy; product form (`catalog-products.js`) — replace
  the raw `#prodTax` number with a **Tax code** dropdown (populated from catalog tax-codes) + a "Custom rate" escape
  hatch; Finance → Tax Register gains the per-rate breakdown table (`/taxBreakdown`); receipt per-rate summary block.
- **Cypress:** `multi-rate-tax.cy.js` — create two codes (18% / 5%), assign to two products, one sale with both →
  `/taxBreakdown` shows the two rate rows with correct taxable+tax; existing `tax-register.cy.js` still green.

## 5. Flow
```
Owner: Tax Codes screen → catalog tax_code {Standard 18, Reduced 5, Zero 0}
Product form: assign product → tax_code_id
Sale: buildLines → ProductRef.taxRate (resolved from the product's code) → taxForLine  [UNCHANGED]
Reporting: sell/purchase lines (tax_rate, tax_amount) → /taxBreakdown grouped by rate
           GL TAX account (aggregate) → net-payable register  [UNCHANGED]
Receipt: invoice lines grouped by rate → per-rate tax summary
```

## 6. Build / deploy
- catalog Flyway V2 (idempotent adds). Rebuild: **catalog-service** (+ commerce-contracts only if D2-optional) +
  **business-service** (breakdown report) + **monolith** (UI). No business/finance pricing or GL change.
- Backward compatible: products with no `tax_code_id` keep using `product.taxRate` / org default — single-rate orgs are
  unaffected.

## 7. Status: DESIGN — awaiting sign-off before implementation.
Open question for sign-off: **D1 (tax_code in catalog)** vs. tax_code in business-service (keeps all tax policy in one
service but adds a cross-service rate lookup on the sale path). Recommendation: **D1** — keeps the hot path unchanged.
