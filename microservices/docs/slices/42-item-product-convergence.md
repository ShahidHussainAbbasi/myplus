# Slice 42 — Item → Product convergence (one product master)

**Decision (2026-06-23):** retire the dual product model — converge the POS off business-service **`Item`** onto
catalog-service **`Product`** (the documented decomposition master) so every vertical (POS, pharmacy, e-commerce)
shares ONE product master. Pharmacy is **paused** (slice 41 P5) and resumes on `Product` after this lands.
Principle: **reuse the whole codebase, do not reinvent** — most pieces already exist; this wires the UI to the master.

## Current state — marked against the codebase

| Concern | business-service (`Item`) | catalog-service (`Product`) | Bridge today |
|---|---|---|---|
| Entity | `Item` (id, iname, icode, idesc, unit, category:String, **company:Company FK**, org/user) | `Product` (id, sku, name, description, **category:Category FK**, unit, **manufacturer**, **sellingPrice**, **taxRate**, isActive, imageUrl, org/user) | `ItemCatalogMap` (itemId↔productId) |
| Registration UI | ✅ **Item Registration** screen (the live one) → `addItem` | ❌ no product-register screen | `CatalogMigrationService` + `importProducts` copy Item→Product |
| Picker (Sell/Purchase) | ✅ `getUserItems` (itemId) | partial — `CatalogClient.getProduct` | `SagaSellService` translates itemId→productId |
| Pricing/tax | on `Stock`/sale (G3 on Product.taxRate) | ✅ `sellingPrice`,`taxRate` on Product | saga prices from catalog (D1) |
| Stock | **local `Stock`** (keyed by Item) + Purchase | ✅ inventory-service `StockEntry`/`StockLevel` (productId) | saga reserves inventory; local Stock still drives the Stock screen |
| Sell | legacy path = itemId+local Stock; **saga path = productId** ✅ | — | saga (trade.saga.enabled) |

**Gaps to close:** (1) `Product` has no Company/Vendor link (Item does) — decide drop vs add. (2) Registration/picker
UI still speaks `itemId`. (3) Purchase + the **Stock screen** still use local `Stock`, not inventory.

## Phased sequence (each = its own build + headed Cypress gate; reuse existing code)

- **M1 — Product master parity + catalog CRUD UI.** Add any Item attribute Product lacks that the POS needs
  (manufacturer/category already there; decide Company/Vendor → keep as catalog attributes or drop). Surface the
  **existing catalog ProductController** (create/list/update/search already exist) through a monolith proxy +
  reuse the relabel-ready Registration screen to register **Products** (productId) instead of Items.
- **M2 — Product picker everywhere.** Sell + Purchase pickers load **Products** (catalog) instead of `getUserItems`;
  submit `productId`. The saga is already productId-native — legacy itemId path becomes dead.
- **M3 — Stock on inventory only.** Point the Stock screen + Purchase stock-in at **inventory-service**
  (`StockEntry`/`StockLevel` by productId); retire local `Stock`. (Largest step — overlaps decomposition Phase 4/5.)
- **M4 — Retire `Item`.** Remove `Item`/`ItemType`/`ItemUnit` CRUD + `ItemCatalogMap` once nothing references
  `itemId`; data migration via existing `importProducts`/`StockMigrationService`. Drop dead columns.
- **M5 — Resume pharmacy on Product.** Re-point slice-41 Prescription/Dispensing/DrugInteraction to `productId`;
  pharmacy reuses the now-Product POS screens (register/sell/dispense) + adds clinical layer only.

## Risk / scope note
This effectively **completes the decomposition's Item→Product migration** (a known multi-phase effort). M3 (stock)
is the heavy one and touches the Purchase + Stock screens and the local-Stock model. Recommend M1→M2 first (visible
product-master value, low risk), then M3 deliberately. Each phase keeps the app working (saga already bridges).

## Status
- [x] Decision + audit + phased plan (this doc)
- [x] **M1 (additive, monolith-only)** — reused existing `CatalogRestClient` + `/catalogProducts` (list) + catalog
  `ProductController`; added `CatalogRestClient.postJson/putJson`, `/addProduct` + `/getCatalogProduct` proxy, a
  **Product (Catalog Master)** registration screen (`ProductDiv` + Register-menu item + `catalog-products.js`), and
  `catalog-product.cy.js`. Strangler: the Item screen still works untouched; this adds the Product master alongside.
  **Cypress GREEN (headed, 2026-06-23): catalog-product.cy.js 2/2** — register product + list + screen renders.
  catalog-service unchanged (reused). Company/Vendor parity deferred to M2.
- [x] **Convergence achieved via MASTER-SYNC (slice 53)** instead of M2: catalog Product is the master, auto-projected
  to a bridged Item, so all verticals read one master and the itemId screens stay untouched. M2 (picker rewire) is moot.
- [x] **M3.1 (slice 62) DONE** — Stock list reads inventory on-hand (batched).
- [x] **M3.2 (slice 63) DONE** — purchases auto-map + always land in inventory; inventory is the de-facto source.
- [ ] ~~M3.3 stop local Stock writes / M3.4 delete Stock~~ **PARKED (decided 2026-06-27).** Investigation showed M3.3
  is a 3–4 sub-slice refactor of the core POS — batch dropdowns (`getBatchesByItem`/`getStockByBatch`), some sell
  rates, and the legacy sell/return paths still READ local `Stock`, so writes can't stop until those reads move to
  inventory/catalog first. Zero user value; high regression risk. The convergence GOAL (one master + inventory-
  authoritative stock-in) is met by M3.1+M3.2, so finishing is backlog. Higher-value P0s (Flyway/ddl-auto:validate,
  real PSP, storefront-auth hardening) take priority.
- [ ] **M3 stock → inventory-only (BACKLOG, decided 2026-06-26).** Worth doing eventually — purchases already
  dual-write inventory (`PurchaseService.pushPurchaseToInventory`) and `getStock` reads inventory, so local `Stock`
  is parallel dead-weight + drift risk. A deliberate, test-heavy slice: Stock screen + Purchase read/write inventory
  only, drop the legacy local-Stock return path, delete `Stock`/`updateStock`. Medium risk (core Purchase/Stock screens).
- [ ] ~~M4 retire Item / ItemCatalogMap~~ **PARKED / obviated (decided 2026-06-26)** — master-sync already gives one
  master; Item is now a thin projection. Full itemId→productId rewire = high risk, low marginal value. Likely never.
- [ ] M5 resume pharmacy on Product — N/A, pharmacy runs on the itemId bridge + master-sync.
