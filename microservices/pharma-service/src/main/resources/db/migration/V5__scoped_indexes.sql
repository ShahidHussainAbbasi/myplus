-- Review finding C3: every scoped read in this service was a full table scan — there was no index on
-- organization_id anywhere. These mirror the actual query shapes:
--
--   prescriptions      findScoped / findByIdScoped  -> (organization_id, created_at DESC) + the user_id
--                      NULL-fallback leg of SCOPE
--   dispensing         findControlledScoped         -> (organization_id, controlled, dispensed_at)
--                      countForInvoiceScoped (B3 idempotency) -> (invoice_no)
--   drug_interactions  findAmongScoped / findPairScoped -> (organization_id)
--
-- medicine_clinical already has uq_medclinical_org_product covering (organization_id, product_id).
-- prescription_items.prescription_id is indexed by its FK from the V1 baseline.
--
-- Idempotent: each CREATE INDEX is guarded on information_schema.STATISTICS, so a re-run is a no-op and a dev DB
-- that already grew one of these by hand is left alone.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescriptions' AND INDEX_NAME='idx_rx_org_created')=0,
    'CREATE INDEX idx_rx_org_created ON prescriptions (organization_id, created_at)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescriptions' AND INDEX_NAME='idx_rx_user')=0,
    'CREATE INDEX idx_rx_user ON prescriptions (user_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dispensing' AND INDEX_NAME='idx_disp_org_controlled')=0,
    'CREATE INDEX idx_disp_org_controlled ON dispensing (organization_id, controlled, dispensed_at)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dispensing' AND INDEX_NAME='idx_disp_invoice')=0,
    'CREATE INDEX idx_disp_invoice ON dispensing (invoice_no)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='drug_interactions' AND INDEX_NAME='idx_interaction_org')=0,
    'CREATE INDEX idx_interaction_org ON drug_interactions (organization_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
