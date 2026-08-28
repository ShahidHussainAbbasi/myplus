-- C6 — per-PRODUCT tracking policy: serial/IMEI and batch.
--
-- WHY THESE ARE NOT TENANT SWITCHES
-- A mobile shop sells handsets that are IMEI-tracked AND chargers that are not. Zubair Traders stocks
-- pesticides needing batch/expiry alongside tools needing neither. A single tenant-wide switch would force
-- chargers to carry an IMEI and tools to carry a batch number nobody has.
--
-- The rule is two-level, and it is the one `allow_loose` already follows:
--     tenant capability  org.cap.serialTracking    may this shop use serial tracking at all?
--     product policy     products.requires_serial  does THIS product require it?
--     enforcement        capability AND policy
-- A tenant without the capability cannot set the policy — enforced on the write in ProductService.
--
-- DEFAULT 0, which is what makes this deploy inert: every product in every tenant is untracked until an owner
-- says otherwise. Nothing changes on the day this ships.
--
-- Idempotent in V11's idiom (dev runs ddl-auto:update, which adds these columns too, so the migration must
-- tolerate finding them already there).
--
-- ⚠ NUMBERED V12 DELIBERATELY. V11 is the highest existing version. V11 ITSELF was first written as V10,
-- collided with V10__products_org_name_index.sql, and Flyway — seeing version 10 already in its history —
-- considered it applied and SILENTLY DID NOT RUN IT. A duplicate version number does not fail; it disappears.
-- Check the directory before choosing a number.

-- Does this product need an individual serial / IMEI recorded? TINYINT(1) to match rx_required and
-- controlled_substance, the per-product policy flags this one sits beside.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='requires_serial')=0,
    'ALTER TABLE products ADD COLUMN requires_serial TINYINT(1) NOT NULL DEFAULT 0', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- Does this product arrive and move in identified batches? Distinct from requires_serial: a batch identifies a
-- DELIVERY of many units, a serial identifies ONE unit. Nothing forbids both — that judgement belongs to the
-- shop, not to a constraint that would have to guess.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='tracks_batch')=0,
    'ALTER TABLE products ADD COLUMN tracks_batch TINYINT(1) NOT NULL DEFAULT 0', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
