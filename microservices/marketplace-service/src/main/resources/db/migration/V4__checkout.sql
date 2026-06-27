-- Flyway: checkout breakdown (slice 69, E5). Idempotent information_schema-guarded ADD COLUMN — no-op where dev
-- ddl-auto already made the column, correct on a fresh/prod DB. Types mirror the Hibernate-generated schema so
-- `validate` accepts it.
--   * cart_item.tax_rate        — per-line tax-rate snapshot so checkout computes tax from the cart alone
--   * orders.sub_total/tax_total/shipping_fee/shipping_method — the persisted checkout breakdown

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cart_item' AND COLUMN_NAME='tax_rate')=0,
  'ALTER TABLE cart_item ADD COLUMN tax_rate decimal(19,2) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='sub_total')=0,
  'ALTER TABLE orders ADD COLUMN sub_total decimal(19,2) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='tax_total')=0,
  'ALTER TABLE orders ADD COLUMN tax_total decimal(19,2) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='shipping_fee')=0,
  'ALTER TABLE orders ADD COLUMN shipping_fee decimal(19,2) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='shipping_method')=0,
  'ALTER TABLE orders ADD COLUMN shipping_method varchar(255) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
