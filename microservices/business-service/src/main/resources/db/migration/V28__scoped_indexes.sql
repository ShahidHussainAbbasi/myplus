-- Tenant-scope indexes. Every org-scoped read in this service filters on the standard NULL-fallback
-- predicate
--     (organization_id = :orgId OR (organization_id IS NULL AND user_id = :userId))
-- and there was no index on organization_id at all, so each scoped read was a full table scan. The
-- composite (organization_id, user_id) covers both legs of that predicate with one index.
--
-- Idempotent: each CREATE is guarded on information_schema.STATISTICS, so re-running — or a database
-- where the index already exists — is a no-op.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cash_movement' AND INDEX_NAME='idx_cash_movement_org_user')=0,
    'CREATE INDEX idx_cash_movement_org_user ON cash_movement (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='companies' AND INDEX_NAME='idx_companies_org_user')=0,
    'CREATE INDEX idx_companies_org_user ON companies (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer' AND INDEX_NAME='idx_customer_org_user')=0,
    'CREATE INDEX idx_customer_org_user ON customer (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='item_unit' AND INDEX_NAME='idx_item_unit_org_user')=0,
    'CREATE INDEX idx_item_unit_org_user ON item_unit (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment' AND INDEX_NAME='idx_payment_org_user')=0,
    'CREATE INDEX idx_payment_org_user ON payment (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sell' AND INDEX_NAME='idx_sell_org_user')=0,
    'CREATE INDEX idx_sell_org_user ON sell (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='vender' AND INDEX_NAME='idx_vender_org_user')=0,
    'CREATE INDEX idx_vender_org_user ON vender (organization_id, user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

