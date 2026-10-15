-- B3 (release readiness 2026-10-11) — make V3's UNIQUE index unable to fail on existing data.
--
-- V3 creates uk_aggregated_metrics_period (organization_id, metric_name, period_type, period_start). Its comment says
-- "nothing has ever written a row" — measured on DEV. If a database holds the same tenant/metric/period twice, the
-- index fails, V3 is marked failed and analytics-service does not start. This keeps the LATEST row (highest id) of
-- each such group, which is the one the P&L trend would have shown, and removes the older copies.
--
-- A Flyway CALLBACK, not a migration: V3 is already applied on databases that run this branch, and changing V3 would
-- change its checksum and stop the service there. A callback is not checksummed. It runs before every migrate, so it
-- does work ONLY while the table exists and the index does not — i.e. exactly once, before V3, and never again.
-- Rows whose key has a NULL are left alone: NULLs never collide in a MySQL unique index.

SET @b3_needed := (SELECT COUNT(*) FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aggregated_metrics') = 1
              AND (SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aggregated_metrics' AND COLUMN_NAME = 'organization_id') = 1
              AND (SELECT COUNT(*) FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aggregated_metrics'
                      AND INDEX_NAME = 'uk_aggregated_metrics_period') = 0;

SET @b3_sql := IF(@b3_needed,
    'DELETE older FROM aggregated_metrics older
       JOIN aggregated_metrics newer
         ON newer.organization_id = older.organization_id
        AND newer.metric_name     = older.metric_name
        AND newer.period_type     = older.period_type
        AND newer.period_start    = older.period_start
        AND newer.id              > older.id',
    'DO 0');
PREPARE b3_stmt FROM @b3_sql;
EXECUTE b3_stmt;
DEALLOCATE PREPARE b3_stmt;
