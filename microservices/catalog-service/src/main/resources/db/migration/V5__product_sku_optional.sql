-- SKU is OPTIONAL: a shop may enter products by name alone, and many retailers do not code every line.
--
-- The column was NOT NULL, so the UI sent '' for "no code". The service's duplicate check then matched
-- that '' against every other blank-SKU product and rejected the second one with
-- "Product SKU already exists: " (note the empty value in the message). NULL is the correct
-- representation of "not set" — any number of rows may share it, and MySQL's index ignores it for
-- uniqueness purposes, so this stays safe if a UNIQUE index is ever added.
--
-- Idempotent, matching V3's style (dev ddl-auto:update may already have relaxed the column).

-- 1. Allow NULL.
SET @ddl := IF((SELECT IS_NULLABLE FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='sku') = 'NO',
    'ALTER TABLE products MODIFY COLUMN sku VARCHAR(255) NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 2. Existing rows saved with the empty-string placeholder become "no code". Without this the old rows
--    keep colliding with each other exactly as before, and editing one still fails.
UPDATE products SET sku = NULL WHERE sku = '';

-- 3. Same treatment for barcode: the column was already nullable, but rows written before the service
--    normalised blanks may still hold ''. A blank barcode must never match a scan.
UPDATE products SET barcode = NULL WHERE barcode = '';
