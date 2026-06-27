-- =============================================================================================
-- migrate-item-to-catalog.sql   (slice 33, U2 — manual/trace equivalent of POST /admin/migrate-catalog)
--
-- PURPOSE: bridge ONE freshly-registered business item into the catalog/inventory world so the saga
--          sell path can find it. Replicates CatalogMigrationService.toLine + catalog ProductImportService:
--            business.item  --(create)-->  catalog.products      (the new product master)
--            business.item  --(bridge)-->  business.item_catalog_map (item_id <-> product_id)
--
-- WHY A SCRIPT (not Flyway): this crosses 3 databases (myplusdb, myplusdb_catalog) on the SAME MySQL
--   instance, which a per-service Flyway migration cannot. So it is a DOCUMENTED MANUAL script, not an
--   auto-run V__*.sql. The Java endpoint (/api/business/admin/migrate-catalog) remains the canonical path;
--   this is a transparent, idempotent equivalent for the trace/dev.
--
-- FAITHFUL TO THE JAVA ETL:
--   sku   = item.icode, else 'ITEM-<itemId>' when blank        (ProductImportService baseSku)
--   name  = item.iname, else the sku                            (catalog requires a name)
--   selling_price = the item's latest Stock.bsell_rate (legacy per-batch sell rate — the only price the
--                   old model stored). This IMPROVES on the Java ETL, which sets selling_price NULL and so
--                   makes the saga sell at 0.00 (the Java CatalogMigrationService.toLine should be fixed the
--                   same way). Items with NO local Stock have no source here -> price them in the catalog.
--   idempotent: skips create if item_catalog_map already has the item; step 3 BACKFILLS the price on re-run.
--   NOTE: sku must be unique per (org); the Java path suffixes '-N' on collision — for a single fresh item
--         with a unique icode this is fine; if you hit a duplicate-sku error, change the item's icode.
--
-- USAGE: set the three params, then run the whole file.
-- =============================================================================================

SET @itemId = 0;     -- <-- put your TRACE-ITEM item_id here
SET @org    = 16;    -- demo.business org
SET @userId = 60;    -- demo.business user

-- 1) Create the catalog product (skip if this item is already mapped) -------------------------
INSERT INTO myplusdb_catalog.products
        (sku, name, description, selling_price, is_active, organization_id, user_id, created_at, updated_at)
SELECT  COALESCE(NULLIF(TRIM(i.icode), ''), CONCAT('ITEM-', i.item_id))                                 AS sku,
        COALESCE(NULLIF(TRIM(i.iname), ''), COALESCE(NULLIF(TRIM(i.icode), ''), CONCAT('ITEM-', i.item_id))) AS name,
        i.idesc,
        (SELECT s.bsell_rate FROM myplusdb.stock s            -- selling_price = latest non-zero batch sell rate
         WHERE s.item_id = i.item_id AND s.bsell_rate IS NOT NULL AND s.bsell_rate > 0
         ORDER BY s.stock_id DESC LIMIT 1),
        1, i.organization_id, i.user_id, NOW(6), NOW(6)
FROM    myplusdb.item i
WHERE   i.item_id = @itemId
  AND   NOT EXISTS (SELECT 1 FROM myplusdb.item_catalog_map m
                    WHERE m.item_id = i.item_id
                      AND (m.organization_id = @org OR m.organization_id IS NULL));

SET @productId = LAST_INSERT_ID();   -- the product just created (0 if the INSERT was skipped)

-- 2) Bridge row: item_id <-> product_id ------------------------------------------------------
INSERT INTO myplusdb.item_catalog_map (item_id, product_id, organization_id, stock_migrated, created_at)
SELECT @itemId, @productId, @org, 0, NOW(6)
WHERE  @productId IS NOT NULL AND @productId > 0
  AND  NOT EXISTS (SELECT 1 FROM myplusdb.item_catalog_map m
                   WHERE m.item_id = @itemId AND (m.organization_id = @org OR m.organization_id IS NULL));

-- 3) Backfill price (RE-RUN FIX) — sets selling_price from the item's latest Stock.bsell_rate for the
--    mapped product when it currently has none (NULL or 0). Idempotent, so re-running this whole file fixes
--    products that were created before the price logic. (Items with no local Stock have no source here.)
UPDATE myplusdb_catalog.products p
JOIN   myplusdb.item_catalog_map m ON m.product_id = p.id AND m.item_id = @itemId
SET    p.selling_price = (SELECT s.bsell_rate FROM myplusdb.stock s
                          WHERE s.item_id = @itemId AND s.bsell_rate IS NOT NULL AND s.bsell_rate > 0
                          ORDER BY s.stock_id DESC LIMIT 1),
       p.updated_at = NOW(6)
WHERE  (p.selling_price IS NULL OR p.selling_price = 0)
  AND  EXISTS (SELECT 1 FROM myplusdb.stock s2
               WHERE s2.item_id = @itemId AND s2.bsell_rate IS NOT NULL AND s2.bsell_rate > 0);

-- 4) Verify the bridge -----------------------------------------------------------------------
SELECT m.item_id, m.product_id, p.sku, p.name, p.selling_price
FROM   myplusdb.item_catalog_map m
JOIN   myplusdb_catalog.products p ON p.id = m.product_id
WHERE  m.item_id = @itemId;

-- ---------------------------------------------------------------------------------------------
-- migrate-stock equivalent: seed inventory from the item's LOCAL stock. For the trace this is a
-- no-op (a fresh item has no stock yet — the Phase-3 PURCHASE creates inventory via the D3 dual-write).
-- Included for completeness / items that already had local Stock:
-- INSERT INTO myplusdb_inventory.stock_entries
--         (batch_no, entry_date, quantity, reserved_quantity, purchase_price, product_id, organization_id, user_id)
-- SELECT  s.batch_no, NOW(6), s.current_stock, 0, s.bpurchase_rate, @productId, @org, @userId
-- FROM    myplusdb.stock s
-- WHERE   s.item_id = @itemId AND s.organization_id = @org AND s.current_stock > 0;
-- UPDATE myplusdb.item_catalog_map SET stock_migrated = 1 WHERE item_id = @itemId AND product_id = @productId;
