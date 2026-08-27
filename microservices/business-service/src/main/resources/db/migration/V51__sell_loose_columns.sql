-- U2 — a sale line can record that a pack was broken.
--
-- Design: docs/slices/u2-loose-sale-arithmetic.md §6
--
-- FOUR NULLABLE COLUMNS, NO BACKFILL, NO DEFAULT.
-- Every existing row is an ordinary pack sale, and `sold_unit IS NULL` is how it stays distinguishable from a
-- line that was explicitly recorded as PACK. Defaulting to 'PACK' would rewrite two million historical rows
-- into a claim nobody made about them.
--
-- What each column is for (§3):
--   sold_unit          PACK | LOOSE — what the customer bought
--   sold_quantity      5 (tablets), so the receipt and the return say what the customer heard.
--                      FLOAT, matching `quantity` and `bonus_quantity` beside it, NOT the DECIMAL(19,4) the
--                      design first wrote: the entity field follows `Sell.quantity`'s Float, and a DECIMAL
--                      column under a Float field makes Hibernate's schema validation refuse to start the
--                      service. Exact for a count of pieces either way -- a whole number below 2^24 is
--                      represented precisely in Float, and non-whole piece counts are REFUSED, not rounded.
--   sold_rate          12.00 per piece, display only — the MONEY is sell_rate x quantity, unchanged
--   pack_size_snapshot 10, FROZEN. quantity=0.5 and sold_quantity=5 agree only while pack_size=10; edit the
--                      product to 12 tomorrow and that historical 0.5 silently becomes SIX tablets on the
--                      receipt, the return, and every report that re-reads a finished sale.
--
-- Version chosen by reading flyway_schema_history (head = V50), NOT `ls | tail`, which sorts lexically and is
-- exactly what made U1's first migration report success = 1 while never opening the file.
--
-- Idempotent in V8's pattern so a re-run on a partially migrated schema is safe.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sell' AND COLUMN_NAME = 'sold_unit') = 0,
               'ALTER TABLE sell ADD COLUMN sold_unit VARCHAR(8) NULL',
               'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sell' AND COLUMN_NAME = 'sold_quantity') = 0,
               'ALTER TABLE sell ADD COLUMN sold_quantity FLOAT NULL',
               'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sell' AND COLUMN_NAME = 'sold_rate') = 0,
               'ALTER TABLE sell ADD COLUMN sold_rate DECIMAL(19,2) NULL',
               'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sell' AND COLUMN_NAME = 'pack_size_snapshot') = 0,
               'ALTER TABLE sell ADD COLUMN pack_size_snapshot INT NULL',
               'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
