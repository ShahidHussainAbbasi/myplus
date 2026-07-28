-- Tenant-scope indexes. Every org-scoped read in this service filters on the standard NULL-fallback predicate
--     (organization_id = :orgId OR (organization_id IS NULL AND user_id = :userId))
-- and there was no index on organization_id at all, so each one was a full table scan.
--
-- `products` is the hottest table on the platform: SagaSellService fetches a ProductRef per sale line, the
-- pharmacy safety check batches over it, and the POS pickers list it. Composite (organization_id, user_id)
-- because both legs of that predicate are covered by one index.
--
-- Idempotent: guarded on information_schema.STATISTICS, so a re-run — or a database where someone already added
-- the index by hand — is a no-op.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND INDEX_NAME='idx_products_org_user')=0,
    'CREATE INDEX idx_products_org_user ON products (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='categories' AND INDEX_NAME='idx_categories_org_user')=0,
    'CREATE INDEX idx_categories_org_user ON categories (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
