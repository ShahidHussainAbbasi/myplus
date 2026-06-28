# Slice 90 — M4a: migrate spec seeding to the Product master (step 1 of retiring the Item form)

Per the M4 decision (full rewrite), and the chosen approach (**migrate specs to Product seeding first, then** neuter
`/addItem` + remove the Item form). This slice lands the shared seeding primitive and proves it on one spec before the
mass migration, so the per-slice Cypress gate stays green.

## Shared command — `cy.seedProduct` (cypress/support/commands.js)
Replaces the legacy `/addItem` + `/addStock` inline seeding with the **single creation path**:
- `POST /addProduct` (catalog master) → auto-syncs to a bridged `Item` via `ProductSyncService`.
- resolves the synced `itemId` from `/getUserItem` (pickers are still itemId-based until M4b).
- optional opening inventory via `POST /addProductStock` (local Stock is gone).
- yields `{ productId, itemId, name, sku }`.

```js
cy.seedProduct({ name, sku, sellingPrice, taxRate, unit, category, stock, purchaseRate, batchNo })
  .then(({ productId, itemId }) => { ... })
```

## Proof spec — `sell.cy.js` (Invoice Numbering block)
Migrated off `/addItem`+`/addStock`+`stockId` to `cy.seedProduct`, selling via the **synced `itemId`**
(`sales:[{ itemId, ... }]`). The saga translates itemId→productId via `ItemCatalogMap`.

> First attempt sold productId-native (`sales:[{ productId }]`) → saga threw `Item 0 is not migrated`. Cause: the
> monolith `/addSell` proxy deserializes into its own `SellDTO` (itemId-based; no `productId` field), so the productId
> was stripped at the proxy. **productId-native submission is M4b** (add `productId` to the monolith SellDTO + put it on
> the pickers). M4a stays on itemId. (These invoice tests were also silently *skipping* pre-migration — `stockId` was
> never set post-M3c — so activating them via the Product seed is correct.)

## Wave plan (each wave = its own green Cypress gate)
1. **(this slice)** `cy.seedProduct` + `sell.cy.js`.
2. core: `purchase.cy.js`, `flow.cy.js`, `stock.cy.js`, `negative.cy.js`.
3. saga/catalog: `saga-sell*.cy.js`, `purchase-*.cy.js`, `product-master-sync.cy.js`, `registration-product-path.cy.js`,
   `itemtype.cy.js`, `itemunit.cy.js`.
4. pharmacy: `dispense*.cy.js`, `prescription.cy.js`, `alerts.cy.js`, `quarantine-*.cy.js`, `insurance-copay.cy.js`,
   `dashboard.cy.js`.
5. pages/auth: `pages/businessDashboard.cy.js`, `pages/navigation.cy.js`, `auth/session.cy.js`.
6. **then** neuter `/addItem` + `/addStock` (business `ItemController`/`StockController`) and remove the Item form from
   `businessDashboard.html` (steer to the Product form) — the actual "retire the Item form" step.

## Status
- [x] `cy.seedProduct` command (collection-field + assert-item-exists fixes applied)
- [x] `sell.cy.js` migrated — **GREEN** (proof)
- [x] wave 2 (core): `purchase.cy.js` (was silently skipping via `.data` — now activated) + `flow.cy.js`
  (all `/addItem` seeds → `seedProduct`; stockId sells → itemId; company/vender + soft stock-screen checks left as-is)
- [x] wave 2 — **GREEN**
- [x] wave 3 (saga/catalog): **no changes needed** — `purchase-batch-prefill`/`purchase-self-describing`/
  `product-master-sync`/`registration-product-path` already seed via `/addProduct`; `saga-sell*` don't seed;
  `itemtype`/`itemunit` test separate entities (ItemType/ItemUnit, not Item).
- [x] wave 4 (pharmacy): `alerts.cy.js`, `dispense.cy.js`, `prescription.cy.js` migrated to `cy.seedProduct`
  (alerts/dispense seed opening `stock` for the dispense). Other pharmacy specs already Product-native.
- [x] wave 5 (pages/auth): none seed via `/addItem`.
- [ ] **Awaiting (user-run) pharmacy `alerts.cy.js` + `dispense.cy.js` + `prescription.cy.js`**

## Seed-migration COMPLETE after that run. Remaining for M4a = the removal step:
- Neuter `/addItem` + `/addStock` (business `ItemController`/`StockController`) and remove the Item form from
  `businessDashboard.html` (steer to the Product form).
- Rewrite/retire the specs that TEST the legacy endpoints (not seedable): `stock.cy.js`, the `addItem`/`addStock`
  cases in `negative.cy.js`, and `purchase-inventory.cy.js` (its "legacy *unmapped* item auto-maps on purchase"
  scenario is obsolete once `/addItem` is gone — no unmapped items can exist).
