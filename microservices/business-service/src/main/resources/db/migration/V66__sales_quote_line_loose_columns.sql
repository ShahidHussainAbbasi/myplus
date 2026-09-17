-- U14 — a sales quote line can be offered in loose units ("10 tablets"), priced the way the till prices them.
-- Design: microservices/docs/slices/u14-loose-units-in-quotes.md
--
-- WHY: a quote line carried only `quantity` + `unit_price`, so a pharmacy could not quote ten tablets out of a box
-- of forty — only 0.25 of a box at the box price, the unreadable figure U13 removed from the Sale Return screen.
-- 122 of 218 quotes on dev belong to PHARMA tenants.
--
-- The first four columns MIRROR V51 on `sell` exactly — same names, same types — because a converted quote line
-- becomes a sell line, and two definitions of one idea drift. Their meanings are V51's:
--   sold_unit            PACK | LOOSE (NULL = an ordinary line, as every existing quote line is)
--   sold_quantity        10 (tablets) — what the customer was quoted
--   sold_rate            7.79 per piece, display only
--   pack_size_snapshot   40, FROZEN at the quote
--
-- The fifth is quote-only, and it is what makes an accepted quote binding (the user's ruling, 2026-09-17):
--   loose_markup_pct     the shop's pos.sale.looseMarkupPct AT THE QUOTE. A per-piece price is
--                        packRate ÷ packSize × (1 + markup), and markup is a setting read when the sale happens. If
--                        the owner changes it between acceptance and conversion, replaying the quote with today's
--                        markup would invoice a different amount from the one the customer accepted. Converting
--                        replays this snapshot instead, so the invoice charges exactly the accepted total.
--
-- Nullable throughout: every existing quote line is a pack line and reads exactly as before.
-- Idempotent in V51's pattern (information_schema-guarded), so a re-run — or a dev database where ddl-auto already
-- added the columns from the entity — is a no-op.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_quote_line' AND COLUMN_NAME = 'sold_unit') = 0,
               'ALTER TABLE sales_quote_line ADD COLUMN sold_unit VARCHAR(8) NULL',
               'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_quote_line' AND COLUMN_NAME = 'sold_quantity') = 0,
               'ALTER TABLE sales_quote_line ADD COLUMN sold_quantity FLOAT NULL',
               'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_quote_line' AND COLUMN_NAME = 'sold_rate') = 0,
               'ALTER TABLE sales_quote_line ADD COLUMN sold_rate DECIMAL(19,2) NULL',
               'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_quote_line' AND COLUMN_NAME = 'pack_size_snapshot') = 0,
               'ALTER TABLE sales_quote_line ADD COLUMN pack_size_snapshot INT NULL',
               'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_quote_line' AND COLUMN_NAME = 'loose_markup_pct') = 0,
               'ALTER TABLE sales_quote_line ADD COLUMN loose_markup_pct DECIMAL(9,4) NULL',
               'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
