-- Flyway: order refunds (slice 70, E6). Idempotent information_schema-guarded ADD COLUMN — no-op where dev ddl-auto
-- already made the column, correct on a fresh/prod DB. Types mirror the Hibernate-generated schema so `validate`
-- accepts it. (paymentStatus is already varchar — REFUNDED/PARTIALLY_REFUNDED need no schema change.)

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='refund_ref')=0,
  'ALTER TABLE orders ADD COLUMN refund_ref varchar(255) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='refunded_amount')=0,
  'ALTER TABLE orders ADD COLUMN refunded_amount decimal(19,2) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
