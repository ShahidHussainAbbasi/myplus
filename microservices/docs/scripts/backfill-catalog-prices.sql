-- =============================================================================================
-- backfill-catalog-prices.sql   (slice 33 — production price fix for migrated catalog products)
--
-- PURPOSE: catalog products created by the item→catalog migration were given NO selling_price, so the
--          saga sells them at 0.00. This sets each mapped product's selling_price from the item's latest
--          non-zero Stock.bsell_rate (the legacy per-batch sell rate — the only price the old model stored).
--
-- SAFE FOR PRODUCTION / RE-RUNNABLE:
--   * Idempotent — only touches products whose selling_price is NULL or 0; already-priced products are left.
--   * Read-only on business data (myplusdb.item/stock); only UPDATEs myplusdb_catalog.products.
--   * No org param needed — fixes every tenant. (Add `AND m.organization_id = <org>` to scope to one.)
--
-- LIMITATION: an item with no local Stock (e.g. brand-new items created after the saga cutover) has no
--   price source here and is left for catalog-side pricing (the catalog owns the price going forward).
--
-- RECOMMENDED ORDER on a fresh environment:  run the item→catalog migration first, then this.
-- =============================================================================================

-- 0) Preview what will change (run first to sanity-check)
SELECT p.id AS product_id, p.name, p.selling_price AS current_price,
       (SELECT s.bsell_rate FROM myplusdb.stock s
        WHERE s.item_id = m.item_id AND s.bsell_rate IS NOT NULL AND s.bsell_rate > 0
        ORDER BY s.stock_id DESC LIMIT 1) AS will_set_to
FROM   myplusdb_catalog.products p
JOIN   myplusdb.item_catalog_map m ON m.product_id = p.id
WHERE  (p.selling_price IS NULL OR p.selling_price = 0)
  AND  EXISTS (SELECT 1 FROM myplusdb.stock s2
               WHERE s2.item_id = m.item_id AND s2.bsell_rate IS NOT NULL AND s2.bsell_rate > 0);

-- 1) Backfill
UPDATE myplusdb_catalog.products p
JOIN   myplusdb.item_catalog_map m ON m.product_id = p.id
SET    p.selling_price = (SELECT s.bsell_rate FROM myplusdb.stock s
                          WHERE s.item_id = m.item_id AND s.bsell_rate IS NOT NULL AND s.bsell_rate > 0
                          ORDER BY s.stock_id DESC LIMIT 1),
       p.updated_at = NOW(6)
WHERE  (p.selling_price IS NULL OR p.selling_price = 0)
  AND  EXISTS (SELECT 1 FROM myplusdb.stock s2
               WHERE s2.item_id = m.item_id AND s2.bsell_rate IS NOT NULL AND s2.bsell_rate > 0);

-- 2) Verify
SELECT
  (SELECT COUNT(*) FROM myplusdb_catalog.products WHERE selling_price > 0)                       AS priced_products,
  (SELECT COUNT(*) FROM myplusdb_catalog.products WHERE selling_price IS NULL OR selling_price = 0) AS still_zero_no_stock_source;
