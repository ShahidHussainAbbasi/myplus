-- M4e.d (slice 106) — DESTRUCTIVE: retire the local `item` entity + its `item_catalog_map` bridge. The
-- Item↔Product convergence is complete — the catalog Product is the single product master, sells/purchases carry
-- product_id (V5) + self-describing snapshots (V6), pharmacy is productId-native, and NO code reads or writes Item
-- or the map anymore (ItemController/ItemService/ItemRepo/ItemCatalogMap(Repo)/ProductSyncService/CatalogMigration*
-- were deleted in the d3 code drop). This drops the orphan item_id columns, the item_catalog_map table, and item.
--
-- IRREVERSIBLE. Order-safe on a fresh/prod deploy: Flyway applies V1..V8 in sequence, so the product_id backfills
-- (V5/V6) and the mapping-driven migrations complete BEFORE this drop. `item`/`purchase`/`sell` are MyISAM (V1
-- baseline) and item_id was a plain column (no @JoinColumn) — so there are NO foreign-key constraints to drop first.
-- Every step is idempotent: column drops are information_schema-guarded; table drops use IF EXISTS.

-- 1) Drop the orphan purchase.item_id column. NOTE: no Flyway ever added this column — it was created by an earlier
--    ddl-auto=update from the (now-deleted) Purchase.itemId field. So it exists on dev but NOT on a fresh Flyway-only
--    deploy; the guard makes this a no-op there.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='item_id')>0,
  'ALTER TABLE purchase DROP COLUMN item_id', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) Drop sell.item_id if a legacy DB ever had it (the baseline sell table does not — guarded no-op there).
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sell' AND COLUMN_NAME='item_id')>0,
  'ALTER TABLE sell DROP COLUMN item_id', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 3) Drop the itemId↔productId bridge table (InnoDB, no inbound FKs — item is MyISAM so nothing could reference it).
DROP TABLE IF EXISTS item_catalog_map;

-- 4) Drop the local item master itself.
DROP TABLE IF EXISTS item;
