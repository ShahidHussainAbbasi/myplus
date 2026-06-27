# Slice 74 — One creation path: steer to the Product master (Item form → edit-only)

Closes the Item-vs-Product divergence (the user's question): today the legacy **Item** form creates business Items that
**never reach the catalog master** (no Item→Product sync), and it can't capture a sell price anyway. Decision: make the
**Product (Catalog Master) form the single creation path**; demote the Item form to **edit-only** for legacy items;
**repoint pharmacy "Medicine" registration to the Product form** (relabeled). UI-only — no backend/DB change. Product→Item
master-sync (slice 53) already projects every new Product into a bridged Item, so the `itemId` POS/purchase/sell screens
keep working.

## Why this (vs adding Item→Product sync)
- The Item form has **no price field** (price comes later from purchase batches) → syncing it would create ₹0 master
  products. The Product form captures price → a complete master entry that flows everywhere (POS + pharmacy + store).
- Pharmacy medicine-specific fields (rx/controlled) live on separate clinical screens, not the item form, so moving
  registration to the Product form loses nothing.

## Changes (monolith only)
- **businessDashboard.html** Register menu: make **Product** the primary entry (`showProducts()` → ProductDiv); demote
  the Item entry to **"Legacy Items (edit)"** (still → `itemDiv` for editing legacy items). The off-screen
  `#registrationType` `itemDiv` option stays (drives the legacy edit link).
- **module-theme.js**: relabel the primary "Product" link + Product-form heading per vertical — PHARMA → "Medicine",
  MARKETPLACE keeps "Product". (Existing `Item`→`Medicine/Product` labels stay for the sell/purchase field wording.)
- Product-form heading gets `data-term` so it relabels with the menu.

## Behavior after
- POS: "Product" opens the master form (price captured) → bridged Item auto-created (slice 53) → appears in the
  purchase/sell item pickers. "Legacy Items (edit)" still edits pre-existing items.
- Pharmacy: "Medicine" opens the master form (relabeled). Legacy medicines editable via "Legacy Items (edit)".
- E-commerce: "Product" opens the master form.

## Boundary / follow-ups
- Item edits via the legacy form still don't propagate to the master (edit-only legacy path) — acceptable; the master
  is the creation path. A later slice can fully retire the Item form (M4) once legacy items are migrated.
- This does not touch purchase/sell (they read the bridged item + inventory + master, already converged).

## Tests
- **Cypress `registration-product-path.cy.js`** (user-run): Register menu → "Product" opens `#ProductDiv` with the
  Product form; "Legacy Items (edit)" opens `#itemDiv`; saving a product via the master form lists it (and it's a
  catalog product). (Pharmacy relabel check optional.)

## Status
- [x] Design (this doc)
- [x] businessDashboard.html menu (Product primary, "Legacy Items (edit)" demoted) + Product heading `data-term`
- [x] module-theme.js: PHARMA relabels "Product"→"Medicine" (link + heading); MARKETPLACE keeps "Product"
- [x] registration-product-path.cy.js authored
- [ ] **Awaiting monolith rebuild/restart + (user-run) Cypress** `registration-product-path.cy.js`
