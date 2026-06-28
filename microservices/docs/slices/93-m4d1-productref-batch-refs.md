# Slice 93 — M4d.1: enrich ProductRef + batch `getProducts` (read-rewire foundation)

The foundation for M4d (resolve POS read-screen line fields from catalog instead of the local `Item`). Compile-only —
no behavior change yet; M4d.2/.3 consume it.

## Changes
- **`commerce-contracts` `ProductRef`** — added `description`, `category` (name), `manufacturer`; added `@Builder`; kept a
  **back-compat 6-arg constructor** (`id,sku,name,unit,sellingPrice,taxRate`) so existing price-focused callers + tests
  (`SagaSellServiceTest`, `CartServiceTest`, `ProductService.getRef`) compile unchanged.
- **`commerce-contracts` `CatalogClient`** — added `List<ProductRef> getProducts(List<Long> ids)` → `GET /products/refs?ids=…`.
- **catalog-service `ProductService`** — `getRef` now builds the full ref (via new `toRef`); added `getRefs(ids)` using the
  existing `ProductRepository.findAllByIdScoped` (tenant-scoped; missing/foreign ids omitted).
- **catalog-service `ProductController`** — added `GET /products/refs?ids=1,2,3` → `getRefs`. (Literal path wins over
  `/{id}` patterns, so no route conflict.)

## Build + verify (user)
- Rebuild **commerce-contracts**, then **catalog-service** + **business-service** (both depend on the contract).
- Confirm no behavior regressed: `saga-sell.cy.js` (uses `getProduct`/ProductRef pricing — back-compat ctor intact) and
  `catalog-product.cy.js` stay green. No new UI behavior to test in this sub-slice.

## Status
- [x] ProductRef enriched (+@Builder, back-compat ctor) · CatalogClient.getProducts · catalog getRefs + /refs endpoint
- [ ] **Awaiting contracts+catalog+business rebuild** (compile check + saga-sell/catalog-product green)

## Next (M4d.2/.3 — the rewire)
- Add a small short-TTL product-ref cache + a batch resolver in business-service.
- Rewire the `itemByProductId` / `Item item =` name-resolution in **SellController** (getUserSell, getSellInvoice,
  getReceipt, date-range report), **PurchaseController** (getUserPurchase), **BusinessDashboardController** (top-items)
  to use `CatalogClient.getProducts` — so reads no longer touch the local `Item`, clearing the way for M4e.
