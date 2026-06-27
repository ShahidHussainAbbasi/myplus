# Slice 60 — Storefront product search

The storefront grid lists every product; there's no way to find one in a large catalog. This adds a **name search**
(case-insensitive contains) to the public browse — the achievable half of the blueprint's E1 "needs media + search".
No auth/upload/email infra.

## Design (sequence)

```mermaid
sequenceDiagram
  participant U as Shopper
  participant S as store.html
  participant M as monolith /storefront/products
  participant G as API gateway
  participant C as catalog-service
  U->>S: type query (debounced 300ms)
  S->>M: GET /storefront/products?org&q
  M->>G: GET /api/catalog/public/products?org&q
  G->>C: PublicProductController (anonymous, org-scoped)
  C->>C: findBy…NameContainingIgnoreCase(org, q)
  C-->>M: matching products (minimal projection)
  M->>M: merge availability (slice 49)
  M-->>S: data
  S-->>U: re-render grid (matches only)
```

## Changes
- **catalog-service** — `ProductRepository.findByOrganizationIdAndIsActiveTrueAndNameContainingIgnoreCaseOrderByNameAsc`;
  `PublicProductController GET /products` accepts an optional `q` (blank → full list, as before).
- **monolith** — `StorefrontController.products` forwards `q` to the catalog public endpoint (availability merge unchanged).
- **store.html** — a search box above the grid; typing re-fetches `/storefront/products?org=&q=` and re-renders
  (debounced).

## Tests
- Cypress `storefront-search.cy.js` (headed): seed two products → `q=<one name>` returns only that one; empty `q`
  returns both; the store page's search box filters the grid.

## Status
- [x] Design (this doc)
- [x] catalog repo search method + `PublicProductController` q; monolith `StorefrontController` q passthrough;
      store.html search box (debounced `loadStoreProducts`)
- [x] Cypress `storefront-search.cy.js` authored
- [x] **Cypress green (headed, 2026-06-26): storefront-search 2/2 + storefront 4/4 regression.**
- Note: catalog + monolith only; no contract/inventory/business/migration change.

## Deferred
- Category/price filters, sort options, pagination; product media/SEO (E2). This slice is name search only.
