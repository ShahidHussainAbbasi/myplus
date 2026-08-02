# Barcode-first sell UX

**Status:** ✅ DONE - `Product.barcode` (catalog V3) + scoped `/products/lookup`, and the scan box adds a cart line via `scanAddToCart`; stock is re-validated server-side at submit.

Closes POS audit R2/R5 (barcode-scan sell is commented out). Cadence: Document → Design → Implement → Cypress → next.

## 1. Problem
At a POS the fastest checkout is **scan → line appears → scan next**. Today a sale is built by picking a product from
`#sellItemDD` (a selectpicker) and pressing *Add to Cart*; there is no scan path, and catalog products have no
**barcode** (only an internal `sku`). A keyboard-wedge scanner types the code + Enter, so the flow must resolve a
scanned code to a product and add it to the cart with no mouse.

## 2. Design decisions

### D1 — a real `barcode` field on the product (distinct from SKU)
A product's scannable code (manufacturer EAN/UPC) is not the same as its internal `sku`. Add `Product.barcode`
(nullable, indexed) — catalog Flyway **V3**. Lookup resolves a scanned code by **barcode OR sku** (exact, tenant-
scoped), so shops that set sku = barcode also work with zero extra data entry. (Multiple barcodes per product / pack
sizes are a future extension.)

### D2 — exact-match lookup endpoint
`GET /api/catalog/products/lookup?code=<scanned>` → the product's `ProductRef` (id, name, sku, sellingPrice, taxRate)
or 404. Exact match (barcode first, then sku), active products only, tenant-scoped. Distinct from `/search` (fuzzy,
paged) — a scan needs one deterministic hit. Monolith proxy `GET /lookupProduct?code=`.

### D3 — scan = **direct cart-append** at catalog price (not driving the form)
The manual pick fires an async `/productStock` fill (rate + FEFO + sellable guard) that is fragile to drive
programmatically. A scan instead appends a cart line directly from the looked-up `ProductRef`: qty 1 at the catalog
`sellingPrice`, **incrementing** the qty if the product is already in the cart. This is the fast-POS norm; the cashier
can still click a line to adjust qty/discount (existing edit path), and the **server enforces stock** at `addSell`
(FEFO reserve) so an out-of-stock scan is rejected at submit. Keeps the change off the fragile selectpicker/async path
and is deterministic. The append reuses the same `data[]` cart + `tablesi` DataTable + `calculateChange()` the manual
Add uses (a focused `scanAddToCart(ref)` helper, not a refactor of the edit-aware `#addInviceItem`).

### D4 — the scan box
A single text input above the sell item picker, autofocused on the Sell screen, `Enter` submits (wedge-scanner
friendly), clears + refocuses after each scan. Not found → a brief inline flash, field stays focused. A tiny "scanned:
<name> ×<qty>" confirmation so the cashier sees the hit without looking at the grid.

## 3. Scope by layer
- **catalog-service:** `Product.barcode` + `ProductDTO.barcode` (to/fromDto) + `ProductRepository.findByCodeScoped`
  (barcode or sku, active, scoped) + `ProductService.lookup(code)` → `ProductRef` + `ProductController GET
  /products/lookup`. Flyway **V3** (`products.barcode` + index). Pure resolution unit test.
- **monolith:** `CatalogRestClient` reuse (`getString`); `CatalogController GET /lookupProduct?code=`; sell screen scan
  box (HTML) + `scanAddToCart` / scan-input handler (business.js); product form Barcode field + save/edit wiring
  (catalog-products.js + businessDashboard.html).
- **Cypress:** `barcode-scan.cy.js` — seed a product with a barcode → `/lookupProduct?code=<barcode>` resolves it (and
  by sku) → a sale built from the looked-up productId succeeds; unknown code → not found.

## 4. Flow
```
Scan (wedge types code + Enter) → /lookupProduct?code → catalog lookup (barcode|sku, active, scoped) → ProductRef
   → scanAddToCart: cart has productId? increment qty : append line @ sellingPrice, qty 1 → calculateChange() → refocus
Submit → addSell (unchanged) → SagaSellService reserves stock (rejects if short)
```

## 5. Build / deploy
catalog Flyway V3 (idempotent add). Rebuild: **catalog-service** + **monolith**. No business/finance/contracts change
(`ProductRef` unchanged). Backward compatible — products with no barcode are simply found by sku.

## 6. Status: IMPLEMENTED
- **catalog-service:** `Product.barcode` + `ProductDTO.barcode` (to/fromDto) + `ProductRepository.findByCodeScoped`
  (barcode|sku, active, scoped, barcode-preferred) + `ProductService.lookup(code)` → `ProductRef` +
  `ProductController GET /products/lookup?code=`. Flyway **V3** (`products.barcode` + `idx_products_barcode`).
- **monolith:** `CatalogController GET /lookupProduct?code=` (miss → `{}`); sell screen scan box `#sellScan` (Enter →
  `sellScanAdd`) + `scanAddToCart` (append at catalog price / qty-increment, reuses `data[]`+`tablesi`+
  `calculateChange`); `snavGo('sellDiv')` autofocuses the scan box; product form Barcode field + save/edit wiring.
- **Cypress:** `barcode-scan.cy.js` (lookup by barcode + by sku + unknown miss + the sale posts); `seedProduct`
  `barcode` passthrough.
- Backward compatible (`ProductRef` shape unchanged; products with no barcode found by sku). Build: catalog + monolith.
