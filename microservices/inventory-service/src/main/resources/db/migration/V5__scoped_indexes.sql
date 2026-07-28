-- Tenant-scope indexes. Every org-scoped read in this service filters on the standard NULL-fallback
-- predicate
--     (organization_id = :orgId OR (organization_id IS NULL AND user_id = :userId))
-- and there was no index on organization_id at all, so each scoped read was a full table scan. The
-- composite (organization_id, user_id) covers both legs of that predicate with one index.
--
-- Idempotent: each CREATE is guarded on information_schema.STATISTICS, so re-running — or a database
-- where the index already exists — is a no-op.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_entries' AND INDEX_NAME='idx_stock_entries_org_user')=0,
    'CREATE INDEX idx_stock_entries_org_user ON stock_entries (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='suppliers' AND INDEX_NAME='idx_suppliers_org_user')=0,
    'CREATE INDEX idx_suppliers_org_user ON suppliers (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='warehouses' AND INDEX_NAME='idx_warehouses_org_user')=0,
    'CREATE INDEX idx_warehouses_org_user ON warehouses (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

