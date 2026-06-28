-- M3c (slice 81): backfill product_id onto historical Stock-linked sells + purchases from the item→product map, so a
-- fresh/prod deploy renders them productId-only (M3c.2/M3c.4a) WITHOUT relying on the manual admin endpoint. Idempotent
-- (only NULL product_id rows) and safe to re-run / no-op on an empty DB.
--
-- Covers items already in item_catalog_map (the common case — anything ever sold/purchased through the saga is mapped).
-- Items NOT yet mapped need a catalog import first (runtime): POST /api/business/admin/migrate-catalog then
-- /backfill-product-ids — that admin path remains the completeness tool; this script covers the deploy-time bulk.

UPDATE sell s
  JOIN stock st ON s.stock_id = st.stock_id
  JOIN item_catalog_map m ON m.item_id = st.item_id
  SET s.product_id = m.product_id
  WHERE s.product_id IS NULL AND s.stock_id IS NOT NULL;

UPDATE purchase p
  JOIN stock st ON p.stock_id = st.stock_id
  JOIN item_catalog_map m ON m.item_id = st.item_id
  SET p.product_id = m.product_id
  WHERE p.product_id IS NULL AND p.stock_id IS NOT NULL;
