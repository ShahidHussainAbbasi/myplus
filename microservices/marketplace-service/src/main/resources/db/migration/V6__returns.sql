-- Flyway: order returns / RMA (slice 71, E10). Idempotent information_schema-guarded ADD COLUMN — no-op where dev
-- ddl-auto already made the column, correct on a fresh/prod DB. (fulfilment_status is already varchar — the new
-- RETURN_REQUESTED/RETURNED enum values need no schema change.)

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='return_reason')=0,
  'ALTER TABLE orders ADD COLUMN return_reason varchar(255) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
