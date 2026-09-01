-- The cost of the goods that actually left (task #17 P3). Design: docs/slices/bonus-schemes-p3.md
--
-- sell_batch already records WHICH batches a sale consumed, for traceability. P3 makes it the COGS source
-- too: the cost of a sale is the cost of the batches it took, stamped at the moment it was written.
--
-- STAMPED, NEVER RE-DERIVED. Reading a current rate at report time would let a purchase made next week change
-- last week's margin -- the same reason last-purchase rates are stamped onto the product master rather than
-- looked up.
--
-- Scale 6, not 2: this is a per-unit derivation of a batch total (5,000 across 11 units is 454.545454...),
-- and the sale multiplies it back out, so the precision has to survive until then. Rounding here would
-- reintroduce exactly the drift P2 exists to remove.
--
-- ALL MODULES ARE LIVE: additive only, one nullable column. Sales written before P3 carry NULL and fall back
-- to their per-line cost snapshot, so historical edits and returns keep producing the numbers they always did.
--
-- Idempotent: information_schema-guarded, so a re-run -- or a dev database where ddl-auto already added the
-- column from the entity -- is a no-op.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sell_batch' AND COLUMN_NAME='unit_cost')=0,
    'ALTER TABLE sell_batch ADD COLUMN unit_cost DECIMAL(19,6) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
