-- HMS H4 — the pharmacy branch (business-service store) a doctor's prescription is routed to (client ruling L-4:
-- "the owner / admin links doctors with a pharmacy, so their prescriptions land in that pharmacy").
-- NULL = not routed (counter prescriptions, doctors with no linked branch, every row before H4): visible to every
-- branch, exactly as before — the multi-location rule for rows without a store (P2b).
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescriptions' AND COLUMN_NAME='store_id')=0,
    'ALTER TABLE prescriptions ADD COLUMN store_id BIGINT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescriptions' AND INDEX_NAME='idx_rx_org_store')=0,
    'CREATE INDEX idx_rx_org_store ON prescriptions (organization_id, store_id, created_at)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
