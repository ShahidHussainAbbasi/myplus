-- The index follows the column. V49 said it would have to, and this is that.
--
-- WHAT CHANGED ABOVE IT
-- The dashboard and the Sale Detail Report filtered `sell.updated`. That column moves every time a row is
-- touched, so an invoice edited months later silently left its own month and reappeared in the current one.
-- They now filter `dated`, which is `@Column(updatable=false)` — when the sale happened, immutable by
-- construction.
--
-- V49 created idx_sell_org_updated for those queries and recorded the risk in its own header:
--
--     "If it is ever changed to `dated`, this index must move with it."
--
-- It has been, so it does. Leaving V49's index in place would have been the quiet failure: the queries would
-- have gone back to a full table scan — type=ALL, rows=1181, filtered=3.72% — while an index sat beside them
-- named for a column nothing reads any more. Nothing would have failed; the dashboard would just have become
-- slow again, months after anyone connected the two changes.
--
-- COLUMN ORDER, unchanged from V49's reasoning
-- (organization_id, dated): the equality/NULL-check column leads so MySQL seeks to the tenant first and then
-- range-scans dates within it. Reversed, every tenant's rows interleave through the range and the tenant
-- predicate degrades to a filter.
--
-- WHY THE OLD INDEX IS DROPPED RATHER THAN LEFT
-- An unused index is not free: every INSERT and UPDATE on `sell` maintains it, and `sell` is the busiest
-- write path in the product. Keeping it "just in case" would tax every sale to serve no read.
--
-- Both statements are idempotent, in the style used throughout this project: re-running is a no-op.

SET @has_new := (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME   = 'sell'
                   AND INDEX_NAME   = 'idx_sell_org_dated');

SET @ddl := IF(@has_new = 0,
    'CREATE INDEX idx_sell_org_dated ON sell (organization_id, dated)',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_old := (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME   = 'sell'
                   AND INDEX_NAME   = 'idx_sell_org_updated');

SET @ddl2 := IF(@has_old > 0,
    'DROP INDEX idx_sell_org_updated ON sell',
    'DO 0');
PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
