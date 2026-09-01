-- The cost of the batch a pick took (task #17 P3). Design: docs/slices/bonus-schemes-p3.md
--
-- COGS is the cost of the goods that actually LEFT, and only inventory knows which batch left. Stamping the
-- unit cost onto the pick at reserve time — where the batch is already in hand — means the sale can record
-- what it consumed without a read per pick on the sale path.
--
-- Scale 6, not 2, deliberately: this is a per-unit DERIVATION of a batch total (5,000 across 11 units is
-- 454.545454...), not a money amount in its own right. Rounding it to 2 here would reintroduce the drift P2
-- exists to remove — the sale multiplies it back out, so the precision has to survive until then.
--
-- ALL MODULES ARE LIVE: additive only, one nullable column. Reservations already in flight simply carry NULL,
-- and the sale falls back to its existing cost snapshot for them.
--
-- Idempotent: information_schema-guarded, so a re-run or a ddl-auto dev database is a no-op.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservation_picks' AND COLUMN_NAME='unit_cost')=0,
    'ALTER TABLE reservation_picks ADD COLUMN unit_cost DECIMAL(19,6) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
