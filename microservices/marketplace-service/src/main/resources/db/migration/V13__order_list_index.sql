-- OMS O4 — the index the back-office list actually needs.
--
-- The orders table carried only idx_order_org (organization_id), while every listing query is
-- "scope by org, then ORDER BY created_at DESC". That is an index scan on the org followed by a filesort of
-- every one of that tenant's orders — so paginating the read without this migration would only move the cost
-- from the response body into the database.
--
-- Composite (organization_id, created_at) serves the scope and the sort in one structure, and a LIMIT then
-- stops early instead of sorting the whole tenant.
--
-- fulfilment_status is deliberately NOT in the key: it has ~7 distinct values, so it is poorly selective, and
-- adding it would widen every entry to help a filter the sort cannot use anyway.
--
-- Idempotent (the CREATE INDEX ... IF NOT EXISTS form does not exist in MySQL 8) — same guarded pattern as V10/V11.

SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND INDEX_NAME='idx_orders_org_created')=0,
    'CREATE INDEX idx_orders_org_created ON orders (organization_id, created_at)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- The NULL-fallback half of the scope predicate (organization_id IS NULL AND user_id = ?) is a legacy path for
-- rows written before org stamping. It is served by this index too for the org branch; the user branch is rare
-- and small, so it gets no index of its own rather than one that would be maintained on every write for a
-- shrinking set of rows.
