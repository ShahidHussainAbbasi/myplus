-- Flyway: M3b (slice 75) — make the purchase row self-describing so it no longer needs a local Stock row. Adds the
-- batch/rate snapshot fields (previously only on the linked Stock) to `purchase`. Idempotent information_schema-guarded
-- ADD COLUMN (no-op where dev ddl-auto already added them; correct on a fresh/prod DB). Types mirror the Hibernate-
-- generated schema so `validate` accepts it. Legacy rows keep their stock_id FK (left untouched).

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='item_id')=0,
  'ALTER TABLE purchase ADD COLUMN item_id bigint DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='product_id')=0,
  'ALTER TABLE purchase ADD COLUMN product_id bigint DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='batch_no')=0,
  'ALTER TABLE purchase ADD COLUMN batch_no varchar(255) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='bpurchase_rate')=0,
  'ALTER TABLE purchase ADD COLUMN bpurchase_rate decimal(19,2) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='bsell_rate')=0,
  'ALTER TABLE purchase ADD COLUMN bsell_rate decimal(19,2) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='bpurchase_discount')=0,
  'ALTER TABLE purchase ADD COLUMN bpurchase_discount decimal(19,2) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='bsell_discount')=0,
  'ALTER TABLE purchase ADD COLUMN bsell_discount decimal(19,2) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='bpurchase_discount_type')=0,
  'ALTER TABLE purchase ADD COLUMN bpurchase_discount_type varchar(255) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='bsell_discount_type')=0,
  'ALTER TABLE purchase ADD COLUMN bsell_discount_type varchar(255) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='bexp_date')=0,
  'ALTER TABLE purchase ADD COLUMN bexp_date date DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
