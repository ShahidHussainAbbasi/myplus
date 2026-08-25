-- PERF — let the dashboard's date-range reads use an index instead of scanning the table.
--
-- THE MEASUREMENT THAT PROMPTED THIS
-- /getBusinessDashboardStats answered in ~640ms for a 183-byte payload, repeatably, warm. Part of that is
-- the query shape (fixed separately, in the same slice); the rest is this:
--
--   EXPLAIN, as the application actually runs the query:
--     type=ALL   key=NULL   rows=1181   filtered=3.72%      <- FULL TABLE SCAN
--
-- It read every row in `sell` and kept under four in a hundred.
--
-- WHY THE EXISTING INDEX DID NOT HELP
-- `idx_sell_org_user (organization_id, user_id)` looks like it should serve a tenant-scoped read, and for a
-- plain `organization_id = ?` it does. But every scoped query on this platform carries the NULL-fallback:
--
--     organization_id = :orgId OR (organization_id IS NULL AND user_id = :userId)
--
-- Isolating the two halves shows exactly what that costs on this table:
--     without the OR branch : type=ref   key=idx_sell_org_user   rows=394
--     with it (as it runs)  : type=ALL   key=NULL                rows=1181
--
-- The OR alone is survivable — `customer` and `vender` still reach their indexes as `ref_or_null`. What
-- breaks `sell` is the OR *combined with a range predicate on a column no index covers*: MySQL has nothing
-- to seek on for the range, so it gives up on the index entirely.
--
-- WHICH COLUMN, AND WHY THIS ONE
-- `findSellByDates` filters on `s.updated`, NOT on `s.dated`. That is easy to get wrong — `dated` is the
-- column a reader expects a sales report to use, and an index built on it would have been measured, shipped,
-- and completely useless to the query it was meant to serve. The index follows the code, not the intuition.
--
--   ⚠ Worth a separate look, deliberately NOT changed here: filtering "sales this month" by `updated` means
--   an edited old invoice moves between months. That is a correctness question about the report, and this
--   migration is not the place to answer it. If it is ever changed to `dated`, this index must move with it.
--
-- PROVEN BEFORE BEING WRITTEN
-- The index below was created on a live copy, EXPLAINed, and dropped again:
--     type=range   key=(this index)   rows=363   filtered=100.00%
-- `filtered=100%` is the part that matters: every row it reads is a row it keeps, where before it discarded
-- 96 in every 100. Rows examined fell 1181 -> 363 at today's small volumes; the real gain is that it stops
-- being O(whole table) as a tenant grows.
--
-- COLUMN ORDER
-- (organization_id, updated) and not the reverse. The equality/NULL-check column leads so MySQL can seek to
-- the tenant first and then range-scan the dates within it. Reversed, every tenant's rows would be
-- interleaved through the date range and the tenant predicate would become a filter again.
--
-- Idempotent in the style this project uses everywhere: re-running is a no-op rather than an error.

SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME   = 'sell'
                  AND INDEX_NAME   = 'idx_sell_org_updated');

SET @ddl := IF(@exists = 0,
    'CREATE INDEX idx_sell_org_updated ON sell (organization_id, updated)',
    'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
