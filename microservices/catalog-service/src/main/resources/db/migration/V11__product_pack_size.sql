-- U1 — a product can say how many sellable pieces are in the unit it is priced in.
--
-- THE PROBLEM
-- A pack of Panadol holds 10 tablets. The shop prices the PACK at 120, a customer asks for 5 tablets, and the
-- cashier does the division at the counter with a queue watching. Two things fail together: the sale is slow,
-- and the price is whatever that cashier worked out — so the same five tablets cost different amounts on
-- different shifts and nobody can audit it afterwards.
--
-- `products.unit` already exists and is FREE TEXT. A shop can type "pack" into it and no code anywhere knows a
-- pack means ten of something. These columns are what make it computable.
--
-- ONE LEVEL, DELIBERATELY. Not a unit-of-measure engine with conversion graphs (SAP MARM, Odoo uom.uom): every
-- case this platform has — pharmacy strips, crates of 24, boxes of 100 screws, trays of 30 eggs, reams of 500
-- sheets — is ONE number, and a graph would charge for generality nobody asked for on every product read.
-- Design: docs/pack-and-loose-selling-design.md §4.
--
-- Idempotent, matching V8's pattern: dev runs ddl-auto:update, which adds these too.
--
-- ⚠ THIS WAS WRITTEN AS V10 AND SILENTLY DID NOT RUN. A V10 already existed
-- (V10__products_org_name_index.sql), so Flyway saw version 10 in its history, considered it applied and
-- never opened this file — while recording success=1. Nothing errored; the columns simply were not there.
--
-- The cause was reading the directory with `ls | tail -3`, which sorts LEXICALLY: V7, V8, V9 looked like the
-- end of the list and V10 sorted above them. Sort NUMERICALLY when picking the next version, or check the
-- flyway_schema_history table, which cannot mislead.

-- How many sellable pieces one priced unit contains. NULL or 1 = not divisible, which is every product today
-- and the overwhelming majority forever. INT, not decimal: a pack holds a countable number of pieces.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='pack_size')=0,
    'ALTER TABLE products ADD COLUMN pack_size INT DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- What ONE piece is called, singular and plural.
--
-- ⚠ TWO COLUMNS, NOT ONE, because "5 tablet" is wrong in every language this platform ships in. The receipt
-- reads `qty + " " + (qty == 1 ? loose_unit : loose_unit_plural)`. These are TENANT DATA rather than i18n
-- keys: a shop names its own units, and the Urdu, Arabic and Hindi tenants each pluralise differently. The
-- platform's six bundles translate the LABELS around them ("Loose", "per pack"); the unit itself is the
-- shop's own word.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='loose_unit')=0,
    'ALTER TABLE products ADD COLUMN loose_unit VARCHAR(32) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='loose_unit_plural')=0,
    'ALTER TABLE products ADD COLUMN loose_unit_plural VARCHAR(32) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- May this product be broken open AT ALL?
--
-- ⚠ SEPARATE FROM pack_size ON PURPOSE. A pharmacy knows an antibiotic course holds 10 tablets — useful for
-- stock counts and reporting — and must still refuse to split it. One field could not say both, and collapsing
-- them would mean a shop had to lie about the pack to enforce the rule.
--
-- DEFAULT 0: a default is not a decision. Nothing becomes divisible because a column appeared.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='allow_loose')=0,
    'ALTER TABLE products ADD COLUMN allow_loose TINYINT(1) NOT NULL DEFAULT 0', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- Which unit a sale line STARTS in.
--
-- A pharmacy selling Panadol loose nine times in ten should not press the loose key nine times in ten. This is
-- the single biggest time saving in the design and it costs one column. Per PRODUCT rather than per tenant,
-- because the same shop sells strips loose and sealed bottles whole.
--
-- ⚠ The screen must show the unit INSIDE the quantity box whenever this is not PACK — a default that silently
-- changes what a familiar keystroke means is worse than no default at all (design §3.3).
--
-- VARCHAR, not ENUM: adding a value to a MySQL ENUM needs ALTER ... MODIFY, and without it the insert fails
-- with "Data truncated" — a trap this platform has already paid for.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='default_sell_unit')=0,
    'ALTER TABLE products ADD COLUMN default_sell_unit VARCHAR(8) NOT NULL DEFAULT ''PACK''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- WHO changed a pack rule, and WHEN.
--
-- pack_size and allow_loose decide what a customer is charged and whether a sealed course may be split, and
-- the standards require pricing controls to be auditable. This table records created_by only — so "who
-- allowed this to be split?" had no answer at all. NULL until somebody changes a pack rule.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='pack_changed_by')=0,
    'ALTER TABLE products ADD COLUMN pack_changed_by BIGINT DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='pack_changed_at')=0,
    'ALTER TABLE products ADD COLUMN pack_changed_at DATETIME DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- No backfill, and none is possible: nothing knows how many tablets are in a pack until somebody says so.
-- Every existing product therefore has pack_size NULL, allow_loose 0 and default_sell_unit PACK — which is
-- exactly today's behaviour, so U1 changes nothing observable until a shop fills these in.
