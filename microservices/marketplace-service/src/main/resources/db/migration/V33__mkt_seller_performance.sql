-- MKT-2e — seller performance (slice doc mkt-2e-merchant-performance.md). No new table: the scorecard is read on
-- demand from the seller orders placed in a window (7, 30 or 90 days), so it can never disagree with the orders.
-- The window reads mkt_seller_order by created_at, which had no index (V28 indexes the seller's queue and the sweep).
-- Idempotent: CREATE INDEX guarded by information_schema, as in V30.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'mkt_seller_order' AND INDEX_NAME = 'idx_mkt_so_created') = 0,
  'CREATE INDEX idx_mkt_so_created ON mkt_seller_order (created_at, seller_organization_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
