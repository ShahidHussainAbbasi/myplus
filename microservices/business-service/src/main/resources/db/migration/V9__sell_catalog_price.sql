-- V9 (sale price recording): capture the catalog MASTER price at the moment of sale on each sell line, alongside
-- the ACTUAL sold rate (sell_rate — the cashier may override the catalog price on the sell screen). Reports can
-- then show BOTH "catalog price" and "sold at" per line. Additive + idempotent (guarded) — safe on a fresh/prod
-- deploy of an existing DB (legacy rows keep catalog_price NULL).
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sell' AND COLUMN_NAME='catalog_price')=0,
  'ALTER TABLE sell ADD COLUMN catalog_price decimal(19,2) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
