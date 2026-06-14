-- Forward migration for the slice 21–28 schema changes (tech-debt #13).
-- Reproduces, via Flyway, what dev applied through ddl-auto:update + manual migrations.md:
--   * organization_id on every business domain table (multi-tenant scoping, slices 21/28)
--   * invoice_seq / invoice_no on customer_history (per-org invoice numbering, slice 22)
--   * money columns FLOAT -> DECIMAL(19,2) (slice 23)
-- IDEMPOTENT: ADD COLUMNs are guarded by information_schema so re-running on an already-migrated DB
-- (where ddl-auto added them) is a no-op; money MODIFYs are naturally idempotent. Safe on fresh,
-- dev, and prod. Runs after the V1 baseline.
-- NOTE: the unique-constraint changes (item_type/item_unit per-tenant unique; customer_history
-- unique(organization_id, invoice_seq)) remain in docs/migrations.md — index names vary per env and
-- Hibernate `validate` does not check them, so they are applied operationally, not here.

-- ---- organization_id on each domain table (guarded add) ----
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer'          AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE customer ADD COLUMN organization_id BIGINT NULL', 'DO 0');           PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history'  AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE customer_history ADD COLUMN organization_id BIGINT NULL', 'DO 0');  PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sell'              AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE sell ADD COLUMN organization_id BIGINT NULL', 'DO 0');               PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='item'              AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE item ADD COLUMN organization_id BIGINT NULL', 'DO 0');               PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='item_type'         AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE item_type ADD COLUMN organization_id BIGINT NULL', 'DO 0');          PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='item_unit'         AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE item_unit ADD COLUMN organization_id BIGINT NULL', 'DO 0');          PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock'             AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE stock ADD COLUMN organization_id BIGINT NULL', 'DO 0');              PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase'          AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE purchase ADD COLUMN organization_id BIGINT NULL', 'DO 0');           PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='vender'            AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE vender ADD COLUMN organization_id BIGINT NULL', 'DO 0');             PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='companies'         AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE companies ADD COLUMN organization_id BIGINT NULL', 'DO 0');          PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---- per-org invoice number on customer_history (guarded add) ----
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='invoice_seq')=0, 'ALTER TABLE customer_history ADD COLUMN invoice_seq BIGINT NULL', 'DO 0');        PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='invoice_no')=0, 'ALTER TABLE customer_history ADD COLUMN invoice_no VARCHAR(255) NULL', 'DO 0');   PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---- money FLOAT -> DECIMAL(19,2) (idempotent: re-applying the same type is a no-op) ----
ALTER TABLE sell             MODIFY sell_rate DECIMAL(19,2), MODIFY discount DECIMAL(19,2), MODIFY total_amount DECIMAL(19,2), MODIFY net_amount DECIMAL(19,2), MODIFY sell_return_profit DECIMAL(19,2);
ALTER TABLE purchase         MODIFY total_amount DECIMAL(19,2), MODIFY net_amount DECIMAL(19,2);
ALTER TABLE customer_history MODIFY paid_amount DECIMAL(19,2), MODIFY due_amount DECIMAL(19,2);
ALTER TABLE customer         MODIFY due_amount DECIMAL(19,2);
ALTER TABLE stock            MODIFY batch_purchase_rate DECIMAL(19,2), MODIFY batch_sale_rate DECIMAL(19,2), MODIFY batch_purchaseDiscount DECIMAL(19,2), MODIFY batch_saleDiscount DECIMAL(19,2);
