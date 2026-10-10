-- EXP-REQ — per-PRODUCT exemption from the expiry requirement.
--
-- Where a business tracks expiry (org.cap.expiryTracking), a purchase must carry an expiry date unless the product is
-- marked "No expiry" (a BP monitor, crutches, accessories in a pharmacy). Same two-level rule as V12 (C6):
--     tenant capability  org.cap.expiryTracking  does this shop track expiry at all?
--     product policy     products.no_expiry      is THIS product exempt?
--     enforcement        capability AND NOT exemption   (PurchaseService, add and edit)
--
-- An EXEMPTION, DEFAULT 0: on deploy every product of an expiry-tracking business requires a date - the owner's rule -
-- and no backfill guesses which products expire. Products of other businesses are unaffected (the capability is off).
--
-- Idempotent in V12's idiom (dev runs ddl-auto:update, which may add the column first). V25: V24 is the highest
-- existing version - checked; a duplicate version is silently skipped by Flyway (see V12's note).
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='no_expiry')=0,
    'ALTER TABLE products ADD COLUMN no_expiry TINYINT(1) NOT NULL DEFAULT 0', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
